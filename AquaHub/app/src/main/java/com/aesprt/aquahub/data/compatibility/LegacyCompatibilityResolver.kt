package com.aesprt.aquahub.data.compatibility

/**
 * Centralizes the narrow cases where the owner app must use its local cache
 * while a record is not yet represented by a canonical Supabase UUID.
 * A Supabase authorization/business error is never a compatibility reason.
 */
class LegacyCompatibilityResolver {
    enum class DeliveryPath { SUPABASE_PRIMARY, ROOM_CACHE_ONLY }

    /** Reasons that are allowed to select a local compatibility read path. */
    enum class CompatibilityReason {
        LEGACY_ORDER_RECORD,
        MISSING_CANONICAL_MAPPING,
        LEGACY_STORAGE_ASSET,
        COMPATIBILITY_SESSION_REQUIRED,
    }

    data class CompatibilityDecision(
        val path: DeliveryPath,
        val reason: CompatibilityReason? = null,
    )

    fun deliveryDecision(
        hasSupabaseSession: Boolean,
        hasCanonicalOrderId: Boolean,
        hasCanonicalRiderId: Boolean = true,
        requiresRiderMapping: Boolean = false,
    ): CompatibilityDecision {
        if (hasSupabaseSession && hasCanonicalOrderId &&
            (!requiresRiderMapping || hasCanonicalRiderId)
        ) {
            return CompatibilityDecision(DeliveryPath.SUPABASE_PRIMARY)
        }
        return CompatibilityDecision(
            path = DeliveryPath.ROOM_CACHE_ONLY,
            reason = if (!hasSupabaseSession) {
                CompatibilityReason.COMPATIBILITY_SESSION_REQUIRED
            } else {
                CompatibilityReason.MISSING_CANONICAL_MAPPING
            },
        )
    }

    fun deliveryPath(
        hasSupabaseSession: Boolean,
        hasCanonicalOrderId: Boolean,
        hasCanonicalRiderId: Boolean = true,
        requiresRiderMapping: Boolean = false,
    ): DeliveryPath {
        return deliveryDecision(
            hasSupabaseSession = hasSupabaseSession,
            hasCanonicalOrderId = hasCanonicalOrderId,
            hasCanonicalRiderId = hasCanonicalRiderId,
            requiresRiderMapping = requiresRiderMapping,
        ).path
    }

    /** Authorization, RLS, tenant, and validation failures are never compatibility reasons. */
    fun mayFallbackAfterSupabaseFailure(
        error: Throwable,
        reason: CompatibilityReason? = null,
    ): Boolean = false
}
