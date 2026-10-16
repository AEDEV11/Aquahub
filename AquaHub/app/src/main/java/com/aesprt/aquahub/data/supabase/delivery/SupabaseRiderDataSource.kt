package com.aesprt.aquahub.data.supabase.delivery

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.domain.model.Rider
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SupabaseRiderRow(
    val id: String,
    @SerialName("firebase_id") val legacyId: String? = null,
    @SerialName("station_id") val stationId: String,
    @SerialName("profile_id") val profileId: String? = null,
    val name: String,
    val phone: String,
    @SerialName("vehicle_type") val vehicleType: String? = null,
    @SerialName("vehicle_name") val vehicleName: String? = null,
    @SerialName("plate_number") val plateNumber: String? = null,
    val rating: Double = 0.0,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("is_online") val isOnline: Boolean,
    @SerialName("is_available") val isAvailable: Boolean,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("active_order_count") val activeOrderCount: Int = 0,
    @SerialName("client_updated_at") val clientUpdatedAt: Long? = null,
    @SerialName("created_at") val createdAtText: String,
    @SerialName("updated_at") val updatedAtText: String
)

/** Owner-managed rider fields only; profile linkage and live rider telemetry remain server-owned. */
class SupabaseRiderDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun listForStation(stationId: String): List<SupabaseRiderRow> =
        supabase.from("riders").select {
            filter { eq("station_id", stationId) }
            order("name", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
        }.decodeList()

    suspend fun upsert(stationId: String, riderId: String, rider: Rider) {
        val payload = buildJsonObject {
            put("id", riderId)
            put("station_id", stationId)
            put("name", rider.name.trim())
            put("phone", rider.phone.trim())
            put("vehicle_type", rider.vehicleType)
            put("vehicle_name", rider.vehicleName)
            put("plate_number", rider.plateNumber)
            put("rating", rider.rating)
            put("is_active", rider.isActive)
            put("is_available", rider.isAvailable)
            put("client_updated_at", rider.updatedAt)
        }
        supabase.from("riders").upsert(payload) { onConflict = "id" }
    }
}
