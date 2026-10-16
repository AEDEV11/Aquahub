package com.aesprt.aquahub.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayPurchaseErrorMessagesTest {
    @Test
    fun `acknowledgement pending gives safe recovery guidance without raw request details`() {
        val providerError = IllegalStateException(
            "{\"error\":\"PURCHASE_ACKNOWLEDGEMENT_PENDING\"} URL: https://example.test/functions/v1/verify-play-purchase Headers: Authorization=Bearer secret"
        )

        val message = PlayPurchaseErrorMessages.verificationFailure(providerError)

        assertTrue(message.contains("check your plan again"))
        assertTrue(message.contains("Do not start another purchase"))
        assertFalse(message.contains("Authorization"))
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("PURCHASE_ACKNOWLEDGEMENT_PENDING"))
    }

    @Test
    fun `other provider failures stay actionable without exposing provider text`() {
        val providerError = IllegalStateException("URL: https://example.test Headers: Authorization=Bearer secret")

        val message = PlayPurchaseErrorMessages.verificationFailure(providerError)

        assertTrue(message.contains("check your plan again"))
        assertFalse(message.contains("Authorization"))
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("secret"))
    }

    @Test
    fun `expired session maps to sign in guidance without exposing request details`() {
        val providerError = IllegalStateException(
            "{\"error\":\"UNAUTHORIZED\"} URL: https://example.test/functions/v1/verify-play-purchase Headers: Authorization=Bearer secret"
        )

        assertTrue(PlayPurchaseErrorMessages.kind(providerError) == PlayPurchaseErrorMessages.Kind.AUTH)
        val message = PlayPurchaseErrorMessages.verificationFailure(providerError)
        assertTrue(message.contains("Sign in again"))
        assertTrue(message.contains("restore"))
        assertFalse(message.contains("Authorization"))
        assertFalse(message.contains("https://"))
    }

    @Test
    fun `bound and stale purchase conflicts have distinct safe recovery guidance`() {
        val bound = IllegalStateException("{\"error\":\"PURCHASE_ALREADY_BOUND\"} Headers: Authorization=Bearer secret")
        val stale = IllegalStateException("{\"error\":\"PURCHASE_REPLACED_OR_STALE\"} Headers: Authorization=Bearer secret")

        assertTrue(PlayPurchaseErrorMessages.kind(bound) == PlayPurchaseErrorMessages.Kind.BOUND_TO_ANOTHER_ACCOUNT)
        assertTrue(PlayPurchaseErrorMessages.kind(stale) == PlayPurchaseErrorMessages.Kind.STALE_TOKEN)
        assertTrue(PlayPurchaseErrorMessages.verificationFailure(bound).contains("another AquaHub account"))
        assertTrue(PlayPurchaseErrorMessages.verificationFailure(stale).contains("older Google Play purchase"))
        assertFalse(PlayPurchaseErrorMessages.verificationFailure(bound).contains("Authorization"))
        assertFalse(PlayPurchaseErrorMessages.verificationFailure(stale).contains("secret"))
    }

    @Test
    fun `organization conflicts give safe business recovery guidance`() {
        val mismatch = IllegalStateException("{\"error\":\"PURCHASE_ORGANIZATION_MISMATCH\"} Headers: Authorization=Bearer secret")
        val ambiguous = IllegalStateException("{\"error\":\"PURCHASE_ORGANIZATION_REQUIRED\"} Headers: Authorization=Bearer secret")

        assertTrue(PlayPurchaseErrorMessages.kind(mismatch) == PlayPurchaseErrorMessages.Kind.BUSINESS_MISMATCH)
        assertTrue(PlayPurchaseErrorMessages.kind(ambiguous) == PlayPurchaseErrorMessages.Kind.BUSINESS_SELECTION_REQUIRED)
        assertTrue(PlayPurchaseErrorMessages.verificationFailure(mismatch).contains("different AquaHub business"))
        assertTrue(PlayPurchaseErrorMessages.verificationFailure(ambiguous).contains("Contact support"))
        assertFalse(PlayPurchaseErrorMessages.verificationFailure(mismatch).contains("Authorization"))
        assertFalse(PlayPurchaseErrorMessages.verificationFailure(ambiguous).contains("secret"))
    }

    @Test
    fun `replacement preflight failure blocks checkout with safe guidance`() {
        val denied = IllegalStateException(
            "{\"error\":\"PURCHASE_REPLACEMENT_NOT_ALLOWED\"} Headers: Authorization=Bearer secret"
        )
        val sessionExpired = IllegalStateException("{\"error\":\"UNAUTHORIZED\"} Headers: Authorization=Bearer secret")

        assertTrue(PlayPurchaseErrorMessages.kind(denied) == PlayPurchaseErrorMessages.Kind.REPLACEMENT_NOT_ALLOWED)
        assertTrue(PlayPurchaseErrorMessages.replacementPreflightFailure(denied).contains("selected business"))
        assertTrue(PlayPurchaseErrorMessages.replacementPreflightFailure(sessionExpired).contains("Sign in again"))
        assertFalse(PlayPurchaseErrorMessages.replacementPreflightFailure(denied).contains("Authorization"))
        assertFalse(PlayPurchaseErrorMessages.replacementPreflightFailure(denied).contains("secret"))
    }
}
