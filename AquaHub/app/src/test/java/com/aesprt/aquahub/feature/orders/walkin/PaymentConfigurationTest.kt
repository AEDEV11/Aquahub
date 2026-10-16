package com.aesprt.aquahub.feature.orders.walkin

import com.aesprt.aquahub.domain.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PaymentConfigurationTest {
    @Test
    fun `pickup shows only activated cash and wallets`() {
        val state = WalkInSaleUiState(
            deliveryMode = "PICKUP",
            cashPaymentEnabled = true,
            codPaymentEnabled = true,
            gcashPaymentEnabled = true,
            mayaPaymentEnabled = false
        )

        assertEquals(listOf(PaymentMethod.CASH, PaymentMethod.GCASH), state.availablePaymentMethods)
        assertFalse(PaymentMethod.CARD in state.availablePaymentMethods)
        assertFalse(PaymentMethod.CASH_ON_DELIVERY in state.availablePaymentMethods)
    }

    @Test
    fun `delivery replaces cash with cod and keeps activated wallets`() {
        val state = WalkInSaleUiState(
            deliveryMode = "DELIVERY",
            cashPaymentEnabled = true,
            codPaymentEnabled = true,
            gcashPaymentEnabled = false,
            mayaPaymentEnabled = true
        )

        assertEquals(listOf(PaymentMethod.CASH_ON_DELIVERY, PaymentMethod.MAYA), state.availablePaymentMethods)
        assertFalse(PaymentMethod.CASH in state.availablePaymentMethods)
        assertFalse(PaymentMethod.CARD in state.availablePaymentMethods)
    }
}
