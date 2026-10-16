package com.aesprt.aquahub.feature.settings.subscription

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.subscription.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SubscriptionBillingUiState(
    val isLoading: Boolean = true,
    val stationId: Long = 0,
    val currentSubscription: Subscription? = null,
    val currentPlan: SubscriptionPlan? = null,
    val availablePlans: List<SubscriptionPlan> = emptyList(),
    val resolvedState: ResolvedSubscriptionState = ResolvedSubscriptionState.ACTIVE,
    val remainingDays: Int = 0,
    val history: List<SubscriptionHistoryEntry> = emptyList(),
    val isActionLoading: Boolean = false,
    val actionSuccessMessage: String? = null,
    val errorMessage: String? = null,
    val billingAvailability: BillingAvailability = BillingAvailability.CONNECTING,
    val billingOffers: List<PlayBillingOffer> = emptyList(),
    val billingOperation: BillingOperationState = BillingOperationState.Idle,
    val ownedPlayProductId: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionBillingViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val observeCurrentSubscriptionUseCase: ObserveCurrentSubscriptionUseCase,
    private val getAvailablePlansUseCase: GetAvailablePlansUseCase,
    private val getSubscriptionStatusUseCase: GetSubscriptionStatusUseCase,
    private val getSubscriptionRemainingDaysUseCase: GetSubscriptionRemainingDaysUseCase,
    private val getSubscriptionHistoryUseCase: GetSubscriptionHistoryUseCase,
    private val refreshSubscriptionStateUseCase: RefreshSubscriptionStateUseCase,
    private val googlePlayBillingRepository: com.aesprt.aquahub.domain.repository.GooglePlayBillingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubscriptionBillingUiState())
    val uiState: StateFlow<SubscriptionBillingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            googlePlayBillingRepository.availability.collect { availability ->
                _uiState.update { it.copy(billingAvailability = availability) }
            }
        }
        viewModelScope.launch {
            googlePlayBillingRepository.offers.collect { offers ->
                _uiState.update { it.copy(billingOffers = offers) }
            }
        }
        viewModelScope.launch {
            googlePlayBillingRepository.operation.collect { operation ->
                _uiState.update { it.copy(billingOperation = operation) }
            }
        }
        viewModelScope.launch {
            googlePlayBillingRepository.ownedPlayProductId.collect { productId ->
                _uiState.update { it.copy(ownedPlayProductId = productId) }
            }
        }
        viewModelScope.launch {
            observeCurrentOwnerUseCase()
                .map { owner -> owner?.stationId?.takeIf { it > 0 } }
                .distinctUntilChanged()
                .collectLatest { stationId ->
                    if (stationId == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = true,
                                stationId = 0,
                                currentSubscription = null,
                                currentPlan = null,
                                availablePlans = emptyList(),
                                history = emptyList()
                            )
                        }
                        return@collectLatest
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = true,
                            stationId = stationId,
                            currentSubscription = null,
                            currentPlan = null,
                            history = emptyList()
                        )
                    }
                    refreshSubscriptionStateUseCase(stationId)
                    loadSubscriptionData(stationId)
                }
        }
    }

    private suspend fun loadSubscriptionData(stationId: Long) {
        val plans = getAvailablePlansUseCase()
        val history = getSubscriptionHistoryUseCase(stationId)
        _uiState.update { it.copy(availablePlans = plans, history = history) }

        observeCurrentSubscriptionUseCase(stationId).collectLatest { sub ->
            val plan = plans.find { it.id == sub?.planId }
            val state = getSubscriptionStatusUseCase(sub)
            val remaining = getSubscriptionRemainingDaysUseCase(sub)
            val updatedHistory = getSubscriptionHistoryUseCase(stationId)

            _uiState.update {
                it.copy(
                    isLoading = false,
                    currentSubscription = sub,
                    currentPlan = plan,
                    resolvedState = state,
                    remainingDays = remaining,
                    history = updatedHistory
                )
            }
        }
    }

    fun renew(activity: Activity) {
        val subscription = _uiState.value.currentSubscription
        val plan = _uiState.value.currentPlan
        val resolvedState = _uiState.value.resolvedState
        if (SubscriptionBillingPolicy.hasOwnedPlayWhileFree(subscription, _uiState.value.ownedPlayProductId)) {
            openPlaySubscriptionManagement(activity)
            return
        }

        // AquaHub-managed trials do not create a Play subscription at trial
        // start, so there is nothing for Play's management page to renew.
        // Start a new verified Play purchase when the trial has expired. For an
        // existing Play subscription, keep the management-page handoff so Play
        // remains authoritative for renewal and payment recovery.
        if (SubscriptionBillingPolicy.shouldOpenPlayManagement(subscription, resolvedState)) {
            openPlaySubscriptionManagement(activity)
            return
        }
        if (plan == null || plan.code == SubscriptionPlanCode.FREE) {
            _uiState.update { it.copy(errorMessage = "Choose a paid plan to activate billing.") }
            return
        }
        googlePlayBillingRepository.purchase(
            activity = activity,
            planCode = plan.code,
            billingCycle = subscription?.billingCycle ?: BillingCycle.MONTHLY
        )
    }

    fun cancelSubscription(activity: Activity) {
        openPlaySubscriptionManagement(activity)
    }

    fun resume(activity: Activity) {
        openPlaySubscriptionManagement(activity)
    }

    fun purchasePlan(activity: Activity, newPlanId: Long, billingCycle: BillingCycle) {
        val availablePlans = _uiState.value.availablePlans
        val targetPlan = availablePlans.find { it.id == newPlanId } ?: return

        if (targetPlan.code == SubscriptionPlanCode.FREE) {
            requestFreePlan(activity)
            return
        }

        if (SubscriptionBillingPolicy.requiresPlayManagementBeforePlanChange(
                _uiState.value.currentSubscription,
                _uiState.value.resolvedState,
                _uiState.value.ownedPlayProductId
            )
        ) {
            openPlaySubscriptionManagement(activity)
            return
        }

        val oldProductId = SubscriptionBillingPolicy.replacementProductId(
            subscription = _uiState.value.currentSubscription,
            resolvedState = _uiState.value.resolvedState
        )
        googlePlayBillingRepository.purchase(activity, targetPlan.code, billingCycle, oldProductId)
    }

    fun requestFreePlan(activity: Activity) {
        if (!SubscriptionBillingPolicy.hasOwnedPlayWhileFree(
                _uiState.value.currentSubscription,
                _uiState.value.ownedPlayProductId
            ) && !SubscriptionBillingPolicy.canManagePaidPlayForFree(
                _uiState.value.currentSubscription,
                _uiState.value.resolvedState
            )
        ) return
        openPlaySubscriptionManagement(activity)
    }

    fun restorePurchases() {
        googlePlayBillingRepository.restorePurchases()
    }

    fun refreshAfterPlayReturn() {
        googlePlayBillingRepository.connect()
        val stationId = _uiState.value.stationId
        if (stationId <= 0L) return
        viewModelScope.launch {
            _uiState.update { it.copy(isActionLoading = true) }
            try {
                refreshSubscriptionStateUseCase(stationId)
            } finally {
                _uiState.update { it.copy(isActionLoading = false) }
            }
        }
    }

    private fun openPlaySubscriptionManagement(activity: Activity) {
        // The Play Store owns cancellation, renewal and resume. The next
        // backend notification or app restore updates Supabase and Room.
        val productId = _uiState.value.currentSubscription
            ?.let { GooglePlayBillingProducts.forPlan(it.planCode) }
            ?: _uiState.value.ownedPlayProductId
        googlePlayBillingRepository.openSubscriptionManagement(activity, productId)
    }

    fun clearMessages() {
        _uiState.update { it.copy(actionSuccessMessage = null, errorMessage = null) }
    }
}
