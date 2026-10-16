package com.aesprt.aquahub.sync

import com.aesprt.aquahub.data.supabase.auth.PhoneNumberNormalizer
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseMigrationModelsTest {
    @Test
    fun `money mapping uses integer centavos`() {
        val centavos = BigDecimal("123.456")
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
        assertEquals(12346L, centavos)
        assertEquals(BigDecimal("123.46"), BigDecimal.valueOf(centavos, 2))
    }

    @Test
    fun `phone auth uses canonical E164 identity without an email alias`() {
        assertEquals("+639171234567", PhoneNumberNormalizer.normalizePhilippines("09 171 234 567"))
        assertEquals("+639171234567", PhoneNumberNormalizer.normalizePhilippines("639171234567"))
    }
}
