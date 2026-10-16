package com.aesprt.aquahub.data.supabase.subscription

import java.time.Instant

/** Rejects a billing row read before or after the entitlement RPC's database snapshot. */
internal fun isSubscriptionSnapshotConsistent(
    effective: EffectiveEntitlementsResponse,
    remote: SupabaseSubscriptionRow,
): Boolean {
    if (effective.organizationId == null || effective.organizationId != remote.organizationId) return false

    if (effective.subscriptionId == null) {
        return effective.planCode.equals("FREE", ignoreCase = true) &&
            remote.id == "effective-free" && remote.planId == null && remote.updatedAt == null
    }

    if (effective.subscriptionId != remote.id || effective.subscriptionPlanId != remote.planId) return false
    if (effective.subscriptionStatus != remote.status ||
        effective.subscriptionBillingCycle != remote.billingCycle) return false
    if (effective.currentPeriodEnd != remote.currentPeriodEnd) return false
    if (!effective.planCode.equals("FREE", ignoreCase = true) &&
        !effective.status.equals(remote.status, ignoreCase = true)
    ) return false

    val entitlementRevision = effective.subscriptionUpdatedAt?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    } ?: return false
    val billingRevision = remote.updatedAt?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    } ?: return false
    return entitlementRevision == billingRevision
}
