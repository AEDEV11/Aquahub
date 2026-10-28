package com.aesprt.aquahub.data.supabase.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BestEffortSignOutTest {
    @Test
    fun `clears local session when remote sign out fails`() = runBlocking {
        var clearCount = 0

        bestEffortSignOut(
            signOut = { throw IllegalStateException("network unavailable") },
            clearLocalSession = { clearCount++ }
        )

        assertEquals(1, clearCount)
    }

    @Test
    fun `clears local session and preserves cancellation`() = runBlocking {
        var clearCount = 0

        assertThrows(CancellationException::class.java) {
            runBlocking {
                bestEffortSignOut(
                    signOut = { throw CancellationException("cancelled") },
                    clearLocalSession = { clearCount++ }
                )
            }
        }

        assertEquals(1, clearCount)
    }
}
