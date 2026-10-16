package com.aesprt.aquahub.data.supabase.staff

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.domain.repository.StaffDirectory
import com.aesprt.aquahub.domain.repository.StaffMember
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseStaffDataSource {
    suspend fun list(): StaffDirectory {
        val rows = SupabaseClientProvider.client.postgrest.rpc("staff_directory")
            .decodeList<StaffDirectoryRow>()
        return StaffDirectory(members = rows.map { it.toDomain() })
    }

    suspend fun create(name: String, phone: String, role: String, stationIds: List<String>): StaffMember =
        invokeMutation(buildJsonObject {
            put("operation", "create")
            put("name", name)
            put("phone", phone)
            put("role", role)
            put("station_ids", kotlinx.serialization.json.JsonArray(stationIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })

    suspend fun update(uid: String, name: String, phone: String, role: String, stationIds: List<String>): StaffMember =
        invokeMutation(buildJsonObject {
            put("operation", "update")
            put("uid", uid)
            put("name", name)
            put("phone", phone)
            put("role", role)
            put("station_ids", kotlinx.serialization.json.JsonArray(stationIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })

    suspend fun delete(uid: String) = invokeMutation(buildJsonObject {
        put("operation", "delete")
        put("uid", uid)
    })

    private suspend fun invokeMutation(body: kotlinx.serialization.json.JsonObject): StaffMember {
        val result = SupabaseClientProvider.client.functions.invoke("staff-admin", body = body)
        return result.body<StaffMutationResponse>().toDomain()
    }

    @Serializable
    private data class StaffDirectoryRow(
        val uid: String,
        val name: String = "",
        val phone: String = "",
        val role: String,
        @SerialName("is_active") val isActive: Boolean,
        @SerialName("station_ids") val stationIds: List<String> = emptyList(),
    ) {
        fun toDomain() = StaffMember(uid, name, phone, role, isActive, stationIds)
    }

    @Serializable
    private data class StaffMutationResponse(
        val uid: String,
        val name: String = "",
        val phone: String = "",
        val role: String,
        @SerialName("isActive") val isActive: Boolean,
        @SerialName("stationIds") val stationIds: List<String> = emptyList(),
        val deleted: Boolean = false,
    ) {
        fun toDomain() = StaffMember(uid, name, phone, role, isActive, stationIds)
    }
}
