package com.aesprt.aquahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class CustomerPhoneNormalizationTest {
    @Test
    fun `Philippine local and international phone formats share one identity`() {
        assertEquals(
            "9127501055",
            "+63 912-750-1055".normalizeCustomerPhone()
        )
        assertEquals(
            "+63 912-750-1055".normalizeCustomerPhone(),
            "0912 750 1055".normalizeCustomerPhone()
        )
    }

    @Test
    fun `blank phone remains unmatchable`() {
        assertEquals("", "  - () ".normalizeCustomerPhone())
    }
}
