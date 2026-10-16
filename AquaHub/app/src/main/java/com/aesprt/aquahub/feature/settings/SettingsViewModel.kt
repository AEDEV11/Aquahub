package com.aesprt.aquahub.feature.settings

import androidx.lifecycle.ViewModel
import com.aesprt.aquahub.domain.context.ActiveStationContext
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.usecase.auth.DeleteAccountUseCase
import com.aesprt.aquahub.domain.usecase.auth.LogoutUseCase
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.auth.ReauthenticateUseCase
import com.aesprt.aquahub.domain.usecase.auth.ReauthenticateWithGoogleUseCase
import com.aesprt.aquahub.domain.usecase.station.ObserveStationUseCase
import com.aesprt.aquahub.data.supabase.auth.SupabaseAuthDataSource
import com.aesprt.aquahub.data.supabase.auth.ReauthenticationIdentityMismatchException
import com.aesprt.aquahub.data.supabase.marketing.SupabaseMarketingPreferencesDataSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val station: Station? = null,
    val isLoading: Boolean = true,
    val deletingAccount: Boolean = false,
    val deletionError: String? = null,
    val requiresReauthentication: Boolean = false,
    val reauthenticating: Boolean = false,
    val reauthenticationError: String? = null,
    val emailAddress: String? = null,
    val emailVerified: Boolean = false,
    val emailVerificationVisible: Boolean = false,
    val sendingEmailVerification: Boolean = false,
    val emailVerificationMessage: String? = null,
    val marketingPreferenceLoading: Boolean = true,
    val marketingPreferenceSaving: Boolean = false,
    val marketingSubscribed: Boolean = false,
    val marketingEmailAvailable: Boolean = false,
    val marketingPreferenceMessage: String? = null
)

internal fun shouldShowEmailVerification(providerType: String?, emailAddress: String?): Boolean =
    providerType.equals("email", ignoreCase = true) && !emailAddress.isNullOrBlank()

sealed interface SettingsEvent {
    data object LogoutSuccess : SettingsEvent
    data object AccountDeleted : SettingsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observeStationUseCase: ObserveStationUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val deleteAccountUseCase: DeleteAccountUseCase,
    private val reauthenticateUseCase: ReauthenticateUseCase,
    private val reauthenticateWithGoogleUseCase: ReauthenticateWithGoogleUseCase,
    private val authDataSource: SupabaseAuthDataSource,
    private val marketingPreferencesDataSource: SupabaseMarketingPreferencesDataSource
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events = _events.asSharedFlow()

    init {
        loadStation()
        loadEmailVerificationState()
        loadMarketingPreference()
    }

    private fun loadMarketingPreference() {
        viewModelScope.launch {
            try {
                val preference = marketingPreferencesDataSource.getMyPreference()
                _uiState.update {
                    it.copy(
                        marketingPreferenceLoading = false,
                        marketingSubscribed = preference.subscribed,
                        marketingEmailAvailable = preference.email_available,
                        marketingPreferenceMessage = if (preference.suppressed) {
                            "This address is suppressed by the email provider and will not receive promotions."
                        } else null
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        marketingPreferenceLoading = false,
                        marketingPreferenceMessage = "We couldn't load your email preference. Check your connection and try again."
                    )
                }
            }
        }
    }

    fun retryLoadingMarketingPreference() {
        val state = _uiState.value
        if (state.marketingPreferenceLoading || state.marketingPreferenceSaving) return
        _uiState.update { it.copy(marketingPreferenceLoading = true, marketingPreferenceMessage = null) }
        loadMarketingPreference()
    }

    fun setMarketingPreference(subscribed: Boolean) {
        val state = _uiState.value
        if (state.marketingPreferenceLoading || state.marketingPreferenceSaving || !state.marketingEmailAvailable) return
        viewModelScope.launch {
            _uiState.update { it.copy(marketingPreferenceSaving = true, marketingPreferenceMessage = null) }
            try {
                val preference = marketingPreferencesDataSource.setMyPreference(subscribed)
                _uiState.update {
                    it.copy(
                        marketingPreferenceSaving = false,
                        marketingSubscribed = preference.subscribed,
                        marketingEmailAvailable = preference.email_available,
                        marketingPreferenceMessage = when {
                            preference.suppressed -> "This address is suppressed by the email provider and will not receive promotions."
                            preference.subscribed -> "Your marketing preference is saved. You can change it here at any time."
                            else -> "Your preference is saved. Account and service messages are unchanged."
                        }
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        marketingPreferenceSaving = false,
                        marketingPreferenceMessage = "We couldn't save your email preference. Please try again."
                    )
                }
            }
        }
    }

