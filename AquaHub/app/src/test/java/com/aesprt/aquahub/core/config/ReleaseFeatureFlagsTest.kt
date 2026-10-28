package com.aesprt.aquahub.core.config

import com.aesprt.aquahub.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseFeatureFlagsTest {
    @Test
    fun `offline pos flag is explicit for each app flavor`() {
        when (BuildConfig.FLAVOR) {
            "production" -> assertFalse(ReleaseFeatureFlags.offlinePosEnabled)
            "qa" -> assertTrue(ReleaseFeatureFlags.offlinePosEnabled)
            else -> throw AssertionError("Unexpected application flavor: ${BuildConfig.FLAVOR}")
        }
    }

    @Test
    fun `disabled release rejects offline checkout, stock lease, and submission`() {
        assertFalse(canCreateOfflineCheckout(featureEnabled = false, isOnline = false, hasValidLease = true))
        assertFalse(canRequestOfflineStockLease(featureEnabled = false, isOnline = true))
        assertFalse(canSubmitWalkInSale(isOnline = false, offlinePosEnabled = false, hasValidOfflineLease = true))
    }

    @Test
    fun `offline-enabled QA retains its explicitly enabled path`() {
        assertTrue(canCreateOfflineCheckout(featureEnabled = true, isOnline = false, hasValidLease = true))
        assertTrue(canRequestOfflineStockLease(featureEnabled = true, isOnline = true))
        assertTrue(canSubmitWalkInSale(isOnline = false, offlinePosEnabled = true, hasValidOfflineLease = true))
        assertEquals(false, canSubmitWalkInSale(isOnline = false, offlinePosEnabled = true, hasValidOfflineLease = false))
    }
}
