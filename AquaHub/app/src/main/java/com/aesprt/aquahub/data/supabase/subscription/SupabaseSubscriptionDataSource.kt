package com.aesprt.aquahub.data.supabase.subscription

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.call.body
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@Serializable
data class SupabaseSubscriptionRow(
    val id: String,
    @SerialName("organization_id") val organizationId: String,
    @SerialName("plan_id") val planId: String? = null,
    val status: String,
    @SerialName("billing_cycle") val billingCycle: String,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("current_period_start") val currentPeriodStart: Long? = null,
    @SerialName("current_period_end") val currentPeriodEnd: Long? = null,
    @SerialName("trial_started_at") val trialStartedAt: Long? = null,
    @SerialName("trial_ends_at") val trialEndsAt: Long? = null,
    @SerialName("cancelled_at") val cancelledAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("grace_period_ends_at") val gracePeriodEndsAt: Long? = null,
    @SerialName("auto_renew") val autoRenew: Boolean = false,
    @SerialName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean = false,
    val provider: String? = null,
    @SerialName("external_subscription_id") val externalSubscriptionId: String? = null,
    val currency: String = "PHP",
    @SerialName("client_updated_at") val clientUpdatedAt: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
private data class SupabasePlanRow(val id: String, val code: String)

@Serializable
data class SupabaseSubscriptionHistoryRow(
    val id: String,
    @SerialName("organization_id") val organizationId: String,
    @SerialName("subscription_id") val subscriptionId: String? = null,
    @SerialName("previous_plan_id") val previousPlanId: String? = null,
    @SerialName("new_plan_id") val newPlanId: String? = null,
    @SerialName("event_type") val eventType: String,
    @SerialName("effective_at") val effectiveAt: Long,
    @SerialName("previous_status") val previousStatus: String? = null,
    @SerialName("new_status") val newStatus: String? = null,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
private data class StationIdRow(val id: String)

@Serializable
private data class OwnerMembershipIdRow(val id: String)

@Serializable
private data class TrialResponse(
    @SerialName("business_id") val businessId: String,
    @SerialName("plan_code") val planCode: String,
    val status: String,
    @SerialName("billing_cycle") val billingCycle: String,
    @SerialName("started_at") val startedAt: Long,
    @SerialName("current_period_start") val currentPeriodStart: Long,
    @SerialName("current_period_end") val currentPeriodEnd: Long,
    @SerialName("trial_started_at") val trialStartedAt: Long? = null,
    @SerialName("trial_ends_at") val trialEndsAt: Long? = null,
    @SerialName("grace_period_ends_at") val gracePeriodEndsAt: Long? = null,
    @SerialName("cancelled_at") val cancelledAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("auto_renew") val autoRenew: Boolean = false,
    @SerialName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean = false,
    val currency: String = "PHP",
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
private data class PlayVerificationResponse(
    @SerialName("organization_id") val organizationId: String,
    @SerialName("plan_code") val planCode: String,
    @SerialName("billing_cycle") val billingCycle: String,
    val status: String,
    @SerialName("current_period_end") val currentPeriodEnd: Long,
    @SerialName("verified_at") val verifiedAt: Long,
)

@Serializable
private data class PlayReplacementPreflightResponse(
    val allowed: Boolean,
    @SerialName("organization_id") val organizationId: String,
)

class SupabaseSubscriptionDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun isOwnerTrialConsumed(): Boolean {
        val response = supabase.functions.invoke(
            function = "subscription-trial",
            body = buildJsonObject { put("operation", "check") },
        )
        return response.body<TrialEligibilityResponse>().trialConsumed
    }

    suspend fun ensureOwnerTrial(planCode: String, billingCycle: String): OwnerTrialProvisioningResult {
        val response = supabase.functions.invoke(
            function = "subscription-trial",
            body = buildJsonObject {
                put("operation", "ensure")
                put("plan_code", planCode)
                put("billing_cycle", billingCycle)
            },
        ).body<TrialResponse>()
        return OwnerTrialProvisioningResult(
            businessId = response.businessId,
            planCode = response.planCode,
            status = response.status,
            billingCycle = response.billingCycle,
            startedAt = response.startedAt,
            currentPeriodStart = response.currentPeriodStart,
            currentPeriodEnd = response.currentPeriodEnd,
            trialStartedAt = response.trialStartedAt,
            trialEndsAt = response.trialEndsAt,
            gracePeriodEndsAt = response.gracePeriodEndsAt,
            cancelledAt = response.cancelledAt,
            expiresAt = response.expiresAt,
            autoRenew = response.autoRenew,
            cancelAtPeriodEnd = response.cancelAtPeriodEnd,
            currency = response.currency,
            createdAt = response.createdAt,
        )
    }

    suspend fun verifyPlaySubscription(
        productId: String,
        purchaseToken: String,
        organizationId: String? = null
    ): PlayBillingVerificationResult {
        val response = supabase.functions.invoke(
            function = "verify-play-purchase",
            body = buildJsonObject {
                put("product_id", productId)
                put("purchase_token", purchaseToken)
                organizationId?.let { put("organization_id", it) }
            },
        ).body<PlayVerificationResponse>()
        return PlayBillingVerificationResult(
            organizationId = response.organizationId,
            planCode = response.planCode,
            billingCycle = response.billingCycle,
            status = response.status,
            currentPeriodEnd = response.currentPeriodEnd,
            verifiedAt = response.verifiedAt,
        )
    }

    /** Read-only server check before Play charges for a subscription replacement. */
    suspend fun preflightPlayReplacement(organizationId: String, oldPurchaseToken: String): Boolean {
        val response = supabase.functions.invoke(
            function = "verify-play-purchase",
            body = buildJsonObject {
                put("operation", "preflight_replacement")
                put("organization_id", organizationId)
                put("purchase_token", oldPurchaseToken)
            },
        ).body<PlayReplacementPreflightResponse>()
        return response.allowed && response.organizationId == organizationId
    }

    suspend fun current(): SupabaseSubscriptionRow? {
        val organizationId = runCatching { effectiveEntitlements().organizationId }.getOrNull()
        return currentForOrganization(organizationId)
    }

    suspend fun current(organizationId: String?): SupabaseSubscriptionRow? = currentForOrganization(organizationId)

    suspend fun stationIdsForOrganization(organizationId: String): Set<String> =
        supabase.from("stations").select {
            filter {
                eq("organization_id", organizationId)
                eq("is_active", true)
            }
        }.decodeList<StationIdRow>().map { it.id }.toSet()

    suspend fun history(organizationId: String): List<SupabaseSubscriptionHistoryRow> = buildList {
        var offset = 0L
        while (true) {
            val page = supabase.from("subscription_history").select {
                filter { eq("organization_id", organizationId) }
                order("effective_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                order("id", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                range(offset, offset + HISTORY_PAGE_SIZE - 1)
            }.decodeList<SupabaseSubscriptionHistoryRow>()
            addAll(page)
            if (page.size < HISTORY_PAGE_SIZE) break
            offset += HISTORY_PAGE_SIZE
        }
    }

    suspend fun planCodes(): Map<String, String> =
        supabase.from("subscription_plans").select().decodeList<SupabasePlanRow>()
            .associate { it.id to it.code }

    private suspend fun currentForOrganization(organizationId: String?): SupabaseSubscriptionRow? {
        if (organizationId == null) return null
        return supabase.from("subscriptions").select {
        filter {
            eq("organization_id", organizationId)
            exact("deleted_at", null)
        }
        order("updated_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
        limit(1)
        }.decodeList<SupabaseSubscriptionRow>().firstOrNull()
    }

    suspend fun effectiveEntitlements(): EffectiveEntitlementsResponse =
        supabase.postgrest.rpc("get_effective_entitlements").decodeAs()

    /** Fail closed before Play checkout if the selected organization is not owned by this user. */
    suspend fun checkoutOrganizationId(userId: String): String? {
        val organizationId = effectiveEntitlements().organizationId ?: return null
        val membership = supabase.from("organization_members").select {
            filter {
                eq("profile_id", userId)
                eq("organization_id", organizationId)
                eq("role", "OWNER")
                eq("is_active", true)
            }
            limit(1)
        }.decodeList<OwnerMembershipIdRow>().firstOrNull()
        return organizationId.takeIf { membership != null }
    }

    suspend fun planCode(planId: String?): String {
        if (planId == null) return "FREE"
        return supabase.from("subscription_plans").select { filter { eq("id", planId) } }
            .decodeList<SupabasePlanRow>().firstOrNull()?.code ?: "FREE"
    }

    suspend fun currentRemoteDocument(): RemoteDocument? {
        val subscription = current() ?: return null
        val planCode = subscription.planId?.let { planId ->
            supabase.from("subscription_plans").select { filter { eq("id", planId) } }
                .decodeList<SupabasePlanRow>().firstOrNull()?.code
        } ?: "FREE"
        val updatedAt = subscription.updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: subscription.clientUpdatedAt
            ?: System.currentTimeMillis()
        val fields: Map<String, Any?> = mapOf(
            "id" to subscription.id,
            "businessId" to subscription.organizationId,
            "planCode" to planCode,
            "status" to subscription.status,
            "billingCycle" to subscription.billingCycle,
            "startedAt" to subscription.startedAt,
            "currentPeriodStart" to subscription.currentPeriodStart,
            "currentPeriodEnd" to subscription.currentPeriodEnd,
            "trialStartedAt" to subscription.trialStartedAt,
            "trialEndsAt" to subscription.trialEndsAt,
            "gracePeriodEndsAt" to subscription.gracePeriodEndsAt,
            "cancelledAt" to subscription.cancelledAt,
            "expiresAt" to subscription.expiresAt,
            "autoRenew" to subscription.autoRenew,
            "cancelAtPeriodEnd" to subscription.cancelAtPeriodEnd,
            "provider" to subscription.provider,
            "externalSubscriptionId" to subscription.externalSubscriptionId,
            "currency" to subscription.currency,
            "createdAt" to (subscription.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: updatedAt),
            "clientUpdatedAt" to (subscription.clientUpdatedAt ?: updatedAt),
        )
        return RemoteDocument(subscription.id, fields, subscription.clientUpdatedAt ?: updatedAt, updatedAt, null)
    }

    @Serializable
    private data class TrialEligibilityResponse(@SerialName("trial_consumed") val trialConsumed: Boolean)

    private companion object {
        const val HISTORY_PAGE_SIZE = 250L
    }
}

data class RemoteDocument(
    val id: String,
    val fields: Map<String, Any?>,
    val version: Long,
    val serverUpdatedAt: Long,
    val deletedAt: Long?
)

data class OwnerTrialProvisioningResult(
    val businessId: String,
    val planCode: String,
    val status: String,
    val billingCycle: String,
    val startedAt: Long,
    val currentPeriodStart: Long,
    val currentPeriodEnd: Long,
    val trialStartedAt: Long?,
    val trialEndsAt: Long?,
    val cancelledAt: Long?,
    val expiresAt: Long?,
    val gracePeriodEndsAt: Long?,
    val autoRenew: Boolean,
    val cancelAtPeriodEnd: Boolean,
    val currency: String,
    val createdAt: Long
)

data class PlayBillingVerificationResult(
    val organizationId: String,
    val planCode: String,
    val billingCycle: String,
    val status: String,
    val currentPeriodEnd: Long,
    val verifiedAt: Long
)

@Serializable
data class EffectiveEntitlementsResponse(
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("subscription_id") val subscriptionId: String? = null,
    @SerialName("subscription_plan_id") val subscriptionPlanId: String? = null,
    @SerialName("subscription_updated_at") val subscriptionUpdatedAt: String? = null,
    @SerialName("subscription_status") val subscriptionStatus: String? = null,
    @SerialName("subscription_billing_cycle") val subscriptionBillingCycle: String? = null,
    @SerialName("current_period_end") val currentPeriodEnd: Long? = null,
    @SerialName("plan_code") val planCode: String = "FREE",
    val status: String = "FREE",
    val features: Map<String, Boolean> = emptyMap(),
    val limits: Map<String, Long> = emptyMap(),
)
