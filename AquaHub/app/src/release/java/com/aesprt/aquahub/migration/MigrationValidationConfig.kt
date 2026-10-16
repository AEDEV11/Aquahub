package com.aesprt.aquahub.migration

import android.content.Context

/** Release builds cannot enable migration validation controls. */
object MigrationValidationConfig {
    fun initialize(context: Context) = Unit
    fun beforeMirrorWrite(resourceType: String, operation: String) = Unit
}
