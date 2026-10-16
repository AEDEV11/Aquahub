package com.aesprt.aquahub.data.supabase.subscription

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionSnapshotConsistencyTest {
    private val effective = EffectiveEntitlementsResponse(
        organizationId = "organization",
        subscriptionId = "subscription",
        subscriptionPlanId = "starter-plan",
        subscriptionUpdatedAt = "2026-10-10T01:02:03.123456+00:00",
        subscriptionStatus = "ACTIVE",
        subscriptionBillingCycle = "MONTHLY",
        currentPeriodEnd = 2_000L,
        planCode = "STARTER",
        status = "ACTIVE",
    )

    private val remote = SupabaseSubscriptionRow(
        id = "subscription",
        organizationId = "organization",
        planId = "starter-plan",
        status = "ACTIVE",
        billingCycle = "MONTHLY",
        currentPeriodEnd = 2_000L,
        updatedAt = "2026-10-10T01:02:03.123456Z",
    )

    @Test
    fun `same server revision accepts equivalent timezone formats`() {
        assertTrue(isSubscriptionSnapshotConsistent(effective, remote))
    }

    @Test
    fun `renewal or plan change between reads is rejected`() {
        assertFalse(isSubscriptionSnapshotConsistent(effective, remote.copy(currentPeriodEnd = 3_000L)))
        assertFalse(isSubscriptionSnapshotConsistent(effective, remote.copy(planId = "business-plan")))
        assertFalse(isSubscriptionSnapshotConsistent(effective, remote.copy(status = "SUSPENDED")))
        assertFalse(isSubscriptionSnapshotConsistent(effective, remote.copy(billingCycle = "ANNUAL")))
        assertFalse(isSubscriptionSnapshotConsistent(effective, remote.copy(updatedAt = "2026-10-10T01:02:04Z")))
    }

    @Test
    fun `no billing row only matches an effective Free placeholder`() {
        val noRow = EffectiveEntitlementsResponse(organizationId = "organization", planCode = "FREE")
        val placeholder = SupabaseSubscriptionRow(
            id = "effective-free",
            organizationId = "organization",
            status = "FREE",
            billingCycle = "MONTHLY",
        )
        assertTrue(isSubscriptionSnapshotConsistent(noRow, placeholder))
        assertFalse(isSubscriptionSnapshotConsistent(noRow.copy(planCode = "BUSINESS"), placeholder))
        assertFalse(isSubscriptionSnapshotConsistent(effective, placeholder))
        assertFalse(isSubscriptionSnapshotConsistent(noRow, placeholder.copy(organizationId = "other")))
    }
}
