package com.aesprt.aquahub.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.model.DashboardSummary
import com.aesprt.aquahub.domain.model.ResolvedSubscriptionState
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.model.Subscription
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.report.GetDailySalesSeriesUseCase
import com.aesprt.aquahub.domain.usecase.report.ObserveDashboardSummaryUseCase
import com.aesprt.aquahub.domain.usecase.station.ObserveStationUseCase
import com.aesprt.aquahub.domain.usecase.subscription.GetSubscriptionRemainingDaysUseCase
import com.aesprt.aquahub.domain.usecase.subscription.GetSubscriptionStatusUseCase
import com.aesprt.aquahub.domain.usecase.subscription.ObserveCurrentSubscriptionUseCase
import com.aesprt.aquahub.domain.usecase.subscription.RefreshSubscriptionStateUseCase
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.usecase.station.ObserveAllStationsUseCase
import com.aesprt.aquahub.domain.repository.RiderRepository
import com.aesprt.aquahub.sync.ConnectivityMonitor
import com.aesprt.aquahub.sync.RemoteOrderRefreshCoordinator
import com.aesprt.aquahub.sync.SupabaseSyncCoordinator
import com.aesprt.aquahub.domain.model.OwnerRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class DashboardUiState(
    val isLoading: Boolean = true,
    val station: Station? = null,
    val allStations: List<Station> = emptyList(),
    val activeStationId: Long? = null,
    val summary: DashboardSummary = DashboardSummary(),
    val salesTrend: List<Pair<String, Double>> = emptyList(),
    val subscription: Subscription? = null,
    val subscriptionState: ResolvedSubscriptionState = ResolvedSubscriptionState.ACTIVE,
    val remainingDays: Int = 0
)

class DashboardViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val riderRepository: RiderRepository,
    private val activeStationContext: ActiveStationContext,
    private val observeAllStationsUseCase: ObserveAllStationsUseCase,
    private val observeDashboardSummaryUseCase: ObserveDashboardSummaryUseCase,
    private val observeStationUseCase: ObserveStationUseCase,
    private val getDailySalesSeriesUseCase: GetDailySalesSeriesUseCase,
    private val observeCurrentSubscriptionUseCase: ObserveCurrentSubscriptionUseCase,
    private val getSubscriptionStatusUseCase: GetSubscriptionStatusUseCase,
    private val getSubscriptionRemainingDaysUseCase: GetSubscriptionRemainingDaysUseCase,
    private val refreshSubscriptionStateUseCase: RefreshSubscriptionStateUseCase,
    private val syncScheduler: com.aesprt.aquahub.sync.SyncScheduler,
    private val connectivityMonitor: ConnectivityMonitor,
    private val syncCoordinator: SupabaseSyncCoordinator,
    private val remoteOrderRefreshCoordinator: RemoteOrderRefreshCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadData()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadData() {
        viewModelScope.launch {
            observeAllStationsUseCase().collect { stations ->
                _uiState.update { it.copy(allStations = stations) }
            }
        }

        viewModelScope.launch {
            combine(
                activeStationContext.observeActiveStationId(),
                observeCurrentOwnerUseCase()
            ) { activeId, owner ->
                DashboardIdentity(activeId ?: owner?.stationId ?: 0L, owner)
            }.distinctUntilChanged().flatMapLatest { identity ->
                val owner = identity.owner
                if (owner?.role != OwnerRole.RIDER) {
                    kotlinx.coroutines.flow.flowOf(DashboardScope(identity.stationId, false, null))
                } else {
                    // A rider's local record may arrive after login when the
                    // assigned station is pulled. Observe the list so Home
                    // switches from its empty state as soon as sync finishes.
                    val normalizedOwnerPhone = normalizePhone(owner.phone)
                    riderRepository.observeRiders(identity.stationId).map { riders ->
                        DashboardScope(
                            stationId = identity.stationId,
                            isRider = true,
                            riderId = riders.firstOrNull { normalizePhone(it.phone) == normalizedOwnerPhone }?.id
                        )
                    }
                }
            }.distinctUntilChanged().collectLatest { scope ->
                val stationId = scope.stationId
                _uiState.update { it.copy(activeStationId = stationId, isLoading = true) }

                syncScheduler.enqueueImmediate()
                refreshSubscriptionStateUseCase(stationId)

                coroutineScope {
                    launch {
                        observeStationUseCase(stationId).collect { station ->
                            _uiState.update { it.copy(station = station) }
                        }
                    }

                    launch {
                        if (scope.isRider && scope.riderId == null) {
                            kotlinx.coroutines.flow.flowOf(DashboardSummary()).collect { summary ->
                                _uiState.update {
                                    it.copy(isLoading = false, summary = summary, salesTrend = emptyList())
                                }
                            }
                        } else {
                            observeDashboardSummaryUseCase(stationId, scope.riderId).collect { summary ->
                                val trend = getDailySalesSeriesUseCase(stationId, 7)
                                _uiState.update {
                                    it.copy(
                                        isLoading = false,
                                        summary = summary,
                                        salesTrend = trend
                                    )
                                }
                            }
                        }
                    }

                    launch {
                        observeCurrentSubscriptionUseCase(stationId).collect { sub ->
                            val state = getSubscriptionStatusUseCase(sub)
                            val remaining = getSubscriptionRemainingDaysUseCase(sub)
                            _uiState.update {
                                it.copy(
                                    subscription = sub,
                                    subscriptionState = state,
                                    remainingDays = remaining
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun switchStation(stationId: Long) {
        viewModelScope.launch {
            activeStationContext.setActiveStationId(stationId)
            refreshRemoteOrders(stationId)
        }
    }

    /** Refreshes server orders without holding up the Room-backed Home content. */
    fun refreshRemoteOrders(stationId: Long? = null) {
        viewModelScope.launch {
            val targetStationId = stationId ?: _uiState
                .map { it.activeStationId }
                .filterNotNull()
                .first()
            withContext(Dispatchers.IO) {
                if (!connectivityMonitor.isCurrentlyOnline()) {
                    remoteOrderRefreshCoordinator.refresh(targetStationId)
                    return@withContext
                }
                try {
                    syncCoordinator.sync()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The outbox worker will retry writes; still attempt the
                    // independent online-order pull and expose its own result.
                    syncScheduler.enqueueImmediate()
                }
                remoteOrderRefreshCoordinator.refresh(targetStationId)
            }
        }
    }
}

private data class DashboardScope(
    val stationId: Long,
    val isRider: Boolean,
    val riderId: Long?
)

private data class DashboardIdentity(
    val stationId: Long,
    val owner: com.aesprt.aquahub.domain.model.Owner?
)

private fun normalizePhone(value: String): String {
    val digits = value.filter(Char::isDigit)
    return if (digits.startsWith("0")) "63${digits.drop(1)}" else digits
}
