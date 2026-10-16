package com.aesprt.aquahub.sync

import com.aesprt.aquahub.domain.repository.RemoteOrderReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RemoteOrderRefreshState {
    data object Idle : RemoteOrderRefreshState
    data object Refreshing : RemoteOrderRefreshState
    data object UpToDate : RemoteOrderRefreshState
    data object Offline : RemoteOrderRefreshState
    data class Failed(val message: String) : RemoteOrderRefreshState
}

/** Serializes remote order pulls and exposes their result to the app-wide sync indicator. */
class RemoteOrderRefreshCoordinator(
    private val remoteOrderReader: RemoteOrderReader,
    private val connectivityMonitor: ConnectivityMonitor
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<RemoteOrderRefreshState>(RemoteOrderRefreshState.Idle)
    @Volatile
    private var lastStationId: Long? = null

    val state: StateFlow<RemoteOrderRefreshState> = _state.asStateFlow()

    suspend fun refresh(stationId: Long): Result<Unit> = mutex.withLock {
        lastStationId = stationId
        if (!connectivityMonitor.isCurrentlyOnline()) {
            _state.value = RemoteOrderRefreshState.Offline
            return@withLock Result.failure(RemoteOrderRefreshOfflineException())
        }

        _state.value = RemoteOrderRefreshState.Refreshing
        val result = try {
            remoteOrderReader.refreshDeliveryReads(stationId)
        } catch (cancelled: CancellationException) {
            _state.value = RemoteOrderRefreshState.Idle
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

        result.fold(
            onSuccess = {
                _state.value = RemoteOrderRefreshState.UpToDate
                Result.success(Unit)
            },
            onFailure = { error ->
                _state.value = RemoteOrderRefreshState.Failed(
                    "Online orders couldn't refresh. Showing saved data; tap to retry."
                )
                Result.failure(error)
            }
        )
    }

    suspend fun refreshLastRequested(): Result<Unit>? =
        lastStationId?.let { refresh(it) }
}

class RemoteOrderRefreshOfflineException : IllegalStateException("No validated internet connection")
