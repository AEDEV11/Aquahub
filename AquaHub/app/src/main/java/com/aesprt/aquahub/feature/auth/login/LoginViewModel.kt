package com.aesprt.aquahub.feature.auth.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.usecase.auth.LoginUseCase
import com.aesprt.aquahub.domain.usecase.auth.LoginWithGoogleUseCase
import com.aesprt.aquahub.domain.usecase.auth.ResetPasswordUseCase
import com.aesprt.aquahub.domain.repository.GoogleAccountNeedsSetupException
import com.aesprt.aquahub.domain.repository.AuthRepository
import com.aesprt.aquahub.domain.repository.LocalAccountSwitchConfirmationRequiredException
import com.aesprt.aquahub.data.supabase.auth.SupabaseAuthDataSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val mode: LoginMode = LoginMode.EMAIL,
    val phone: String = "",
    val email: String = "",
    val password: String = "",
    val otp: String = "",
    val otpRequested: Boolean = false,
    val isPasswordVisible: Boolean = false,
    val showPasswordResetDialog: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val canClearPreviousAccountData: Boolean = false,
    val showLocalDataClearDialog: Boolean = false,
    val infoMessage: String? = null
)

enum class LoginMode { EMAIL, PHONE_OTP }

sealed interface LoginEvent {
    data object LoginSuccess : LoginEvent
    data object GoogleNeedsOnboarding : LoginEvent
    data class ShowToast(val message: String) : LoginEvent
}

class LoginViewModel(
    private val loginUseCase: LoginUseCase,
    private val loginWithGoogleUseCase: LoginWithGoogleUseCase,
    private val resetPasswordUseCase: ResetPasswordUseCase,
    private val authDataSource: SupabaseAuthDataSource,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<LoginEvent>()
    val events = _events.asSharedFlow()

    fun onPhoneChange(phone: String) {
        _uiState.update {
            it.copy(
                phone = phone,
                errorMessage = null,
                canClearPreviousAccountData = false,
                showLocalDataClearDialog = false,
                infoMessage = null
            )
        }
    }

    fun selectMode(mode: LoginMode) = _uiState.update {
        it.copy(
            mode = mode,
            errorMessage = null,
            otpRequested = false,
            canClearPreviousAccountData = false,
            showLocalDataClearDialog = false,
            infoMessage = null
        )
    }
    fun onEmailChange(email: String) = _uiState.update {
        it.copy(email = email, errorMessage = null, canClearPreviousAccountData = false, showLocalDataClearDialog = false, infoMessage = null)
    }
    fun onOtpChange(otp: String) = _uiState.update {
        it.copy(otp = otp, errorMessage = null, canClearPreviousAccountData = false, showLocalDataClearDialog = false)
    }

    fun requestOtp() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, canClearPreviousAccountData = false, showLocalDataClearDialog = false, infoMessage = null) }
            runCatching { authDataSource.requestPhoneOtp(_uiState.value.phone) }
                .onSuccess { _uiState.update { it.copy(isLoading = false, otpRequested = true) } }
                .onFailure { error -> _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Unable to send verification code") } }
        }
    }

    fun verifyOtp() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, canClearPreviousAccountData = false, showLocalDataClearDialog = false, infoMessage = null) }
            runCatching {
                authDataSource.verifyPhoneOtp(_uiState.value.phone, _uiState.value.otp)
                authRepository.restoreCurrentSession().getOrThrow()
            }
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    _events.emit(LoginEvent.LoginSuccess)
                }
                .onFailure { error -> onSignInFailure(error, "Verification failed") }
        }
    }

    fun loginWithEmail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, canClearPreviousAccountData = false, showLocalDataClearDialog = false, infoMessage = null) }
            runCatching {
                authDataSource.signInWithEmail(_uiState.value.email, _uiState.value.password)
                authRepository.restoreCurrentSession().getOrThrow()
            }
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    _events.emit(LoginEvent.LoginSuccess)
                }
                .onFailure { error -> onSignInFailure(error, "Email authentication failed") }
        }
    }

    fun onPasswordChange(password: String) {
        _uiState.update {
            it.copy(password = password, errorMessage = null, canClearPreviousAccountData = false, showLocalDataClearDialog = false, infoMessage = null)
        }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun openPasswordReset() = _uiState.update { it.copy(showPasswordResetDialog = true, errorMessage = null) }
    fun closePasswordReset() = _uiState.update { it.copy(showPasswordResetDialog = false) }
    fun resetPassword(email: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            resetPasswordUseCase(email).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false, showPasswordResetDialog = false) }
                    _events.emit(LoginEvent.ShowToast("Password reset email sent. Check your inbox and spam folder."))
                },
                onFailure = { error -> _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Unable to send password reset email") } }
            )
        }
    }

    fun showError(message: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = message,
                canClearPreviousAccountData = false,
                showLocalDataClearDialog = false,
                infoMessage = null
            )
        }
    }

    fun openLocalDataClearConfirmation() {
        _uiState.update {
            if (it.canClearPreviousAccountData && !it.isLoading) it.copy(showLocalDataClearDialog = true)
            else it
        }
    }

    fun closeLocalDataClearConfirmation() {
        _uiState.update { if (!it.isLoading) it.copy(showLocalDataClearDialog = false) else it }
    }

    fun confirmClearPreviousAccountData() {
        if (!_uiState.value.canClearPreviousAccountData || !_uiState.value.showLocalDataClearDialog) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            authRepository.clearLocalDataForAccountSwitch().fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            otp = "",
                            otpRequested = false,
                            canClearPreviousAccountData = false,
                            showLocalDataClearDialog = false,
                            errorMessage = null,
                            infoMessage = "Saved business data was cleared from this device. Sign in again to continue."
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            canClearPreviousAccountData = false,
                            showLocalDataClearDialog = false,
                            errorMessage = error.message ?: "Unable to clear saved data from this device.",
                            infoMessage = null
                        )
                    }
                }
            )
        }
    }

    private fun onSignInFailure(error: Throwable, fallback: String) {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = error.message ?: fallback,
                canClearPreviousAccountData = error is LocalAccountSwitchConfirmationRequiredException,
                showLocalDataClearDialog = false,
                infoMessage = null
            )
        }
    }

    fun login() {
        val state = _uiState.value
        if (state.mode != LoginMode.EMAIL) return
        loginWithEmail()
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, canClearPreviousAccountData = false, showLocalDataClearDialog = false, infoMessage = null) }
            loginWithGoogleUseCase(idToken).fold(
                onSuccess = {
                    _uiState.update { it.copy(isLoading = false) }
                    _events.emit(LoginEvent.LoginSuccess)
                },
                onFailure = { error ->
                    if (error is GoogleAccountNeedsSetupException) {
                        // Supabase authentication succeeded. Keep that user
                        // signed in while the registration flow creates the
                        // AquaHub owner, business, subscription and station.
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = null, canClearPreviousAccountData = false, showLocalDataClearDialog = false)
                        }
                        _events.emit(LoginEvent.GoogleNeedsOnboarding)
                    } else {
                        onSignInFailure(error, "Google sign-in failed")
                    }
                }
            )
        }
    }

}
