package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.GeoPoint
import com.aesprt.aquahub.domain.model.LocationSuggestion
import com.aesprt.aquahub.domain.model.StationLocation

interface LocationRepository {
    suspend fun searchPlaces(query: String): Result<List<LocationSuggestion>>
    suspend fun getPlaceDetails(placeId: String): Result<StationLocation>
    suspend fun reverseGeocode(latitude: Double, longitude: Double): Result<StationLocation>
    suspend fun getCurrentDeviceLocation(): Result<GeoPoint?>
}
