package com.aesprt.aquahub.data.repository

import org.junit.Assert.assertThrows
import org.junit.Test

class AccountDeletionIdentityTest {
    @Test
    fun directDeletionIsRejectedWithoutAReauthenticationMarker() {
        assertThrows(IllegalStateException::class.java) {
            requireDeletionIdentity(reauthenticatedUid = null, currentUid = "owner-a")
        }
    }

    @Test
    fun deletionAfterReauthenticationRequiresTheSameSupabaseUser() {
        requireDeletionIdentity(reauthenticatedUid = "owner-a", currentUid = "owner-a")
        assertThrows(IllegalStateException::class.java) {
            requireDeletionIdentity(reauthenticatedUid = "owner-a", currentUid = "owner-b")
        }
    }
}
