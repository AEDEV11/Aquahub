package com.aesprt.aquahub.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class SyncScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context)
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    init {
        // WorkManager persists requests across APK upgrades. Older installs
        // may still contain FirebaseSyncWorker requests even though that
        // worker was removed from the application. Cancel those requests
        // before registering the Supabase worker so WorkManager cannot try to
        // reflectively restore the deleted class.
        workManager.cancelUniqueWork(LEGACY_IMMEDIATE_WORK)
        workManager.cancelUniqueWork(LEGACY_PERIODIC_WORK)
        workManager.cancelAllWorkByTag(LEGACY_TAG)
    }

    fun enqueueImmediate(stationRemoteId: String? = null) {
        val request = OneTimeWorkRequestBuilder<SupabaseSyncWorker>()
            .setConstraints(networkConstraint)
            .setInputData(
                stationRemoteId?.let { workDataOf(SupabaseSyncWorker.KEY_STATION_REMOTE_ID to it) }
                    ?: androidx.work.Data.EMPTY
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        // Never cancel an in-flight reconciliation, but also never discard a
        // wake-up that arrives while it is running. The next chained worker
        // sees the same durable queue and normally exits immediately when the
        // bounded drain already handled everything.
        workManager.enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun schedulePeriodic() {
        // FCM is the low-latency wake-up path, but periodic reconciliation
        // must recover from dropped notifications and stopped processes.
        // WorkManager enforces the platform minimum of 15 minutes.
        val request = PeriodicWorkRequestBuilder<SupabaseSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Stop in-flight tenant work before local account data is replaced. */
    fun cancelPending() {
        workManager.cancelAllWorkByTag(TAG)
    }

    companion object {
        private const val IMMEDIATE_WORK = "aquahub-supabase-sync"
        private const val PERIODIC_WORK = "aquahub-supabase-periodic-sync"
        private const val TAG = "supabase-sync"
        private const val LEGACY_IMMEDIATE_WORK = "aquahub-firebase-sync"
        private const val LEGACY_PERIODIC_WORK = "aquahub-firebase-periodic-sync"
        private const val LEGACY_TAG = "firebase-sync"
    }
}
