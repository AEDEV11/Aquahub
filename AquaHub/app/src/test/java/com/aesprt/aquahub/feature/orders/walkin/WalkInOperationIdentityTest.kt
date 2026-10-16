package com.aesprt.aquahub.feature.orders.walkin

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkInOperationIdentityTest {
    @Test
    fun `same checkout retry reuses the operation id`() {
        val identity = WalkInOperationIdentity { "generated-id" }

        val first = identity.forRequest(fingerprint = "same sale", initialOperationId = "first-id")
        val retry = identity.forRequest(fingerprint = "same sale", initialOperationId = "new-screen-id")

        assertEquals("first-id", first)
        assertEquals(first, retry)
    }

    @Test
    fun `edited sale receives a new operation id and reset starts a fresh identity`() {
        val identity = WalkInOperationIdentity { "generated-id" }

        val first = identity.forRequest(fingerprint = "one item", initialOperationId = "first-id")
        val edited = identity.forRequest(fingerprint = "two items", initialOperationId = "stale-id")
        identity.reset()
        val nextSale = identity.forRequest(fingerprint = "two items", initialOperationId = "next-sale-id")

        assertEquals("first-id", first)
        assertEquals("generated-id", edited)
        assertEquals("next-sale-id", nextSale)
    }
}
