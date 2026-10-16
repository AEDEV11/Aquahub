package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.domain.model.GooglePlayBillingProducts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCheckoutOwnershipPolicyTest {
    @Test
    fun `owned AquaHub product blocks a new checkout even among other products`() {
        assertTrue(PlayCheckoutOwnershipPolicy.hasAquaHubSubscription(
            listOf("unrelated_subscription", GooglePlayBillingProducts.STARTER)
        ))
    }

    @Test
    fun `unrelated products do not block a new checkout`() {
        assertFalse(PlayCheckoutOwnershipPolicy.hasAquaHubSubscription(listOf("unrelated_subscription")))
        assertFalse(PlayCheckoutOwnershipPolicy.hasAquaHubSubscription(emptyList()))
    }

    @Test
    fun `Play owned product can identify management target without granting entitlement`() {
        assertEquals(
            GooglePlayBillingProducts.STARTER,
            PlayCheckoutOwnershipPolicy.aquaHubProductId(
                listOf("unrelated_subscription", GooglePlayBillingProducts.STARTER)
            )
        )
        assertNull(PlayCheckoutOwnershipPolicy.aquaHubProductId(listOf("unrelated_subscription")))
    }
}
