package com.aesprt.aquahub.domain.usecase.order

import com.aesprt.aquahub.data.supabase.order.SupabaseCreatedOrder
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderItem
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.domain.model.PaymentStatus
import com.aesprt.aquahub.domain.model.ProductType
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseWalkInOrderSnapshotTest {
    @Test
    fun `committed order snapshot replaces optimistic prices and state with server cents`() {
        val optimistic = Order(
            orderNumber = "local-order",
            subtotal = 12.0,
            deliveryFee = 3.0,
            discount = 2.0,
            total = 13.0,
            paymentStatus = PaymentStatus.PENDING,
            orderStatus = OrderStatus.READY_FOR_PICKUP,
            items = listOf(OrderItem(productName = "stale product name", unitPrice = 12.0, subtotal = 12.0))
        )
        val committedItem = OrderItem(
            productId = 7,
            productName = "Purified Water",
            productType = ProductType.REFILL,
            sizeLabel = "5 Gallon",
            unitPrice = 35.25,
            quantity = 2,
            subtotal = 70.50
        )
        val snapshot = SupabaseCreatedOrder(
            orderId = "remote-order",
            orderNumber = "AQH-20261009-0001",
            subtotalCentavos = 7_050,
            deliveryFeeCentavos = 250,
            discountCentavos = 0,
            totalCentavos = 7_300,
            created = true,
            status = "COMPLETED",
            paymentStatus = "PAID",
            requestedAt = 100L,
            acceptedAt = 110L,
            readyAt = 120L,
            completedAt = 130L,
            items = listOf(committedItem)
        )

        val actual = optimistic.withCommittedSupabaseSnapshot(snapshot)

        assertEquals("AQH-20261009-0001", actual.orderNumber)
        assertEquals(70.50, actual.subtotal, 0.0)
        assertEquals(2.50, actual.deliveryFee, 0.0)
        assertEquals(0.0, actual.discount, 0.0)
        assertEquals(73.0, actual.total, 0.0)
        assertEquals(PaymentStatus.PAID, actual.paymentStatus)
        assertEquals(OrderStatus.COMPLETED, actual.orderStatus)
        assertEquals(committedItem, actual.items.single())
        assertEquals(130L, actual.completedAt)
    }
}
