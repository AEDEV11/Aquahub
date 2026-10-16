package com.aesprt.aquahub.domain.usecase.auth

import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.data.local.seeder.DatabaseSeeder
import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.Owner
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.domain.repository.AuthRepository
import com.aesprt.aquahub.domain.repository.StationRepository
import com.aesprt.aquahub.domain.repository.SubscriptionRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RegistrationInput(
    val ownerName: String,
    val ownerPhone: String,
    val ownerEmail: String?,
    val password: String,
    val stationName: String,
    val stationDescription: String?,
    val stationPhone: String,
    val stationAddress: String,
    val barangay: String?,
    val city: String?,
    val province: String?,
    val latitude: Double?,
    val longitude: Double?,
    val placeId: String?,
    val openingTime: String?,
    val closingTime: String?,
    val marketingConsent: Boolean = false
)

class RegisterAndSetupStationUseCase(
    private val authRepository: AuthRepository,
    private val stationRepository: StationRepository,
    private val databaseSeeder: DatabaseSeeder,
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(
        ownerName: String,
        ownerPhone: String,
        ownerEmail: String?,
        password: String,
        stationName: String,
        stationDescription: String?,
        stationPhone: String,
        stationAddress: String,
        barangay: String?,
        city: String?,
        province: String?,
        latitude: Double? = null,
        longitude: Double? = null,
        placeId: String? = null,
        openingTime: String? = "07:00 AM",
        closingTime: String? = "06:00 PM",
        subscriptionPlanId: String = "business",
        subscriptionBilling: String = "monthly",
        marketingConsent: Boolean = false
    ): Result<Long> {
        val input = RegistrationInput(
            ownerName, ownerPhone, ownerEmail, password, stationName, stationDescription,
            stationPhone, stationAddress, barangay, city, province, latitude, longitude,
            placeId, openingTime, closingTime, marketingConsent
        )
        val prepared = prepareAccountAndStation(input)
        return prepared.fold(
            onSuccess = { stationId ->
                activateSelectedPlan(stationId, subscriptionPlanId, subscriptionBilling)
                    .fold(
                        onSuccess = { Result.success(stationId) },
                        onFailure = { Result.failure(it) }
                    )
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun prepareAccountAndStation(input: RegistrationInput): Result<Long> = registrationMutex.withLock {
        if (input.ownerName.isBlank()) return Result.failure(AppError.ValidationError("Owner name is required"))
        if (input.ownerPhone.isBlank()) return Result.failure(AppError.ValidationError("Phone number is required"))
        if (input.stationName.isBlank()) return Result.failure(AppError.ValidationError("Station name is required"))
        if (input.stationAddress.isBlank()) return Result.failure(AppError.ValidationError("Station address is required"))

        // Registration starts a new tenant. Clear any previous account's local
        // cache before creating the first station so it cannot be inherited.
        // Pending changes belong to the previous tenant and must be synced
        // before switching accounts; surface that as a normal UI error rather
        // than allowing the repository to crash the registration coroutine.
        val preparation = authRepository.prepareForRegistration()
        if (preparation.isFailure) {
            return Result.failure(
                preparation.exceptionOrNull()
                    ?: AppError.ValidationError("Finish syncing the previous account before registering another account")
            )
        }

        val now = System.currentTimeMillis()
        val station = Station(
            id = 0,
            name = input.stationName,
            description = input.stationDescription ?: "Clean, safe, and quality purified refilled water",
            phone = input.stationPhone.ifBlank { input.ownerPhone },
            email = input.ownerEmail,
            address = input.stationAddress,
            barangay = input.barangay,
            city = input.city ?: "Cagayan de Oro",
            province = input.province ?: "Misamis Oriental",
            latitude = input.latitude,
            longitude = input.longitude,
            placeId = input.placeId,
            openingTime = input.openingTime ?: "07:00 AM",
            closingTime = input.closingTime ?: "06:00 PM",
            isOpen = true,
            createdAt = now,
            updatedAt = now
        )

        // Reuse an exact unfinished station draft after a retry.
        val stationId = stationRepository.getAllStations()
            .firstOrNull {
                it.name.trim().equals(station.name.trim(), ignoreCase = true) &&
                    it.address.trim().equals(station.address.trim(), ignoreCase = true) &&
                    it.phone == station.phone
            }?.id ?: stationRepository.createStation(station)

        val owner = Owner(
            id = 0,
            stationId = stationId,
            name = input.ownerName,
            phone = input.ownerPhone,
            email = input.ownerEmail,
            createdAt = now,
            updatedAt = now
        )
        val registerResult = authRepository.register(owner, input.password, station, input.marketingConsent)
        if (registerResult.isFailure) {
            return Result.failure(registerResult.exceptionOrNull() ?: AppError.ValidationError("Registration failed"))
        }

        databaseSeeder.seedPlansIfEmpty()
        // Supabase provisioning may bind the draft to an existing cloud
        // station and therefore return a different local Room id. Continue
        // onboarding with the canonical id so the trial cache and dashboard
        // observe the same station as the authenticated owner.
        Result.success(registerResult.getOrNull()?.stationId ?: stationId)
    }

    suspend fun isOwnerTrialConsumed(): Result<Boolean> = try {
        Result.success(subscriptionRepository.isOwnerTrialConsumed())
    } catch (error: Throwable) {
        Result.failure(AppError.NetworkError("We could not verify trial eligibility. Check your connection and try again."))
    }

    suspend fun activateSelectedPlan(
        stationId: Long,
        subscriptionPlanId: String,
        subscriptionBilling: String
    ): Result<Unit> = registrationMutex.withLock {
        val chosenPlanCode = SubscriptionPlanCode.fromString(subscriptionPlanId)
        val cycle = BillingCycle.fromString(subscriptionBilling)
        try {
            subscriptionRepository.createTrial(stationId, chosenPlanCode, cycle)
            Result.success(Unit)
        } catch (error: Throwable) {
            val trialAlreadyUsed = generateSequence(error) { it.cause }
                .any { cause ->
                    cause.message?.contains("already used its free trial", ignoreCase = true) == true
                }
            if (trialAlreadyUsed) {
                Result.failure(
                    AppError.ValidationError(
                        "This phone number or recovery email already used its free trial. Choose a paid plan without a trial, or choose Free."
                    )
                )
            } else {
                Result.failure(
                    AppError.NetworkError(
                        "Account setup is ready, but subscription setup could not be completed. Check your connection and choose the plan again."
                    )
                )
            }
        }
    }

    private companion object {
        val registrationMutex = Mutex()
    }
}
