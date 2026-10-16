package com.aesprt.aquahub.sync

import com.aesprt.aquahub.domain.repository.RemoteOrderReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteOrderRefreshCoordinatorTest {
    @Test
    fun `refresh pulls requested station and records success`() = runTest {
        var refreshedStationId: Long? = null
        val coordinator = RemoteOrderRefreshCoordinator(
            remoteOrderReader = RemoteOrderReader { stationId ->
                refreshedStationId = stationId
                Result.success(Unit)
            },
            connectivityMonitor = FakeConnectivityMonitor(online = true)
        )

        val result = coordinator.refresh(stationId = 42L)

        assertTrue(result.isSuccess)
        assertEquals(42L, refreshedStationId)
        assertEquals(RemoteOrderRefreshState.UpToDate, coordinator.state.value)
    }

    @Test
    fun `failed refresh is visible and a later successful refresh clears it`() = runTest {
        var shouldFail = true
        var attempts = 0
        val coordinator = RemoteOrderRefreshCoordinator(
            remoteOrderReader = RemoteOrderReader {
                attempts++
                if (shouldFail) Result.failure(IllegalStateException("remote unavailable"))
                else Result.success(Unit)
            },
            connectivityMonitor = FakeConnectivityMonitor(online = true)
        )

        assertTrue(coordinator.refresh(42L).isFailure)
        assertTrue(coordinator.state.value is RemoteOrderRefreshState.Failed)

        shouldFail = false
        assertTrue(coordinator.refresh(42L).isSuccess)
        assertEquals(2, attempts)
        assertEquals(RemoteOrderRefreshState.UpToDate, coordinator.state.value)
    }

    @Test
    fun `offline refresh leaves remote untouched and reports offline`() = runTest {
        var called = false
        val coordinator = RemoteOrderRefreshCoordinator(
            remoteOrderReader = RemoteOrderReader {
                called = true
                Result.success(Unit)
            },
            connectivityMonitor = FakeConnectivityMonitor(online = false)
        )

        val result = coordinator.refresh(42L)

        assertFalse(result.isSuccess)
        assertFalse(called)
        assertEquals(RemoteOrderRefreshState.Offline, coordinator.state.value)
    }

    private class FakeConnectivityMonitor(online: Boolean) : ConnectivityMonitor {
        private val onlineState = MutableStateFlow(online)

        override val isOnline = onlineState
        override fun isCurrentlyOnline(): Boolean = onlineState.value
    }
}
