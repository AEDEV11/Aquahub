package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.Rider
import kotlinx.coroutines.flow.Flow

interface RiderRepository {
    fun observeRiders(stationId: Long): Flow<List<Rider>>
    fun observeAvailableRiders(stationId: Long): Flow<List<Rider>>
    suspend fun refreshRiders(stationId: Long): Result<Unit> = Result.success(Unit)
    suspend fun getRiderById(riderId: Long): Rider?
    /**
     * Resolve the authenticated rider to a local rider record.
     *
     * When a station is supplied the lookup is restricted to that station. This
     * matters because a business can have more than one rider record with the
     * same phone number across different branches.
     */
    suspend fun getRiderByPhone(phone: String, stationId: Long? = null): Rider?
    suspend fun countActiveOrders(riderId: Long): Int
    suspend fun insertRider(rider: Rider): Long
    suspend fun updateRider(rider: Rider)
    suspend fun updateRiderAvailability(riderId: Long, isAvailable: Boolean)
    suspend fun incrementActiveOrderCount(riderId: Long)
    suspend fun decrementActiveOrderCount(riderId: Long)
}
