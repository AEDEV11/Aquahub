package com.aesprt.aquahub.feature.settings.subscription

import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.GooglePlayBillingProducts
import com.aesprt.aquahub.domain.model.ResolvedSubscriptionState
import com.aesprt.aquahub.domain.model.Subscription
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.domain.model.SubscriptionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionBillingPolicyTest {
    @Test
    fun `expired Play subscription starts a new purchase`() {
        val subscription = playSubscription()

        assertFalse(
            SubscriptionBillingPolicy.shouldOpenPlayManagement(
                subscription,
                ResolvedSubscriptionState.EXPIRED
            )
        )
        assertNull(
            SubscriptionBillingPolicy.replacementProductId(
                subscription,
                ResolvedSubscriptionState.EXPIRED
            )
        )
    }

    @Test
    fun `active Play subscription remains eligible for plan replacement`() {
        val subscription = playSubscription()

        assertTrue(
            SubscriptionBillingPolicy.shouldOpenPlayManagement(
                subscription,
                ResolvedSubscriptionState.ACTIVE
            )
        )
        assertTrue(
            SubscriptionBillingPolicy.replacementProductId(
                subscription,
                ResolvedSubscriptionState.ACTIVE
            ) != null
        )
    }

    @Test
    fun `expired plan is selectable again`() {
        val subscription = playSubscription()

        assertFalse(
            SubscriptionBillingPolicy.isCurrentPlanSelection(
                subscription = subscription,
                resolvedState = ResolvedSubscriptionState.EXPIRED,
                planId = subscription.planId,
                billingCycle = subscription.billingCycle
            )
        )
    }

    @Test
    fun `active matching plan remains locked as current`() {
        val subscription = playSubscription()

        assertTrue(
            SubscriptionBillingPolicy.isCurrentPlanSelection(
                subscription = subscription,
                resolvedState = ResolvedSubscriptionState.ACTIVE,
                planId = subscription.planId,
                billingCycle = subscription.billingCycle
            )
        )
    }

    @Test
    fun `free plan opens Play cancellation only while a paid Play plan is still renewing`() {
        val active = playSubscription()

        assertTrue(SubscriptionBillingPolicy.canCancelToFree(active, ResolvedSubscriptionState.ACTIVE))
        assertTrue(SubscriptionBillingPolicy.canManagePaidPlayForFree(active, ResolvedSubscriptionState.PAST_DUE))
        assertFalse(SubscriptionBillingPolicy.canCancelToFree(active, ResolvedSubscriptionState.PAST_DUE))
        assertFalse(SubscriptionBillingPolicy.canCancelToFree(
            active.copy(cancelAtPeriodEnd = true), ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END
        ))
        assertFalse(SubscriptionBillingPolicy.canManagePaidPlayForFree(
            active.copy(cancelAtPeriodEnd = true), ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END
        ))
        assertFalse(SubscriptionBillingPolicy.canCancelToFree(active, ResolvedSubscriptionState.EXPIRED))
        assertFalse(SubscriptionBillingPolicy.canCancelToFree(
            active.copy(provider = null), ResolvedSubscriptionState.ACTIVE
        ))
    }

    @Test
    fun `past due and suspended Play plans require management before another checkout`() {
        val active = playSubscription()

        assertTrue(SubscriptionBillingPolicy.requiresPlayManagementBeforePlanChange(active, ResolvedSubscriptionState.PAST_DUE))
        assertTrue(SubscriptionBillingPolicy.requiresPlayManagementBeforePlanChange(active, ResolvedSubscriptionState.SUSPENDED))
        assertFalse(SubscriptionBillingPolicy.requiresPlayManagementBeforePlanChange(active, ResolvedSubscriptionState.ACTIVE))
        assertFalse(SubscriptionBillingPolicy.requiresPlayManagementBeforePlanChange(active, ResolvedSubscriptionState.EXPIRED))
    }

    @Test
    fun `effective Free with a Play owned product opens management before a new checkout`() {
        val free = playSubscription().copy(planCode = SubscriptionPlanCode.FREE, provider = null)
        val ownedProduct = GooglePlayBillingProducts.STARTER

        assertTrue(SubscriptionBillingPolicy.hasOwnedPlayWhileFree(free, ownedProduct))
        assertTrue(SubscriptionBillingPolicy.requiresPlayManagementBeforePlanChange(
            free, ResolvedSubscriptionState.ACTIVE, ownedProduct
        ))
        assertFalse(SubscriptionBillingPolicy.hasOwnedPlayWhileFree(free, null))
        assertFalse(SubscriptionBillingPolicy.hasOwnedPlayWhileFree(playSubscription(), ownedProduct))
    }

    @Test
    fun `free stays current on both billing cycle tabs`() {
        val free = playSubscription().copy(planId = 1L, planCode = SubscriptionPlanCode.FREE)

        assertTrue(SubscriptionBillingPolicy.isCurrentPlanSelection(
            subscription = free,
            resolvedState = ResolvedSubscriptionState.ACTIVE,
            planId = free.planId,
            billingCycle = BillingCycle.ANNUAL
        ))
    }

    @Test
    fun `secondary billing action follows Play subscription state`() {
        val active = playSubscription()

        assertEquals(
            SubscriptionBillingPolicy.SecondaryAction.CANCEL,
            SubscriptionBillingPolicy.secondaryAction(active, ResolvedSubscriptionState.ACTIVE)
        )
        assertEquals(
            SubscriptionBillingPolicy.SecondaryAction.RESUME,
            SubscriptionBillingPolicy.secondaryAction(
                active.copy(cancelAtPeriodEnd = true), ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END
            )
        )
        assertEquals(
            SubscriptionBillingPolicy.SecondaryAction.MANAGE_PAYMENT,
            SubscriptionBillingPolicy.secondaryAction(active, ResolvedSubscriptionState.PAST_DUE)
        )
        assertEquals(
            SubscriptionBillingPolicy.SecondaryAction.MANAGE_PAYMENT,
            SubscriptionBillingPolicy.secondaryAction(active, ResolvedSubscriptionState.SUSPENDED)
        )
        assertEquals(
            SubscriptionBillingPolicy.SecondaryAction.ACTIVATE,
            SubscriptionBillingPolicy.secondaryAction(active, ResolvedSubscriptionState.EXPIRED)
        )
        assertEquals(
            SubscriptionBillingPolicy.SecondaryAction.NONE,
            SubscriptionBillingPolicy.secondaryAction(
                active.copy(planCode = SubscriptionPlanCode.FREE), ResolvedSubscriptionState.ACTIVE
            )
        )
    }

    private fun playSubscription() = Subscription(
        id = 1L,
        stationId = 1L,
        planId = 2L,
        planCode = SubscriptionPlanCode.BUSINESS,
        status = SubscriptionStatus.ACTIVE,
        billingCycle = BillingCycle.MONTHLY,
        startedAt = 1L,
        currentPeriodStart = 1L,
        currentPeriodEnd = 2L,
        provider = "GOOGLE_PLAY"
    )
}
