package com.aesprt.aquahub.feature.orders.detail

import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.Payment
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.PaymentStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderSettlementDecisionTest {
    @Test
    fun `pending receipts are excluded from collected amount`() {
        val receipts = listOf(
            Payment(amount = 50.0, status = PaymentStatus.PAID),
            Payment(amount = 70.0, status = PaymentStatus.PENDING)
        )

        assertEquals(50.0, collectedPaymentAmount(receipts), 0.0)
    }

    @Test
    fun `client account order completes without another settlement prompt`() {
        val order = Order(paymentMethod = PaymentMethod.CLIENT_ACCOUNT, paymentStatus = PaymentStatus.PENDING)

        assertFalse(order.requiresSettlementConfirmation())
    }

    @Test
    fun `already paid order completes without another settlement prompt`() {
        val order = Order(paymentMethod = PaymentMethod.CASH, paymentStatus = PaymentStatus.PAID)

        assertFalse(order.requiresSettlementConfirmation())
    }

    @Test
    fun `unpaid cod order still requires settlement confirmation`() {
        val order = Order(paymentMethod = PaymentMethod.CASH_ON_DELIVERY, paymentStatus = PaymentStatus.PENDING)

        assertTrue(order.requiresSettlementConfirmation())
    }
}
