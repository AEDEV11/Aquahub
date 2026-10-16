package com.aesprt.aquahub.data.supabase.workspace

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.domain.model.Owner
import com.aesprt.aquahub.domain.model.Station
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SupabaseWorkspaceProvisioningResult(
    val profile_id: String,
    val organization_id: String,
    val member_id: String,
    val station_id: String,
    val firebase_business_id: String? = null,
    val firebase_station_id: String? = null
)

/** Writes the owner/workspace boundary to the hosted Supabase database. */
class SupabaseWorkspaceDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun provisionOwnerWorkspace(owner: Owner, station: Station): SupabaseWorkspaceProvisioningResult {
        val result = supabase.postgrest.rpc(
            function = "provision_owner_workspace",
            parameters = buildJsonObject {
                put("p_owner_name", owner.name)
                put("p_phone", owner.phone)
                put("p_email", owner.email)
                put("p_station_name", station.name)
                put("p_station_description", station.description)
                put("p_station_phone", station.phone)
                put("p_station_address", station.address)
                put("p_barangay", station.barangay)
                put("p_city", station.city)
                put("p_province", station.province)
                put("p_latitude", station.latitude)
                put("p_longitude", station.longitude)
                put("p_place_id", station.placeId)
                put("p_opening_time", station.openingTime)
                put("p_closing_time", station.closingTime)
            }
        )
        return result.decodeAs<SupabaseWorkspaceProvisioningResult>()
    }

    suspend fun repairLegacyOwnerWorkspace(
        owner: Owner,
        station: Station,
        firebaseBusinessId: String,
        firebaseStationId: String,
        firebaseBusinessName: String? = null
    ): SupabaseWorkspaceProvisioningResult {
        val result = supabase.postgrest.rpc(
            function = "provision_owner_workspace_legacy_safe",
            parameters = buildJsonObject {
                put("p_firebase_business_id", firebaseBusinessId)
                put("p_firebase_station_id", firebaseStationId)
                put("p_business_name", firebaseBusinessName?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: "${owner.name.trim()}'s AquaHub Business")
                put("p_owner_name", owner.name)
                put("p_phone", owner.phone)
                put("p_email", owner.email)
                put("p_station_name", station.name)
                put("p_station_description", station.description)
                put("p_station_phone", station.phone)
                put("p_station_address", station.address)
                put("p_barangay", station.barangay)
                put("p_city", station.city)
                put("p_province", station.province)
                put("p_latitude", station.latitude)
                put("p_longitude", station.longitude)
                put("p_place_id", station.placeId)
                put("p_opening_time", station.openingTime)
                put("p_closing_time", station.closingTime)
            }
        )
        return result.decodeAs<SupabaseWorkspaceProvisioningResult>()
    }
}
