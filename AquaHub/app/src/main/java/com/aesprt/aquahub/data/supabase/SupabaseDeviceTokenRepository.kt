package com.aesprt.aquahub.data.supabase

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant

/** Supabase is the device-token registry; Firebase Messaging remains the FCM transport. */
object SupabaseDeviceTokenRepository {
    @Serializable
    private data class DeviceTokenRecord(
        @SerialName("profile_id") val profileId: String,
        @SerialName("token_hash") val tokenHash: String,
        val token: String,
        val platform: String,
        @SerialName("last_seen_at") val lastSeenAt: String
    )

    suspend fun upsert(token: String) {
        val profileId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id ?: return
        val record = DeviceTokenRecord(
            profileId = profileId,
            tokenHash = sha256(token),
            token = token,
            platform = "ANDROID",
            lastSeenAt = Instant.now().toString()
        )
        SupabaseClientProvider.client.from("device_tokens").upsert(record) {
            onConflict = "profile_id,token_hash"
        }
    }

    suspend fun remove(token: String) {
        val profileId = SupabaseClientProvider.client.auth.currentUserOrNull()?.id ?: return
        SupabaseClientProvider.client.from("device_tokens").delete {
            filter {
                eq("profile_id", profileId)
                eq("token_hash", sha256(token))
            }
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
