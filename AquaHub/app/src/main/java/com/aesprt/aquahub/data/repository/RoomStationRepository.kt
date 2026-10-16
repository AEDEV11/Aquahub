package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.local.dao.StationDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.local.mapper.toEntity
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.repository.StationRepository
import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.sync.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.aesprt.aquahub.data.supabase.settings.SupabaseSettingsDataSource
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.auth.auth

class RoomStationRepository(
    private val stationDao: StationDao,
    private val database: AquaHubDatabase,
    private val syncQueue: SyncQueueWriter,
    private val syncDao: SyncDao,
    private val supabaseSettings: SupabaseSettingsDataSource
) : StationRepository {
    override fun observeStation(stationId: Long): Flow<Station?> =
        stationDao.observeStation(stationId).map { it?.toDomain() }

    override suspend fun getStation(stationId: Long): Station? =
        stationDao.getStation(stationId)?.toDomain()

    override fun observeAllStations(): Flow<List<Station>> =
        stationDao.observeAllStations().map { list -> list.map { it.toDomain() } }

    override suspend fun getAllStations(): List<Station> =
        stationDao.getAllStations().map { it.toDomain() }

    override suspend fun getRemoteStationId(stationId: Long): String? =
        syncDao.getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId

    override suspend fun createStation(station: Station): Long = database.withTransaction {
        val id = stationDao.insertStation(station.toEntity())
        syncQueue.enqueue(SyncResourceType.STATION, id, id, SyncOperation.CREATE, station.updatedAt)
        id
    }

    override suspend fun updateStation(station: Station) {
        val updated = station.copy(updatedAt = System.currentTimeMillis())
        val remoteId = syncDao.getMetadata(SyncResourceType.STATION.name, updated.id)?.remoteId
        if (remoteId != null && SupabaseClientProvider.client.auth.currentSessionOrNull() != null) {
            // Authenticated online settings writes are Supabase-only. If the
            // RPC fails, surface the failure and leave the local row unchanged
            // rather than silently creating an untracked cloud write.
            supabaseSettings.updateStationSettings(remoteId, updated)
            stationDao.updateStation(updated.toEntity())
        } else {
            database.withTransaction {
                stationDao.updateStation(updated.toEntity())
                syncQueue.enqueue(SyncResourceType.STATION, updated.id, updated.id, SyncOperation.UPDATE, updated.updatedAt)
            }
        }
    }

    override suspend fun deleteStation(stationId: Long) = database.withTransaction {
        syncQueue.enqueue(SyncResourceType.STATION, stationId, stationId, SyncOperation.ARCHIVE)
        stationDao.deleteStation(stationId)
    }
}
