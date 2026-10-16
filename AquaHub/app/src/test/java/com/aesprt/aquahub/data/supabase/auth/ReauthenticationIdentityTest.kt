package com.aesprt.aquahub.data.supabase.auth

import org.junit.Assert.assertThrows
import org.junit.Test

class ReauthenticationIdentityTest {
    @Test
    fun sameSupabaseIdentityAllowsReauthenticationToComplete() {
        requireSameReauthenticationIdentity("owner-1", "owner-1")
    }

    @Test
    fun differentSupabaseIdentityFailsClosed() {
        assertThrows(ReauthenticationIdentityMismatchException::class.java) {
            requireSameReauthenticationIdentity("owner-1", "owner-2")
        }
    }

    @Test
    fun missingIdentityFailsClosed() {
        assertThrows(ReauthenticationIdentityMismatchException::class.java) {
            requireSameReauthenticationIdentity("owner-1", null)
        }
        assertThrows(ReauthenticationIdentityMismatchException::class.java) {
            requireSameReauthenticationIdentity("", "owner-1")
        }
    }
}
