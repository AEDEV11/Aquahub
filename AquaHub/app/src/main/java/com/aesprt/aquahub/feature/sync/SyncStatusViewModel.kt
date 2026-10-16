package com.aesprt.aquahub.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.sync.ConnectivityMonitor
import com.aesprt.aquahub.sync.RemoteOrderRefreshCoordinator
import com.aesprt.aquahub.sync.RemoteOrderRefreshState
import com.aesprt.aquahub.sync.SyncScheduler
import com.aesprt.aquahub.sync.SupabaseSyncCoordinator
import com.aesprt.aquahub.sync.SyncUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SyncStatusViewModel(
    connectivityMonitor: ConnectivityMonitor,
    private val syncDao: SyncDao,
    private val scheduler: SyncScheduler,
    private val syncCoordinator: SupabaseSyncCoordinator,
    private val activeStationContext: ActiveStationContext,
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val remoteOrderRefreshCoordinator: RemoteOrderRefreshCoordinator
) : ViewModel() {
    private val manualSyncing = MutableStateFlow(false)
    private val lastSyncError = MutableStateFlow<String?>(null)

    private val queueState = combine(
        connectivityMonitor.isOnline,
        syncDao.observeOutstandingCount(),
        syncDao.observeFailedCount(),
        syncDao.observeQuarantinedCount()
    ) { online, outstanding, failed, quarantined ->
        SyncQueueSnapshot(online, outstanding, failed, quarantined)
    }

    val state = combine(
        queueState,
        manualSyncing,
        lastSyncError,
        remoteOrderRefreshCoordinator.state
    ) { queue, syncing, error, remoteRefresh ->
        val (online, outstanding, failed, quarantined) = queue
        when {
            syncing || remoteRefresh is RemoteOrderRefreshState.Refreshing -> SyncUiState.Syncing
            error != null -> SyncUiState.Failed(failed.coerceAtLeast(1), error)
            remoteRefresh is RemoteOrderRefreshState.Failed ->
                SyncUiState.Failed(1, remoteRefresh.message)
            remoteRefresh is RemoteOrderRefreshState.Offline || !online -> SyncUiState.Offline(outstanding)
            failed > 0 -> SyncUiState.Failed(
                failed,
                if (quarantined > 0) {
                    "Earlier offline changes are saved on this device and were not uploaded. Recover the original account before resolving them."
                } else error
            )
            outstanding == 0 -> SyncUiState.Synced
            else -> SyncUiState.Pending(outstanding)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState.Synced)

    init {
        viewModelScope.launch {
            var wasOnline = connectivityMonitor.isCurrentlyOnline()
            connectivityMonitor.isOnline.collect { online ->
                if (!wasOnline && online) syncNow()
                wasOnline = online
            }
        }
    }

    fun syncNow() {
        if (manualSyncing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            manualSyncing.value = true
            lastSyncError.value = null
            try {
                syncDao.resetFailedItems()
                val summary = syncCoordinator.sync()
                if (summary.retryableFailures > 0) scheduler.enqueueImmediate()
                val stationId = activeStationContext.observeActiveStationId().firstOrNull()
                    ?: observeCurrentOwnerUseCase().firstOrNull()?.stationId
                if (stationId != null && stationId > 0L) {
                    remoteOrderRefreshCoordinator.refresh(stationId)
                } else {
                    remoteOrderRefreshCoordinator.refreshLastRequested()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastSyncError.value = syncErrorMessage(error)
                scheduler.enqueueImmediate()
            } finally {
                manualSyncing.value = false
            }
        }
    }

    private fun syncErrorMessage(error: Throwable): String =
        "Could not sync with Supabase right now. Your local changes are saved and will retry."

    private data class SyncQueueSnapshot(
        val online: Boolean,
        val outstanding: Int,
        val failed: Int,
        val quarantined: Int
    )
}
