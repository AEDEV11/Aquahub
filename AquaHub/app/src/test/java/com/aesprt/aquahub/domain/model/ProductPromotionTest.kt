package com.aesprt.aquahub.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProductPromotionTest {
    @Test
    fun `quantity promotion applies only when threshold is reached`() {
        val product = Product(
            price = 40.0,
            promotion = ProductPromotion(
                label = "Buy three",
                type = PromotionType.QUANTITY_BREAK,
                percentBps = 2_500,
                minimumQuantity = 3,
            ),
        )

        assertEquals(40.0, product.unitPriceFor(2), 0.0)
        assertEquals(30.0, product.unitPriceFor(3), 0.0)
        assertEquals(40.0, product.unitPriceFor(3, promotionsAllowed = false), 0.0)
    }

    @Test
    fun `fixed price promotion remains exact in centavos`() {
        val product = Product(
            price = 35.0,
            promotion = ProductPromotion(
                label = "Sale",
                type = PromotionType.FIXED_PRICE,
                promotionalPrice = 29.95,
            ),
        )

        assertEquals(29.95, product.unitPriceFor(1), 0.0)
    }
}
