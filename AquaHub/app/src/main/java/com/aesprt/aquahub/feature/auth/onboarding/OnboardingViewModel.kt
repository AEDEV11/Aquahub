package com.aesprt.aquahub.feature.auth.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.GooglePlayBillingRepository
import com.aesprt.aquahub.domain.usecase.auth.RegisterAndSetupStationUseCase
import com.aesprt.aquahub.domain.usecase.auth.RegistrationInput
import com.aesprt.aquahub.domain.usecase.auth.SetCompletedOnboardingUseCase
import com.aesprt.aquahub.data.supabase.auth.SupabaseAuthDataSource
import com.aesprt.aquahub.data.supabase.auth.PhoneOtpRejectedException
import com.aesprt.aquahub.data.supabase.auth.PhoneProfileProvisioningException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class OnboardingUiState(
    // Step 3: Subscription
    val selectedPlan: SubscriptionPlan = DEFAULT_SUBSCRIPTION_PLANS.first { it.code == SubscriptionPlanCode.BUSINESS },
    val isAnnualBilling: Boolean = false,
    val billingOffers: List<PlayBillingOffer> = emptyList(),

    // Step 1: Owner Account
    val ownerName: String = "",
    val ownerPhone: String = "",
    val ownerEmail: String = "",
    val marketingConsent: Boolean = false,
    val authMethod: RegistrationAuthMethod = RegistrationAuthMethod.EMAIL,
    val isGoogleOnboarding: Boolean = false,
    val password: String = "",
    val confirmPassword: String = "",
    val isPasswordVisible: Boolean = false,
    val phoneVerificationSent: Boolean = false,
    val phoneVerified: Boolean = false,
    val phoneVerificationCode: String = "",
    val phoneOtpCooldownSeconds: Int = 0,

    // Step 2: Station Profile
    val stationName: String = "",
    val stationDescription: String = "",
    val stationPhone: String = "",
    val stationAddress: String = "",
    val barangay: String = "",
    val city: String = "Cagayan de Oro",
    val province: String = "Misamis Oriental",
    val latitude: Double? = 8.4542,
    val longitude: Double? = 124.6319,
    val placeId: String? = null,
    val openingTime: String = "07:00 AM",
    val closingTime: String = "06:00 PM",

    // State
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val trialAlreadyUsed: Boolean = false,
    val preparedStationId: Long? = null,
    val billingOperation: BillingOperationState = BillingOperationState.Idle
)

enum class RegistrationAuthMethod { EMAIL, PHONE }

