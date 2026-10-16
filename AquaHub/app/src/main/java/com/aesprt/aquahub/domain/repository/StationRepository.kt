package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.Station
import kotlinx.coroutines.flow.Flow

interface StationRepository {
    fun observeStation(stationId: Long): Flow<Station?>
    suspend fun getStation(stationId: Long): Station?
    fun observeAllStations(): Flow<List<Station>>
    suspend fun getAllStations(): List<Station>
    suspend fun getRemoteStationId(stationId: Long): String? = stationId.toString()
    suspend fun createStation(station: Station): Long
    suspend fun updateStation(station: Station)
    suspend fun deleteStation(stationId: Long)
}
