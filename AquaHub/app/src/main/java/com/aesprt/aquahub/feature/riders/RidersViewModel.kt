package com.aesprt.aquahub.feature.riders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.Rider
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.rider.CreateRiderUseCase
import com.aesprt.aquahub.domain.usecase.rider.ObserveRidersUseCase
import com.aesprt.aquahub.domain.usecase.rider.UpdateRiderUseCase
import com.aesprt.aquahub.domain.usecase.subscription.CanCreateRiderUseCase
import com.aesprt.aquahub.domain.repository.StaffMember
import com.aesprt.aquahub.domain.repository.StaffRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class RidersUiState(
    val riders: List<Rider> = emptyList(),
    val isLoading: Boolean = true,
    val showAddDialog: Boolean = false,
    val showEditDialog: Boolean = false,
    val editingRider: Rider? = null,
    val isLoadingRiderAccounts: Boolean = false,
    val isSavingRider: Boolean = false,
    val limitReachedResult: SubscriptionAccessResult.LimitReached? = null,
    val errorMessage: String? = null,
    val availableRiderAccounts: List<StaffMember> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class RidersViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observeRidersUseCase: ObserveRidersUseCase,
    private val createRiderUseCase: CreateRiderUseCase,
    private val updateRiderUseCase: UpdateRiderUseCase,
    private val canCreateRiderUseCase: CanCreateRiderUseCase,
    private val staffRepository: StaffRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(RidersUiState())
    val uiState = _uiState.asStateFlow()

    private var currentStationId: Long = 0L
    private var riderAccountsLoadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                activeStationContext.observeActiveStationId(),
                observeCurrentOwnerUseCase()
            ) { activeId, owner ->
                activeId ?: owner?.stationId ?: 0L
            }.distinctUntilChanged().flatMapLatest { stationId ->
                currentStationId = stationId
                _uiState.update { it.copy(isLoading = true) }
                observeRidersUseCase(stationId)
            }.collect { list ->
                _uiState.update { it.copy(riders = list, isLoading = false) }
            }
        }
    }

    fun openAddDialog() {
        if (_uiState.value.isSavingRider || _uiState.value.isLoadingRiderAccounts) return
        // Show the form immediately. Subscription checks and the staff-account
        // callable can take several seconds and must never block the tap.
        _uiState.update {
            it.copy(
                showAddDialog = true,
                isLoadingRiderAccounts = true,
                availableRiderAccounts = emptyList(),
                limitReachedResult = null,
                errorMessage = null
            )
        }
        riderAccountsLoadJob?.cancel()
        riderAccountsLoadJob = viewModelScope.launch(Dispatchers.IO) {
            val accessResult = canCreateRiderUseCase(currentStationId)
            if (accessResult is SubscriptionAccessResult.LimitReached) {
                _uiState.update {
                    it.copy(
                        showAddDialog = false,
                        isLoadingRiderAccounts = false,
                        limitReachedResult = accessResult
                    )
                }
            } else if (accessResult is SubscriptionAccessResult.FeatureNotIncluded) {
                _uiState.update {
                    it.copy(
                        showAddDialog = false,
                        isLoadingRiderAccounts = false,
                        errorMessage = "Rider management requires an upgraded subscription plan."
                    )
                }
            } else if (accessResult is SubscriptionAccessResult.SubscriptionExpired) {
                _uiState.update {
                    it.copy(
                        showAddDialog = false,
                        isLoadingRiderAccounts = false,
                        errorMessage = "Subscription expired. Renew your plan to add more riders."
                    )
                }
            } else if (accessResult is SubscriptionAccessResult.SubscriptionSuspended) {
                _uiState.update {
                    it.copy(
                        showAddDialog = false,
                        isLoadingRiderAccounts = false,
                        errorMessage = "Subscription suspended"
                    )
                }
            } else {
                val linkedPhones = _uiState.value.riders.map { normalizePhone(it.phone) }.toSet()
                val staffAccounts = try {
                    staffRepository.list().members
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _uiState.update {
                        it.copy(
                            isLoadingRiderAccounts = false,
                            errorMessage = error.message?.takeIf(String::isNotBlank)
                                ?: "Could not load Rider team accounts. Check your connection and try again."
                        )
                    }
                    return@launch
                }
                if (!_uiState.value.showAddDialog) return@launch
                val accounts = staffAccounts
                    .filter { it.role.equals("RIDER", ignoreCase = true) && it.isActive }
                    .filterNot { normalizePhone(it.phone) in linkedPhones }
                if (accounts.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoadingRiderAccounts = false,
                            errorMessage = "Create an active Rider team account in Staff Management before adding a fleet rider."
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoadingRiderAccounts = false,
                            limitReachedResult = null,
                            errorMessage = null,
                            availableRiderAccounts = accounts
                        )
                    }
                }
            }
        }
    }

    fun closeAddDialog() {
        if (_uiState.value.isSavingRider) return
        riderAccountsLoadJob?.cancel()
        riderAccountsLoadJob = null
        _uiState.update {
            it.copy(
                showAddDialog = false,
                isLoadingRiderAccounts = false,
                availableRiderAccounts = emptyList(),
                limitReachedResult = null,
                errorMessage = null
            )
        }
    }

    fun openEditDialog(rider: Rider) {
        if (_uiState.value.isSavingRider) return
        _uiState.update {
            it.copy(
                showEditDialog = true,
                editingRider = rider,
                errorMessage = null
            )
        }
    }

    fun closeEditDialog() {
        if (_uiState.value.isSavingRider) return
        _uiState.update { it.copy(showEditDialog = false, editingRider = null, errorMessage = null) }
    }

    fun dismissLimitDialog() = _uiState.update { it.copy(limitReachedResult = null) }

    fun toggleAvailability(rider: Rider) {
        if (_uiState.value.isSavingRider) return
        viewModelScope.launch {
            updateRiderUseCase(rider.copy(isAvailable = !rider.isAvailable)).onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun addRider(name: String, phone: String, vehicle: String, plate: String, ratingText: String) {
        if (_uiState.value.isSavingRider) return
        val account = _uiState.value.availableRiderAccounts
            .firstOrNull { normalizePhone(it.phone) == normalizePhone(phone) }
        if (account == null) {
            _uiState.update { it.copy(errorMessage = "Select an active Rider team account first.") }
            return
        }
        val rating = parseRating(ratingText) ?: run {
            _uiState.update { it.copy(errorMessage = "Rating must be between 0 and 5") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingRider = true, errorMessage = null) }
            val accessResult = canCreateRiderUseCase(currentStationId)
            if (accessResult !is SubscriptionAccessResult.Allowed) {
                _uiState.update {
                    it.copy(
                        isSavingRider = false,
                        limitReachedResult = accessResult as? SubscriptionAccessResult.LimitReached,
                        errorMessage = if (accessResult is SubscriptionAccessResult.LimitReached) null else accessMessage(accessResult)
                    )
                }
                return@launch
            }

            val rider = Rider(
                stationId = currentStationId,
                name = account.name.trim(),
                phone = account.phone.trim(),
                vehicleType = "Motorcycle",
                vehicleName = vehicle.trim().ifBlank { null },
                plateNumber = plate.trim().ifBlank { null },
                rating = rating,
                isActive = true,
                isOnline = true,
                isAvailable = true
            )
            createRiderUseCase(rider).fold(
                onSuccess = {
                    _uiState.update { it.copy(showAddDialog = false, isSavingRider = false, errorMessage = null) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSavingRider = false, errorMessage = error.message) }
                }
            )
        }
    }

    fun updateRider(rider: Rider, name: String, phone: String, vehicle: String, plate: String, ratingText: String) {
        if (_uiState.value.isSavingRider) return
        val rating = parseRating(ratingText) ?: run {
            _uiState.update { it.copy(errorMessage = "Rating must be between 0 and 5") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingRider = true, errorMessage = null) }
            updateRiderUseCase(
                rider.copy(
                    name = name.trim(),
                    phone = phone.trim(),
                    vehicleName = vehicle.trim().ifBlank { null },
                    plateNumber = plate.trim().ifBlank { null },
                    rating = rating
                )
            ).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            showEditDialog = false,
                            editingRider = null,
                            isSavingRider = false,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSavingRider = false, errorMessage = error.message) }
                }
            )
        }
    }

    private fun parseRating(value: String): Double? =
        value.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it in 0.0..5.0 } ?:
            if (value.isBlank()) 0.0 else null

    private fun accessMessage(result: SubscriptionAccessResult): String = when (result) {
        is SubscriptionAccessResult.FeatureNotIncluded -> "Rider management requires an upgraded subscription plan."
        is SubscriptionAccessResult.SubscriptionExpired -> "Subscription expired. Renew your plan to manage riders."
        is SubscriptionAccessResult.SubscriptionSuspended -> "Subscription suspended."
        else -> "Rider management is unavailable for your subscription."
    }

    private fun normalizePhone(value: String): String {
        val digits = value.filter(Char::isDigit)
        return if (digits.startsWith("0")) "63${digits.drop(1)}" else digits
    }
}
