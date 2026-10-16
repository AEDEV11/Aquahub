package com.aesprt.aquahub.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailVerificationVisibilityTest {
    @Test
    fun `verification is shown only for an email provider with an address`() {
        assertTrue(shouldShowEmailVerification("email", "owner@example.com"))
        assertTrue(shouldShowEmailVerification("EMAIL", "owner@example.com"))

        assertFalse(shouldShowEmailVerification("phone", "owner@example.com"))
        assertFalse(shouldShowEmailVerification("google", "owner@example.com"))
        assertFalse(shouldShowEmailVerification("unknown", "owner@example.com"))
        assertFalse(shouldShowEmailVerification(null, "owner@example.com"))
        assertFalse(shouldShowEmailVerification("email", null))
        assertFalse(shouldShowEmailVerification("email", "  "))
    }
}
