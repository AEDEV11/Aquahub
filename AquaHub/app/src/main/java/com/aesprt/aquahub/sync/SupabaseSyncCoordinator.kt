package com.aesprt.aquahub.sync

import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Replays the Room outbox through Supabase only. There is no Firebase fallback. */
class SupabaseSyncCoordinator(
    private val syncDao: SyncDao,
    private val pendingMutationWriter: SupabasePendingMutationWriter,
    private val scheduler: SyncScheduler,
    private val localAccountScope: LocalAccountScopeManager
) {
    private val mutex = Mutex()

    suspend fun syncSubscription(): SyncSummary = sync()

    suspend fun sync(): SyncSummary = localAccountScope.withExclusive {
      mutex.withLock {
        if (!localAccountScope.isLegacyOutboxPrepared()) throw LegacyOutboxNotPreparedException()
        val currentUid = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
            ?: throw NoAuthenticatedCloudSessionException()
        if (localAccountScope.ownerUid() != currentUid) throw LocalAccountScopeMismatchException()
        syncDao.recoverInterruptedWork()
        syncDao.requeueLegacyContainerIdentityConflicts()
        var summary = SyncSummary()
        for (round in 0 until MAX_ROUNDS) {
            val pending = syncDao.getPending(BATCH_SIZE)
            if (pending.isEmpty()) break
            var progressed = false
            for (item in pending) {
                try {
                    if (pendingMutationWriter.push(item)) {
                        syncDao.markCompleted(item.id)
                        summary += SyncSummary(pushed = 1)
                        progressed = true
                    } else {
                        syncDao.markFailed(item.id, "FAILED", System.currentTimeMillis(), "SUPABASE_MAPPING_REQUIRED", "This offline mutation has no Supabase replay path.")
                        summary += SyncSummary(permanentFailures = 1)
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val retryable = error.isTransientSyncFailure()
                    val failureCount = item.retryCount + 1
                    val retryState = if (retryable) syncRetryState(failureCount) else "FAILED"
                    syncDao.markFailed(
                        item.id,
                        retryState,
                        System.currentTimeMillis(),
                        if (retryable && retryState == "FAILED") "RETRY_LIMIT_REACHED" else error::class.simpleName,
                        if (retryable && retryState == "FAILED") {
                            "Automatic synchronization stopped after a bounded retry window. Retry this saved operation when the service is available."
                        } else {
                            error.message
                        }
                    )
                    summary += if (retryable && retryState == "RETRY") {
                        SyncSummary(retryableFailures = 1)
                    } else {
                        SyncSummary(permanentFailures = 1)
                    }
                }
            }
            if (!progressed) break
            if (round == MAX_ROUNDS - 1 && syncDao.getPendingCount() > 0) {
                // Keep large first-login migrations moving without holding a
                // single worker for an unbounded number of database writes.
                scheduler.enqueueImmediate()
            }
        }

        try {
            pendingMutationWriter.refreshContainerInventory()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            summary += if (error.isTransientSyncFailure()) {
                SyncSummary(retryableFailures = 1)
            } else {
                SyncSummary(permanentFailures = 1)
            }
        }
        summary
      }
    }

    private companion object {
        const val BATCH_SIZE = 64
        const val MAX_ROUNDS = 8
    }
}

class NoAuthenticatedCloudSessionException : IllegalStateException("No authenticated Supabase session")
