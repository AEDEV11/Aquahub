package com.aesprt.aquahub.core.config

import com.aesprt.aquahub.BuildConfig

/** Release capabilities that must be fixed by the signed app variant. */
object ReleaseFeatureFlags {
    val offlinePosEnabled: Boolean
        get() = BuildConfig.OFFLINE_POS_ENABLED

    const val OFFLINE_POS_DISABLED_MESSAGE =
        "Offline sales are unavailable in this release. Reconnect to complete this sale online."

    const val OFFLINE_POS_RECOVERY_MESSAGE =
        "Saved offline cash records are preserved for review and are not being submitted or counted as confirmed sales in this release."
}

internal fun canCreateOfflineCheckout(
    featureEnabled: Boolean,
    isOnline: Boolean,
    hasValidLease: Boolean,
): Boolean = featureEnabled && !isOnline && hasValidLease

internal fun canRequestOfflineStockLease(
    featureEnabled: Boolean,
    isOnline: Boolean,
): Boolean = featureEnabled && isOnline

internal fun canSubmitWalkInSale(
    isOnline: Boolean,
    offlinePosEnabled: Boolean,
    hasValidOfflineLease: Boolean,
): Boolean = isOnline || canCreateOfflineCheckout(
    featureEnabled = offlinePosEnabled,
    isOnline = isOnline,
    hasValidLease = hasValidOfflineLease,
)
