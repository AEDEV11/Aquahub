package com.aesprt.aquahub.feature.orders.walkin

import com.aesprt.aquahub.domain.model.Product
import com.aesprt.aquahub.domain.model.ProductPromotion
import com.aesprt.aquahub.domain.model.PromotionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkInSaleUiStateTest {
    private val promotionalProduct = Product(
        id = 7L,
        stationId = 1L,
        name = "Purified",
        sizeLabel = "20 Liters Slim",
        price = 40.0,
        stockQuantity = 20,
        promotion = ProductPromotion(
            label = "Refill saver",
            type = PromotionType.FIXED_PRICE,
            promotionalPrice = 25.0,
        ),
    )

    @Test
    fun `walk-in summary applies eligible promotion and exposes savings`() {
        val state = WalkInSaleUiState(
            products = listOf(promotionalProduct),
            selectedQuantities = mapOf(promotionalProduct.id to 2),
            promotionsAllowed = true,
        )

        assertEquals(80.0, state.regularSubtotalAmount, 0.0)
        assertEquals(50.0, state.subtotalAmount, 0.0)
        assertEquals(30.0, state.promotionSavingsAmount, 0.0)
        assertEquals(50.0, state.totalAmount, 0.0)
        assertEquals(25.0, state.selectedItemsList.single().unitPrice, 0.0)
    }

    @Test
    fun `walk-in summary keeps regular price when plan has no promotions`() {
        val state = WalkInSaleUiState(
            products = listOf(promotionalProduct),
            selectedQuantities = mapOf(promotionalProduct.id to 2),
            promotionsAllowed = false,
        )

        assertEquals(80.0, state.regularSubtotalAmount, 0.0)
        assertEquals(80.0, state.subtotalAmount, 0.0)
        assertEquals(0.0, state.promotionSavingsAmount, 0.0)
        assertEquals(80.0, state.totalAmount, 0.0)
    }

    @Test
    fun `delivery stays unavailable until delivery entitlement is resolved and allowed`() {
        assertFalse(canSelectWalkInDelivery(deliveryAccessResolved = false, deliveryManagementAllowed = true))
        assertFalse(canSelectWalkInDelivery(deliveryAccessResolved = true, deliveryManagementAllowed = false))
        assertTrue(canSelectWalkInDelivery(deliveryAccessResolved = true, deliveryManagementAllowed = true))
    }
}
