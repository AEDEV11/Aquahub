package com.aesprt.aquahub.data.local.mapper

import com.aesprt.aquahub.data.local.dao.OrderWithItems
import com.aesprt.aquahub.data.local.entity.OrderEntity
import com.aesprt.aquahub.data.local.entity.PaymentEntity
import com.aesprt.aquahub.data.local.entity.PaymentRefundEntity
import com.aesprt.aquahub.domain.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentMethodAliasMapperTest {
    @Test
    fun `web order COD value maps to cash on delivery`() {
        val order = OrderWithItems(
            order = orderEntity(paymentMethod = "COD"),
            items = emptyList()
        ).toDomain()

        assertEquals(PaymentMethod.CASH_ON_DELIVERY, order.paymentMethod)
    }

    @Test
    fun `payment COD value maps to cash on delivery`() {
        val payment = PaymentEntity(
            orderId = 1L,
            paymentMethod = "COD",
            amount = 25.0,
            status = "PENDING",
            referenceNumber = null,
            createdAt = 1L,
            paidAt = null
        ).toDomain()

        assertEquals(PaymentMethod.CASH_ON_DELIVERY, payment.paymentMethod)
    }

    @Test
    fun `refund COD value maps to cash on delivery`() {
        val refund = PaymentRefundEntity(
            remoteId = "refund-test",
            orderId = 1L,
            originalPaymentId = null,
            operationId = "refund-operation-test",
            amountCentavos = 2_500L,
            paymentMethod = "COD",
            status = "PENDING",
            reason = "Test refund",
            createdAt = 1L,
            updatedAt = 1L
        ).toDomain()

        assertEquals(PaymentMethod.CASH_ON_DELIVERY, refund.paymentMethod)
    }

    private fun orderEntity(paymentMethod: String) = OrderEntity(
        orderNumber = "WEB-TEST",
        stationId = 1L,
        customerId = 0L,
        customerName = "Web Customer",
        customerPhone = "",
        riderId = null,
        riderName = null,
        deliveryAddressId = null,
        deliveryAddress = "Test address",
        subtotal = 25.0,
        deliveryFee = 0.0,
        discount = 0.0,
        total = 25.0,
        paymentMethod = paymentMethod,
        paymentStatus = "PENDING",
        orderStatus = "PENDING",
        scheduledDeliveryStart = null,
        scheduledDeliveryEnd = null,
        customerNote = null,
        stationNote = null,
        createdAt = 1L,
        acceptedAt = null,
        readyAt = null,
        assignedAt = null,
        outForDeliveryAt = null,
        deliveredAt = null,
        updatedAt = null,
        completedAt = null,
        cancelledAt = null
    )
}
