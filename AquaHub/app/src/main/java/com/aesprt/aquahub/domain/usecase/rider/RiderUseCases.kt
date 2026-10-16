package com.aesprt.aquahub.domain.usecase.rider

import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.core.common.Constants
import com.aesprt.aquahub.domain.model.Rider
import com.aesprt.aquahub.domain.model.RiderRecommendation
import com.aesprt.aquahub.domain.repository.RiderRepository
import com.aesprt.aquahub.domain.usecase.subscription.CanCreateRiderUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.math.*

class ObserveRidersUseCase(private val riderRepository: RiderRepository) {
    operator fun invoke(stationId: Long): Flow<List<Rider>> =
        riderRepository.observeRiders(stationId)
}

class ObserveAvailableRidersUseCase(private val riderRepository: RiderRepository) {
    operator fun invoke(stationId: Long): Flow<List<Rider>> =
        riderRepository.observeAvailableRiders(stationId)
}

class CreateRiderUseCase(
    private val riderRepository: RiderRepository,
    private val canCreateRiderUseCase: CanCreateRiderUseCase,
    private val transactionRunner: com.aesprt.aquahub.domain.common.LocalTransactionRunner
) {
    suspend operator fun invoke(rider: Rider): Result<Long> = transactionRunner.run {
        if (rider.name.isBlank()) return@run Result.failure(AppError.ValidationError("Rider name is required"))
        if (rider.phone.isBlank()) return@run Result.failure(AppError.ValidationError("Phone is required"))
        if (canCreateRiderUseCase(rider.stationId) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed) {
            return@run Result.failure(AppError.ValidationError("Rider management is not available for this subscription"))
        }
        return@run Result.success(riderRepository.insertRider(rider))
    }
}

class UpdateRiderUseCase(private val riderRepository: RiderRepository, private val access: com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(rider: Rider): Result<Unit> {
        if (access(rider.stationId, com.aesprt.aquahub.domain.model.SubscriptionFeature.RIDER_MANAGEMENT) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed)
            return Result.failure(AppError.ValidationError("This feature is unavailable for your subscription"))
        if (rider.name.isBlank()) return Result.failure(AppError.ValidationError("Rider name is required"))
        riderRepository.updateRider(rider)
        return Result.success(Unit)
    }
}

class RemoveRiderUseCase(
    private val riderRepository: RiderRepository,
    private val access: com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(rider: Rider): Result<Unit> {
        if (access(rider.stationId, com.aesprt.aquahub.domain.model.SubscriptionFeature.RIDER_MANAGEMENT) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed) {
            return Result.failure(AppError.ValidationError("This feature is unavailable for your subscription"))
        }
        if (riderRepository.countActiveOrders(rider.id) > 0) {
            return Result.failure(AppError.ValidationError("Reassign this rider's active orders before removing them"))
        }
        riderRepository.updateRider(
            rider.copy(
                isActive = false,
                isAvailable = false,
                isOnline = false,
                activeOrderCount = 0
            )
        )
        return Result.success(Unit)
    }
}

class RecommendRidersUseCase(private val riderRepository: RiderRepository) {
    suspend operator fun invoke(
        stationId: Long,
        stationLat: Double?,
        stationLon: Double?
    ): List<RiderRecommendation> {
        val availableRiders = riderRepository.observeAvailableRiders(stationId).first()
        return availableRiders.map { rider ->
            val distanceKm = if (stationLat != null && stationLon != null &&
                rider.latitude != null && rider.longitude != null
            ) {
                haversineKm(stationLat, stationLon, rider.latitude, rider.longitude)
            } else null

            val maxDistance = 20.0
            val normalizedDistance = if (distanceKm != null)
                minOf(distanceKm / maxDistance, 1.0) else 0.5

            val maxOrders = 5.0
            val workloadScore = minOf(rider.activeOrderCount / maxOrders, 1.0)

            val score = normalizedDistance * 0.60 + workloadScore * 0.30 +
                    (if (!rider.isAvailable) 1.0 else 0.0) * 0.10

            RiderRecommendation(rider = rider, score = score, distanceKm = distanceKm)
        }.sortedBy { it.score }
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = Constants.EARTH_RADIUS_KM
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
