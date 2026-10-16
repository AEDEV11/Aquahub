package com.aesprt.aquahub.data.observability

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Authentication telemetry boundary; QA uses a no-op implementation. */
interface AuthTelemetry {
    fun setUser(uid: String?)
    fun clearUser()
    fun login(method: String)
    fun signUp(method: String)
}

class FirebaseAuthTelemetry(context: Context) : AuthTelemetry {
    private val analytics = FirebaseAnalytics.getInstance(context)
    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun setUser(uid: String?) {
        analytics.setUserId(uid)
        crashlytics.setUserId(uid.orEmpty())
    }

    override fun clearUser() = setUser(null)

    override fun login(method: String) {
        analytics.logEvent(FirebaseAnalytics.Event.LOGIN, Bundle().apply {
            putString(FirebaseAnalytics.Param.METHOD, method)
        })
    }

    override fun signUp(method: String) {
        analytics.logEvent(FirebaseAnalytics.Event.SIGN_UP, Bundle().apply {
            putString(FirebaseAnalytics.Param.METHOD, method)
        })
    }
}

class NoOpAuthTelemetry : AuthTelemetry {
    override fun setUser(uid: String?) = Unit
    override fun clearUser() = Unit
    override fun login(method: String) = Unit
    override fun signUp(method: String) = Unit
}
