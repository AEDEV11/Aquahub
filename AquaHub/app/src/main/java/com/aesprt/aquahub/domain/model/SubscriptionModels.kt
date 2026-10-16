package com.aesprt.aquahub.domain.model

import java.math.BigDecimal
import java.math.RoundingMode

enum class SubscriptionPlanCode {
    FREE,
    STARTER,
    BUSINESS,
    PRO;

    companion object {
        fun fromString(value: String): SubscriptionPlanCode {
            return when (value.uppercase()) {
                "FREE" -> FREE
                "STARTER" -> STARTER
                "BUSINESS" -> BUSINESS
                "PRO" -> PRO
                else -> STARTER
            }
        }
    }
}

enum class SubscriptionStatus {
    FREE,
    TRIALING,
    ACTIVE,
    ACTIVE_UNTIL_PERIOD_END,
    PAST_DUE,
    GRACE_PERIOD,
    CANCELLED,
    EXPIRED,
    SUSPENDED;

    companion object {
        fun fromString(value: String): SubscriptionStatus {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: EXPIRED
        }
    }
}

enum class BillingCycle {
    MONTHLY,
    ANNUAL;

    companion object {
        fun fromString(value: String): BillingCycle {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: MONTHLY
        }
    }
}

enum class SubscriptionFeature {
    STATION_MANAGEMENT,
    DASHBOARD,
    ORDER_MANAGEMENT,
    CUSTOMER_MANAGEMENT,
    PRODUCT_MANAGEMENT,
    BASIC_INVENTORY,
    ADVANCED_INVENTORY,
    BASIC_CONTAINER_TRACKING,
    ADVANCED_CONTAINER_TRACKING,
    RIDER_MANAGEMENT,
    DELIVERY_MANAGEMENT,
    /** @deprecated Kept only so older stored plan rows can still be read. */
    @Deprecated("Use MOBILE_APP_ORDERING")
    QR_ORDERING,
    MOBILE_APP_ORDERING,
    QR_ANALYTICS,
    BASIC_REPORTS,
    ADVANCED_REPORTS,
    ADVANCED_ANALYTICS,
    CUSTOMER_INSIGHTS,
    ACCOUNTS_RECEIVABLE,
    PROMOTIONS,
    EXPENSE_TRACKING,
    SCHEDULED_DELIVERIES,
    STAFF_ACCOUNTS,
    MULTI_BRANCH,
    MULTI_STATION,
    DATA_EXPORT,
    EXPORT_CSV,
    EXPORT_EXCEL,
    EXPORT_PDF,
    CONSOLIDATED_REPORTS,
    STATION_COMPARISON,
    DELIVERY_ANALYTICS,
    PRIORITY_SUPPORT;

    val displayName: String
        get() = when (this) {
            STATION_MANAGEMENT -> "Station Profile & Settings"
            DASHBOARD -> "Dashboard Overview"
            ORDER_MANAGEMENT -> "Order Management"
            CUSTOMER_MANAGEMENT -> "Customer Directory"
            PRODUCT_MANAGEMENT -> "Product Catalog"
            BASIC_INVENTORY -> "Basic Inventory"
            ADVANCED_INVENTORY -> "Advanced Inventory & Alerts"
            BASIC_CONTAINER_TRACKING -> "Basic Container Tracking"
            ADVANCED_CONTAINER_TRACKING -> "Advanced Container Tracking"
            RIDER_MANAGEMENT -> "Rider Fleet Management"
            DELIVERY_MANAGEMENT -> "Delivery Dispatch & Tracking"
            QR_ORDERING, MOBILE_APP_ORDERING -> "Mobile App Ordering"
            QR_ANALYTICS -> "QR Analytics & Scans"
            BASIC_REPORTS -> "Basic Sales Reports"
            ADVANCED_REPORTS, ADVANCED_ANALYTICS -> "Advanced Business Analytics"
            CUSTOMER_INSIGHTS -> "Customer Insights & Retention"
            ACCOUNTS_RECEIVABLE -> "Client Credit, Payments & PDF Statements"
            PROMOTIONS -> "Product Promotions"
            EXPENSE_TRACKING -> "Daily Expenses & Profit Tracking"
            SCHEDULED_DELIVERIES -> "Scheduled Deliveries"
            STAFF_ACCOUNTS -> "Multiple Staff Accounts"
            MULTI_BRANCH, MULTI_STATION -> "Multi-Station Management"
            DATA_EXPORT, EXPORT_CSV -> "CSV Data Export"
            EXPORT_EXCEL -> "Excel (.xlsx) Export"
            EXPORT_PDF -> "Professional PDF Reports"
            CONSOLIDATED_REPORTS -> "All-Station Consolidated Reports"
            STATION_COMPARISON -> "Station Comparison & Benchmarks"
            DELIVERY_ANALYTICS -> "Delivery Performance Analytics"
            PRIORITY_SUPPORT -> "24/7 Priority Support"
        }

