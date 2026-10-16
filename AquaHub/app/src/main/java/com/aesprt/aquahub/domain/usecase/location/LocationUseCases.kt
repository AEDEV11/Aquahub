package com.aesprt.aquahub.domain.usecase.location

import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.domain.model.GeoPoint
import com.aesprt.aquahub.domain.model.LocationSuggestion
import com.aesprt.aquahub.domain.model.StationLocation
import com.aesprt.aquahub.domain.repository.LocationRepository
import com.aesprt.aquahub.domain.repository.StationRepository

class SearchLocationsUseCase(private val locationRepository: LocationRepository) {
    suspend operator fun invoke(query: String): Result<List<LocationSuggestion>> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return Result.success(emptyList())
        return locationRepository.searchPlaces(trimmed)
    }
}

class GetPlaceDetailsUseCase(private val locationRepository: LocationRepository) {
    suspend operator fun invoke(placeId: String): Result<StationLocation> {
        if (placeId.isBlank()) return Result.failure(AppError.ValidationError("Invalid place ID"))
        return locationRepository.getPlaceDetails(placeId)
    }
}

class ReverseGeocodeUseCase(private val locationRepository: LocationRepository) {
    suspend operator fun invoke(latitude: Double, longitude: Double): Result<StationLocation> {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return Result.failure(AppError.ValidationError("Coordinates out of valid range: ($latitude, $longitude)"))
        }
        return locationRepository.reverseGeocode(latitude, longitude)
    }
}

class GetCurrentDeviceLocationUseCase(private val locationRepository: LocationRepository) {
    suspend operator fun invoke(): Result<GeoPoint?> {
        return locationRepository.getCurrentDeviceLocation()
    }
}

class UpdateStationLocationUseCase(
    private val stationRepository: StationRepository
) {
    suspend operator fun invoke(
        stationId: Long,
        location: StationLocation
    ): Result<Unit> {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) {
            return Result.failure(AppError.ValidationError("Invalid coordinates: (${location.latitude}, ${location.longitude})"))
        }
        if (location.formattedAddress.isBlank()) {
            return Result.failure(AppError.ValidationError("Station address cannot be blank"))
        }

        val existing = stationRepository.getStation(stationId)
            ?: return Result.failure(AppError.NotFoundError("Station", stationId))

        val updated = existing.copy(
            address = location.formattedAddress,
            barangay = location.barangay ?: existing.barangay,
            city = location.city ?: existing.city,
            province = location.province ?: existing.province,
            latitude = location.latitude,
            longitude = location.longitude,
            placeId = location.placeId,
            updatedAt = System.currentTimeMillis()
        )

        stationRepository.updateStation(updated)
        return Result.success(Unit)
    }
}
