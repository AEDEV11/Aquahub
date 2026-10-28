package com.aesprt.aquahub.feature.settings.subscription

import androidx.lifecycle.ViewModelStore
import com.aesprt.aquahub.domain.common.TimeProvider
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.AuthRepository
import com.aesprt.aquahub.domain.repository.GooglePlayBillingRepository
import com.aesprt.aquahub.domain.repository.SubscriptionPlanRepository
import com.aesprt.aquahub.domain.repository.SubscriptionRepository
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.subscription.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionBillingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val now = 1_800_000_000_000L
    private val owner = MutableStateFlow(Owner(id = 1, stationId = 1))
    private val subscription = MutableStateFlow<Subscription?>(
        Subscription(
            stationId = 1, planId = 3, planCode = SubscriptionPlanCode.BUSINESS,
            status = SubscriptionStatus.ACTIVE, billingCycle = BillingCycle.MONTHLY,
            startedAt = now - 1_000, currentPeriodStart = now - 1_000,
            currentPeriodEnd = now + 86_400_000,
        )
    )
    private var processFailure: Exception? = null
    private var historyBarrier: CompletableDeferred<Unit>? = null
    private var refreshBarrier: CompletableDeferred<Unit>? = null
    private var refreshCalls = 0
    private var historyCalls = 0

    private val repository = object : SubscriptionRepository by unsupported() {
        override fun observeCurrentSubscription(stationId: Long) = subscription
        override suspend fun refreshFromSupabase(): Boolean {
            refreshCalls++
            refreshBarrier?.await()
            return true
        }
        override suspend fun processSubscriptionExpiration(stationId: Long): Subscription? {
            processFailure?.let { throw it }
            return subscription.value
        }
        override suspend fun getSubscriptionHistory(stationId: Long): List<SubscriptionHistoryEntry> {
            historyCalls++
            historyBarrier?.await()
            return emptyList()
        }
    }

    private fun createViewModel(): SubscriptionBillingViewModel {
        val auth = object : AuthRepository by unsupported() {
            override fun observeCurrentOwner() = owner
        }
        val plans = object : SubscriptionPlanRepository by unsupported() {
            override suspend fun getPlans() = DEFAULT_SUBSCRIPTION_PLANS
        }
        val play = object : GooglePlayBillingRepository by unsupported() {
            override val availability = MutableStateFlow(BillingAvailability.AVAILABLE)
            override val offers = MutableStateFlow(emptyList<PlayBillingOffer>())
            override val operation = MutableStateFlow<BillingOperationState>(BillingOperationState.Idle)
            override val ownedPlayProductId = MutableStateFlow<String?>(null)
            override fun connect() = Unit
        }
        val time = object : TimeProvider { override fun now() = now }
        return SubscriptionBillingViewModel(
            ObserveCurrentOwnerUseCase(auth), ObserveCurrentSubscriptionUseCase(repository),
            GetAvailablePlansUseCase(plans), GetSubscriptionStatusUseCase(time),
            GetSubscriptionRemainingDaysUseCase(time), GetSubscriptionHistoryUseCase(repository),
            RefreshSubscriptionStateUseCase(repository), play,
        ).also { store.put("billing", it) }
    }

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun `return refresh failure preserves displayed subscription and allows retry`() = runTest(dispatcher) {
        val vm = createViewModel()
        runCurrent()
        val displayed = vm.uiState.value.currentSubscription
        processFailure = IllegalStateException("Simulated local refresh failure")

        vm.refreshAfterPlayReturn()
        runCurrent()

        assertFalse(vm.uiState.value.isActionLoading)
        assertEquals(displayed, vm.uiState.value.currentSubscription)
        assertNotNull(vm.uiState.value.errorMessage)
        processFailure = null
        vm.refreshAfterPlayReturn()
        runCurrent()
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun `repeated resume events share the in flight refresh`() = runTest(dispatcher) {
        val vm = createViewModel()
        runCurrent()
        val before = refreshCalls
        refreshBarrier = CompletableDeferred()
        vm.refreshAfterPlayReturn()
        vm.refreshAfterPlayReturn()
        runCurrent()
        assertEquals(before + 1, refreshCalls)
        assertTrue(vm.uiState.value.isActionLoading)
        refreshBarrier!!.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.isActionLoading)
    }

    @Test fun `Play cancellation updates access while history refresh is still running`() = runTest(dispatcher) {
        val vm = createViewModel()
        runCurrent()
        historyBarrier = CompletableDeferred()
        vm.refreshAfterPlayReturn()
        runCurrent()
        val requestsBeforeChange = historyCalls
        subscription.value = subscription.value!!.copy(
            status = SubscriptionStatus.ACTIVE_UNTIL_PERIOD_END,
            autoRenew = false, cancelAtPeriodEnd = true,
        )
        runCurrent()

        assertEquals(ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END, vm.uiState.value.resolvedState)
        assertTrue(vm.uiState.value.currentSubscription!!.cancelAtPeriodEnd)
        assertEquals(requestsBeforeChange, historyCalls)
        historyBarrier!!.complete(Unit)
        runCurrent()
        assertFalse(vm.uiState.value.isActionLoading)
        assertNull(vm.uiState.value.errorMessage)
    }

    @Test fun `initial refresh failure is shown as a recoverable screen error`() = runTest(dispatcher) {
        processFailure = IllegalStateException("Simulated initial refresh failure")
        val vm = createViewModel()
        runCurrent()
        assertFalse(vm.uiState.value.isLoading)
        assertNotNull(vm.uiState.value.errorMessage)
    }

    @Test fun `return refresh finishing after a station change clears the loading indicator`() = runTest(dispatcher) {
        val vm = createViewModel()
        runCurrent()
        historyBarrier = CompletableDeferred()
        vm.refreshAfterPlayReturn()
        runCurrent()
        assertTrue(vm.uiState.value.isActionLoading)

        owner.value = owner.value.copy(stationId = 2)
        runCurrent()
        historyBarrier!!.complete(Unit)
        runCurrent()

        assertEquals(2L, vm.uiState.value.stationId)
        assertFalse(vm.uiState.value.isActionLoading)
    }

    private inline fun <reified T> unsupported(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected repository call: ${method.name}") } as T
}
