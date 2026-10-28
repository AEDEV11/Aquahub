package com.aesprt.aquahub.domain.usecase.subscription

import com.aesprt.aquahub.domain.common.TimeProvider
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlin.math.max

class ObserveCurrentSubscriptionUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    operator fun invoke(stationId: Long): Flow<Subscription?> {
        return subscriptionRepository.observeCurrentSubscription(stationId)
    }
}

class GetCurrentSubscriptionUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(stationId: Long): Subscription? {
        return subscriptionRepository.getCurrentSubscription(stationId)
    }
}

class GetCurrentBusinessSubscriptionUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(): Subscription? {
        return subscriptionRepository.getCurrentBusinessSubscription()
    }
}

class GetSubscriptionStatusUseCase(
    private val timeProvider: TimeProvider
) {
    operator fun invoke(subscription: Subscription?): ResolvedSubscriptionState {
        if (subscription == null) return ResolvedSubscriptionState.EXPIRED
        // The server represents the perpetual no-cost tier with status FREE
        // (not ACTIVE), and stores current_period_end as the provisioning time.
        // Keep accepting ACTIVE for older local replicas, but don't expire a
        // valid FREE entitlement based on its non-billing period timestamp.
        if (subscription.planCode == SubscriptionPlanCode.FREE &&
            subscription.status in setOf(SubscriptionStatus.FREE, SubscriptionStatus.ACTIVE)
        ) return ResolvedSubscriptionState.ACTIVE

        val now = timeProvider.now()

        if (subscription.status == SubscriptionStatus.SUSPENDED) return ResolvedSubscriptionState.SUSPENDED
        // The trial ends exactly at the server-provided deadline. There is no
        // grace period for trial accounts.
        if (subscription.status == SubscriptionStatus.TRIALING &&
            subscription.trialEndsAt != null && now >= subscription.trialEndsAt
        ) return ResolvedSubscriptionState.EXPIRED
        if (subscription.status == SubscriptionStatus.EXPIRED ||
            (subscription.expiresAt != null && now >= subscription.expiresAt)) return ResolvedSubscriptionState.EXPIRED
        if (subscription.status == SubscriptionStatus.PAST_DUE) return ResolvedSubscriptionState.PAST_DUE
        val end = if (subscription.status == SubscriptionStatus.TRIALING) {
            subscription.trialEndsAt ?: return ResolvedSubscriptionState.EXPIRED
        } else subscription.currentPeriodEnd
        if (subscription.cancelAtPeriodEnd ||
            subscription.status == SubscriptionStatus.CANCELLED ||
            subscription.status == SubscriptionStatus.ACTIVE_UNTIL_PERIOD_END
        ) {
            return if (now < end) ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END else ResolvedSubscriptionState.EXPIRED
        }
        if (subscription.status == SubscriptionStatus.GRACE_PERIOD) {
            return if (subscription.gracePeriodEndsAt?.let { now < it } == true)
                ResolvedSubscriptionState.GRACE_PERIOD else ResolvedSubscriptionState.EXPIRED
        }
        if (now < end) return if (subscription.status == SubscriptionStatus.TRIALING)
            ResolvedSubscriptionState.TRIAL_ACTIVE else ResolvedSubscriptionState.ACTIVE
        return if (subscription.gracePeriodEndsAt?.let { now < it } == true)
            ResolvedSubscriptionState.GRACE_PERIOD else ResolvedSubscriptionState.EXPIRED
    }
}

class GetSubscriptionRemainingDaysUseCase(
    private val timeProvider: TimeProvider
) {
    operator fun invoke(subscription: Subscription?): Int {
        if (subscription == null) return 0
        val now = timeProvider.now()

        val targetEnd = when {
            subscription.status == SubscriptionStatus.TRIALING && subscription.trialEndsAt != null -> subscription.trialEndsAt
            subscription.status == SubscriptionStatus.GRACE_PERIOD && subscription.gracePeriodEndsAt != null -> subscription.gracePeriodEndsAt
            now > subscription.currentPeriodEnd && subscription.gracePeriodEndsAt != null && now <= subscription.gracePeriodEndsAt -> subscription.gracePeriodEndsAt
            else -> subscription.currentPeriodEnd
        }

        val remainingMillis = targetEnd - now
        if (remainingMillis <= 0) return 0
        val days = (remainingMillis / (1000L * 60L * 60L * 24L)).toInt()
        return max(days, 0)
    }
}

class GetAvailablePlansUseCase(
    private val planRepository: SubscriptionPlanRepository
) {
    fun observe(): Flow<List<SubscriptionPlan>> = planRepository.observePlans()
    suspend operator fun invoke(): List<SubscriptionPlan> = planRepository.getPlans()
}

class GetPlanEntitlementsUseCase(
    private val planRepository: SubscriptionPlanRepository
) {
    suspend operator fun invoke(planId: Long): List<FeatureEntitlement> {
        return planRepository.getPlanFeatures(planId)
    }
}

