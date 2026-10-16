package com.aesprt.aquahub.feature.settings.delivery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.station.ObserveStationUseCase
import com.aesprt.aquahub.domain.usecase.station.UpdateStationUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

data class DeliverySettingsUiState(
    val station: Station? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null
)

class DeliverySettingsViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observeStationUseCase: ObserveStationUseCase,
    private val updateStationUseCase: UpdateStationUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeliverySettingsUiState())
    val uiState = _uiState.asStateFlow()

    init {
        observeStation()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeStation() {
        viewModelScope.launch {
            combine(
                activeStationContext.observeActiveStationId(),
                observeCurrentOwnerUseCase()
            ) { activeStationId, owner -> activeStationId ?: owner?.stationId }
                .distinctUntilChanged()
                .flatMapLatest { stationId ->
                    if (stationId == null) flowOf(null) else observeStationUseCase(stationId)
                }
                .collect { station ->
                    _uiState.update { it.copy(station = station, isLoading = false) }
                }
        }
    }

    fun saveDeliverySettings(
        isAcceptingOnlineOrders: Boolean,
        deliveryRadiusText: String,
        deliveryFeeText: String,
        preparationMinutesText: String
    ) {
        val current = _uiState.value.station ?: return
        if (_uiState.value.isSaving) return

        val radius = deliveryRadiusText.trim().ifBlank { null }?.toDoubleOrNull()
        val fee = deliveryFeeText.trim().ifBlank { "0" }.toDoubleOrNull()
        val preparationMinutes = preparationMinutesText.trim().toIntOrNull()
        val validationError = when {
            deliveryRadiusText.isNotBlank() && (radius == null || !radius.isFinite() || radius < 0.0) ->
                "Enter a valid delivery radius, or leave it blank for no radius limit"
            fee == null || !fee.isFinite() || fee < 0.0 ->
                "Enter a valid delivery fee"
            preparationMinutes == null || preparationMinutes !in 1..1440 ->
                "Preparation time must be between 1 and 1,440 minutes"
            else -> null
        }
        if (validationError != null) {
            _uiState.update { it.copy(errorMessage = validationError) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, isSaved = false, errorMessage = null) }
            val updated = current.copy(
                isAcceptingOnlineOrders = isAcceptingOnlineOrders,
                deliveryRadiusKm = radius?.takeIf { it > 0.0 },
                deliveryFeeCentavos = (fee!! * 100.0).roundToLong(),
                estimatedPreparationMinutes = preparationMinutes!!,
                updatedAt = System.currentTimeMillis()
            )
            updateStationUseCase(updated).fold(
                onSuccess = {
                    _uiState.update { it.copy(isSaving = false, isSaved = true) }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(isSaving = false, errorMessage = error.message ?: "Could not save delivery settings")
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
