package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.local.dao.RiderDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.entity.RiderEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.local.mapper.toEntity
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.supabase.delivery.SupabaseRiderDataSource
import com.aesprt.aquahub.data.supabase.delivery.SupabaseRiderRow
import com.aesprt.aquahub.domain.model.Rider
import com.aesprt.aquahub.domain.repository.RiderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import io.github.jan.supabase.auth.auth
import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.sync.SyncOperation
import com.aesprt.aquahub.sync.SyncQueueWriter
import com.aesprt.aquahub.sync.SyncResourceType
import java.time.Instant

class RoomRiderRepository(
    private val riderDao: RiderDao,
    private val database: AquaHubDatabase,
    private val syncQueue: SyncQueueWriter,
    private val syncDao: SyncDao,
    private val supabaseRider: SupabaseRiderDataSource
) : RiderRepository {

    override fun observeRiders(stationId: Long): Flow<List<Rider>> =
        flow {
            coroutineScope {
                launch { refreshRiders(stationId) }
                emitAll(riderDao.observeRiders(stationId).map { list -> list.map { it.toDomain() } })
            }
        }

    override fun observeAvailableRiders(stationId: Long): Flow<List<Rider>> =
        flow {
            coroutineScope {
                launch { refreshRiders(stationId) }
                emitAll(riderDao.observeAvailableRiders(stationId).map { list -> list.map { it.toDomain() } })
            }
        }

    override suspend fun refreshRiders(stationId: Long): Result<Unit> = try {
        if (SupabaseClientProvider.client.auth.currentUserOrNull() == null) return Result.success(Unit)
        val remoteStationId = syncDao.getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId
            ?: return Result.success(Unit)
        val rows = supabaseRider.listForStation(remoteStationId)
        val localRows = riderDao.getAllRiders().filter { it.stationId == stationId }
        database.withTransaction {
            rows.groupBy { remote ->
                normalize(remote.phone).ifBlank { "remote:${remote.id}" }
            }.values.forEach { duplicates ->
                val remote = duplicates.minWithOrNull(
                    compareBy<SupabaseRiderRow>(
                        { runCatching { Instant.parse(it.createdAtText).toEpochMilli() }.getOrDefault(Long.MAX_VALUE) },
                        { it.id }
                    )
                ) ?: return@forEach
                val metadata = syncDao.getMetadataByRemoteId(SyncResourceType.RIDER.name, remote.id)
                    ?: remote.legacyId?.let { syncDao.getMetadataByRemoteId(SyncResourceType.RIDER.name, it) }
                val existing = metadata?.let { riderDao.getRiderById(it.localId) }
                    ?: localRows.filter { normalize(it.phone) == normalize(remote.phone) }.minByOrNull { it.id }
                if (existing != null && syncDao.hasOutstandingMutation(SyncResourceType.RIDER.name, existing.id) > 0) {
                    return@forEach
                }
                val savedId = riderDao.insertRider(remote.toEntity(stationId, existing?.id ?: 0L))
                syncDao.upsertMetadata(
                    SyncMetadataEntity(
                        resourceType = SyncResourceType.RIDER.name,
                        localId = savedId,
                        remoteId = remote.id,
                        stationLocalId = stationId,
                        lastLocalUpdatedAt = remote.clientUpdatedAt ?: remote.updatedAtText.toMillis(),
                        lastServerUpdatedAt = remote.updatedAtText.toMillis(),
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    override suspend fun getRiderById(riderId: Long): Rider? =
        riderDao.getRiderById(riderId)?.toDomain()

    override suspend fun getRiderByPhone(phone: String, stationId: Long?): Rider? {
        val normalized = normalize(phone)
        val riders = if (stationId != null) {
            riderDao.getActiveRidersForStation(stationId)
        } else {
            riderDao.getAllRiders()
        }
        return riders
            .firstOrNull { normalize(it.phone) == normalized }
            ?.toDomain()
    }

    private fun normalize(value: String): String {
        val digits = value.filter(Char::isDigit)
        return when {
            digits.startsWith("63") -> digits.drop(2)
            digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
    }

    override suspend fun countActiveOrders(riderId: Long): Int =
        riderDao.countActiveOrders(riderId)

    override suspend fun insertRider(rider: Rider): Long = database.withTransaction {
        val normalizedPhone = normalize(rider.phone)
        if (normalizedPhone.isNotEmpty()) {
            riderDao.getAllRiders()
                .firstOrNull { it.stationId == rider.stationId && normalize(it.phone) == normalizedPhone }
                ?.let { return@withTransaction it.id }
        }
        val updated = rider.copy(updatedAt = System.currentTimeMillis())
        val id = riderDao.insertRider(updated.toEntity())
        syncQueue.enqueue(SyncResourceType.RIDER, id, updated.stationId, SyncOperation.CREATE, updated.updatedAt)
        id
    }

    override suspend fun updateRider(rider: Rider) = database.withTransaction {
        val updated = rider.copy(updatedAt = System.currentTimeMillis())
        riderDao.updateRider(updated.toEntity())
        syncQueue.enqueue(SyncResourceType.RIDER, updated.id, updated.stationId, SyncOperation.UPDATE, updated.updatedAt)
        Unit
    }

    override suspend fun updateRiderAvailability(riderId: Long, isAvailable: Boolean) = database.withTransaction {
        val row = riderDao.getRiderById(riderId) ?: return@withTransaction
        val now = System.currentTimeMillis()
        riderDao.updateRider(row.copy(isAvailable = isAvailable, updatedAt = now))
        syncQueue.enqueue(SyncResourceType.RIDER, riderId, row.stationId, SyncOperation.UPDATE, now)
    }

    override suspend fun incrementActiveOrderCount(riderId: Long) = database.withTransaction {
        val row = riderDao.getRiderById(riderId) ?: return@withTransaction
        val now = System.currentTimeMillis()
        riderDao.updateRider(row.copy(activeOrderCount = row.activeOrderCount + 1, updatedAt = now))
        syncQueue.enqueue(SyncResourceType.RIDER, riderId, row.stationId, SyncOperation.UPDATE, now)
    }

    override suspend fun decrementActiveOrderCount(riderId: Long) = database.withTransaction {
        val row = riderDao.getRiderById(riderId) ?: return@withTransaction
        val now = System.currentTimeMillis()
        riderDao.updateRider(row.copy(activeOrderCount = (row.activeOrderCount - 1).coerceAtLeast(0), updatedAt = now))
        syncQueue.enqueue(SyncResourceType.RIDER, riderId, row.stationId, SyncOperation.UPDATE, now)
    }
}

private fun SupabaseRiderRow.toEntity(stationLocalId: Long, localId: Long): RiderEntity = RiderEntity(
    id = localId,
    stationId = stationLocalId,
    name = name,
    phone = phone,
    vehicleType = vehicleType,
    vehicleName = vehicleName,
    plateNumber = plateNumber,
    rating = rating,
    isActive = isActive,
    isOnline = isOnline,
    isAvailable = isAvailable,
    latitude = latitude,
    longitude = longitude,
    activeOrderCount = activeOrderCount,
    createdAt = createdAtText.toMillis(),
    updatedAt = updatedAtText.toMillis()
)

private fun String.toMillis(): Long = runCatching { Instant.parse(this).toEpochMilli() }
    .getOrDefault(System.currentTimeMillis())
