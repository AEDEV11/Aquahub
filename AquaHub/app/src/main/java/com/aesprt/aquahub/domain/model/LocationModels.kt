package com.aesprt.aquahub.domain.model

data class GeoPoint(
    val latitude: Double,
    val longitude: Double
)

data class LocationSuggestion(
    val placeId: String,
    val primaryText: String,
    val secondaryText: String? = null
)

data class StationLocation(
    val placeId: String? = null,
    val formattedAddress: String,
    val latitude: Double,
    val longitude: Double,
    val barangay: String? = null,
    val city: String? = null,
    val province: String? = null
)