class CheckSubscriptionAccessUseCase(
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val getSubscriptionStatusUseCase: GetSubscriptionStatusUseCase,
    private val stationRepository: StationRepository
) {
    suspend operator fun invoke(
        stationId: Long,
        feature: SubscriptionFeature
    ): SubscriptionAccessResult {
        val subscription = subscriptionRepository.getCurrentSubscription(stationId)
            ?: return SubscriptionAccessResult.SubscriptionExpired(expiredAt = null)

        val resolvedState = getSubscriptionStatusUseCase(subscription)

        when (resolvedState) {
            ResolvedSubscriptionState.SUSPENDED -> {
                return SubscriptionAccessResult.SubscriptionSuspended("Your station subscription has been suspended.")
            }
            ResolvedSubscriptionState.EXPIRED -> {
                return SubscriptionAccessResult.SubscriptionExpired(subscription.expiresAt ?: subscription.currentPeriodEnd)
            }
            ResolvedSubscriptionState.PAST_DUE -> {
                return SubscriptionAccessResult.SubscriptionExpired(subscription.currentPeriodEnd)
            }
            ResolvedSubscriptionState.TRIAL_ACTIVE,
            ResolvedSubscriptionState.ACTIVE,
            ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END,
            ResolvedSubscriptionState.GRACE_PERIOD -> {
                // Check if plan includes feature
                val plan = planRepository.getPlan(subscription.planId)
                if (plan == null || !plan.isActive) return SubscriptionAccessResult.SubscriptionExpired(null)
                val planFeatures = planRepository.getPlanFeatures(subscription.planId)
                val featureEntitlement = planFeatures.find { it.feature == feature }

                if (featureEntitlement == null || !featureEntitlement.enabled) {
                    val requiredPlans = findPlansWithFeature(feature)
                    return SubscriptionAccessResult.FeatureNotIncluded(feature, requiredPlans)
                }

                val stationLimit = planRepository.getPlanLimits(subscription.planId)
                    .firstOrNull { it.limitType == SubscriptionLimit.STATION_COUNT }?.maxValue ?: 0L
                val stationIndex = stationRepository.getAllStations()
                    .sortedWith(compareBy<Station> { it.createdAt }.thenBy { it.id })
                    .indexOfFirst { it.id == stationId }
                if (stationIndex < 0 || stationIndex.toLong() >= stationLimit) {
                    return SubscriptionAccessResult.LimitReached(SubscriptionLimit.STATION_COUNT, (stationIndex + 1).toLong(), stationLimit)
                }
                return SubscriptionAccessResult.Allowed
            }
        }
    }

    private suspend fun findPlansWithFeature(feature: SubscriptionFeature): List<SubscriptionPlanCode> {
        val allPlans = planRepository.getPlans()
        return allPlans.filter { plan ->
            val features = planRepository.getPlanFeatures(plan.id)
            features.any { it.feature == feature && it.enabled }
        }.map { it.code }
    }
}

class CheckSubscriptionLimitUseCase(
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val getSubscriptionStatusUseCase: GetSubscriptionStatusUseCase
) {
    suspend operator fun invoke(
        stationId: Long,
        limitType: SubscriptionLimit,
        currentUsage: Long
    ): SubscriptionAccessResult {
        val subscription = subscriptionRepository.getCurrentSubscription(stationId)
            ?: return SubscriptionAccessResult.SubscriptionExpired(expiredAt = null)

        val resolvedState = getSubscriptionStatusUseCase(subscription)
        if (resolvedState == ResolvedSubscriptionState.EXPIRED || resolvedState == ResolvedSubscriptionState.PAST_DUE) {
            return SubscriptionAccessResult.SubscriptionExpired(subscription.expiresAt ?: subscription.currentPeriodEnd)
        }
        if (resolvedState == ResolvedSubscriptionState.SUSPENDED) {
            return SubscriptionAccessResult.SubscriptionSuspended("Account suspended")
        }

        val limits = planRepository.getPlanLimits(subscription.planId)
        val planLimit = limits.find { it.limitType == limitType }
            ?: return SubscriptionAccessResult.LimitReached(limitType, currentUsage, 0)
        if (currentUsage < 0 || planLimit.maxValue < 0) return SubscriptionAccessResult.LimitReached(limitType, currentUsage, 0)

        if (currentUsage >= planLimit.maxValue) {
            return SubscriptionAccessResult.LimitReached(
                limitType = limitType,
                current = currentUsage,
                maximum = planLimit.maxValue
            )
        }

        return SubscriptionAccessResult.Allowed
    }
}

class StartFreeTrialUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(
        stationId: Long,
        planCode: SubscriptionPlanCode = SubscriptionPlanCode.BUSINESS,
        billingCycle: BillingCycle = BillingCycle.MONTHLY
    ): Subscription {
        return subscriptionRepository.createTrial(stationId, planCode, billingCycle)
    }
}

class ActivateSubscriptionUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(
        stationId: Long,
        planId: Long,
        billingCycle: BillingCycle
    ): Subscription {
        return subscriptionRepository.activatePlan(stationId, planId, billingCycle)
    }
}

class RenewSubscriptionUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(stationId: Long): Subscription? {
        return subscriptionRepository.renewSubscription(stationId)
    }
}

class CancelSubscriptionUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(
        stationId: Long,
        cancelAtPeriodEnd: Boolean = true
    ): Subscription? {
        return subscriptionRepository.cancelSubscription(stationId, cancelAtPeriodEnd)
    }
}

class ResumeSubscriptionUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(stationId: Long): Subscription? {
        return subscriptionRepository.resumeSubscription(stationId)
    }
}

class UpgradeSubscriptionUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(
        stationId: Long,
        newPlanId: Long,
        billingCycle: BillingCycle = BillingCycle.MONTHLY
    ): Subscription {
        return subscriptionRepository.upgradePlan(stationId, newPlanId, billingCycle)
    }
}

class ScheduleDowngradeUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(
        stationId: Long,
        newPlanId: Long,
        effectiveAt: Long
    ): Subscription? {
        return subscriptionRepository.schedulePlanChange(stationId, newPlanId, effectiveAt)
    }
}

class ProcessSubscriptionExpirationUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(stationId: Long): Subscription? {
        return subscriptionRepository.processSubscriptionExpiration(stationId)
    }
}

class GetSubscriptionHistoryUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(stationId: Long): List<SubscriptionHistoryEntry> {
        return subscriptionRepository.getSubscriptionHistory(stationId)
    }
}

class RefreshSubscriptionStateUseCase(
    private val subscriptionRepository: SubscriptionRepository
) {
    suspend operator fun invoke(stationId: Long): Subscription? {
        // Subscription authority is Supabase. Refresh the Room replica before
        // resolving access so a newly-provisioned trial (or a subscription
        // created on another device) cannot appear as expired just because the
        // local cache has not been populated yet. Offline callers keep using
        // the last cached state.
        try {
            subscriptionRepository.refreshFromSupabase()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Use the cached deadline only when a remote refresh fails;
            // cancellation must stop this operation without mutating access.
        }
        return subscriptionRepository.processSubscriptionExpiration(stationId)
    }
}

// ---------------- Feature-Specific Guard Use Cases ----------------

class CanCreateRiderUseCase(
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val checkSubscriptionLimitUseCase: CheckSubscriptionLimitUseCase,
    private val riderRepository: RiderRepository
) {
    suspend operator fun invoke(stationId: Long): SubscriptionAccessResult {
        val accessResult = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.RIDER_MANAGEMENT)
        if (accessResult !is SubscriptionAccessResult.Allowed) {
            return accessResult
        }

        val existingRiders = riderRepository.observeRiders(stationId).firstOrNull() ?: emptyList()
        return checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.RIDER_COUNT, existingRiders.size.toLong())
    }
}

class CanCreateProductUseCase(
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val checkSubscriptionLimitUseCase: CheckSubscriptionLimitUseCase,
    private val productRepository: ProductRepository
) {
    suspend operator fun invoke(stationId: Long): SubscriptionAccessResult {
        val accessResult = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.PRODUCT_MANAGEMENT)
        if (accessResult !is SubscriptionAccessResult.Allowed) {
            return accessResult
        }

        val existingProducts = productRepository.observeProducts(stationId).firstOrNull() ?: emptyList()
        return checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.PRODUCT_COUNT, existingProducts.size.toLong())
    }
}

class CanCreateCustomerUseCase(
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val checkSubscriptionLimitUseCase: CheckSubscriptionLimitUseCase,
    private val customerRepository: CustomerRepository
) {
    suspend operator fun invoke(stationId: Long): SubscriptionAccessResult {
        val accessResult = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.CUSTOMER_MANAGEMENT)
        if (accessResult !is SubscriptionAccessResult.Allowed) {
            return accessResult
        }

        val existingCustomers = customerRepository.observeCustomers(stationId).firstOrNull() ?: emptyList()
        return checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.CUSTOMER_COUNT, existingCustomers.size.toLong())
    }
}

class CanCreateOrderUseCase(
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val checkSubscriptionLimitUseCase: CheckSubscriptionLimitUseCase,
    private val orderRepository: OrderRepository
) {
    suspend operator fun invoke(stationId: Long): SubscriptionAccessResult {
        val accessResult = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ORDER_MANAGEMENT)
        if (accessResult !is SubscriptionAccessResult.Allowed) {
            return accessResult
        }

        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val start = calendar.timeInMillis
        calendar.add(java.util.Calendar.MONTH, 1)
        val end = calendar.timeInMillis
        val ordersThisMonth = orderRepository.countOrdersBetween(stationId, start, end)
        return checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.MONTHLY_ORDER_COUNT, ordersThisMonth.toLong())
    }
}

class CanCreateBranchUseCase(
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val checkSubscriptionLimitUseCase: CheckSubscriptionLimitUseCase
) {
    suspend operator fun invoke(stationId: Long, currentBranchCount: Long): SubscriptionAccessResult {
        val accessResult = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.MULTI_BRANCH)
        if (accessResult !is SubscriptionAccessResult.Allowed) {
            return accessResult
        }

        return checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.STATION_COUNT, currentBranchCount)
    }
}
