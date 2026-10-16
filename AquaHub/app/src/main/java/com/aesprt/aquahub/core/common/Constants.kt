package com.aesprt.aquahub.core.common

object Constants {
    const val DB_NAME = "aquahub_database"
    const val DEFAULT_STATION_ID = 1L

    // Delivery fee config
    const val BASE_DELIVERY_FEE = 20.0
    const val FREE_DELIVERY_KM = 2.0
    const val PER_KM_RATE = 5.0

    // DataStore keys
    const val DS_KEY_IS_LOGGED_IN = "is_logged_in"
    const val DS_KEY_OWNER_ID = "owner_id"
    const val DS_KEY_STATION_ID = "station_id"
    const val DS_KEY_OWNER_NAME = "owner_name"
    const val DS_KEY_HAS_COMPLETED_ONBOARDING = "has_completed_onboarding"

    // Order number prefix
    const val ORDER_PREFIX = "AQH"

    // Earth radius for Haversine
    const val EARTH_RADIUS_KM = 6371.0
}
