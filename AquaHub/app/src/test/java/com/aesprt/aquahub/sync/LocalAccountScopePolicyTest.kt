package com.aesprt.aquahub.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAccountScopePolicyTest {
    @Test
    fun `legacy owner can be mapped only by exact authenticated server identity`() {
        assertTrue(
            isVerifiedLegacyOwnerMapping(
                authenticatedUid = "supabase-user",
                boundUid = "firebase-user",
                serverLegacyUid = "firebase-user",
                localOwnerUid = "firebase-user"
            )
        )
        assertTrue(
            isVerifiedLegacyOwnerMapping(
                authenticatedUid = "supabase-user",
                boundUid = null,
                serverLegacyUid = "firebase-user",
                localOwnerUid = "firebase-user"
            )
        )
        assertFalse(
            isVerifiedLegacyOwnerMapping(
                authenticatedUid = "supabase-user",
                boundUid = "different-account",
                serverLegacyUid = "firebase-user",
                localOwnerUid = "firebase-user"
            )
        )
        assertFalse(
            isVerifiedLegacyOwnerMapping(
                authenticatedUid = "supabase-user",
                boundUid = "firebase-user",
                serverLegacyUid = null,
                localOwnerUid = "firebase-user"
            )
        )
        assertFalse(
            isVerifiedLegacyOwnerMapping(
                authenticatedUid = "supabase-user",
                boundUid = "firebase-user",
                serverLegacyUid = "firebase-user",
                localOwnerUid = "another-local-owner"
            )
        )
        assertFalse(
            isVerifiedLegacyOwnerMapping(
                authenticatedUid = "same-uid",
                boundUid = "same-uid",
                serverLegacyUid = "same-uid",
                localOwnerUid = "same-uid"
            )
        )
    }

    @Test
    fun `logout clears the local account cache only after sync work is settled`() {
        assertTrue(shouldClearLocalAccountCacheAfterLogout(outstandingWork = 0))
        assertFalse(shouldClearLocalAccountCacheAfterLogout(outstandingWork = 1))
        assertFalse(shouldClearLocalAccountCacheAfterLogout(outstandingWork = 4))
    }

    @Test
    fun `same account keeps existing cache and outbox`() {
        assertEquals(
            AccountScopeAction.KEEP,
            decideAccountScopeAction("owner-a", "owner-a", outstandingWork = 4, hasMatchingLocalOwner = true)
        )
    }

    @Test
    fun `legacy cache is claimed only by its exact stored owner identity`() {
        assertEquals(
            AccountScopeAction.CLAIM_LEGACY_CACHE,
            decideAccountScopeAction(null, "owner-a", outstandingWork = 3, hasMatchingLocalOwner = true)
        )
        assertEquals(
            AccountScopeAction.BLOCK_PENDING_WORK,
            decideAccountScopeAction(null, "owner-b", outstandingWork = 3, hasMatchingLocalOwner = false)
        )
    }

    @Test
    fun `different account can replace only an empty outbox`() {
        assertEquals(
            AccountScopeAction.BLOCK_PENDING_WORK,
            decideAccountScopeAction("owner-a", "owner-b", outstandingWork = 1, hasMatchingLocalOwner = false)
        )
        assertEquals(
            AccountScopeAction.CLEAR_AND_BIND,
            decideAccountScopeAction("owner-a", "owner-b", outstandingWork = 0, hasMatchingLocalOwner = false)
        )
    }

    @Test
    fun `different account cannot erase unqueued local business records`() {
        assertEquals(
            AccountScopeAction.BLOCK_LOCAL_DATA,
            decideAccountScopeAction(
                boundUid = "owner-a",
                requestedUid = "owner-b",
                outstandingWork = 0,
                hasMatchingLocalOwner = false,
                hasLocalBusinessData = true
            )
        )
    }

    @Test
    fun `known previous account with no outstanding work requires confirmation before cache clear`() {
        assertEquals(
            AccountScopeAction.CONFIRM_LOCAL_DATA_CLEAR,
            decideAccountScopeAction(
                boundUid = "owner-a",
                requestedUid = "owner-b",
                outstandingWork = 0,
                hasMatchingLocalOwner = false,
                hasLocalBusinessData = true,
                hasBoundLocalOwner = true
            )
        )
    }

    @Test
    fun `outstanding work still blocks even when previous owner is known`() {
        assertEquals(
            AccountScopeAction.BLOCK_PENDING_WORK,
            decideAccountScopeAction(
                boundUid = "owner-a",
                requestedUid = "owner-b",
                outstandingWork = 1,
                hasMatchingLocalOwner = false,
                hasLocalBusinessData = true,
                hasBoundLocalOwner = true
            )
        )
    }

    @Test
    fun `unverified local data stays blocked without a known previous owner`() {
        assertEquals(
            AccountScopeAction.BLOCK_LOCAL_DATA,
            decideAccountScopeAction(
                boundUid = "owner-a",
                requestedUid = "owner-b",
                outstandingWork = 0,
                hasMatchingLocalOwner = false,
                hasLocalBusinessData = true,
                hasBoundLocalOwner = false
            )
        )
    }
}