    private fun loadEmailVerificationState() {
        val emailAddress = authDataSource.currentUserEmail
        val providerType = authDataSource.currentUserProvider
        _uiState.update {
            it.copy(
                emailAddress = emailAddress,
                emailVerified = authDataSource.currentUserEmailVerified,
                emailVerificationVisible = shouldShowEmailVerification(providerType, emailAddress)
            )
        }
    }

    fun resendEmailVerification() {
        val state = _uiState.value
        if (!state.emailVerificationVisible || state.sendingEmailVerification || state.emailVerified) return
        viewModelScope.launch {
            _uiState.update { it.copy(sendingEmailVerification = true, emailVerificationMessage = null) }
            try {
                authDataSource.resendEmailVerification()
                _uiState.update {
                    it.copy(
                        sendingEmailVerification = false,
                        emailVerificationMessage = "Verification link sent. Check your inbox and spam folder."
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        sendingEmailVerification = false,
                        emailVerificationMessage = "We couldn't send the verification link. Check your connection and try again."
                    )
                }
            }
        }
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
                    _uiState.update { it.copy(station = station, isLoading = false) }
                }
        }
    }

    fun deleteAccount() {
        if (_uiState.value.deletingAccount) return
        viewModelScope.launch {
            _uiState.update { it.copy(deletingAccount = true, deletionError = null) }
            deleteAccountUseCase().fold(
                onSuccess = {
                    _uiState.update { it.copy(deletingAccount = false) }
                    _events.emit(SettingsEvent.AccountDeleted)
                },
                onFailure = { error ->
                    _uiState.update {
                        if (isRecentLoginRequired(error)) it.copy(deletingAccount = false, requiresReauthentication = true, reauthenticationError = null)
                        else it.copy(deletingAccount = false, deletionError = mapDeletionError(error))
                    }
                }
            )
        }
    }

    fun reauthenticate(password: String) {
        if (_uiState.value.reauthenticating) return
        viewModelScope.launch {
            _uiState.update { it.copy(reauthenticating = true, reauthenticationError = null) }
            reauthenticateUseCase(password).fold(
                onSuccess = {
                    _uiState.update { it.copy(reauthenticating = false, requiresReauthentication = false) }
                    deleteAccount()
                },
                onFailure = { error ->
                    _uiState.update { it.copy(reauthenticating = false, reauthenticationError = "We could not verify your password. Please try again.") }
                }
            )
        }
    }

    fun reauthenticateWithGoogle(idToken: String) {
        if (_uiState.value.reauthenticating) return
        viewModelScope.launch {
            _uiState.update { it.copy(reauthenticating = true, reauthenticationError = null) }
            reauthenticateWithGoogleUseCase(idToken).fold(
                onSuccess = {
                    _uiState.update { it.copy(reauthenticating = false, requiresReauthentication = false) }
                    deleteAccount()
                },
                onFailure = { error ->
                    val message = if (error is ReauthenticationIdentityMismatchException) {
                        "That Google account does not match this AquaHub account. No account was deleted. Sign in to the original account and try again."
                    } else {
                        "We could not verify your Google account. Please try again."
                    }
                    _uiState.update { it.copy(reauthenticating = false, reauthenticationError = message) }
                }
            )
        }
    }

    fun dismissReauthentication() {
        if (!_uiState.value.reauthenticating) _uiState.update { it.copy(requiresReauthentication = false, reauthenticationError = null) }
    }

    fun dismissDeletionError() {
        _uiState.update { it.copy(deletionError = null) }
    }

    private fun isRecentLoginRequired(error: Throwable): Boolean =
        error.message?.contains("recent", ignoreCase = true) == true ||
            error.message?.contains("requires-recent-login", ignoreCase = true) == true

    private fun mapDeletionError(error: Throwable): String = when {
        error.message?.contains("recent", ignoreCase = true) == true -> "For your security, please sign in again before deleting your account."
        error.message?.contains("network", ignoreCase = true) == true -> "Check your internet connection and try again."
        else -> "We could not delete your account. Please try again."
    }

    fun logout() {
        viewModelScope.launch {
            logoutUseCase()
            _events.emit(SettingsEvent.LogoutSuccess)
        }
    }
}
