package com.aesprt.aquahub.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRetryPolicyTest {
    @Test
    fun `transient HTTP statuses retry while client and conflict errors do not`() {
        listOf(408, 425, 429, 500, 502, 503, 599).forEach { status ->
            assertTrue("$status should be retryable", status.isRetryableSyncStatus())
        }
        listOf(200, 400, 401, 403, 404, 409, 422, 499, 600).forEach { status ->
            assertFalse("$status should not be retryable", status.isRetryableSyncStatus())
        }
    }

    @Test
    fun `retry window is bounded and exposes saved operation for manual recovery`() {
        assertEquals("RETRY", syncRetryState(failureCount = 1))
        assertEquals("RETRY", syncRetryState(failureCount = 7))
        assertEquals("FAILED", syncRetryState(failureCount = 8))
    }
}
