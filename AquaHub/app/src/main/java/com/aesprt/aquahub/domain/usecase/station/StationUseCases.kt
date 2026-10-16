package com.aesprt.aquahub.domain.usecase.station

import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.repository.StationRepository
import kotlinx.coroutines.flow.Flow

class ObserveStationUseCase(private val stationRepository: StationRepository) {
    operator fun invoke(stationId: Long): Flow<Station?> =
        stationRepository.observeStation(stationId)
}

class GetStationUseCase(private val stationRepository: StationRepository) {
    suspend operator fun invoke(stationId: Long): Station? =
        stationRepository.getStation(stationId)
}

class UpdateStationUseCase(private val stationRepository: StationRepository) {
    suspend operator fun invoke(station: Station): Result<Unit> {
        if (station.name.isBlank()) {
            return Result.failure(AppError.ValidationError("Station name cannot be empty"))
        }
        stationRepository.updateStation(station)
        return Result.success(Unit)
    }
}
