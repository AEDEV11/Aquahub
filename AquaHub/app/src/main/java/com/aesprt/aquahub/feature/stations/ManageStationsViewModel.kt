package com.aesprt.aquahub.feature.stations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.OrderRepository
import com.aesprt.aquahub.domain.repository.RiderRepository
import com.aesprt.aquahub.domain.repository.StationRepository
import com.aesprt.aquahub.domain.usecase.station.CreateNewStationUseCase
import com.aesprt.aquahub.domain.usecase.subscription.GetCurrentBusinessSubscriptionUseCase
import com.aesprt.aquahub.domain.usecase.subscription.GetSubscriptionStatusUseCase
import com.aesprt.aquahub.domain.repository.SubscriptionPlanRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

data class StationWithStats(
    val station: Station,
    val isActiveContext: Boolean,
    val isLockedBySubscriptionLimit: Boolean = false,
    val todayOrdersCount: Int = 0,
    val todaySales: Double = 0.0,
    val activeRidersCount: Int = 0
)

data class ManageStationsUiState(
    val stations: List<StationWithStats> = emptyList(),
    val activeStationId: Long? = null,
    val stationCount: Long = 1,
    val maxStationLimit: Long = 1,
    val availableStationSlots: Long = 0,
    val subscriptionPlanName: String = "No active plan",
    val isLimitReached: Boolean = false,
    val showAddDialog: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isLoading: Boolean = true
)

class ManageStationsViewModel(
    private val stationRepository: StationRepository,
    private val activeStationContext: ActiveStationContext,
    private val createNewStationUseCase: CreateNewStationUseCase,
    private val getCurrentBusinessSubscriptionUseCase: GetCurrentBusinessSubscriptionUseCase,
    private val getSubscriptionStatusUseCase: GetSubscriptionStatusUseCase,
    private val subscriptionPlanRepository: SubscriptionPlanRepository,
    private val orderRepository: OrderRepository,
    private val riderRepository: RiderRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ManageStationsUiState())
    val uiState: StateFlow<ManageStationsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                stationRepository.observeAllStations(),
                activeStationContext.observeActiveStationId()
            ) { allStations, activeId ->
                val targetActiveId = activeId ?: allStations.firstOrNull()?.id

                val startOfDay = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val endOfDay = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis

                // Subscription is business-scoped in Supabase. Do not resolve it from the active station's
                // local replica while context is switching.
                val subscription = getCurrentBusinessSubscriptionUseCase()
                val plan = subscription?.let { subscriptionPlanRepository.getPlan(it.planId) }
                val maxLimit = plan?.limits
                    ?.firstOrNull { it.limitType == SubscriptionLimit.STATION_COUNT }
                    ?.maxValue ?: 1L
                val subscriptionState = getSubscriptionStatusUseCase(subscription)
                val subscriptionActive = subscriptionState in setOf(
                    ResolvedSubscriptionState.TRIAL_ACTIVE,
                    ResolvedSubscriptionState.ACTIVE,
                    ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END,
                    ResolvedSubscriptionState.GRACE_PERIOD
                )
                val limitReached = !subscriptionActive || allStations.size.toLong() >= maxLimit

                val sortedStations = allStations.sortedWith(compareBy<Station> { it.createdAt }.thenBy { it.id })
                val stationsWithStats = sortedStations.mapIndexed { index, station ->
                    val orders = orderRepository.observeTodayOrders(station.id, startOfDay, endOfDay).firstOrNull() ?: emptyList()
                    val completedOrders = orders.filter { it.orderStatus == OrderStatus.COMPLETED || it.orderStatus == OrderStatus.DELIVERED }
                    val sales = completedOrders.sumOf { it.total }
                    val riders = riderRepository.observeRiders(station.id).firstOrNull() ?: emptyList()
                    val isLocked = !subscriptionActive || index.toLong() >= maxLimit

                    StationWithStats(
                        station = station,
                        isActiveContext = station.id == targetActiveId,
                        isLockedBySubscriptionLimit = isLocked,
                        todayOrdersCount = orders.size,
                        todaySales = sales,
                        activeRidersCount = riders.count { it.isActive }
                    )
                }

                _uiState.update {
                    it.copy(
                        stations = stationsWithStats,
                        activeStationId = targetActiveId,
                        stationCount = allStations.size.toLong(),
                        maxStationLimit = maxLimit,
                        availableStationSlots = (maxLimit - allStations.size.toLong()).coerceAtLeast(0),
                        subscriptionPlanName = plan?.name ?: "No active plan",
                        isLimitReached = limitReached,
                        isLoading = false
                    )
                }
            }.collect()
        }
    }

    fun switchActiveStation(stationId: Long) {
        val targetStation = _uiState.value.stations.find { it.station.id == stationId }
        if (targetStation?.isLockedBySubscriptionLimit == true) {
            _uiState.update {
                it.copy(
                    errorMessage = "This station exceeds your current plan's slot limit (${_uiState.value.maxStationLimit}). Upgrade your subscription to unlock it."
                )
            }
            return
        }
        viewModelScope.launch {
            activeStationContext.setActiveStationId(stationId)
            _uiState.update { it.copy(activeStationId = stationId, successMessage = "Switched active station context") }
        }
    }

    fun openAddDialog() {
        if (_uiState.value.isLimitReached) {
            val state = _uiState.value
            val message = if (state.subscriptionPlanName == "No active plan") {
                "An active subscription is required to add a station."
            } else {
                "${state.subscriptionPlanName} allows ${state.maxStationLimit} station${if (state.maxStationLimit == 1L) "" else "s"}. Upgrade your plan to add more."
            }
            _uiState.update { it.copy(errorMessage = message) }
            return
        }
        _uiState.update { it.copy(showAddDialog = true, errorMessage = null) }
    }

    fun closeAddDialog() {
        _uiState.update { it.copy(showAddDialog = false, errorMessage = null) }
    }

    fun createStation(
        name: String,
        phone: String,
        address: String,
        city: String,
        province: String,
        openingTime: String,
        closingTime: String,
        latitude: Double? = null,
        longitude: Double? = null
    ) {
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Station name is required") }
            return
        }
        if (address.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Station address is required") }
            return
        }
        if (!com.aesprt.aquahub.core.common.DateTimeUtils.isClosingAfterOpening(openingTime, closingTime)) {
            _uiState.update { it.copy(errorMessage = "Closing time must be later than opening time") }
            return
        }

        val activeId = _uiState.value.activeStationId ?: return
        if (_uiState.value.isLimitReached) {
            _uiState.update { it.copy(errorMessage = "Your current subscription does not allow another station.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val newStation = Station(
                name = name.trim(),
                phone = phone.trim(),
                address = address.trim(),
                city = city.trim(),
                province = province.trim(),
                latitude = latitude,
                longitude = longitude,
                openingTime = openingTime,
                closingTime = closingTime,
                isOpen = true
            )

            val result = createNewStationUseCase(activeId, newStation)
            result.fold(
                onSuccess = { createdId ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            showAddDialog = false,
                            successMessage = "New station branch added successfully!"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = error.message ?: "Failed to add station"
                        )
                    }
                }
            )
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