    companion object {
        fun fromString(value: String): SubscriptionFeature? {
            return when {
                value.equals("QR_ORDERING", ignoreCase = true) -> MOBILE_APP_ORDERING
                else -> entries.find { it.name.equals(value, ignoreCase = true) }
            }
        }
    }
}

enum class SubscriptionLimit {
    BRANCH_COUNT,
    STATION_COUNT,
    RIDER_COUNT,
    STAFF_COUNT,
    MONTHLY_ORDER_COUNT,
    CUSTOMER_COUNT,
    PRODUCT_COUNT,
    QR_CODE_COUNT;

    val displayName: String
        get() = when (this) {
            BRANCH_COUNT, STATION_COUNT -> "Water Stations"
            RIDER_COUNT -> "Delivery Riders"
            STAFF_COUNT -> "Staff Accounts"
            MONTHLY_ORDER_COUNT -> "Monthly Orders"
            CUSTOMER_COUNT -> "Saved Customers"
            PRODUCT_COUNT -> "Products in Catalog"
            QR_CODE_COUNT -> "Legacy QR Codes"
        }

    companion object {
        fun fromString(value: String): SubscriptionLimit? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }
}

enum class SubscriptionEventType {
    TRIAL_STARTED,
    TRIAL_ENDED,
    SUBSCRIBED,
    RENEWED,
    UPGRADED,
    DOWNGRADED,
    CANCELLED,
    EXPIRED,
    GRACE_PERIOD_STARTED,
    SUSPENDED,
    REACTIVATED;

    companion object {
        fun fromString(value: String): SubscriptionEventType {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: SUBSCRIBED
        }
    }
}

data class FeatureEntitlement(
    val feature: SubscriptionFeature,
    val enabled: Boolean,
    val limit: Long? = null
)

data class PlanLimit(
    val limitType: SubscriptionLimit,
    val maxValue: Long
)

