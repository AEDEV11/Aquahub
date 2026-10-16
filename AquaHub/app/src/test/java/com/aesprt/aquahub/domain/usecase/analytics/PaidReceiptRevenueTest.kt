package com.aesprt.aquahub.domain.usecase.analytics

import com.aesprt.aquahub.domain.model.Payment
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.PaymentStatus
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class PaidReceiptRevenueTest {
    @Test
    fun `only paid receipt amounts are counted at their collection time`() {
        val receipts = listOf(
            Payment(
                orderId = 1,
                paymentMethod = PaymentMethod.CASH,
                amount = 25.0,
                status = PaymentStatus.PAID,
                createdAt = 100,
                paidAt = 200
            ),
            Payment(
                orderId = 1,
                paymentMethod = PaymentMethod.CASH,
                amount = 10.0,
                status = PaymentStatus.PAID,
                createdAt = 210,
                paidAt = null
            ),
            Payment(
                orderId = 2,
                paymentMethod = PaymentMethod.CASH_ON_DELIVERY,
                amount = 70.0,
                status = PaymentStatus.PENDING,
                createdAt = 220
            )
        )

        val collected = paidReceiptsBetween(receipts, start = 200, end = 220)

        assertEquals(listOf(25.0, 10.0), collected.map { it.amount })
        assertEquals(35.0, collected.sumOf { it.amount }, 0.001)
    }

    @Test
    fun `cancelled and rejected orders are not eligible for sales reporting`() {
        assertEquals(true, isSalesEligibleOrder(Order(orderStatus = OrderStatus.COMPLETED)))
        assertEquals(false, isSalesEligibleOrder(Order(orderStatus = OrderStatus.CANCELLED)))
        assertEquals(false, isSalesEligibleOrder(Order(orderStatus = OrderStatus.REJECTED)))
    }
}
