package com.aesprt.aquahub.domain.usecase.station

import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.repository.StationRepository
import com.aesprt.aquahub.domain.usecase.subscription.CanCreateBranchUseCase
import kotlinx.coroutines.flow.Flow

class ObserveActiveStationIdUseCase(
    private val activeStationContext: ActiveStationContext
) {
    operator fun invoke(): Flow<Long?> = activeStationContext.observeActiveStationId()
}

class SetActiveStationIdUseCase(
    private val activeStationContext: ActiveStationContext
) {
    suspend operator fun invoke(stationId: Long) = activeStationContext.setActiveStationId(stationId)
}

class ObserveAllStationsUseCase(
    private val stationRepository: StationRepository
) {
    operator fun invoke(): Flow<List<Station>> = stationRepository.observeAllStations()
}

class GetAllStationsUseCase(
    private val stationRepository: StationRepository
) {
    suspend operator fun invoke(): List<Station> = stationRepository.getAllStations()
}

class CreateNewStationUseCase(
    private val stationRepository: StationRepository,
    private val canCreateBranchUseCase: CanCreateBranchUseCase,
    private val activeStationContext: ActiveStationContext,
    private val subscriptionRepository: com.aesprt.aquahub.domain.repository.SubscriptionRepository
) {
    suspend operator fun invoke(
        currentActiveStationId: Long,
        station: Station
    ): Result<Long> {
        val existingStations = stationRepository.getAllStations()
        val limitCheck = canCreateBranchUseCase(currentActiveStationId, existingStations.size.toLong())
        if (limitCheck !is SubscriptionAccessResult.Allowed) {
            val message = when (limitCheck) {
                is SubscriptionAccessResult.LimitReached -> "Station limit reached (${limitCheck.current}/${limitCheck.maximum}). Upgrade your plan to add more stations."
                is SubscriptionAccessResult.FeatureNotIncluded -> "Multi-station management is only available on Business and Pro plans."
                is SubscriptionAccessResult.SubscriptionExpired -> "Subscription expired. Please renew your plan."
                is SubscriptionAccessResult.SubscriptionSuspended -> "Subscription is suspended: ${limitCheck.message ?: "Account suspended"}"
                SubscriptionAccessResult.Allowed -> "Station limit reached."
            }
            return Result.failure(IllegalStateException(message))
        }

        val newStationId = stationRepository.createStation(station)
        subscriptionRepository.replicateSubscription(currentActiveStationId, newStationId)
        return Result.success(newStationId)
    }
}
