package com.aesprt.aquahub.migration

import android.content.Context

/** Debug-only controls for the hosted Firebase -> Supabase migration runbook. */
object MigrationValidationConfig {
    const val ACTION_SET_MIRROR_FAILURE = "com.aesprt.aquahub.DEBUG_SET_MIRROR_FAILURE"
    const val ACTION_CREATE_CUSTOMER = "com.aesprt.aquahub.DEBUG_CREATE_CUSTOMER"
    const val ACTION_UPDATE_CUSTOMER = "com.aesprt.aquahub.DEBUG_UPDATE_CUSTOMER"
    const val ACTION_ARCHIVE_CUSTOMER = "com.aesprt.aquahub.DEBUG_ARCHIVE_CUSTOMER"
    const val ACTION_SYNC_NOW = "com.aesprt.aquahub.DEBUG_SYNC_NOW"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_CUSTOMER_ID = "customer_id"
    const val EXTRA_NOTES = "notes"

    private const val PREFS = "migration_validation_debug"
    private const val FORCE_MIRROR_FAILURE = "force_customer_mirror_failure"
    private var preferences: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun setForceCustomerMirrorFailure(enabled: Boolean) {
        preferences?.edit()?.putBoolean(FORCE_MIRROR_FAILURE, enabled)?.apply()
    }

    fun beforeMirrorWrite(resourceType: String, operation: String) {
        if (resourceType == "CUSTOMER" && preferences?.getBoolean(FORCE_MIRROR_FAILURE, false) == true) {
            throw InjectedMirrorFailureException(operation)
        }
    }

    class InjectedMirrorFailureException(operation: String) :
        IllegalStateException("Migration validation mirror failure injected for CUSTOMER $operation")
}
