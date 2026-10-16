package com.aesprt.aquahub.data.supabase.marketing

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class OwnerMarketingPreference(
    val subscribed: Boolean = false,
    val suppressed: Boolean = false,
    val email_available: Boolean = false
)

/** Authenticated, owner-scoped consent operations. Reach credentials stay on the server. */
class SupabaseMarketingPreferencesDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun getMyPreference(): OwnerMarketingPreference {
        val result = supabase.postgrest.rpc(function = "get_my_marketing_preference")
        return result.decodeList<OwnerMarketingPreference>().firstOrNull() ?: OwnerMarketingPreference()
    }

    suspend fun setMyPreference(subscribed: Boolean): OwnerMarketingPreference {
        val result = supabase.postgrest.rpc(
            function = "set_my_marketing_preference",
            parameters = buildJsonObject { put("p_subscribed", JsonPrimitive(subscribed)) }
        )
        return result.decodeList<OwnerMarketingPreference>().firstOrNull() ?: OwnerMarketingPreference()
    }
}

