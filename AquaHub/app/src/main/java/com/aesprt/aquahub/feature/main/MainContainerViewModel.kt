package com.aesprt.aquahub.feature.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.order.ObserveUncompletedOrdersCountUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.aesprt.aquahub.domain.usecase.auth.LogoutUseCase
import com.aesprt.aquahub.domain.model.OwnerRole
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class MainContainerViewModel(
    activeStationContext: ActiveStationContext,
    observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    observeUncompletedOrdersCountUseCase: ObserveUncompletedOrdersCountUseCase,
    private val logoutUseCase: LogoutUseCase
) : ViewModel() {

    val uncompletedOrdersCount: StateFlow<Int> = combine(
        activeStationContext.observeActiveStationId(),
        observeCurrentOwnerUseCase()
    ) { activeId, owner ->
        activeId ?: owner?.stationId ?: 0L
    }.distinctUntilChanged().flatMapLatest { stationId ->
        observeUncompletedOrdersCountUseCase(stationId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val role: StateFlow<OwnerRole?> = observeCurrentOwnerUseCase()
        .map { it?.role }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isStaff: StateFlow<Boolean> = role
        .map { it == OwnerRole.STAFF }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isRider: StateFlow<Boolean> = role
        .map { it == OwnerRole.RIDER }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun logout(onFinished: () -> Unit) {
        viewModelScope.launch {
            logoutUseCase()
            onFinished()
        }
    }
}
