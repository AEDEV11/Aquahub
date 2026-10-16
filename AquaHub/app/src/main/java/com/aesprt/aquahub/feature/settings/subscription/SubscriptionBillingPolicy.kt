package com.aesprt.aquahub.feature.settings.subscription

import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.GooglePlayBillingProducts
import com.aesprt.aquahub.domain.model.ResolvedSubscriptionState
import com.aesprt.aquahub.domain.model.Subscription
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.domain.model.SubscriptionStatus

/** Keeps expired Play purchases out of replacement and management flows. */
internal object SubscriptionBillingPolicy {
    enum class SecondaryAction { NONE, ACTIVATE, MANAGE_PAYMENT, RESUME, CANCEL }

    private val replaceableStates = setOf(
        ResolvedSubscriptionState.ACTIVE,
        ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END,
        ResolvedSubscriptionState.GRACE_PERIOD
    )

    fun shouldOpenPlayManagement(
        subscription: Subscription?,
        resolvedState: ResolvedSubscriptionState
    ): Boolean = subscription?.provider == "GOOGLE_PLAY" &&
        resolvedState != ResolvedSubscriptionState.EXPIRED

    fun canCancelToFree(
        subscription: Subscription?,
        resolvedState: ResolvedSubscriptionState
    ): Boolean = canManagePaidPlayForFree(subscription, resolvedState) &&
        resolvedState in setOf(
            ResolvedSubscriptionState.ACTIVE,
            ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END
        )

    fun canManagePaidPlayForFree(
        subscription: Subscription?,
        resolvedState: ResolvedSubscriptionState
    ): Boolean = subscription?.provider == "GOOGLE_PLAY" &&
        subscription.planCode != SubscriptionPlanCode.FREE &&
        !subscription.cancelAtPeriodEnd &&
        subscription.status != SubscriptionStatus.CANCELLED &&
        resolvedState != ResolvedSubscriptionState.EXPIRED

    fun requiresPlayManagementBeforePlanChange(
        subscription: Subscription?,
        resolvedState: ResolvedSubscriptionState,
        ownedPlayProductId: String? = null
    ): Boolean = hasOwnedPlayWhileFree(subscription, ownedPlayProductId) ||
        (subscription?.provider == "GOOGLE_PLAY" &&
            subscription.planCode != SubscriptionPlanCode.FREE &&
            resolvedState != ResolvedSubscriptionState.EXPIRED &&
            replacementProductId(subscription, resolvedState) == null)

    fun hasOwnedPlayWhileFree(subscription: Subscription?, ownedPlayProductId: String?): Boolean =
        ownedPlayProductId != null && (subscription == null || subscription.planCode == SubscriptionPlanCode.FREE)

    fun secondaryAction(
        subscription: Subscription?,
        resolvedState: ResolvedSubscriptionState
    ): SecondaryAction {
        if (subscription == null || subscription.planCode == SubscriptionPlanCode.FREE) return SecondaryAction.NONE
        if (subscription.provider != "GOOGLE_PLAY") {
            return if (resolvedState == ResolvedSubscriptionState.EXPIRED) SecondaryAction.ACTIVATE
                else SecondaryAction.NONE
        }
        if (resolvedState == ResolvedSubscriptionState.EXPIRED) return SecondaryAction.ACTIVATE
        if (resolvedState in setOf(
                ResolvedSubscriptionState.GRACE_PERIOD,
                ResolvedSubscriptionState.PAST_DUE,
                ResolvedSubscriptionState.SUSPENDED
            )
        ) {
            return SecondaryAction.MANAGE_PAYMENT
        }
        if (subscription.cancelAtPeriodEnd) return SecondaryAction.RESUME
        return if (resolvedState in replaceableStates) SecondaryAction.CANCEL else SecondaryAction.NONE
    }

    fun replacementProductId(
        subscription: Subscription?,
        resolvedState: ResolvedSubscriptionState
    ): String? = subscription
        ?.takeIf {
            it.provider == "GOOGLE_PLAY" &&
                it.planCode != SubscriptionPlanCode.FREE &&
                resolvedState in replaceableStates
        }
        ?.let { GooglePlayBillingProducts.forPlan(it.planCode) }

    fun isCurrentPlanSelection(
        subscription: Subscription?,
        resolvedState: ResolvedSubscriptionState,
        planId: Long,
        billingCycle: BillingCycle
    ): Boolean = resolvedState != ResolvedSubscriptionState.EXPIRED &&
        subscription?.planId == planId &&
        (subscription.planCode == SubscriptionPlanCode.FREE || subscription.billingCycle == billingCycle)
}
