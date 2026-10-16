package com.aesprt.aquahub.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.sync.SyncResourceType
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.coroutines.cancellation.CancellationException

class SupabaseSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val coordinator: SupabaseSyncCoordinator by inject()
    private val syncDao: SyncDao by inject()
    private val remoteOrderRefreshCoordinator: RemoteOrderRefreshCoordinator by inject()
    private val offlinePosSyncCoordinator: OfflinePosSyncCoordinator by inject()

    override suspend fun doWork(): Result = try {
        val offlineRetryable = offlinePosSyncCoordinator.syncPending()
        val summary = coordinator.sync()
        val stationRemoteId = inputData.getString(KEY_STATION_REMOTE_ID)
        val stationLocalId = stationRemoteId?.let {
            syncDao.getMetadataByRemoteId(SyncResourceType.STATION.name, it)?.localId
        }
        val orderRefresh = stationLocalId?.let { remoteOrderRefreshCoordinator.refresh(it) }
        when {
            offlineRetryable || summary.retryableFailures > 0 -> Result.retry()
            orderRefresh?.isFailure == true && orderRefresh.exceptionOrNull().isRetryableSyncFailure() -> Result.retry()
            else -> Result.success()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: NoAuthenticatedCloudSessionException) {
        Result.success()
    } catch (_: LocalAccountScopeMismatchException) {
        // A tenant transition is in progress or this install has legacy
        // unscoped data. Leave its durable rows untouched until the owner is
        // authenticated and the cache has been reconciled.
        Result.success()
    } catch (_: LegacyOutboxNotPreparedException) {
        // Application startup quarantines pre-upgrade work before scheduling sync.
        Result.success()
    } catch (error: Exception) {
        Log.e("AquaHubSync", "Supabase sync failed; WorkManager will retry", error)
        Result.retry()
    }

    companion object {
        const val KEY_STATION_REMOTE_ID = "station_remote_id"
    }
}

private fun Throwable?.isRetryableSyncFailure(): Boolean {
    if (this == null) return false
    return isTransientSyncFailure()
}
