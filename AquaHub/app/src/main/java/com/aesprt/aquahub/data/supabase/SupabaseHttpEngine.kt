package com.aesprt.aquahub.data.supabase

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android's HttpURLConnection engine disconnects synchronously from coroutine
 * cancellation handlers. Cancelling a response on the UI thread can then close
 * an SSL stream there and throw NetworkOnMainThreadException (Crashlytics
 * 4e33aa1d8b62a1925a39b953bd5a964e). Use OkHttp's call cancellation instead.
 */
internal fun createSupabaseHttpEngine(): HttpClientEngine = OkHttp.create()
