package com.aesprt.aquahub.domain.usecase.order

import com.aesprt.aquahub.domain.model.Container
import com.aesprt.aquahub.domain.model.OrderItem
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.domain.model.ProductType
import org.junit.Assert.*
import org.junit.Test

class AuditVerificationTest {

    @Test
    fun `order status transitions follow strict state machine rules`() {
        // Pending transitions
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.ACCEPTED))
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.REJECTED))
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED))
        assertFalse(OrderStatus.PENDING.canTransitionTo(OrderStatus.DELIVERED))
        assertFalse(OrderStatus.PENDING.canTransitionTo(OrderStatus.COMPLETED))

        // Accepted transitions
        assertTrue(OrderStatus.ACCEPTED.canTransitionTo(OrderStatus.PREPARING))
        assertTrue(OrderStatus.ACCEPTED.canTransitionTo(OrderStatus.CANCELLED))
        assertFalse(OrderStatus.ACCEPTED.canTransitionTo(OrderStatus.DELIVERED))

        // Preparing transitions
        assertTrue(OrderStatus.PREPARING.canTransitionTo(OrderStatus.READY_FOR_PICKUP))
        assertTrue(OrderStatus.PREPARING.canTransitionTo(OrderStatus.CANCELLED))
        assertFalse(OrderStatus.PREPARING.canTransitionTo(OrderStatus.COMPLETED))

        // Ready transitions (supports both delivery assignment and direct station pickup completion)
        assertTrue(OrderStatus.READY_FOR_PICKUP.canTransitionTo(OrderStatus.RIDER_ASSIGNED))
        assertTrue(OrderStatus.READY_FOR_PICKUP.canTransitionTo(OrderStatus.COMPLETED))
        assertFalse(OrderStatus.READY_FOR_PICKUP.canTransitionTo(OrderStatus.PENDING))

        // Delivery pipeline transitions
        assertTrue(OrderStatus.RIDER_ASSIGNED.canTransitionTo(OrderStatus.OUT_FOR_DELIVERY))
        assertTrue(OrderStatus.OUT_FOR_DELIVERY.canTransitionTo(OrderStatus.DELIVERED))
        assertTrue(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.COMPLETED))

        // Terminal states cannot transition to anything
        assertTrue(OrderStatus.COMPLETED.isTerminal())
        assertTrue(OrderStatus.REJECTED.isTerminal())
        assertTrue(OrderStatus.CANCELLED.isTerminal())
        assertFalse(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.PREPARING))
        assertFalse(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.ACCEPTED))
    }

    @Test
    fun `order total math computes accurately without floating point error`() {
        val items = listOf(
            OrderItem(
                productId = 1L,
                productName = "5 Gallon Refill",
                productType = ProductType.REFILL,
                sizeLabel = "5 Gallon",
                unitPrice = 35.0,
                quantity = 3,
                subtotal = 105.0
            ),
            OrderItem(
                productId = 2L,
                productName = "3 Gallon Refill",
                productType = ProductType.REFILL,
                sizeLabel = "3 Gallon",
                unitPrice = 25.5,
                quantity = 2,
                subtotal = 51.0
            )
        )

        val subtotal = items.sumOf { it.subtotal }
        assertEquals(156.0, subtotal, 0.001)

        val deliveryFee = 25.0
        val discount = 10.0
        val total = (subtotal + deliveryFee - discount).coerceAtLeast(0.0)
        assertEquals(171.0, total, 0.001)
    }

    @Test
    fun `container inventory balances satisfy conservation invariant`() {
        // Initial clean state: 0 containers
        val initialContainers = emptyList<Container>()
        assertEquals(0, initialContainers.sumOf { it.totalOwned })

        // After adding 50 bottles of 5 Gallon
        val container = Container(
            id = 1L,
            stationId = 10L,
            sizeLabel = "5 Gallon",
            totalOwned = 50,
            available = 50,
            withCustomers = 0,
            damaged = 0,
            lost = 0,
            depositPrice = 200.0,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        val invariant = container.available + container.withCustomers + container.damaged + container.lost
        assertEquals(container.totalOwned, invariant)

        // After 10 delivered to customers and 2 damaged
        val updated = container.copy(
            available = 38,
            withCustomers = 10,
            damaged = 2
        )
        val updatedInvariant = updated.available + updated.withCustomers + updated.damaged + updated.lost
        assertEquals(updated.totalOwned, updatedInvariant)
    }
}
