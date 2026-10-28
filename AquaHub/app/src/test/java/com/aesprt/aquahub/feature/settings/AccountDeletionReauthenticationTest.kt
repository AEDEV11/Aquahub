package com.aesprt.aquahub.feature.settings

import com.aesprt.aquahub.data.repository.AccountDeletionReauthenticationRequiredException
import com.aesprt.aquahub.data.supabase.auth.ReauthenticationIdentityMismatchException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionReauthenticationTest {
    @Test
    fun repositoryReauthenticationRequirementOpensVerificationFlow() {
        assertTrue(requiresDeletionReauthentication(AccountDeletionReauthenticationRequiredException()))
    }

    @Test
    fun changedIdentityDoesNotPromptToDeleteTheNewAccount() {
        assertFalse(requiresDeletionReauthentication(ReauthenticationIdentityMismatchException()))
    }
}
