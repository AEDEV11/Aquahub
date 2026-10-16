package com.aesprt.aquahub.feature.settings.profile

import androidx.lifecycle.ViewModel
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.core.common.DateTimeUtils
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.model.CustomerOrderingLink
import com.aesprt.aquahub.domain.repository.ImageStorageRepository
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.station.GetStationUseCase
import com.aesprt.aquahub.domain.usecase.station.ObserveStationUseCase
import com.aesprt.aquahub.domain.usecase.station.UpdateStationUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

data class StationProfileUiState(
    val station: Station? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class StationProfileViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observeStationUseCase: ObserveStationUseCase,
    private val updateStationUseCase: UpdateStationUseCase,
    private val imageStorageRepository: ImageStorageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StationProfileUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadStation()
        startClosingTimeMonitor()
    }

    private fun loadStation() {
        viewModelScope.launch {
            combine(activeStationContext.observeActiveStationId(), observeCurrentOwnerUseCase()) { activeId, owner ->
                activeId ?: owner?.stationId
            }.distinctUntilChanged()
                .flatMapLatest { stationId ->
                    if (stationId == null) flowOf(null) else observeStationUseCase(stationId)
                }
                .collect { station ->
                    val effectiveStation = autoCloseIfNeeded(station)
                    _uiState.update { it.copy(station = effectiveStation, isLoading = false) }
                }
        }
    }

    private suspend fun autoCloseIfNeeded(station: Station?): Station? {
        if (station?.isOpen != true || station.manualOpenOverride || !DateTimeUtils.isAtOrAfterClosingTime(station.closingTime)) return station
        val closed = station.copy(isOpen = false, updatedAt = System.currentTimeMillis())
        updateStationUseCase(closed)
        return closed
    }

    private fun startClosingTimeMonitor() {
        viewModelScope.launch {
            while (isActive) {
                autoCloseIfNeeded(_uiState.value.station)
                delay(30_000L)
            }
        }
    }

    fun ensurePublicOrderingCode() {
        val current = _uiState.value.station ?: return
        if (!current.publicStationCode.isNullOrBlank()) return
        viewModelScope.launch {
            updateStationUseCase(current.copy(publicStationCode = CustomerOrderingLink.newPublicStationCode())).fold(
                onSuccess = { _uiState.update { it.copy(isSaved = true) } },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } },
            )
        }
    }

    fun regeneratePublicOrderingCode() {
        val current = _uiState.value.station ?: return
        viewModelScope.launch {
            updateStationUseCase(current.copy(publicStationCode = CustomerOrderingLink.newPublicStationCode())).fold(
                onSuccess = { _uiState.update { it.copy(isSaved = true) } },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } },
            )
        }
    }

    fun updatePaymentMethods(
        cashEnabled: Boolean,
        codEnabled: Boolean,
        gcashEnabled: Boolean,
        gcashName: String,
        gcashNumber: String,
        gcashQrSourceUri: String?,
        mayaEnabled: Boolean,
        mayaName: String,
        mayaNumber: String,
        mayaQrSourceUri: String?
    ) {
        val current = _uiState.value.station ?: return
        val normalizedGcashNumber = gcashNumber.filter(Char::isDigit)
        val normalizedMayaNumber = mayaNumber.filter(Char::isDigit)
        val error = when {
            !cashEnabled && !gcashEnabled && !mayaEnabled -> "Enable at least one payment method for pickup sales"
            !codEnabled && !gcashEnabled && !mayaEnabled -> "Enable COD, GCash, or Maya for delivery sales"
            gcashEnabled && gcashName.isBlank() -> "Enter the GCash account name"
            gcashEnabled && normalizedGcashNumber.length !in 10..13 -> "Enter a valid GCash mobile number"
            mayaEnabled && mayaName.isBlank() -> "Enter the Maya account name"
            mayaEnabled && normalizedMayaNumber.length !in 10..13 -> "Enter a valid Maya mobile number"
            else -> null
        }
        if (error != null) {
            _uiState.update { it.copy(errorMessage = error, isSaved = false) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val uploadedGcashQr = gcashQrSourceUri?.let { uri ->
                imageStorageRepository.uploadPaymentQr(current.id, "gcash", uri).getOrElse { error ->
                    _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "GCash QR upload failed") }
                    return@launch
                }
            }
            val uploadedMayaQr = mayaQrSourceUri?.let { uri ->
                imageStorageRepository.uploadPaymentQr(current.id, "maya", uri).getOrElse { error ->
                    uploadedGcashQr?.let { imageStorageRepository.deleteOwnedImage(it) }
                    _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "Maya QR upload failed") }
                    return@launch
                }
            }
            updateStationUseCase(
                current.copy(
                    cashPaymentEnabled = cashEnabled,
                    codPaymentEnabled = codEnabled,
                    gcashPaymentEnabled = gcashEnabled,
                    gcashAccountName = gcashName.trim().takeIf { gcashEnabled },
                    gcashAccountNumber = normalizedGcashNumber.takeIf { gcashEnabled },
                    gcashQrImagePath = (uploadedGcashQr ?: current.gcashQrImagePath).takeIf { gcashEnabled },
                    mayaPaymentEnabled = mayaEnabled,
                    mayaAccountName = mayaName.trim().takeIf { mayaEnabled },
                    mayaAccountNumber = normalizedMayaNumber.takeIf { mayaEnabled },
                    mayaQrImagePath = (uploadedMayaQr ?: current.mayaQrImagePath).takeIf { mayaEnabled }
                )
            ).fold(
                onSuccess = { _uiState.update { it.copy(isSaved = true, isSaving = false, errorMessage = null) } },
                onFailure = { error ->
                    uploadedGcashQr?.let { imageStorageRepository.deleteOwnedImage(it) }
                    uploadedMayaQr?.let { imageStorageRepository.deleteOwnedImage(it) }
                    _uiState.update { it.copy(isSaved = false, isSaving = false, errorMessage = error.message) }
                }
            )
        }
    }

    fun clearStatus() = _uiState.update { it.copy(isSaved = false, errorMessage = null) }

    fun updateStation(
        name: String,
        phone: String,
        address: String,
        openingTime: String,
        closingTime: String,
        isOpen: Boolean,
        latitude: Double? = null,
        longitude: Double? = null,
        businessRulesText: String = "",
        logoSourceUri: String? = null
    ) {
        val current = _uiState.value.station ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val uploadedLogoUrl = logoSourceUri?.let { uri ->
                imageStorageRepository.uploadStationLogo(current.id, uri).fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(isSaving = false, errorMessage = error.message ?: "Station image upload failed")
                        }
                        return@launch
                    }
                )
            }
            val normalizedRules = businessRulesText.trim().take(12000).ifBlank { null }
            val statusChanged = isOpen != current.isOpen
            val scheduleChanged = openingTime != current.openingTime || closingTime != current.closingTime
            val rulesVersion = if (normalizedRules != current.businessRulesText) {
                current.businessRulesVersion + 1L
            } else {
                current.businessRulesVersion
            }
            val updated = current.copy(
                name = name,
                phone = phone,
                address = address,
                openingTime = openingTime,
                closingTime = closingTime,
                isOpen = isOpen,
                manualOpenOverride = when {
                    statusChanged -> true
                    scheduleChanged -> false
                    else -> current.manualOpenOverride
                },
                latitude = latitude ?: current.latitude,
                longitude = longitude ?: current.longitude,
                logoPath = uploadedLogoUrl ?: current.logoPath,
                businessRulesText = normalizedRules,
                businessRulesVersion = rulesVersion,
                updatedAt = System.currentTimeMillis()
            )
            updateStationUseCase(updated).fold(
                onSuccess = { _uiState.update { it.copy(isSaved = true, isSaving = false) } },
                onFailure = { error ->
                    uploadedLogoUrl?.let { imageStorageRepository.deleteOwnedImage(it) }
                    _uiState.update { it.copy(isSaving = false, errorMessage = error.message) }
                }
            )
        }
    }
}
