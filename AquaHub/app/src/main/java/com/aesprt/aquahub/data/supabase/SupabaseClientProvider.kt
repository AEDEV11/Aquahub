package com.aesprt.aquahub.data.supabase

import com.aesprt.aquahub.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.functions.Functions
import io.ktor.client.engine.android.Android
import kotlin.time.Duration.Companion.seconds

/**
 * Creates the public Supabase client used by the Android app.
 *
 * The URL and publishable/anon key are build-time configuration values. A
 * service-role key is intentionally not supported by this class.
 */
object SupabaseClientProvider {
    val client by lazy {
        require(BuildConfig.SUPABASE_URL.isNotBlank()) {
            "Supabase URL is not configured for this build variant. Set the matching production or QA environment variables."
        }
        require(BuildConfig.SUPABASE_ANON_KEY.isNotBlank()) {
            "Supabase public key is not configured for this build variant. Set the matching production or QA environment variables."
        }
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            httpEngine = Android.create()
            // OTP delivery can be slower on mobile networks. Keep the auth
            // request bounded, but do not fail after the 1–10 second defaults
            // that commonly occur during a cold start or carrier handoff.
            requestTimeout = 30.seconds
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                flowType = FlowType.PKCE
                scheme = "aquahub"
                host = "auth"
            }
            install(Postgrest)
            install(Storage)
            install(Functions)
        }
    }
}
