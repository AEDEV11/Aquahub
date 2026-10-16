package com.aesprt.aquahub.data.supabase.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test
    fun `normalizes local Philippine mobile number`() {
        assertEquals("+639171234567", PhoneNumberNormalizer.normalizePhilippines("0917 123 4567"))
    }

    @Test
    fun `normalizes existing E164 number`() {
        assertEquals("+639171234567", PhoneNumberNormalizer.normalizePhilippines("+639171234567"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non Philippine number`() {
        PhoneNumberNormalizer.normalizePhilippines("0912345678")
    }
}
