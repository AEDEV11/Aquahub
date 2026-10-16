package com.aesprt.aquahub.data.offline

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.ktor.client.call.body
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class OfflinePosAuthorizationResponse(
    @SerialName("grant_token") val grantToken: String,
    @SerialName("lease_token") val leaseToken: String,
    @SerialName("server_time_ms") val serverTimeMs: Long
)

@Serializable
data class OfflinePosCommitResponse(
    @SerialName("operation_id") val operationId: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("order_number") val orderNumber: String? = null,
    val status: String,
    val duplicate: Boolean = false
)

class SupabaseOfflinePosDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun getStationOrganizationId(stationId: String): String? = supabase.from("stations").select {
        filter { eq("id", stationId); eq("is_active", true) }
    }.decodeList<OfflineStationIdentity>().singleOrNull()?.organizationId

    suspend fun authorize(
        organizationId: String,
        stationId: String,
        deviceId: String,
        publicKeyDerBase64: String,
        verificationPublicKeySha256: String,
        grantId: String,
        leaseId: String,
        items: List<Pair<String, Int>>,
        elapsedRealtimeAnchorMs: Long,
        bootCount: Int,
        grantDurationHours: Int = 8,
        leaseDurationHours: Int = 8
    ): OfflinePosAuthorizationResponse = supabase.functions.invoke(
        function = "offline-pos",
        body = buildJsonObject {
            put("action", "authorize")
            put("organization_id", organizationId)
            put("station_id", stationId)
            put("device_id", deviceId)
            put("public_key_der", publicKeyDerBase64)
            put("verification_public_key_sha256", verificationPublicKeySha256)
            put("grant_id", grantId)
            put("lease_id", leaseId)
            put("grant_duration_hours", grantDurationHours)
            put("lease_duration_hours", leaseDurationHours)
            put("elapsed_realtime_anchor_ms", elapsedRealtimeAnchorMs)
            put("boot_count", bootCount)
            put("items", buildJsonArray {
                items.forEach { (productId, quantity) ->
                    add(buildJsonObject {
                        put("product_id", productId)
                        put("quantity", quantity)
                    })
                }
            })
        }
    ).body()

    suspend fun commit(
        deviceId: String,
        grantToken: String,
        leaseToken: String,
        canonicalPayload: String,
        payloadDigest: String,
        deviceSignature: String
    ): OfflinePosCommitResponse = supabase.functions.invoke(
        function = "offline-pos",
        body = buildJsonObject {
            put("action", "commit")
            put("device_id", deviceId)
            put("grant_token", grantToken)
            put("lease_token", leaseToken)
            put("canonical_payload", canonicalPayload)
            put("payload_digest", payloadDigest)
            put("device_signature", deviceSignature)
        }
    ).body()
}

@Serializable
private data class OfflineStationIdentity(
    val id: String,
    @SerialName("organization_id") val organizationId: String
)
