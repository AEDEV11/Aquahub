package com.aesprt.aquahub.domain.model

/** The only Google Play product IDs the Owner app is allowed to request. */
object GooglePlayBillingProducts {
    const val STARTER = "aquahub_starter"
    const val BUSINESS = "aquahub_business"
    const val PRO = "aquahub_pro"

    const val MONTHLY_BASE_PLAN = "monthly"
    const val YEARLY_BASE_PLAN = "yearly"

    val productIds: Set<String> = setOf(STARTER, BUSINESS, PRO)

    fun forPlan(planCode: SubscriptionPlanCode): String? = when (planCode) {
        SubscriptionPlanCode.FREE -> null
        SubscriptionPlanCode.STARTER -> STARTER
        SubscriptionPlanCode.BUSINESS -> BUSINESS
        SubscriptionPlanCode.PRO -> PRO
    }

    fun forCycle(cycle: BillingCycle): String = when (cycle) {
        BillingCycle.MONTHLY -> MONTHLY_BASE_PLAN
        BillingCycle.ANNUAL -> YEARLY_BASE_PLAN
    }

    fun planForProduct(productId: String): SubscriptionPlanCode? = when (productId) {
        STARTER -> SubscriptionPlanCode.STARTER
        BUSINESS -> SubscriptionPlanCode.BUSINESS
        PRO -> SubscriptionPlanCode.PRO
        else -> null
    }

    fun cycleForBasePlan(basePlanId: String): BillingCycle? = when (basePlanId) {
        MONTHLY_BASE_PLAN -> BillingCycle.MONTHLY
        YEARLY_BASE_PLAN -> BillingCycle.ANNUAL
        else -> null
    }

    /** Only the reviewed base-plan IDs may be purchased; duration is not identity. */
    fun cycleForOffer(basePlanId: String, billingPeriod: String?): BillingCycle? = when (basePlanId to billingPeriod?.uppercase()) {
        MONTHLY_BASE_PLAN to "P1M" -> BillingCycle.MONTHLY
        YEARLY_BASE_PLAN to "P1Y" -> BillingCycle.ANNUAL
        else -> null
    }
}

enum class BillingAvailability {
    CONNECTING,
    AVAILABLE,
    NOT_CONFIGURED,
    BILLING_UNAVAILABLE,
    NETWORK_ERROR
}

data class PlayBillingOffer(
    val planCode: SubscriptionPlanCode,
    val billingCycle: BillingCycle,
    val productId: String,
    val basePlanId: String,
    val formattedPrice: String,
    val priceAmountMicros: Long? = null,
    val currencyCode: String? = null,
    val offerId: String? = null,
    val introductoryTerms: String? = null,
)

sealed interface BillingOperationState {
    data object Idle : BillingOperationState
    data class Purchasing(val planCode: SubscriptionPlanCode, val billingCycle: BillingCycle) : BillingOperationState
    data object Verifying : BillingOperationState
    data object Completed : BillingOperationState
    data class Pending(val message: String = "Payment is pending. Access will be enabled after Google confirms payment.") : BillingOperationState
    data class Failed(val message: String) : BillingOperationState
}
