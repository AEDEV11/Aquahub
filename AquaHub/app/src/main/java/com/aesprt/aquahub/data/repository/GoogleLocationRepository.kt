package com.aesprt.aquahub.data.repository

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.os.Build
import android.util.Log
import com.aesprt.aquahub.BuildConfig
import com.aesprt.aquahub.domain.model.GeoPoint
import com.aesprt.aquahub.domain.model.LocationSuggestion
import com.aesprt.aquahub.domain.model.StationLocation
import com.aesprt.aquahub.domain.repository.LocationRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class GoogleLocationRepository(
    private val context: Context
) : LocationRepository {

    private var placesClient: PlacesClient? = null

    init {
        val apiKey = BuildConfig.MAPS_API_KEY
        if (apiKey.isNotBlank()) {
            try {
                if (!Places.isInitialized()) {
                    Places.initializeWithNewPlacesApiEnabled(context.applicationContext, apiKey)
                }
                placesClient = Places.createClient(context.applicationContext)
            } catch (e: Exception) {
                // Ignore initialization failures and fallback gracefully
                Log.e("adriel-testing", "Error: ${e.localizedMessage}", e)
                placesClient = null
            }
        }
    }

    override suspend fun searchPlaces(query: String): Result<List<LocationSuggestion>> = withContext(Dispatchers.IO) {
        val client = placesClient
        if (client != null && BuildConfig.MAPS_API_KEY.isNotBlank()) {
            try {
                val request = FindAutocompletePredictionsRequest.builder()
                    .setQuery(query)
                    .build()

                val response = Tasks.await(client.findAutocompletePredictions(request), 5, TimeUnit.SECONDS)
                val suggestions = response.autocompletePredictions.map { prediction ->
                    LocationSuggestion(
                        placeId = prediction.placeId,
                        primaryText = prediction.getPrimaryText(null).toString(),
                        secondaryText = prediction.getSecondaryText(null).toString()
                    )
                }
                return@withContext Result.success(suggestions)
            } catch (e: Exception) {
                Log.e("adriel-testing", "Error: ${e.localizedMessage}", e)
                // If Google Places SDK call fails, fallback to Android Geocoder below
            }
        }

        // Fallback to native Android Geocoder
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocationName(query, 5) { addresses ->
                        continuation.resume(addresses)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 5) ?: emptyList()
            }

            val suggestions = addresses.mapIndexed { idx, address ->
                val primary = address.featureName ?: address.thoroughfare ?: query
                val secondary = listOfNotNull(address.subLocality, address.locality, address.adminArea, address.countryName)
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                LocationSuggestion(
                    placeId = "geo_${address.latitude}_${address.longitude}_$idx",
                    primaryText = primary,
                    secondaryText = secondary.ifBlank { null }
                )
            }
            Result.success(suggestions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getPlaceDetails(placeId: String): Result<StationLocation> = withContext(Dispatchers.IO) {
        // If it was a Geocoder fallback pseudo-placeId
        if (placeId.startsWith("geo_")) {
            val parts = placeId.removePrefix("geo_").split("_")
            if (parts.size >= 2) {
                val lat = parts[0].toDoubleOrNull() ?: 8.4542
                val lng = parts[1].toDoubleOrNull() ?: 124.6319
                return@withContext reverseGeocode(lat, lng)
            }
        }

        val client = placesClient
        if (client != null && BuildConfig.MAPS_API_KEY.isNotBlank()) {
            try {
                val placeFields = listOf(
                    Place.Field.ID,
                    Place.Field.DISPLAY_NAME,
                    Place.Field.FORMATTED_ADDRESS,
                    Place.Field.LOCATION,
                    Place.Field.ADDRESS_COMPONENTS
                )
                val request = FetchPlaceRequest.newInstance(placeId, placeFields)
                val response = Tasks.await(client.fetchPlace(request), 5, TimeUnit.SECONDS)
                val place = response.place

                val lat = place.location?.latitude ?: 8.4542
                val lng = place.location?.longitude ?: 124.6319
                val address = place.formattedAddress ?: place.displayName ?: "Water Station Location"

                var barangay: String? = null
                var city: String? = null
                var province: String? = null

                place.addressComponents?.asList()?.forEach { comp ->
                    val types = comp.types
                    if (types.contains("sublocality") || types.contains("neighborhood")) {
                        barangay = comp.name
                    }
                    if (types.contains("locality") || types.contains("administrative_area_level_2")) {
                        city = comp.name
                    }
                    if (types.contains("administrative_area_level_1")) {
                        province = comp.name
                    }
                }

                return@withContext Result.success(
                    StationLocation(
                        placeId = place.id,
                        formattedAddress = address,
                        latitude = lat,
                        longitude = lng,
                        barangay = barangay,
                        city = city,
                        province = province
                    )
                )
            } catch (e: Exception) {
                Log.e("adriel-testing", "Error: ${e.localizedMessage}", e)
                // Fallback to reverse geocoding if place details failed
            }
        }

        Result.failure(Exception("Unable to retrieve place details for $placeId"))
    }

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): Result<StationLocation> = withContext(Dispatchers.IO) {
        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                        continuation.resume(addresses)
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(latitude, longitude, 1) ?: emptyList()
            }

            if (addresses.isNotEmpty()) {
                val address = addresses[0]
                val fullAddress = (0..address.maxAddressLineIndex)
                    .mapNotNull { address.getAddressLine(it) }
                    .joinToString(", ")
                    .ifBlank {
                        listOfNotNull(address.featureName, address.thoroughfare, address.subLocality, address.locality)
                            .joinToString(", ")
                    }

                Result.success(
                    StationLocation(
                        placeId = null,
                        formattedAddress = fullAddress.ifBlank { "Coordinates ($latitude, $longitude)" },
                        latitude = latitude,
                        longitude = longitude,
                        barangay = address.subLocality,
                        city = address.locality ?: address.subAdminArea,
                        province = address.adminArea
                    )
                )
            } else {
                Result.success(
                    StationLocation(
                        placeId = null,
                        formattedAddress = "Location ($latitude, $longitude)",
                        latitude = latitude,
                        longitude = longitude
                    )
                )
            }
        } catch (_: Exception) {
            // Even if geocoder fails, return the valid coordinates
            Result.success(
                StationLocation(
                    placeId = null,
                    formattedAddress = "Location (${String.format(Locale.US, "%.5f", latitude)}, ${String.format(Locale.US, "%.5f", longitude)})",
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentDeviceLocation(): Result<GeoPoint?> = withContext(Dispatchers.IO) {
        try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val location = Tasks.await(
                fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null),
                5,
                TimeUnit.SECONDS
            ) ?: Tasks.await(fusedClient.lastLocation, 3, TimeUnit.SECONDS)

            if (location != null) {
                Result.success(GeoPoint(location.latitude, location.longitude))
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
