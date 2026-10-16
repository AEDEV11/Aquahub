package com.aesprt.aquahub.data.local.mapper

import com.aesprt.aquahub.domain.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationPaymentMapperTest {
    @Test
    fun `payment activation survives Room mapping`() {
        val station = Station(
            id = 7,
            name = "Station",
            cashPaymentEnabled = false,
            codPaymentEnabled = true,
            gcashPaymentEnabled = true,
            gcashAccountName = "Aqua Hub",
            gcashAccountNumber = "09171234567",
            mayaPaymentEnabled = true,
            mayaAccountName = "Aqua Maya",
            mayaAccountNumber = "09981234567"
        )

        val restored = station.toEntity().toDomain()

        assertFalse(restored.cashPaymentEnabled)
        assertTrue(restored.codPaymentEnabled)
        assertTrue(restored.gcashPaymentEnabled)
        assertEquals("Aqua Hub", restored.gcashAccountName)
        assertEquals("09171234567", restored.gcashAccountNumber)
        assertTrue(restored.mayaPaymentEnabled)
        assertEquals("Aqua Maya", restored.mayaAccountName)
        assertEquals("09981234567", restored.mayaAccountNumber)
    }
}
