package com.aesprt.aquahub.feature.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.usecase.subscription.GetCurrentBusinessSubscriptionUseCase
import com.aesprt.aquahub.domain.usecase.subscription.GetPlanEntitlementsUseCase
import com.aesprt.aquahub.domain.repository.StaffMember
import com.aesprt.aquahub.domain.repository.StaffRepository
import com.aesprt.aquahub.domain.repository.RiderRepository
import com.aesprt.aquahub.domain.repository.StationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class StaffStationOption(
    val remoteId: String,
    val station: com.aesprt.aquahub.domain.model.Station
)

data class StaffManagementUiState(
    val staff: List<StaffMember> = emptyList(),
    val stations: List<StaffStationOption> = emptyList(),
    val stationsPendingSync: Boolean = false,
    val staffLimit: Int? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class StaffManagementViewModel(
    private val staffRepository: StaffRepository,
    private val getCurrentBusinessSubscription: GetCurrentBusinessSubscriptionUseCase,
    private val getPlanEntitlements: GetPlanEntitlementsUseCase,
    private val riderRepository: RiderRepository,
    private val stationRepository: StationRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(StaffManagementUiState())
    val uiState = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                coroutineScope {
                    val directoryDeferred = async { staffRepository.list() }
                    val localStationsDeferred = async { stationRepository.getAllStations() }
                    val localLimitDeferred = async {
                        getCurrentBusinessSubscription()?.let { subscription ->
                            getPlanEntitlements(subscription.planId)
                                .firstOrNull { it.feature == SubscriptionFeature.STAFF_ACCOUNTS }
                                ?.limit
                                ?.toInt()
                        }
                    }
                    val directory = directoryDeferred.await()
                    val localStations = localStationsDeferred.await()
                    val stations = localStations.mapNotNull { station ->
                        stationRepository.getRemoteStationId(station.id)?.let { remoteId ->
                            StaffStationOption(remoteId = remoteId, station = station)
                        }
                    }
                    val localLimit = localLimitDeferred.await()
                StaffLoadSnapshot(
                    directory = directory,
                    stations = stations,
                    stationsPendingSync = localStations.isNotEmpty() && stations.isEmpty(),
                    limit = directory.staffLimit ?: localLimit
                )
            }
                }
                .onSuccess { snapshot ->
                    _uiState.update {
                        it.copy(
                            staff = snapshot.directory.members,
                            stations = snapshot.stations,
                            stationsPendingSync = snapshot.stationsPendingSync,
                            staffLimit = snapshot.limit,
                            isLoading = false
                        )
                    }
                }
                .onFailure { error -> _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Could not load staff accounts") } }
        }
    }

    fun create(name: String, phone: String, role: String, stationIds: List<String>) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, successMessage = null) }
            runCatching { staffRepository.create(name.trim(), phone.trim(), role, stationIds) }
                .onSuccess { created ->
                    _uiState.update { it.copy(staff = it.staff + created, isSaving = false, successMessage = "${created.name} can now sign in with this phone number and a one-time SMS code.") }
                }
                .onFailure { error -> _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "Could not create staff account") } }
        }
    }

    fun update(member: StaffMember, name: String, phone: String, role: String, stationIds: List<String>) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, successMessage = null) }
            runCatching { staffRepository.update(member.uid, name.trim(), phone.trim(), role, stationIds) }
                .onSuccess { updated ->
                    _uiState.update { state ->
                        state.copy(
                            staff = state.staff.map { if (it.uid == updated.uid) updated else it },
                            isSaving = false,
                            successMessage = "${updated.name}'s account was updated."
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "Could not update staff account") }
                }
        }
    }

    fun delete(member: StaffMember) {
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null, successMessage = null) }
            runCatching { staffRepository.delete(member.uid) }
                .onSuccess {
                    if (member.role.equals("RIDER", ignoreCase = true)) {
                        riderRepository.getRiderByPhone(member.phone)?.let { rider ->
                            riderRepository.updateRider(
                                rider.copy(isActive = false, isAvailable = false, isOnline = false, activeOrderCount = 0)
                            )
                        }
                    }
                    _uiState.update { state ->
                        state.copy(
                            staff = state.staff.filterNot { it.uid == member.uid },
                            isSaving = false,
                            successMessage = "${member.name}'s account was deactivated."
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "Could not remove staff account") }
                }
        }
    }

    fun clearMessage() { _uiState.update { it.copy(errorMessage = null, successMessage = null) } }
}

private data class StaffLoadSnapshot(
    val directory: com.aesprt.aquahub.domain.repository.StaffDirectory,
    val stations: List<StaffStationOption>,
    val stationsPendingSync: Boolean,
    val limit: Int?
)
