package com.aesprt.aquahub.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class SubscriptionPricingTest {
    @Test
    fun `monthly catalog prices match latest intended pricing`() {
        assertEquals(199.0, DEFAULT_SUBSCRIPTION_PLANS.single { it.code == SubscriptionPlanCode.STARTER }.monthlyPrice, 0.0)
        assertEquals(499.0, DEFAULT_SUBSCRIPTION_PLANS.single { it.code == SubscriptionPlanCode.BUSINESS }.monthlyPrice, 0.0)
        assertEquals(999.0, DEFAULT_SUBSCRIPTION_PLANS.single { it.code == SubscriptionPlanCode.PRO }.monthlyPrice, 0.0)
    }

    @Test
    fun `annual prices and daily equivalents are derived from monthly price`() {
        val business = DEFAULT_SUBSCRIPTION_PLANS.single { it.code == SubscriptionPlanCode.BUSINESS }
        assertEquals(4790.4, business.annualPrice, 0.0)
        assertEquals(17, dailyPriceFromMonthly(business.monthlyPrice).roundToInt())
        assertEquals(399, business.monthlyEquivalentFor(BillingCycle.ANNUAL).roundToInt())
        assertEquals(13, business.dailyEquivalentFor(BillingCycle.ANNUAL).roundToInt())
    }

    @Test
    fun `free plan keeps core operations but excludes premium access`() {
        val free = DEFAULT_SUBSCRIPTION_PLANS.single { it.code == SubscriptionPlanCode.FREE }
        fun enabled(feature: SubscriptionFeature) = free.features.any { it.feature == feature && it.enabled }
        assertEquals(0.0, free.monthlyPrice, 0.0)
        assertTrue(enabled(SubscriptionFeature.ORDER_MANAGEMENT))
        assertTrue(enabled(SubscriptionFeature.BASIC_REPORTS))
        assertFalse(enabled(SubscriptionFeature.MOBILE_APP_ORDERING))
        assertFalse(enabled(SubscriptionFeature.DELIVERY_MANAGEMENT))
        assertFalse(enabled(SubscriptionFeature.DATA_EXPORT))
        assertEquals(1L, free.limits.single { it.limitType == SubscriptionLimit.STATION_COUNT }.maxValue)
    }

    @Test
    fun `export capabilities match plan matrix`() {
        val starter = DEFAULT_SUBSCRIPTION_PLANS.single { it.code == SubscriptionPlanCode.STARTER }
        val business = DEFAULT_SUBSCRIPTION_PLANS.single { it.code == SubscriptionPlanCode.BUSINESS }
        val pro = DEFAULT_SUBSCRIPTION_PLANS.single { it.code == SubscriptionPlanCode.PRO }
        fun has(plan: SubscriptionPlan, feature: SubscriptionFeature) = plan.features.any { it.feature == feature && it.enabled }
        assertTrue(has(starter, SubscriptionFeature.EXPORT_CSV))
        assertTrue(has(starter, SubscriptionFeature.MOBILE_APP_ORDERING))
        assertTrue(has(starter, SubscriptionFeature.DELIVERY_MANAGEMENT))
        assertFalse(has(starter, SubscriptionFeature.EXPORT_EXCEL))
        assertFalse(has(starter, SubscriptionFeature.EXPORT_PDF))
        assertTrue(has(business, SubscriptionFeature.EXPORT_EXCEL))
        assertTrue(has(business, SubscriptionFeature.EXPORT_PDF))
        assertTrue(has(pro, SubscriptionFeature.CONSOLIDATED_REPORTS))
    }

    @Test
    fun `client accounts are included only in business and pro`() {
        fun has(planCode: SubscriptionPlanCode) = DEFAULT_SUBSCRIPTION_PLANS
            .single { it.code == planCode }
            .features
            .any { it.feature == SubscriptionFeature.ACCOUNTS_RECEIVABLE && it.enabled }

        assertFalse(has(SubscriptionPlanCode.FREE))
        assertFalse(has(SubscriptionPlanCode.STARTER))
        assertTrue(has(SubscriptionPlanCode.BUSINESS))
        assertTrue(has(SubscriptionPlanCode.PRO))
    }

    @Test
    fun `expense tracking is included only in business and pro`() {
        fun has(planCode: SubscriptionPlanCode) = DEFAULT_SUBSCRIPTION_PLANS
            .single { it.code == planCode }
            .features
            .any { it.feature == SubscriptionFeature.EXPENSE_TRACKING && it.enabled }

        assertFalse(has(SubscriptionPlanCode.FREE))
        assertFalse(has(SubscriptionPlanCode.STARTER))
        assertTrue(has(SubscriptionPlanCode.BUSINESS))
        assertTrue(has(SubscriptionPlanCode.PRO))
    }
}
