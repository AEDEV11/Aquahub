package com.aesprt.aquahub.feature.orders.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderFilter
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.order.ObservePagedOrdersUseCase
import com.aesprt.aquahub.domain.repository.RiderRepository
import com.aesprt.aquahub.domain.model.OwnerRole
import com.aesprt.aquahub.sync.SupabaseSyncCoordinator
import com.aesprt.aquahub.sync.SyncScheduler
import com.aesprt.aquahub.sync.RemoteOrderRefreshCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.paging.PagingData
import androidx.paging.cachedIn

data class OrdersUiState(
    val selectedFilter: OrderFilter = OrderFilter.ALL,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isRider: Boolean = false,
    val riderLinked: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
class OrdersViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observePagedOrdersUseCase: ObservePagedOrdersUseCase,
    private val riderRepository: RiderRepository,
    private val syncScheduler: SyncScheduler,
    private val syncCoordinator: SupabaseSyncCoordinator,
    private val remoteOrderRefreshCoordinator: RemoteOrderRefreshCoordinator
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrdersUiState())
    val uiState = _uiState.asStateFlow()

    private val selectedFilterFlow = MutableStateFlow(OrderFilter.ALL)

    val orders: Flow<PagingData<Order>> = combine(
        activeStationContext.observeActiveStationId(),
        observeCurrentOwnerUseCase(),
        selectedFilterFlow
    ) { activeId, owner, filter ->
        Triple(activeId ?: owner?.stationId ?: 0L, owner, filter)
    }.distinctUntilChanged().flatMapLatest { (stationId, owner, filter) ->
        val isRider = owner?.role == OwnerRole.RIDER
        val rider = if (isRider) riderRepository.getRiderByPhone(owner.phone, stationId) else null
        _uiState.update {
            it.copy(
                selectedFilter = filter,
                isLoading = false,
                isRider = isRider,
                riderLinked = !isRider || rider != null
            )
        }
        refresh(isManual = false, stationId = stationId)
        if (isRider && rider == null) {
            flowOf(PagingData.empty())
        } else {
            observePagedOrdersUseCase(stationId, filter, rider?.id)
        }
    }.cachedIn(viewModelScope)

    fun selectFilter(filter: OrderFilter) {
        selectedFilterFlow.value = filter
    }

    fun refresh(isManual: Boolean = true, stationId: Long? = null) {
        viewModelScope.launch {
            if (isManual) {
                _uiState.update { it.copy(isRefreshing = true) }
            }
            try {
                withContext(Dispatchers.IO) {
                    try {
                        syncCoordinator.sync()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Keep attempting the independent read; the shared
                        // refresh state reports its outcome to the sync banner.
                        syncScheduler.enqueueImmediate()
                    }
                    val currentStationId = stationId
                        ?: activeStationContext.observeActiveStationId().firstOrNull()
                        ?: observeCurrentOwnerUseCase().firstOrNull()?.stationId
                    if (currentStationId != null && currentStationId > 0L) {
                        remoteOrderRefreshCoordinator.refresh(currentStationId)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // If refresh setup failed (e.g. offline/demo), enqueue writes for WorkManager.
                syncScheduler.enqueueImmediate()
            } finally {
                if (isManual) {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }
}
