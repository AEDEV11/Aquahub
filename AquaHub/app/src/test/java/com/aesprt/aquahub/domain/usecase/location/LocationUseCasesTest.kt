package com.aesprt.aquahub.domain.usecase.location

import com.aesprt.aquahub.domain.model.GeoPoint
import com.aesprt.aquahub.domain.model.LocationSuggestion
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.model.StationLocation
import com.aesprt.aquahub.domain.repository.LocationRepository
import com.aesprt.aquahub.domain.repository.StationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocationUseCasesTest {

    private val fakeLocationRepository = object : LocationRepository {
        override suspend fun searchPlaces(query: String): Result<List<LocationSuggestion>> {
            return if (query == "SM City") {
                Result.success(
                    listOf(
                        LocationSuggestion("place_123", "SM City CDO", "Upper Carmen, CDO"),
                        LocationSuggestion("place_456", "SM City Davao", "Ecoland, Davao City")
                    )
                )
            } else {
                Result.success(emptyList())
            }
        }

        override suspend fun getPlaceDetails(placeId: String): Result<StationLocation> {
            return if (placeId == "place_123") {
                Result.success(
                    StationLocation(
                        placeId = "place_123",
                        formattedAddress = "SM City CDO, Upper Carmen, Cagayan de Oro",
                        latitude = 8.4852,
                        longitude = 124.6472,
                        barangay = "Carmen",
                        city = "Cagayan de Oro",
                        province = "Misamis Oriental"
                    )
                )
            } else {
                Result.failure(Exception("Place not found"))
            }
        }

        override suspend fun reverseGeocode(latitude: Double, longitude: Double): Result<StationLocation> {
            return Result.success(
                StationLocation(
                    placeId = null,
                    formattedAddress = "Geocoded Station Address",
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }

        override suspend fun getCurrentDeviceLocation(): Result<GeoPoint?> {
            return Result.success(GeoPoint(8.4542, 124.6319))
        }
    }

    private val fakeStationRepository = object : StationRepository {
        private var savedStation: Station? = Station(
            id = 1L,
            name = "Test Station",
            address = "Initial Address",
            latitude = 8.0,
            longitude = 124.0
        )

        override fun observeStation(stationId: Long): Flow<Station?> = flowOf(savedStation)
        override suspend fun getStation(stationId: Long): Station? = savedStation
        override fun observeAllStations(): Flow<List<Station>> = flowOf(listOfNotNull(savedStation))
        override suspend fun getAllStations(): List<Station> = listOfNotNull(savedStation)
        override suspend fun createStation(station: Station): Long = 1L
        override suspend fun updateStation(station: Station) { savedStation = station }
        override suspend fun deleteStation(stationId: Long) { savedStation = null }
    }

    @Test
    fun `search locations ignores short queries below 2 chars`() = runTest {
        val useCase = SearchLocationsUseCase(fakeLocationRepository)

        val result1 = useCase("")
        assertTrue(result1.isSuccess)
        assertTrue(result1.getOrNull()!!.isEmpty())

        val result2 = useCase("S")
        assertTrue(result2.isSuccess)
        assertTrue(result2.getOrNull()!!.isEmpty())

        val result3 = useCase("SM City")
        assertTrue(result3.isSuccess)
        assertEquals(2, result3.getOrNull()!!.size)
    }

    @Test
    fun `reverse geocode validates coordinate ranges`() = runTest {
        val useCase = ReverseGeocodeUseCase(fakeLocationRepository)

        // Valid coordinates
        val valid = useCase(8.4542, 124.6319)
        assertTrue(valid.isSuccess)
        assertEquals(8.4542, valid.getOrNull()!!.latitude, 0.001)

        // Invalid latitude (> 90)
        val invalidLat = useCase(95.0, 124.6319)
        assertTrue(invalidLat.isFailure)

        // Invalid longitude (> 180)
        val invalidLng = useCase(8.4542, 195.0)
        assertTrue(invalidLng.isFailure)
    }

    @Test
    fun `update station location persists valid coordinates and address`() = runTest {
        val useCase = UpdateStationLocationUseCase(fakeStationRepository)

        val newLoc = StationLocation(
            formattedAddress = "Updated Physical Station St, CDO",
            latitude = 8.4821,
            longitude = 124.6512,
            city = "Cagayan de Oro"
        )

        val result = useCase(1L, newLoc)
        assertTrue(result.isSuccess)

        val updated = fakeStationRepository.getStation(1L)
        assertNotNull(updated)
        assertEquals("Updated Physical Station St, CDO", updated!!.address)
        assertEquals(8.4821, updated.latitude!!, 0.001)
        assertEquals(124.6512, updated.longitude!!, 0.001)
    }
}
