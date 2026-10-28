package com.aesprt.aquahub.sync

import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.dao.ContainerTransactionDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.entity.ContainerTransactionEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.supabase.inventory.SupabaseContainerMovementRow

/** Merges the immutable Supabase bottle ledger into Room without creating replay work. */
class ContainerMovementPullApplier(
    private val database: AquaHubDatabase,
    private val transactionDao: ContainerTransactionDao,
    private val syncDao: SyncDao
) {
    suspend fun apply(stationId: Long, rows: List<SupabaseContainerMovementRow>): Int {
        var imported = 0
        rows.forEach { remote ->
            val type = runCatching {
                com.aesprt.aquahub.domain.model.ContainerTransactionType.valueOf(remote.movementType.uppercase())
            }.getOrNull() ?: return@forEach
            val stableRemoteId = remote.legacyId?.takeIf(String::isNotBlank) ?: remote.id
            database.withTransaction {
                val metadata = syncDao.getMetadataByRemoteId(
                    SyncResourceType.CONTAINER_MOVEMENT.name,
                    stableRemoteId
                ) ?: syncDao.getMetadataByRemoteId(
                    SyncResourceType.CONTAINER_MOVEMENT.name,
                    remote.id
                )
                val existing = metadata?.let { transactionDao.getTransactionById(it.localId) }
                val hasPendingMutation = existing != null &&
                    syncDao.hasOutstandingMutation(SyncResourceType.CONTAINER_MOVEMENT.name, existing.id) > 0
                val customerLocalId = remote.customerId?.let {
                    syncDao.getMetadataByRemoteId(SyncResourceType.CUSTOMER.name, it)?.localId
                }
                val orderLocalId = remote.orderId?.let {
                    syncDao.getMetadataByRemoteId(SyncResourceType.ORDER.name, it)?.localId
                }
                val createdAt = remote.createdAt.toEpochMillisOrNow()
                val entity = ContainerTransactionEntity(
                    id = existing?.id ?: 0L,
                    stationId = stationId,
                    customerId = customerLocalId ?: existing?.customerId,
                    customerName = remote.customerName ?: existing?.customerName,
                    orderId = orderLocalId ?: existing?.orderId,
                    type = type.name,
                    quantity = remote.quantity,
                    containerSize = remote.containerSize,
                    notes = remote.notes ?: existing?.notes,
                    createdAt = createdAt
                )
                val localId = if (existing == null) {
                    transactionDao.insertTransaction(entity).also { imported += 1 }
                } else {
                    if (!hasPendingMutation) transactionDao.updateTransaction(entity)
                    existing.id
                }
                val syncedAt = System.currentTimeMillis()
                syncDao.upsertMetadata(
                    SyncMetadataEntity(
                        resourceType = SyncResourceType.CONTAINER_MOVEMENT.name,
                        localId = localId,
                        remoteId = stableRemoteId,
                        stationLocalId = stationId,
                        lastLocalUpdatedAt = createdAt,
                        lastServerUpdatedAt = createdAt,
                        lastSyncedAt = syncedAt
                    )
                )
            }
        }
        return imported
    }
}

private fun String.toEpochMillisOrNow(): Long =
    runCatching { java.time.Instant.parse(this).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
