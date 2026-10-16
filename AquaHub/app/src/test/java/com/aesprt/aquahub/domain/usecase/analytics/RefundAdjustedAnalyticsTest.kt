package com.aesprt.aquahub.domain.usecase.analytics

import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.Payment
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.PaymentRefund
import com.aesprt.aquahub.domain.model.PaymentStatus
import com.aesprt.aquahub.domain.model.RefundStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RefundAdjustedAnalyticsTest {
    @Test
    fun customerRiderAndStationTotalsUseNetCompletedRefundsForSelectedPeriod() {
        val receipts = listOf(
            receipt(orderId = 1L, amount = 100.0, paidAt = 1_100L),
            receipt(orderId = 1L, amount = 20.0, paidAt = 1_200L),
            receipt(orderId = 2L, amount = 40.0, paidAt = 1_200L),
            receipt(orderId = 3L, amount = 30.0, paidAt = 500L),
        )
        val refunds = listOf(
            refund(orderId = 1L, cents = 2_500L, status = RefundStatus.COMPLETED, completedAt = 1_500L),
            refund(orderId = 1L, cents = 2_500L, status = RefundStatus.PENDING, completedAt = null),
            refund(orderId = 1L, cents = 5_000L, status = RefundStatus.FAILED, completedAt = null),
            refund(orderId = 2L, cents = 4_000L, status = RefundStatus.COMPLETED, completedAt = 1_600L),
            // The receipt was collected outside this report window; this refund is still a current-period adjustment.
            refund(orderId = 3L, cents = 1_000L, status = RefundStatus.COMPLETED, completedAt = 1_700L),
            refund(orderId = 1L, cents = 1_000L, status = RefundStatus.COMPLETED, completedAt = 2_000L),
        )

        val netByOrder = netPaidAmountsByOrder(receipts, refunds, start = 1_000L, end = 2_000L)
        val orders = mapOf(
            1L to Order(id = 1L, stationId = 7L, customerId = 11L, riderId = 5L),
            2L to Order(id = 2L, stationId = 7L, customerId = 11L, riderId = 6L),
            3L to Order(id = 3L, stationId = 8L, customerId = 12L, riderId = 5L),
        )

        assertEquals(95.0, netByOrder.getValue(1L), 0.001)
        assertEquals(0.0, netByOrder.getValue(2L), 0.001)
        assertEquals(-10.0, netByOrder.getValue(3L), 0.001)
        assertEquals(
            mapOf(11L to 95.0, 12L to -10.0),
            netSalesByDimension(netByOrder, orders) { it.customerId },
        )
        assertEquals(
            mapOf(5L to 85.0, 6L to 0.0),
            netSalesByDimension(netByOrder, orders) { it.riderId },
        )
        assertEquals(
            mapOf(7L to 95.0, 8L to -10.0),
            netSalesByDimension(netByOrder, orders) { it.stationId },
        )
    }

    @Test
    fun unpaidReceiptsAndRefundsOutsideWindowDoNotChangeNetSales() {
        val net = netPaidAmountsByOrder(
            receipts = listOf(
                receipt(orderId = 4L, amount = 500.0, paidAt = 1_000L, status = PaymentStatus.PENDING),
                receipt(orderId = 5L, amount = 60.0, paidAt = 2_000L),
            ),
            refunds = listOf(
                refund(orderId = 4L, cents = 50_000L, status = RefundStatus.COMPLETED, completedAt = 1_500L),
                refund(orderId = 5L, cents = 2_000L, status = RefundStatus.COMPLETED, completedAt = 999L),
            ),
            start = 1_000L,
            end = 2_000L,
        )

        assertEquals(mapOf(4L to -500.0), net)
    }

    private fun receipt(
        orderId: Long,
        amount: Double,
        paidAt: Long,
        status: PaymentStatus = PaymentStatus.PAID,
    ) = Payment(
        orderId = orderId,
        paymentMethod = PaymentMethod.CASH,
        amount = amount,
        status = status,
        createdAt = paidAt,
        paidAt = paidAt,
    )

    private fun refund(orderId: Long, cents: Long, status: RefundStatus, completedAt: Long?) = PaymentRefund(
        orderId = orderId,
        originalPaymentId = null,
        operationId = "refund-$orderId-$cents-$status-$completedAt",
        amountCentavos = cents,
        paymentMethod = PaymentMethod.CASH,
        status = status,
        reason = "Test adjustment",
        createdAt = completedAt ?: 1_100L,
        completedAt = completedAt,
    )
}