class OnboardingViewModel(
    private val registerAndSetupStationUseCase: RegisterAndSetupStationUseCase,
    private val setCompletedOnboardingUseCase: SetCompletedOnboardingUseCase,
    private val supabaseAuthDataSource: SupabaseAuthDataSource,
    private val googlePlayBillingRepository: GooglePlayBillingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()
    private var pendingPaidSetup: Pair<Long, (Long) -> Unit>? = null
    private var phoneOtpCooldownJob: Job? = null

    init {
        // Google sign-in completes before the registration flow is opened. Do
        // this synchronously when the flow-scoped ViewModel is created so the
        // account screen never starts in password-registration mode.
        prepareGoogleOnboardingIfNeeded()
        // The activity normally connects onResume, but onboarding can be
        // created during the same frame as navigation. Refresh here as well
        // so plan cards receive live Play prices instead of the local fallback.
        googlePlayBillingRepository.connect()

        viewModelScope.launch {
            googlePlayBillingRepository.offers.collect { offers ->
                _uiState.update { it.copy(billingOffers = offers) }
            }
        }

        viewModelScope.launch {
            googlePlayBillingRepository.operation.collect { operation ->
                _uiState.update { it.copy(billingOperation = operation) }

                val pending = pendingPaidSetup ?: return@collect
                when (operation) {
                    BillingOperationState.Idle -> {
                        pendingPaidSetup = null
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "Google Play purchase was cancelled. Choose a plan to try again."
                            )
                        }
                    }
                    is BillingOperationState.Failed -> {
                        pendingPaidSetup = null
                        _uiState.update { it.copy(isLoading = false, errorMessage = operation.message) }
                    }
                    BillingOperationState.Completed -> {
                        pendingPaidSetup = null
                        setCompletedOnboardingUseCase(true)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                preparedStationId = null,
                                errorMessage = null
                            )
                        }
                        pending.second(pending.first)
                    }
                    is BillingOperationState.Purchasing,
                    BillingOperationState.Verifying -> {
                        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                    }
                    is BillingOperationState.Pending -> {
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = operation.message)
                        }
                    }
                }
            }
        }
    }

    /** Prefills the account step after Google auth while keeping the user in
     * the account → station → subscription flow. */
    fun prepareGoogleOnboardingIfNeeded() {
        if (!supabaseAuthDataSource.isGoogleUser) return
        _uiState.update {
            it.copy(
                isGoogleOnboarding = true,
                ownerName = it.ownerName.ifBlank { supabaseAuthDataSource.currentUserDisplayName.orEmpty() },
                ownerEmail = it.ownerEmail.ifBlank { supabaseAuthDataSource.currentUserEmail.orEmpty() },
                ownerPhone = it.ownerPhone.ifBlank { supabaseAuthDataSource.currentUserPhone.orEmpty() }
            )
        }
        viewModelScope.launch {
            val profile = runCatching { supabaseAuthDataSource.loadCurrentProfile() }.getOrNull()
            _uiState.update { state ->
                if (!state.isGoogleOnboarding) return@update state
                state.copy(
                    ownerName = state.ownerName.ifBlank {
                        profile?.displayName.orEmpty().ifBlank {
                            supabaseAuthDataSource.currentUserDisplayName.orEmpty()
                        }
                    },
                    ownerEmail = state.ownerEmail.ifBlank {
                        profile?.email.orEmpty().ifBlank {
                            supabaseAuthDataSource.currentUserEmail.orEmpty()
                        }
                    },
                    ownerPhone = state.ownerPhone.ifBlank {
                        profile?.phone.orEmpty().ifBlank {
                            supabaseAuthDataSource.currentUserPhone.orEmpty()
                        }
                    }
                )
            }
        }
    }

    fun completeIntroOnboarding(onFinish: () -> Unit) {
        viewModelScope.launch {
            setCompletedOnboardingUseCase(true)
            onFinish()
        }
    }

    fun selectPlan(planId: String?, billing: String?) {
        val plan = DEFAULT_SUBSCRIPTION_PLANS.find { it.code.name.equals(planId, ignoreCase = true) } ?: DEFAULT_SUBSCRIPTION_PLANS[1]
        val isAnnual = billing.equals("annual", ignoreCase = true)
        _uiState.update { it.copy(selectedPlan = plan, isAnnualBilling = isAnnual) }
    }

    fun toggleBillingCycle(annual: Boolean) {
        _uiState.update { it.copy(isAnnualBilling = annual) }
    }

    fun setPlan(plan: SubscriptionPlan) {
        _uiState.update { it.copy(selectedPlan = plan, errorMessage = null) }
    }

    fun onOwnerNameChange(value: String) = _uiState.update { it.copy(ownerName = value, errorMessage = null) }
    fun onOwnerPhoneChange(value: String) = _uiState.update {
        phoneOtpCooldownJob?.cancel()
        it.copy(
            ownerPhone = value,
            phoneVerificationSent = false,
            phoneVerified = false,
            phoneVerificationCode = "",
            phoneOtpCooldownSeconds = 0,
            errorMessage = null,
            trialAlreadyUsed = false
        )
    }
    fun onOwnerEmailChange(value: String) = _uiState.update {
        it.copy(
            ownerEmail = value,
            marketingConsent = if (value.trim().equals(it.ownerEmail.trim(), ignoreCase = true)) {
                it.marketingConsent
            } else {
                false
            },
            errorMessage = null,
            trialAlreadyUsed = false
        )
    }
    fun onMarketingConsentChange(value: Boolean) = _uiState.update {
        it.copy(marketingConsent = value && it.ownerEmail.isNotBlank(), errorMessage = null)
    }
    fun selectAuthMethod(method: RegistrationAuthMethod) = _uiState.update {
        if (method != RegistrationAuthMethod.PHONE) phoneOtpCooldownJob?.cancel()
        it.copy(
            authMethod = method,
            password = if (method == RegistrationAuthMethod.PHONE) "" else it.password,
            confirmPassword = if (method == RegistrationAuthMethod.PHONE) "" else it.confirmPassword,
            phoneVerificationSent = if (method == RegistrationAuthMethod.PHONE) it.phoneVerificationSent else false,
            phoneVerified = if (method == RegistrationAuthMethod.PHONE) it.phoneVerified else false,
            phoneVerificationCode = if (method == RegistrationAuthMethod.PHONE) it.phoneVerificationCode else "",
            phoneOtpCooldownSeconds = if (method == RegistrationAuthMethod.PHONE) it.phoneOtpCooldownSeconds else 0,
            errorMessage = null
        )
    }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }
    fun onConfirmPasswordChange(value: String) = _uiState.update { it.copy(confirmPassword = value, errorMessage = null) }
    fun togglePasswordVisibility() = _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }

    fun onPhoneVerificationCodeChange(value: String) = _uiState.update {
        it.copy(phoneVerificationCode = value.filter(Char::isDigit).take(6), errorMessage = null)
    }

    fun requestPhoneOtp() {
        val state = _uiState.value
        if (state.phoneOtpCooldownSeconds > 0) return
        if (state.ownerName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your full name") }
            return
        }
        if (state.ownerPhone.length < 10) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid mobile phone number") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching { supabaseAuthDataSource.requestPhoneOtp(state.ownerPhone) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            phoneVerificationSent = true,
                            phoneVerificationCode = "",
                            phoneOtpCooldownSeconds = 120
                        )
                    }
                    startPhoneOtpCooldown()
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = phoneOtpErrorMessage(error)
                        )
                    }
                }
        }
    }

    private fun phoneOtpErrorMessage(error: Throwable): String {
        val details = listOfNotNull(error::class.simpleName, error.message)
            .joinToString(" ")
            .lowercase()

        return when {
            "timeout" in details || "connect" in details || "network" in details ->
                "The SMS service took too long to respond. Check your connection and try again."
            "429" in details || "rate" in details || "too many" in details || "frequency" in details ->
                "Please wait before requesting another verification code."
            "signup" in details || "signups" in details || "disabled" in details ->
                "Phone sign-up is not enabled for this project yet. Please use email or contact support."
            "hook" in details || "semaphore" in details || "sms provider" in details ||
                "provider" in details || "500" in details || "502" in details || "503" in details ->
                "SMS delivery is not configured correctly yet. Please try again later."
            else ->
                "We couldn’t send a verification code. Check the number and your connection, then try again."
        }
    }

    fun resendPhoneOtp() {
        if (_uiState.value.phoneOtpCooldownSeconds > 0 || _uiState.value.isLoading) return
        requestPhoneOtp()
    }

    private fun startPhoneOtpCooldown() {
        phoneOtpCooldownJob?.cancel()
        phoneOtpCooldownJob = viewModelScope.launch {
            while (_uiState.value.phoneOtpCooldownSeconds > 0) {
                delay(1_000)
                _uiState.update { current ->
                    current.copy(phoneOtpCooldownSeconds = (current.phoneOtpCooldownSeconds - 1).coerceAtLeast(0))
                }
            }
        }
    }

    fun confirmPhoneOtp() {
        val state = _uiState.value
        if (state.phoneVerificationCode.length != 6) {
            _uiState.update { it.copy(errorMessage = "Enter the 6-digit verification code") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            runCatching {
                supabaseAuthDataSource.verifyPhoneOtp(
                    phone = state.ownerPhone,
                    token = state.phoneVerificationCode,
                    displayName = state.ownerName
                )
            }
                .onSuccess { _uiState.update { it.copy(isLoading = false, phoneVerified = true) } }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = when {
                                error is PhoneOtpRejectedException -> "That code could not be verified. Check the latest SMS and try again."
                                error is PhoneProfileProvisioningException -> "Your phone was verified, but we couldn’t prepare your account. Please try again."
                                else -> "We couldn’t complete phone verification. Please try again."
                            }
                        )
                    }
                }
        }
    }

    fun validateOwnerAccount(): Boolean {
        val state = _uiState.value
        if (state.ownerName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your full name") }
            return false
        }
        if (state.ownerPhone.length < 10) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid mobile phone number") }
            return false
        }
        if (state.authMethod == RegistrationAuthMethod.EMAIL && !state.isGoogleOnboarding) {
            if (!state.ownerEmail.trim().contains("@")) {
                _uiState.update { it.copy(errorMessage = "Enter an email address for account authentication") }
                return false
            }
            if (state.password.length < 8) {
                _uiState.update { it.copy(errorMessage = "Password must be at least 8 characters") }
                return false
            }
            if (state.password != state.confirmPassword) {
                _uiState.update { it.copy(errorMessage = "Passwords do not match") }
                return false
            }
        }
        if (state.authMethod == RegistrationAuthMethod.PHONE && !state.phoneVerified) {
            _uiState.update { it.copy(errorMessage = "Verify your phone number before continuing") }
            return false
        }
        _uiState.update { it.copy(errorMessage = null) }
        return true
    }

    fun onStationNameChange(value: String) = _uiState.update { it.copy(stationName = value, errorMessage = null) }
    fun onStationDescriptionChange(value: String) = _uiState.update { it.copy(stationDescription = value, errorMessage = null) }
    fun onStationPhoneChange(value: String) = _uiState.update { it.copy(stationPhone = value, errorMessage = null) }
    fun onStationAddressChange(value: String) = _uiState.update { it.copy(stationAddress = value, errorMessage = null) }
    fun onBarangayChange(value: String) = _uiState.update { it.copy(barangay = value, errorMessage = null) }
    fun onCityChange(value: String) = _uiState.update { it.copy(city = value, errorMessage = null) }
    fun onProvinceChange(value: String) = _uiState.update { it.copy(province = value, errorMessage = null) }
    fun onOpeningTimeChange(value: String) = _uiState.update { it.copy(openingTime = value, errorMessage = null) }
    fun onClosingTimeChange(value: String) = _uiState.update { it.copy(closingTime = value, errorMessage = null) }

    fun continueToPlan(onValid: () -> Unit) {
        val state = _uiState.value
        if (state.stationName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Station name is required") }
            return
        }
        if (state.stationAddress.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Station address is required") }
            return
        }
        if (!com.aesprt.aquahub.core.common.DateTimeUtils.isClosingAfterOpening(state.openingTime, state.closingTime)) {
            _uiState.update { it.copy(errorMessage = "Closing time must be later than opening time") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val prepared = registerAndSetupStationUseCase.prepareAccountAndStation(registrationInput(state))
            prepared.fold(
                onSuccess = { stationId ->
                    registerAndSetupStationUseCase.isOwnerTrialConsumed().fold(
                        onSuccess = { consumed ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    preparedStationId = stationId,
                                    trialAlreadyUsed = consumed,
                                    errorMessage = null
                                )
                            }
                            onValid()
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(isLoading = false, errorMessage = error.message ?: "We could not verify trial eligibility")
                            }
                        }
                    )
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "We could not start account setup. Check your connection and try again."
                        )
                    }
                }
            )
        }
    }

    fun onLocationSelected(location: com.aesprt.aquahub.domain.model.StationLocation) {
        _uiState.update {
            it.copy(
                stationAddress = location.formattedAddress,
                barangay = location.barangay ?: it.barangay,
                city = location.city ?: it.city,
                province = location.province ?: it.province,
                latitude = location.latitude,
                longitude = location.longitude,
                placeId = location.placeId,
                errorMessage = null
            )
        }
    }

    fun completeSetup(activity: Activity, onSuccess: (Long) -> Unit) {
        val state = _uiState.value

        val stationId = state.preparedStationId
        if (stationId == null) {
            _uiState.update { it.copy(errorMessage = "Complete account and station setup before choosing a plan") }
            return
        }

        if (state.ownerName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Owner name is required") }
            return
        }
        if (state.ownerPhone.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Phone number is required") }
            return
        }
        if (state.authMethod == RegistrationAuthMethod.EMAIL && !state.isGoogleOnboarding) {
            if (state.password.length < 8) {
                _uiState.update { it.copy(errorMessage = "Password must be at least 8 characters") }
                return
            }
            if (state.password != state.confirmPassword) {
                _uiState.update { it.copy(errorMessage = "Passwords do not match") }
                return
            }
        }
        if (state.authMethod == RegistrationAuthMethod.PHONE && !state.phoneVerified) {
            _uiState.update { it.copy(errorMessage = "Verify your phone number before completing setup") }
            return
        }
        if (state.stationName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Station name is required") }
            return
        }
        if (state.stationAddress.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Station address is required") }
            return
        }
        if (!com.aesprt.aquahub.core.common.DateTimeUtils.isClosingAfterOpening(state.openingTime, state.closingTime)) {
            _uiState.update { it.copy(errorMessage = "Closing time must be later than opening time") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            if (state.selectedPlan.code != SubscriptionPlanCode.FREE) {
                val billingCycle = if (state.isAnnualBilling) BillingCycle.ANNUAL else BillingCycle.MONTHLY

                if (state.trialAlreadyUsed) {
                    // An identity that has already consumed the AquaHub trial must
                    // activate payment immediately. The Play purchase is verified
                    // server-side before onboarding is completed.
                    pendingPaidSetup = stationId to onSuccess
                    runCatching {
                        googlePlayBillingRepository.purchase(
                            activity = activity,
                            planCode = state.selectedPlan.code,
                            billingCycle = billingCycle
                        )
                    }.onFailure { error ->
                        pendingPaidSetup = null
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = error.message ?: "Google Play checkout could not be started. Please try again."
                            )
                        }
                    }
                    return@launch
                }

                // Eligible first-time owners use AquaHub's app-managed trial.
                // This deliberately does not open Google Play checkout: the
                // server creates the 30-day TRIALING entitlement, and payment is
                // requested later after the trial has expired.
                val trialResult = registerAndSetupStationUseCase.activateSelectedPlan(
                    stationId = stationId,
                    subscriptionPlanId = state.selectedPlan.code.name.lowercase(),
                    subscriptionBilling = billingCycle.name.lowercase()
                )
                trialResult.fold(
                    onSuccess = {
                        setCompletedOnboardingUseCase(true)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                preparedStationId = null,
                                errorMessage = null
                            )
                        }
                        onSuccess(stationId)
                    },
                    onFailure = { err ->
                        val trialAlreadyUsed = err.message?.contains("already used its free trial", ignoreCase = true) == true
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                trialAlreadyUsed = trialAlreadyUsed,
                                errorMessage = err.message ?: "Trial setup failed. Please check your connection and try again."
                            )
                        }
                    }
                )
                return@launch
            }

            val result = registerAndSetupStationUseCase.activateSelectedPlan(
                stationId = stationId,
                subscriptionPlanId = state.selectedPlan.code.name.lowercase(),
                subscriptionBilling = if (state.isAnnualBilling) "annual" else "monthly"
            )

            result.fold(
                onSuccess = {
                    setCompletedOnboardingUseCase(true)
                    _uiState.update { it.copy(isLoading = false) }
                    onSuccess(stationId)
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            trialAlreadyUsed = err.message?.contains("already used its free trial", ignoreCase = true) == true,
                            errorMessage = when {
                                err.message?.contains("already used its free trial", ignoreCase = true) == true ->
                                    "This phone number or recovery email already used its free trial. Choose a paid plan without a trial, or choose Free."
                                else -> err.message ?: "Registration failed. Please check inputs."
                            }
                        )
                    }
                }
            )
        }
    }

    private fun registrationInput(state: OnboardingUiState): RegistrationInput = RegistrationInput(
        ownerName = state.ownerName,
        ownerPhone = state.ownerPhone,
        ownerEmail = state.ownerEmail.ifBlank { null },
        password = if (state.authMethod == RegistrationAuthMethod.PHONE) "" else state.password,
        stationName = state.stationName,
        stationDescription = state.stationDescription.ifBlank { null },
        stationPhone = state.stationPhone.ifBlank { state.ownerPhone },
        stationAddress = state.stationAddress,
        barangay = state.barangay.ifBlank { null },
        city = state.city,
        province = state.province,
        latitude = state.latitude,
        longitude = state.longitude,
        placeId = state.placeId,
        openingTime = state.openingTime,
        closingTime = state.closingTime,
        marketingConsent = state.marketingConsent
    )
}
