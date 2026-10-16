package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.Subscription
import com.aesprt.aquahub.domain.model.SubscriptionHistoryEntry
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.domain.model.SubscriptionStatus
import kotlinx.coroutines.flow.Flow

interface SubscriptionRepository {

    fun observeCurrentSubscription(stationId: Long): Flow<Subscription?>

    suspend fun getCurrentSubscription(stationId: Long): Subscription?

    suspend fun getCurrentBusinessSubscription(): Subscription?

    /** Refreshes the local cache from the Supabase subscription authority. */
    suspend fun refreshFromSupabase(): Boolean = false

    suspend fun isOwnerTrialConsumed(): Boolean

    suspend fun createTrial(
        stationId: Long,
        planCode: SubscriptionPlanCode = SubscriptionPlanCode.BUSINESS,
        billingCycle: BillingCycle = BillingCycle.MONTHLY
    ): Subscription

    suspend fun activatePlan(
        stationId: Long,
        planId: Long,
        billingCycle: BillingCycle
    ): Subscription

    suspend fun cancelSubscription(
        stationId: Long,
        cancelAtPeriodEnd: Boolean
    ): Subscription?

    suspend fun resumeSubscription(
        stationId: Long
    ): Subscription?

    suspend fun renewSubscription(
        stationId: Long
    ): Subscription?

    suspend fun upgradePlan(
        stationId: Long,
        newPlanId: Long,
        billingCycle: BillingCycle
    ): Subscription

    suspend fun schedulePlanChange(
        stationId: Long,
        planId: Long,
        effectiveAt: Long
    ): Subscription?

    suspend fun updateSubscriptionStatus(
        subscriptionId: Long,
        status: SubscriptionStatus
    )

    suspend fun getSubscriptionHistory(
        stationId: Long
    ): List<SubscriptionHistoryEntry>

    suspend fun processSubscriptionExpiration(
        stationId: Long
    ): Subscription?

    suspend fun replicateSubscription(
        sourceStationId: Long,
        targetStationId: Long
    ): Subscription?
}
