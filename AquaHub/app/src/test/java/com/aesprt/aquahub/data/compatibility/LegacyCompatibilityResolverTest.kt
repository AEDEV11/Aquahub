package com.aesprt.aquahub.data.compatibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyCompatibilityResolverTest {
    private val resolver = LegacyCompatibilityResolver()

    @Test
    fun missingCanonicalMappingUsesRoomCacheOnly() {
        val decision = resolver.deliveryDecision(
            hasSupabaseSession = false,
            hasCanonicalOrderId = false,
        )

        assertEquals(LegacyCompatibilityResolver.DeliveryPath.ROOM_CACHE_ONLY, decision.path)
        assertEquals(
            LegacyCompatibilityResolver.CompatibilityReason.COMPATIBILITY_SESSION_REQUIRED,
            decision.reason,
        )
    }

    @Test
    fun authorizationFailuresNeverEnableAnyFallback() {
        assertFalse(
            resolver.mayFallbackAfterSupabaseFailure(
                error = IllegalStateException("RLS denied"),
                reason = LegacyCompatibilityResolver.CompatibilityReason.LEGACY_ORDER_RECORD,
            ),
        )
    }

    @Test
    fun transportAndAuthorizationFailuresNeverEnableFallback() {
        listOf(
            IllegalStateException("HTTP 401"),
            IllegalStateException("HTTP 403"),
            IllegalStateException("SQLSTATE 42501"),
            java.io.IOException("request timeout"),
        ).forEach { error ->
            assertFalse(resolver.mayFallbackAfterSupabaseFailure(error))
        }
    }
}
