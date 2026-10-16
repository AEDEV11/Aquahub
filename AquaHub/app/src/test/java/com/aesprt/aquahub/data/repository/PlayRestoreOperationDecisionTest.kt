package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.domain.model.BillingOperationState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayRestoreOperationDecisionTest {
    @Test
    fun `verified current purchase wins over errors from other tokens`() {
        val operation = PlayRestoreOperationDecision.choose(
            verifiedCurrent = true,
            verifiedOlder = false,
            currentFailure = null,
            pendingCurrent = false,
            pendingOlder = false,
            failure = "Another token failed"
        )

        assertEquals(BillingOperationState.Completed, operation)
    }

    @Test
    fun `current token rejection stays actionable when no purchase verifies`() {
        val operation = PlayRestoreOperationDecision.choose(
            verifiedCurrent = false,
            verifiedOlder = false,
            currentFailure = "Sign in again and restore",
            pendingCurrent = false,
            pendingOlder = false,
            failure = "An older token failed"
        )

        assertEquals(BillingOperationState.Failed("Sign in again and restore"), operation)
    }

    @Test
    fun `older success cannot hide latest purchase verification failure`() {
        val operation = PlayRestoreOperationDecision.choose(
            verifiedCurrent = false,
            verifiedOlder = true,
            currentFailure = "The latest purchase needs attention",
            pendingCurrent = false,
            pendingOlder = false,
            failure = null
        )

        assertEquals(BillingOperationState.Failed("The latest purchase needs attention"), operation)
    }

    @Test
    fun `older success can recover when latest token belongs to another business`() {
        val operation = PlayRestoreOperationDecision.choose(
            verifiedCurrent = false,
            verifiedOlder = true,
            currentFailure = null,
            pendingCurrent = false,
            pendingOlder = false,
            failure = "A different business was verified"
        )

        assertEquals(BillingOperationState.Completed, operation)
    }
}
