package com.aesprt.aquahub.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GooglePlayBillingProductsTest {
    @Test
    fun `configured monthly base plan resolves to monthly`() {
        assertEquals(BillingCycle.MONTHLY, GooglePlayBillingProducts.cycleForOffer("monthly", "P1M"))
    }

    @Test
    fun `configured yearly base plan resolves to annual`() {
        assertEquals(BillingCycle.ANNUAL, GooglePlayBillingProducts.cycleForOffer("yearly", "P1Y"))
    }

    @Test
    fun `unknown billing period is ignored`() {
        assertNull(GooglePlayBillingProducts.cycleForOffer("base", "P1W"))
    }

    @Test
    fun `unknown base plan is rejected even when duration looks supported`() {
        assertNull(GooglePlayBillingProducts.cycleForOffer("base", "P1M"))
    }

    @Test
    fun `configured base plan is rejected when Play period disagrees`() {
        assertNull(GooglePlayBillingProducts.cycleForOffer("monthly", "P1Y"))
        assertNull(GooglePlayBillingProducts.cycleForOffer("yearly", "P1M"))
    }
}
