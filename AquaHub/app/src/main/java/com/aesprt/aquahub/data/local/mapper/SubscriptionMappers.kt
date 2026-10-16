package com.aesprt.aquahub.data.local.mapper

import com.aesprt.aquahub.data.local.entity.PlanFeatureEntity
import com.aesprt.aquahub.data.local.entity.PlanLimitEntity
import com.aesprt.aquahub.data.local.entity.SubscriptionEntity
import com.aesprt.aquahub.data.local.entity.SubscriptionHistoryEntity
import com.aesprt.aquahub.data.local.entity.SubscriptionPlanEntity
import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.FeatureEntitlement
import com.aesprt.aquahub.domain.model.PlanLimit
import com.aesprt.aquahub.domain.model.Subscription
import com.aesprt.aquahub.domain.model.SubscriptionEventType
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.model.SubscriptionHistoryEntry
import com.aesprt.aquahub.domain.model.SubscriptionLimit
import com.aesprt.aquahub.domain.model.SubscriptionPlan
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.domain.model.SubscriptionStatus

fun SubscriptionPlanEntity.toDomain(
    features: List<FeatureEntitlement> = emptyList(),
    limits: List<PlanLimit> = emptyList()
): SubscriptionPlan {
    return SubscriptionPlan(
        id = id,
        code = SubscriptionPlanCode.fromString(code),
        name = name,
        description = description,
        monthlyPrice = monthlyPrice,
        annualPrice = annualPrice,
        isPopular = isPopular,
        trialDays = trialDays,
        ctaText = ctaText,
        isActive = isActive,
        sortOrder = sortOrder,
        features = features,
        limits = limits,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun SubscriptionPlan.toEntity(): SubscriptionPlanEntity {
    return SubscriptionPlanEntity(
        id = id,
        code = code.name,
        name = name,
        description = description,
        monthlyPrice = monthlyPrice,
        annualPrice = annualPrice,
        isPopular = isPopular,
        trialDays = trialDays,
        ctaText = ctaText,
        isActive = isActive,
        sortOrder = sortOrder,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun PlanFeatureEntity.toDomain(): FeatureEntitlement? {
    val featureEnum = SubscriptionFeature.fromString(feature) ?: return null
    return FeatureEntitlement(
        feature = featureEnum,
        enabled = enabled,
        limit = limitValue
    )
}

fun FeatureEntitlement.toEntity(planId: Long): PlanFeatureEntity {
    return PlanFeatureEntity(
        planId = planId,
        feature = feature.name,
        enabled = enabled,
        limitValue = limit,
        createdAt = System.currentTimeMillis()
    )
}

fun PlanLimitEntity.toDomain(): PlanLimit? {
    val limitEnum = SubscriptionLimit.fromString(limitType) ?: return null
    return PlanLimit(
        limitType = limitEnum,
        maxValue = maxValue
    )
}

fun PlanLimit.toEntity(planId: Long): PlanLimitEntity {
    return PlanLimitEntity(
        planId = planId,
        limitType = limitType.name,
        maxValue = maxValue
    )
}

fun SubscriptionEntity.toDomain(planCode: SubscriptionPlanCode): Subscription {
    return Subscription(
        id = id,
        stationId = stationId,
        planId = planId,
        planCode = planCode,
        status = SubscriptionStatus.fromString(status),
        billingCycle = BillingCycle.fromString(billingCycle),
        startedAt = startedAt,
        currentPeriodStart = currentPeriodStart,
        currentPeriodEnd = currentPeriodEnd,
        trialStartedAt = trialStartedAt,
        trialEndsAt = trialEndsAt,
        cancelledAt = cancelledAt,
        expiresAt = expiresAt,
        gracePeriodEndsAt = gracePeriodEndsAt,
        autoRenew = autoRenew,
        cancelAtPeriodEnd = cancelAtPeriodEnd,
        pendingPlanId = pendingPlanId,
        pendingPlanEffectiveAt = pendingPlanEffectiveAt,
        provider = provider,
        externalSubscriptionId = externalSubscriptionId,
        externalCustomerId = externalCustomerId,
        lastPaymentId = lastPaymentId,
        currency = currency,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun Subscription.toEntity(): SubscriptionEntity {
    return SubscriptionEntity(
        id = id,
        stationId = stationId,
        planId = planId,
        status = status.name,
        billingCycle = billingCycle.name,
        startedAt = startedAt,
        currentPeriodStart = currentPeriodStart,
        currentPeriodEnd = currentPeriodEnd,
        trialStartedAt = trialStartedAt,
        trialEndsAt = trialEndsAt,
        cancelledAt = cancelledAt,
        expiresAt = expiresAt,
        gracePeriodEndsAt = gracePeriodEndsAt,
        autoRenew = autoRenew,
        cancelAtPeriodEnd = cancelAtPeriodEnd,
        pendingPlanId = pendingPlanId,
        pendingPlanEffectiveAt = pendingPlanEffectiveAt,
        provider = provider,
        externalSubscriptionId = externalSubscriptionId,
        externalCustomerId = externalCustomerId,
        lastPaymentId = lastPaymentId,
        currency = currency,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun SubscriptionHistoryEntity.toDomain(): SubscriptionHistoryEntry {
    return SubscriptionHistoryEntry(
        id = id,
        stationId = stationId,
        subscriptionId = subscriptionId,
        previousPlanId = previousPlanId,
        newPlanId = newPlanId,
        eventType = SubscriptionEventType.fromString(eventType),
        effectiveAt = effectiveAt,
        previousStatus = previousStatus?.let { SubscriptionStatus.fromString(it) },
        newStatus = newStatus?.let { SubscriptionStatus.fromString(it) },
        notes = notes,
        createdAt = createdAt
    )
}

fun SubscriptionHistoryEntry.toEntity(): SubscriptionHistoryEntity {
    return SubscriptionHistoryEntity(
        id = id,
        stationId = stationId,
        subscriptionId = subscriptionId,
        previousPlanId = previousPlanId,
        newPlanId = newPlanId,
        eventType = eventType.name,
        effectiveAt = effectiveAt,
        previousStatus = previousStatus?.name,
        newStatus = newStatus?.name,
        notes = notes,
        createdAt = createdAt
    )
}
