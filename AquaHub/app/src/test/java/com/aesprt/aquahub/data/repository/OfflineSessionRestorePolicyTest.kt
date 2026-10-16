package com.aesprt.aquahub.data.repository

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OfflineSessionRestorePolicyTest {
    @Test
    fun cachedOwnerIsUsedOnlyForTheSameAuthenticatedAndBoundIdentity() {
        assertTrue(
            canUseCachedOwnerForOfflineRestore(
                activeAuthUid = "owner-a",
                expectedAuthUid = "owner-a",
                boundLocalUid = "owner-a",
                cachedOwnerUid = "owner-a"
            )
        )
        assertFalse(canUseCachedOwnerForOfflineRestore(null, "owner-a", "owner-a", "owner-a"))
        assertFalse(canUseCachedOwnerForOfflineRestore("owner-b", "owner-a", "owner-a", "owner-a"))
        assertFalse(canUseCachedOwnerForOfflineRestore("owner-a", "owner-a", "owner-b", "owner-a"))
        assertFalse(canUseCachedOwnerForOfflineRestore("owner-a", "owner-a", "owner-a", "owner-b"))
    }

    @Test
    fun onlyTransportFailuresQualifyForCachedSessionFallback() {
        assertTrue(IOException("offline").isOfflineTransportFailure())
        assertTrue(IllegalStateException("wrapped", IOException("offline")).isOfflineTransportFailure())
        assertFalse(IllegalStateException("invalid session").isOfflineTransportFailure())
        assertFalse(CancellationException("cancelled").isOfflineTransportFailure())
    }

    @Test
    fun legacyOutboxRecoveryRequiresTheAuthenticatedBoundAndCachedOwnerToMatch() {
        assertTrue(mayRecoverLegacyOutbox("owner-a", "owner-a", "owner-a"))
        assertFalse(mayRecoverLegacyOutbox("owner-a", null, "owner-a"))
        assertFalse(mayRecoverLegacyOutbox("owner-a", "owner-b", "owner-a"))
        assertFalse(mayRecoverLegacyOutbox("owner-a", "owner-a", "owner-b"))
        assertFalse(mayRecoverLegacyOutbox("", "", ""))
    }
}
