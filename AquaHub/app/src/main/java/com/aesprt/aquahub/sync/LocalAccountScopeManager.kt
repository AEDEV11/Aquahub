package com.aesprt.aquahub.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes tenant changes with outbox replay and records which Supabase user owns Room data. */
class LocalAccountScopeManager(
    private val dataStore: DataStore<Preferences>
) {
    private val mutex = Mutex()
    private val ownerUidKey = stringPreferencesKey("local_account_scope_uid")
    private val legacyOutboxPreparedKey = booleanPreferencesKey("legacy_outbox_quarantine_prepared_v1")

    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun ownerUid(): String? = dataStore.data.first()[ownerUidKey]

    suspend fun isLegacyOutboxPrepared(): Boolean =
        dataStore.data.first()[legacyOutboxPreparedKey] == true

    /** Runs once before sync scheduling; a failed quarantine leaves the marker unset for safe retry. */
    suspend fun prepareLegacyOutbox(quarantine: suspend () -> Int): Int = withExclusive {
        if (isLegacyOutboxPrepared()) return@withExclusive 0
        val changed = quarantine()
        dataStore.edit { it[legacyOutboxPreparedKey] = true }
        changed
    }

    suspend fun bind(uid: String) {
        require(uid.isNotBlank())
        dataStore.edit { it[ownerUidKey] = uid }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(ownerUidKey) }
    }
}

internal enum class AccountScopeAction {
    KEEP,
    CLAIM_LEGACY_CACHE,
    CLEAR_AND_BIND,
    BLOCK_PENDING_WORK,
    BLOCK_LOCAL_DATA,
    CONFIRM_LOCAL_DATA_CLEAR
}

/** Logout may erase the cached tenant only when every queued sync operation is settled. */
internal fun shouldClearLocalAccountCacheAfterLogout(outstandingWork: Int): Boolean =
    outstandingWork == 0

internal fun countOutstandingLocalOperations(syncOperations: Int, offlinePosOperations: Int): Int =
    (syncOperations.coerceAtLeast(0).toLong() + offlinePosOperations.coerceAtLeast(0).toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

/**
 * A legacy Firebase-owned Room cache may be rebound only when the currently
 * authenticated Supabase profile explicitly maps to that exact local identity.
 * Email/phone similarity is not an identity proof, and a cache already bound to
 * a different account must never be claimed through this migration path.
 */
internal fun isVerifiedLegacyOwnerMapping(
    authenticatedUid: String,
    boundUid: String?,
    serverLegacyUid: String?,
    localOwnerUid: String?
): Boolean {
    val legacyUid = serverLegacyUid?.trim()?.takeIf(String::isNotEmpty) ?: return false
    return authenticatedUid.isNotBlank() &&
        authenticatedUid != legacyUid &&
        localOwnerUid == legacyUid &&
        (boundUid == null || boundUid == legacyUid)
}

internal fun decideAccountScopeAction(
    boundUid: String?,
    requestedUid: String,
    outstandingWork: Int,
    hasMatchingLocalOwner: Boolean,
    hasLocalBusinessData: Boolean = false,
    hasBoundLocalOwner: Boolean = false
): AccountScopeAction = when {
    boundUid == requestedUid -> AccountScopeAction.KEEP
    boundUid == null && hasMatchingLocalOwner -> AccountScopeAction.CLAIM_LEGACY_CACHE
    outstandingWork > 0 -> AccountScopeAction.BLOCK_PENDING_WORK
    hasLocalBusinessData && boundUid != null && hasBoundLocalOwner -> AccountScopeAction.CONFIRM_LOCAL_DATA_CLEAR
    hasLocalBusinessData -> AccountScopeAction.BLOCK_LOCAL_DATA
    else -> AccountScopeAction.CLEAR_AND_BIND
}

class LocalAccountScopeMismatchException : IllegalStateException(
    "Local pending changes belong to a different AquaHub account. Sign in to that account to sync first."
)

class LegacyOutboxNotPreparedException : IllegalStateException(
    "Legacy offline changes have not yet been protected from automatic replay."
)