data class SubscriptionPlan(
    val id: Long = 0,
    val code: SubscriptionPlanCode,
    val name: String,
    val description: String? = null,
    val monthlyPrice: Double,
    val annualPrice: Double,
    val isPopular: Boolean = false,
    val trialDays: Int = 30,
    val ctaText: String,
    val isActive: Boolean = true,
    val sortOrder: Int = 0,
    val features: List<FeatureEntitlement> = emptyList(),
    val limits: List<PlanLimit> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

const val ANNUAL_DISCOUNT_PERCENT = 20
const val BILLING_MONTH_DAYS = 30

/** Pricing is defined from monthly values; annual and daily figures are derived. */
fun annualPriceFromMonthly(monthlyPrice: Double): Double =
    BigDecimal.valueOf(monthlyPrice)
        .multiply(BigDecimal.valueOf(12L))
        .multiply(BigDecimal.valueOf(100L - ANNUAL_DISCOUNT_PERCENT).movePointLeft(2))
        .setScale(2, RoundingMode.HALF_UP)
        .toDouble()

fun dailyPriceFromMonthly(monthlyPrice: Double): Double = monthlyPrice / BILLING_MONTH_DAYS

fun SubscriptionPlan.priceFor(billingCycle: BillingCycle): Double = when (billingCycle) {
    BillingCycle.MONTHLY -> monthlyPrice
    BillingCycle.ANNUAL -> annualPrice
}

fun SubscriptionPlan.monthlyEquivalentFor(billingCycle: BillingCycle): Double = when (billingCycle) {
    BillingCycle.MONTHLY -> monthlyPrice
    BillingCycle.ANNUAL -> annualPrice / 12.0
}

fun SubscriptionPlan.dailyEquivalentFor(billingCycle: BillingCycle): Double =
    dailyPriceFromMonthly(monthlyEquivalentFor(billingCycle))

fun SubscriptionPlan.annualSavings(): Double = monthlyPrice * 12.0 - annualPrice

val DEFAULT_SUBSCRIPTION_PLANS = listOf(
    SubscriptionPlan(
        id = 4L,
        code = SubscriptionPlanCode.FREE,
        name = "Free",
        description = "Core station, customer, product, basic inventory, and basic reporting tools for one water station",
        monthlyPrice = 0.0,
        annualPrice = annualPriceFromMonthly(0.0),
        isPopular = false,
        trialDays = 0,
        ctaText = "Continue with Free",
        sortOrder = 0,
        features = listOf(
            FeatureEntitlement(SubscriptionFeature.STATION_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.DASHBOARD, true),
            FeatureEntitlement(SubscriptionFeature.ORDER_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.CUSTOMER_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.PRODUCT_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_INVENTORY, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_CONTAINER_TRACKING, false),
            FeatureEntitlement(SubscriptionFeature.BASIC_REPORTS, true),
            FeatureEntitlement(SubscriptionFeature.DATA_EXPORT, false),
            FeatureEntitlement(SubscriptionFeature.MOBILE_APP_ORDERING, false),
            FeatureEntitlement(SubscriptionFeature.DELIVERY_MANAGEMENT, false),
            FeatureEntitlement(SubscriptionFeature.ADVANCED_REPORTS, false),
            FeatureEntitlement(SubscriptionFeature.ADVANCED_ANALYTICS, false),
            FeatureEntitlement(SubscriptionFeature.CUSTOMER_INSIGHTS, false),
            FeatureEntitlement(SubscriptionFeature.ACCOUNTS_RECEIVABLE, false),
            FeatureEntitlement(SubscriptionFeature.PROMOTIONS, false),
            FeatureEntitlement(SubscriptionFeature.EXPENSE_TRACKING, false),
            FeatureEntitlement(SubscriptionFeature.STAFF_ACCOUNTS, false),
            FeatureEntitlement(SubscriptionFeature.MULTI_STATION, false),
            FeatureEntitlement(SubscriptionFeature.CONSOLIDATED_REPORTS, false)
        ),
        limits = listOf(
            PlanLimit(SubscriptionLimit.STATION_COUNT, 1L),
            PlanLimit(SubscriptionLimit.PRODUCT_COUNT, 20L),
            PlanLimit(SubscriptionLimit.MONTHLY_ORDER_COUNT, 100L),
            PlanLimit(SubscriptionLimit.CUSTOMER_COUNT, 50L)
        )
    ),
    SubscriptionPlan(
        id = 1L,
        code = SubscriptionPlanCode.STARTER,
        name = "Starter",
        description = "Inventory, mobile ordering, and rider tools for one water station",
        monthlyPrice = 199.0,
        annualPrice = annualPriceFromMonthly(199.0),
        isPopular = false,
        trialDays = 30,
        ctaText = "Start 30-Day Free Trial",
        sortOrder = 1,
        features = listOf(
            FeatureEntitlement(SubscriptionFeature.STATION_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.DASHBOARD, true),
            FeatureEntitlement(SubscriptionFeature.ORDER_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.CUSTOMER_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.PRODUCT_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_INVENTORY, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_CONTAINER_TRACKING, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_REPORTS, true),
            FeatureEntitlement(SubscriptionFeature.DATA_EXPORT, true),
            FeatureEntitlement(SubscriptionFeature.EXPORT_CSV, true),
            FeatureEntitlement(SubscriptionFeature.EXPORT_EXCEL, false),
            FeatureEntitlement(SubscriptionFeature.EXPORT_PDF, false),
            FeatureEntitlement(SubscriptionFeature.ADVANCED_REPORTS, false),
            FeatureEntitlement(SubscriptionFeature.MULTI_BRANCH, false),
            FeatureEntitlement(SubscriptionFeature.CONSOLIDATED_REPORTS, false),
            FeatureEntitlement(SubscriptionFeature.MOBILE_APP_ORDERING, true),
            FeatureEntitlement(SubscriptionFeature.RIDER_MANAGEMENT, true, 2L),
            FeatureEntitlement(SubscriptionFeature.DELIVERY_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.STAFF_ACCOUNTS, true, 2L),
            FeatureEntitlement(SubscriptionFeature.ACCOUNTS_RECEIVABLE, false),
            FeatureEntitlement(SubscriptionFeature.PROMOTIONS, false),
            FeatureEntitlement(SubscriptionFeature.EXPENSE_TRACKING, false)
        ),
        limits = listOf(
            PlanLimit(SubscriptionLimit.STATION_COUNT, 1L),
            PlanLimit(SubscriptionLimit.RIDER_COUNT, 2L),
            PlanLimit(SubscriptionLimit.PRODUCT_COUNT, 30L),
            PlanLimit(SubscriptionLimit.MONTHLY_ORDER_COUNT, 300L),
            PlanLimit(SubscriptionLimit.CUSTOMER_COUNT, 100L),
            PlanLimit(SubscriptionLimit.STAFF_COUNT, 2L),
        )
    ),
    SubscriptionPlan(
        id = 2L,
        code = SubscriptionPlanCode.BUSINESS,
        name = "Business",
        description = "Delivery, advanced reporting, and up to three water stations",
        monthlyPrice = 499.0,
        annualPrice = annualPriceFromMonthly(499.0),
        isPopular = true,
        trialDays = 30,
        ctaText = "Start 30-Day Free Trial",
        sortOrder = 2,
        features = listOf(
            FeatureEntitlement(SubscriptionFeature.STATION_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.DASHBOARD, true),
            FeatureEntitlement(SubscriptionFeature.ORDER_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.CUSTOMER_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.PRODUCT_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_INVENTORY, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_CONTAINER_TRACKING, true),
            FeatureEntitlement(SubscriptionFeature.RIDER_MANAGEMENT, true, 10L),
            FeatureEntitlement(SubscriptionFeature.DELIVERY_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.MOBILE_APP_ORDERING, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_REPORTS, true),
            FeatureEntitlement(SubscriptionFeature.ADVANCED_REPORTS, true),
            FeatureEntitlement(SubscriptionFeature.ADVANCED_ANALYTICS, true),
            FeatureEntitlement(SubscriptionFeature.DATA_EXPORT, true),
            FeatureEntitlement(SubscriptionFeature.EXPORT_CSV, true),
            FeatureEntitlement(SubscriptionFeature.EXPORT_EXCEL, true),
            FeatureEntitlement(SubscriptionFeature.EXPORT_PDF, true),
            FeatureEntitlement(SubscriptionFeature.MULTI_BRANCH, true, 3L),
            FeatureEntitlement(SubscriptionFeature.CONSOLIDATED_REPORTS, false),
            FeatureEntitlement(SubscriptionFeature.STAFF_ACCOUNTS, true, 10L),
            FeatureEntitlement(SubscriptionFeature.ACCOUNTS_RECEIVABLE, true),
            FeatureEntitlement(SubscriptionFeature.PROMOTIONS, true),
            FeatureEntitlement(SubscriptionFeature.EXPENSE_TRACKING, true),
        ),
        limits = listOf(
            PlanLimit(SubscriptionLimit.STATION_COUNT, 3L),
            PlanLimit(SubscriptionLimit.RIDER_COUNT, 10L),
            PlanLimit(SubscriptionLimit.PRODUCT_COUNT, 500L),
            PlanLimit(SubscriptionLimit.MONTHLY_ORDER_COUNT, 10000L),
            PlanLimit(SubscriptionLimit.CUSTOMER_COUNT, 2000L),
            PlanLimit(SubscriptionLimit.STAFF_COUNT, 10L),
        )
    ),
    SubscriptionPlan(
        id = 3L,
        code = SubscriptionPlanCode.PRO,
        name = "Pro",
        description = "Full operations, analytics, and up to ten water stations",
        monthlyPrice = 999.0,
        annualPrice = annualPriceFromMonthly(999.0),
        isPopular = false,
        trialDays = 30,
        ctaText = "Start 30-Day Free Trial",
        sortOrder = 3,
        features = listOf(
            FeatureEntitlement(SubscriptionFeature.STATION_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.DASHBOARD, true),
            FeatureEntitlement(SubscriptionFeature.ORDER_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.CUSTOMER_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.PRODUCT_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_INVENTORY, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_CONTAINER_TRACKING, true),
            FeatureEntitlement(SubscriptionFeature.RIDER_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.DELIVERY_MANAGEMENT, true),
            FeatureEntitlement(SubscriptionFeature.MOBILE_APP_ORDERING, true),
            FeatureEntitlement(SubscriptionFeature.BASIC_REPORTS, true),
            FeatureEntitlement(SubscriptionFeature.ADVANCED_REPORTS, true),
            FeatureEntitlement(SubscriptionFeature.MULTI_BRANCH, true, 10L),
            FeatureEntitlement(SubscriptionFeature.DATA_EXPORT, true),
            FeatureEntitlement(SubscriptionFeature.EXPORT_CSV, true),
            FeatureEntitlement(SubscriptionFeature.EXPORT_EXCEL, true),
            FeatureEntitlement(SubscriptionFeature.EXPORT_PDF, true),
            FeatureEntitlement(SubscriptionFeature.CONSOLIDATED_REPORTS, true),
            FeatureEntitlement(SubscriptionFeature.DELIVERY_ANALYTICS, true),
            FeatureEntitlement(SubscriptionFeature.CUSTOMER_INSIGHTS, true),
            FeatureEntitlement(SubscriptionFeature.ACCOUNTS_RECEIVABLE, true),
            FeatureEntitlement(SubscriptionFeature.PROMOTIONS, true),
            FeatureEntitlement(SubscriptionFeature.EXPENSE_TRACKING, true),
            FeatureEntitlement(SubscriptionFeature.STAFF_ACCOUNTS, true, 100L),
        ),
        limits = listOf(
            PlanLimit(SubscriptionLimit.STATION_COUNT, 10L),
            PlanLimit(SubscriptionLimit.RIDER_COUNT, 100L),
            PlanLimit(SubscriptionLimit.PRODUCT_COUNT, 10000L),
            PlanLimit(SubscriptionLimit.MONTHLY_ORDER_COUNT, 100000L),
            PlanLimit(SubscriptionLimit.CUSTOMER_COUNT, 50000L),
            PlanLimit(SubscriptionLimit.STAFF_COUNT, 100L),
        )
    )
)

data class Subscription(
    val id: Long = 0,
    val stationId: Long,
    val planId: Long,
    val planCode: SubscriptionPlanCode,
    val status: SubscriptionStatus,
    val billingCycle: BillingCycle,
    val startedAt: Long,
    val currentPeriodStart: Long,
    val currentPeriodEnd: Long,
    val trialStartedAt: Long? = null,
    val trialEndsAt: Long? = null,
    val cancelledAt: Long? = null,
    val expiresAt: Long? = null,
    val gracePeriodEndsAt: Long? = null,
    val autoRenew: Boolean = true,
    val cancelAtPeriodEnd: Boolean = false,
    val pendingPlanId: Long? = null,
    val pendingPlanEffectiveAt: Long? = null,
    val provider: String? = null,
    val externalSubscriptionId: String? = null,
    val externalCustomerId: String? = null,
    val lastPaymentId: String? = null,
    val currency: String = "PHP",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class SubscriptionHistoryEntry(
    val id: Long = 0,
    val stationId: Long,
    val subscriptionId: Long,
    val previousPlanId: Long? = null,
    val newPlanId: Long? = null,
    val eventType: SubscriptionEventType,
    val effectiveAt: Long,
    val previousStatus: SubscriptionStatus? = null,
    val newStatus: SubscriptionStatus? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ResolvedSubscriptionState {
    TRIAL_ACTIVE,
    ACTIVE,
    ACTIVE_UNTIL_PERIOD_END,
    GRACE_PERIOD,
    EXPIRED,
    PAST_DUE,
    SUSPENDED
}

sealed interface SubscriptionAccessResult {
    data object Allowed : SubscriptionAccessResult

    data class FeatureNotIncluded(
        val feature: SubscriptionFeature,
        val requiredPlans: List<SubscriptionPlanCode>
    ) : SubscriptionAccessResult

    data class SubscriptionExpired(
        val expiredAt: Long?
    ) : SubscriptionAccessResult

    data class LimitReached(
        val limitType: SubscriptionLimit,
        val current: Long,
        val maximum: Long
    ) : SubscriptionAccessResult

    data class SubscriptionSuspended(
        val message: String?
    ) : SubscriptionAccessResult
}
