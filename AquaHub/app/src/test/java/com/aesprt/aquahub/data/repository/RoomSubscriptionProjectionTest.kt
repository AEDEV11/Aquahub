package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.local.entity.SubscriptionEntity
import com.aesprt.aquahub.data.supabase.subscription.EffectiveEntitlementsResponse
import com.aesprt.aquahub.data.supabase.subscription.SupabaseSubscriptionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSubscriptionProjectionTest {
    @Test
    fun `expired paid row becomes usable Free access without retaining Play management state`() {
        val rawPaid = remote(status = "EXPIRED", periodEnd = 1_000L)
        val effective = EffectiveEntitlementsResponse(
            organizationId = "organization",
            planCode = "FREE",
            status = "EXPIRED", // Older deployed RPC versions returned this combination.
            features = mapOf("ORDER_MANAGEMENT" to true),
        )

        val local = projectEffectiveSubscription(1L, 10L, null, rawPaid, effective, 2_000L)

        assertEquals(10L, local.planId)
        assertEquals("FREE", local.status)
        assertNull(local.provider)
        assertNull(local.externalSubscriptionId)
        assertFalse(local.autoRenew)
        assertFalse(local.cancelAtPeriodEnd)
        assertEquals("EXPIRED", rawPaid.status)
    }

    @Test
    fun `cancelled Play purchase keeps paid access through its verified period`() {
        val rawPaid = remote(status = "ACTIVE_UNTIL_PERIOD_END", periodEnd = 5_000L)
        val effective = EffectiveEntitlementsResponse(
            organizationId = "organization",
            planCode = "STARTER",
            status = "ACTIVE_UNTIL_PERIOD_END",
            features = mapOf("MOBILE_APP_ORDERING" to true),
        )

        val local = projectEffectiveSubscription(1L, 20L, null, rawPaid, effective, 1_000L)

        assertEquals("ACTIVE_UNTIL_PERIOD_END", local.status)
        assertEquals("GOOGLE_PLAY", local.provider)
        assertTrue(local.cancelAtPeriodEnd)
        assertTrue(hasCurrentPaidAccess(local, 4_999L))
        assertFalse(hasCurrentPaidAccess(local, 5_000L))
    }

    @Test
    fun `offline grace access ends at its provider deadline`() {
        val grace = cachedPaid().copy(status = "GRACE_PERIOD", gracePeriodEndsAt = 3_000L)

        assertTrue(hasCurrentPaidAccess(grace, 2_999L))
        assertFalse(hasCurrentPaidAccess(grace, 3_000L))
        val free = projectOfflineFreeSubscription(grace, freePlanId = 10L, now = 3_000L)
        assertEquals("FREE", free.status)
        assertNull(free.provider)
        assertEquals(10L, free.planId)
    }

    private fun remote(status: String, periodEnd: Long) = SupabaseSubscriptionRow(
        id = "subscription",
        organizationId = "organization",
        status = status,
        billingCycle = "MONTHLY",
        currentPeriodEnd = periodEnd,
        provider = "GOOGLE_PLAY",
        externalSubscriptionId = "token-hash",
        cancelAtPeriodEnd = status == "ACTIVE_UNTIL_PERIOD_END",
    )

    private fun cachedPaid() = SubscriptionEntity(
        stationId = 1L,
        planId = 20L,
        status = "ACTIVE",
        billingCycle = "MONTHLY",
        startedAt = 1L,
        currentPeriodStart = 1L,
        currentPeriodEnd = 2_000L,
        trialStartedAt = null,
        trialEndsAt = null,
        cancelledAt = null,
        expiresAt = null,
        gracePeriodEndsAt = null,
        autoRenew = true,
        cancelAtPeriodEnd = false,
        pendingPlanId = null,
        pendingPlanEffectiveAt = null,
        provider = "GOOGLE_PLAY",
        externalSubscriptionId = "token-hash",
        externalCustomerId = null,
        lastPaymentId = null,
        currency = "PHP",
        createdAt = 1L,
        updatedAt = 1L,
    )
}
