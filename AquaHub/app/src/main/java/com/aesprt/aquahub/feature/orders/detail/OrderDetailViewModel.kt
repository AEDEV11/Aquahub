package com.aesprt.aquahub.feature.orders.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.Payment
import com.aesprt.aquahub.domain.model.PaymentRefund
import com.aesprt.aquahub.domain.model.RiderRecommendation
import com.aesprt.aquahub.domain.usecase.inventory.RecordContainerDeliveryUseCase
import com.aesprt.aquahub.domain.usecase.inventory.ObserveContainerInventoryUseCase
import com.aesprt.aquahub.domain.usecase.inventory.ObserveCustomerContainerTransactionsUseCase
import com.aesprt.aquahub.domain.model.Container
import com.aesprt.aquahub.domain.usecase.order.*
import com.aesprt.aquahub.domain.usecase.rider.RecommendRidersUseCase
import com.aesprt.aquahub.domain.usecase.station.ObserveStationUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import com.aesprt.aquahub.domain.model.OwnerRole
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.repository.RiderRepository
import com.aesprt.aquahub.domain.repository.OrderRepository
import com.aesprt.aquahub.sync.RemoteOrderRefreshCoordinator
import com.aesprt.aquahub.domain.repository.PaymentRepository
import com.aesprt.aquahub.domain.repository.ImageStorageRepository
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.PaymentStatus

data class OrderDetailUiState(
    val isLoading: Boolean = true,
    val order: Order? = null,
    val recommendedRiders: List<RiderRecommendation> = emptyList(),
    val isAssigningRider: Boolean = false,
    val showAssignRiderDialog: Boolean = false,
    val showContainerLoanDialog: Boolean = false,
    val isRecordingContainerLoan: Boolean = false,
    val stationContainers: List<Container> = emptyList(),
    val existingContainerLoanQuantity: Int = 0,
    val errorMessage: String? = null,
    val settlementErrorMessage: String? = null,
    val successMessage: String? = null,
    val isRider: Boolean = false,
    val canManageRefunds: Boolean = false,
    val showSettlementDialog: Boolean = false,
    val isSettling: Boolean = false,
    val isCancelling: Boolean = false,
    val payments: List<Payment> = emptyList(),
    val isPaymentsLoading: Boolean = false,
    val paymentHistoryError: String? = null,
    val refunds: List<PaymentRefund> = emptyList(),
    val isRefundHistoryLoading: Boolean = false,
    val refundHistoryError: String? = null,
    val isRefunding: Boolean = false,
    val deliveryPaymentProofUri: String? = null,
    val deliveryPaymentProofObjectPath: String? = null,
    val availablePaymentMethods: List<PaymentMethod> = emptyList()
)

internal fun collectedPaymentAmount(payments: List<Payment>): Double =
    payments.asSequence()
        .filter { it.status == PaymentStatus.PAID }
        .sumOf { it.amount }

class OrderDetailViewModel(
    private val getOrderDetailUseCase: GetOrderDetailUseCase,
    private val acceptOrderUseCase: AcceptOrderUseCase,
    private val rejectOrderUseCase: RejectOrderUseCase,
    private val startPreparingOrderUseCase: StartPreparingOrderUseCase,
    private val markOrderReadyUseCase: MarkOrderReadyUseCase,
    private val assignRiderToOrderUseCase: AssignRiderToOrderUseCase,
    private val markOutForDeliveryUseCase: MarkOutForDeliveryUseCase,
    private val markDeliveredUseCase: MarkDeliveredUseCase,
    private val completeOrderUseCase: CompleteOrderUseCase,
    private val cancelOrderUseCase: CancelOrderUseCase,
    private val recommendRidersUseCase: RecommendRidersUseCase,
    private val recordContainerDeliveryUseCase: RecordContainerDeliveryUseCase,
    private val observeContainerInventoryUseCase: ObserveContainerInventoryUseCase,
    private val observeCustomerContainerTransactionsUseCase: ObserveCustomerContainerTransactionsUseCase,
    private val observeStationUseCase: ObserveStationUseCase,
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val riderRepository: RiderRepository,
    private val orderRepository: OrderRepository,
    private val remoteOrderRefreshCoordinator: RemoteOrderRefreshCoordinator,
    private val paymentRepository: PaymentRepository,
    private val imageStorageRepository: ImageStorageRepository
) : ViewModel() {
    private var paymentHistoryRefreshJob: kotlinx.coroutines.Job? = null

    private val _uiState = MutableStateFlow(OrderDetailUiState())
    val uiState = _uiState.asStateFlow()

    fun loadOrder(orderId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(
                isLoading = true,
                errorMessage = null,
                payments = emptyList(),
                refunds = emptyList(),
                refundHistoryError = null
            ) }
            val owner = observeCurrentOwnerUseCase().firstOrNull()
            val isRider = owner?.role == OwnerRole.RIDER
            var order = getOrderDetailUseCase(orderId)
            if (order == null && owner != null) {
                remoteOrderRefreshCoordinator.refresh(owner.stationId)
                order = getOrderDetailUseCase(orderId)
            }
            val rider = if (isRider && order != null) {
                // Resolve the login against the branch that owns this order.
                // A global phone lookup can select a different rider record
                // when the same account/phone exists on multiple branches.
                owner?.phone?.let { phone ->
                    riderRepository.getRiderByPhone(phone, order.stationId)
                }
            } else {
                null
            }
            val canViewOrder = !isRider || (rider != null && order?.riderId == rider.id)
            if (order != null && canViewOrder) {
                showOrder(order, isRider, owner?.role)
                // Render Room immediately; a slow connection only delays the
                // remote refresh, not the detail screen's first usable state.
                remoteOrderRefreshCoordinator.refresh(order.stationId)
                val refreshed = getOrderDetailUseCase(orderId)
                if (refreshed != null && (!isRider || refreshed.riderId == rider?.id)) {
                    showOrder(refreshed, isRider, owner?.role)
                }
            } else if (isRider) {
                _uiState.update {
                    it.copy(
                        order = null,
                        isLoading = false,
                        isRider = true,
                        errorMessage = "This delivery is not assigned to your rider account"
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Order not found") }
            }
        }
    }

    private suspend fun showOrder(order: Order, isRider: Boolean, role: OwnerRole?) {
        val stationContainers = observeContainerInventoryUseCase(order.stationId).firstOrNull().orEmpty()
        val customerTransactions = if (order.customerId > 0) {
            observeCustomerContainerTransactionsUseCase(order.customerId).firstOrNull().orEmpty()
        } else emptyList()
        val existingLoanQuantity = customerTransactions
            .filter {
                it.stationId == order.stationId &&
                    it.orderId == order.id &&
                    it.type == com.aesprt.aquahub.domain.model.ContainerTransactionType.DELIVERED
            }
            .sumOf { it.quantity }
        val payments = paymentRepository.getPaymentsForOrder(order.id)
        _uiState.update {
            it.copy(
                order = order,
                isLoading = false,
                isRider = isRider,
                canManageRefunds = role == OwnerRole.OWNER || role == OwnerRole.MANAGER,
                payments = payments,
                stationContainers = stationContainers,
                existingContainerLoanQuantity = existingLoanQuantity
            )
        }
        if (!isRider) {
            refreshPaymentHistory(order.id)
            refreshRefundHistory(order.id)
        }
    }

    private fun refreshPaymentHistory(orderId: Long) {
        paymentHistoryRefreshJob?.cancel()
        paymentHistoryRefreshJob = viewModelScope.launch {
            _uiState.update { state ->
                if (state.order?.id == orderId) state.copy(isPaymentsLoading = true, paymentHistoryError = null)
                else state
            }
            try {
                val payments = paymentRepository.refreshPaymentsForOrder(orderId)
                _uiState.update { state ->
                    if (state.order?.id == orderId) state.copy(
                        payments = payments,
                        isPaymentsLoading = false,
                        paymentHistoryError = null
                    ) else state
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { state ->
                    if (state.order?.id == orderId) state.copy(
                        isPaymentsLoading = false,
                        paymentHistoryError = error.message ?: "Payment history could not be refreshed"
                    ) else state
                }
            }
        }
    }

    fun retryPaymentHistory() {
        _uiState.value.order?.id?.let(::refreshPaymentHistory)
    }

    private fun refreshRefundHistory(orderId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefundHistoryLoading = true, refundHistoryError = null) }
            try {
                val refunds = paymentRepository.getRefundsForOrder(orderId)
                _uiState.update { state ->
                    if (state.order?.id == orderId) state.copy(refunds = refunds, isRefundHistoryLoading = false)
                    else state
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { state ->
                    if (state.order?.id == orderId) state.copy(
                        isRefundHistoryLoading = false,
                        refundHistoryError = error.message ?: "Refund history could not be refreshed"
                    ) else state
                }
            }
        }
    }

    fun requestRefund(paymentId: Long, amountCentavos: Long, reason: String) {
        val orderId = _uiState.value.order?.id ?: return
        if (_uiState.value.isRefunding) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefunding = true, errorMessage = null) }
            try {
                paymentRepository.requestRefund(paymentId, amountCentavos, reason)
                _uiState.update {
                    it.copy(isRefunding = false, successMessage = "Refund reserved. Mark it complete only after the money is returned.")
                }
                refreshRefundHistory(orderId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(isRefunding = false, errorMessage = error.message ?: "Refund request could not be confirmed. Retry the same request when online.") }
            }
        }
    }

    fun completeRefund(refundId: String, externalReference: String?) {
        val orderId = _uiState.value.order?.id ?: return
        if (_uiState.value.isRefunding) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefunding = true, errorMessage = null) }
            try {
                paymentRepository.completeRefund(
                    refundId,
                    actualRefundConfirmed = true,
                    externalReference = externalReference
                )
                _uiState.update { it.copy(isRefunding = false, successMessage = "Refund completion recorded") }
                loadOrder(orderId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(isRefunding = false, errorMessage = error.message ?: "Refund completion could not be confirmed") }
            }
        }
    }

    fun failRefund(refundId: String, failureReason: String) {
        val orderId = _uiState.value.order?.id ?: return
        if (_uiState.value.isRefunding) return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefunding = true, errorMessage = null) }
            try {
                paymentRepository.failRefund(refundId, failureReason)
                _uiState.update { it.copy(isRefunding = false, successMessage = "Refund marked failed; its reserved amount is available again") }
                refreshRefundHistory(orderId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(isRefunding = false, errorMessage = error.message ?: "Refund failure could not be recorded") }
            }
        }
    }

    fun acceptOrder() {
        val orderId = _uiState.value.order?.id ?: return
        viewModelScope.launch {
            acceptOrderUseCase(orderId).fold(
                onSuccess = { loadOrder(orderId) },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            )
        }
    }

    fun rejectOrder() {
        val orderId = _uiState.value.order?.id ?: return
        viewModelScope.launch {
            rejectOrderUseCase(orderId).fold(
                onSuccess = { loadOrder(orderId) },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            )
        }
    }

    fun cancelOrder(reason: String) {
        val orderId = _uiState.value.order?.id ?: return
        if (_uiState.value.isCancelling) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCancelling = true, errorMessage = null) }
            cancelOrderUseCase(orderId, reason).fold(
                onSuccess = {
                    _uiState.update { it.copy(isCancelling = false) }
                    loadOrder(orderId)
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isCancelling = false, errorMessage = error.message) }
                }
            )
        }
    }

    fun startPreparing() {
        val orderId = _uiState.value.order?.id ?: return
        viewModelScope.launch {
            startPreparingOrderUseCase(orderId).fold(
                onSuccess = { loadOrder(orderId) },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            )
        }
    }

    fun markReadyForPickup() {
        val orderId = _uiState.value.order?.id ?: return
        viewModelScope.launch {
            markOrderReadyUseCase(orderId).fold(
                onSuccess = { loadOrder(orderId) },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            )
        }
    }

    fun openAssignRiderDialog() {
        val stationId = _uiState.value.order?.stationId ?: 0L
        viewModelScope.launch {
            _uiState.update { it.copy(isAssigningRider = true) }
            val station = observeStationUseCase(stationId).firstOrNull()
            val riders = recommendRidersUseCase(
                stationId = stationId,
                stationLat = station?.latitude,
                stationLon = station?.longitude
            )
            _uiState.update {
                it.copy(
                    recommendedRiders = riders,
                    isAssigningRider = false,
                    showAssignRiderDialog = true
                )
            }
            riderRepository.refreshRiders(stationId)
            if (_uiState.value.showAssignRiderDialog) {
                val refreshedRiders = recommendRidersUseCase(
                    stationId = stationId,
                    stationLat = station?.latitude,
                    stationLon = station?.longitude
                )
                _uiState.update { it.copy(recommendedRiders = refreshedRiders) }
            }
        }
    }

    fun closeAssignRiderDialog() {
        _uiState.update { it.copy(showAssignRiderDialog = false) }
    }

    fun openContainerLoanDialog() {
        val order = _uiState.value.order
        if (order == null || order.customerId <= 0) {
            _uiState.update { it.copy(errorMessage = "This order is not linked to a saved customer") }
            return
        }
        if (_uiState.value.existingContainerLoanQuantity > 0) {
            _uiState.update { it.copy(errorMessage = "A station bottle loan is already recorded for this order") }
            return
        }
        val hasMatchingInventory = order.items
            .filter { it.productType == com.aesprt.aquahub.domain.model.ProductType.REFILL }
            .any { item -> _uiState.value.stationContainers.any { it.sizeLabel.trim().equals(item.sizeLabel.trim(), ignoreCase = true) } }
        if (!hasMatchingInventory) {
            _uiState.update { it.copy(errorMessage = "No station-bottle inventory is configured for this order's refill size") }
            return
        }
        _uiState.update {
            it.copy(
                showContainerLoanDialog = true,
                errorMessage = null,
                successMessage = null
            )
        }
    }

    fun closeContainerLoanDialog() {
        if (_uiState.value.isRecordingContainerLoan) return
        _uiState.update { it.copy(showContainerLoanDialog = false, errorMessage = null) }
    }

    fun recordContainerLoan(sizeLabel: String, quantity: Int) {
        val order = _uiState.value.order ?: return
        if (_uiState.value.isRecordingContainerLoan) return
        if (sizeLabel.isBlank() || quantity <= 0) {
            _uiState.update { it.copy(errorMessage = "Choose a bottle size and enter a quantity") }
            return
        }
        val orderQuantity = _uiState.value.order?.items
            ?.filter {
                it.productType == com.aesprt.aquahub.domain.model.ProductType.REFILL &&
                    it.sizeLabel == sizeLabel
            }
            ?.sumOf { it.quantity } ?: 0
        if (orderQuantity == 0 || quantity > orderQuantity) {
            _uiState.update {
                it.copy(errorMessage = "The loan quantity cannot exceed the refill quantity in this order")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRecordingContainerLoan = true,
                    errorMessage = null,
                    successMessage = null
                )
            }
            try {
                recordContainerDeliveryUseCase(
                    stationId = order.stationId,
                    customerId = order.customerId,
                    customerName = order.customerName,
                    orderId = order.id,
                    sizeLabel = sizeLabel,
                    quantity = quantity
                )
                _uiState.update {
                    it.copy(
                        showContainerLoanDialog = false,
                        isRecordingContainerLoan = false,
                        errorMessage = null,
                        successMessage = "Station bottle loan saved"
                    )
                }
                loadOrder(order.id)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRecordingContainerLoan = false,
                        errorMessage = e.message?.takeIf { message -> message.isNotBlank() }
                            ?: "Could not record the station bottle loan. Check the station inventory and try again."
                    )
                }
            }
        }
    }

    fun assignRider(riderId: Long, riderName: String) {
        val orderId = _uiState.value.order?.id ?: return
        viewModelScope.launch {
            assignRiderToOrderUseCase(orderId, riderId, riderName).fold(
                onSuccess = {
                    _uiState.update { it.copy(showAssignRiderDialog = false) }
                    loadOrder(orderId)
                },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            )
        }
    }

    fun markOutForDelivery() {
        val orderId = _uiState.value.order?.id ?: return
        viewModelScope.launch {
            markOutForDeliveryUseCase(orderId).fold(
                onSuccess = { loadOrder(orderId) },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            )
        }
    }

    fun markDelivered() {
        val orderId = _uiState.value.order?.id ?: return
        viewModelScope.launch {
            markDeliveredUseCase(orderId).fold(
                onSuccess = {
                    loadOrder(orderId)
                    openSettlementDialog()
                },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            )
        }
    }

    fun openSettlementDialog() {
        val order = _uiState.value.order ?: return
        if (!order.requiresSettlementConfirmation()) {
            completeOrderWithoutSettlementPrompt(order)
            return
        }
        viewModelScope.launch {
            val station = observeStationUseCase(order.stationId).firstOrNull()
            val methods = buildList {
                if (order.deliveryMode.equals("DELIVERY", true) && station?.codPaymentEnabled != false) {
                    add(PaymentMethod.CASH_ON_DELIVERY)
                }
                if (!order.deliveryMode.equals("DELIVERY", true) && station?.cashPaymentEnabled != false) {
                    add(PaymentMethod.CASH)
                }
                if (station?.gcashPaymentEnabled == true) add(PaymentMethod.GCASH)
                if (station?.mayaPaymentEnabled == true) add(PaymentMethod.MAYA)
            }.ifEmpty {
                listOf(if (order.deliveryMode.equals("DELIVERY", true)) PaymentMethod.CASH_ON_DELIVERY else PaymentMethod.CASH)
            }.sortedBy { it != order.paymentMethod }
            _uiState.update {
                it.copy(
                    showSettlementDialog = true,
                    availablePaymentMethods = methods,
                    errorMessage = null,
                    settlementErrorMessage = null
                )
            }
        }
    }

    private fun completeOrderWithoutSettlementPrompt(order: Order) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    showSettlementDialog = false,
                    isSettling = true,
                    errorMessage = null
                )
            }
            completeOrderUseCase(order.id).fold(
                onSuccess = {
                    val message = if (order.paymentMethod == PaymentMethod.CLIENT_ACCOUNT) {
                        "Order completed. The amount remains on the client balance."
                    } else {
                        "Order completed"
                    }
                    _uiState.update { it.copy(isSettling = false, successMessage = message) }
                    loadOrder(order.id)
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSettling = false,
                            errorMessage = error.message ?: "Could not complete this order"
                        )
                    }
                }
            )
        }
    }

    fun closeSettlementDialog() {
        if (_uiState.value.isSettling) return
        _uiState.update { it.copy(showSettlementDialog = false) }
    }

    fun setDeliveryPaymentProofUri(uri: String?) {
        _uiState.update {
            it.copy(
                deliveryPaymentProofUri = uri,
                deliveryPaymentProofObjectPath = null,
                errorMessage = null,
                settlementErrorMessage = null
            )
        }
    }

    fun paymentProofCaptureFailed() {
        _uiState.update {
            it.copy(
                errorMessage = "Could not capture payment proof. Please try again.",
                settlementErrorMessage = "Could not capture payment proof. Please try again."
            )
        }
    }

    fun settleAsPaid(method: PaymentMethod, reference: String?) {
        val order = _uiState.value.order ?: return
        if (method !in _uiState.value.availablePaymentMethods) {
            _uiState.update {
                it.copy(errorMessage = "Choose an enabled payment method", settlementErrorMessage = "Choose an enabled payment method")
            }
            return
        }
        val requiresRiderProof = _uiState.value.isRider && order.deliveryMode.equals("DELIVERY", ignoreCase = true)
        val proofUri = _uiState.value.deliveryPaymentProofUri
        if (requiresRiderProof && proofUri.isNullOrBlank()) {
            val message = "Capture a photo of the payment proof before confirming collection"
            _uiState.update { it.copy(errorMessage = message, settlementErrorMessage = message) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSettling = true, errorMessage = null, settlementErrorMessage = null) }
            runCatching {
                val proofObjectPath = if (requiresRiderProof) {
                    _uiState.value.deliveryPaymentProofObjectPath
                        ?: imageStorageRepository.uploadDeliveryPaymentProof(
                            order.stationId,
                            order.id,
                            requireNotNull(proofUri)
                        ).getOrThrow().also { uploadedPath ->
                            _uiState.update { it.copy(deliveryPaymentProofObjectPath = uploadedPath) }
                        }
                } else null
                val paid = collectedPaymentAmount(paymentRepository.getPaymentsForOrder(order.id))
                val remaining = (order.total - paid).coerceAtLeast(0.0)
                if (remaining > 0.005) {
                    val receipt = paymentRepository.recordPayment(
                        orderId = order.id,
                        amount = remaining,
                        method = method,
                        referenceNumber = reference?.trim()?.ifBlank { null },
                        deliveryProofObjectPath = proofObjectPath,
                        requireRemoteConfirmation = requiresRiderProof
                    )
                    check(receipt.status == PaymentStatus.PAID) {
                        "Payment is still awaiting server confirmation; the order remains unpaid"
                    }
                }
                completeOrderUseCase(order.id).getOrThrow()
            }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            showSettlementDialog = false,
                            isSettling = false,
                            deliveryPaymentProofUri = null,
                            deliveryPaymentProofObjectPath = null,
                            settlementErrorMessage = null,
                            successMessage = "Payment confirmed and order completed"
                        )
                    }
                    loadOrder(order.id)
                },
                onFailure = { error ->
                    val message = error.message ?: "Could not confirm payment. Check your connection and retry."
                    _uiState.update {
                        it.copy(isSettling = false, errorMessage = message, settlementErrorMessage = message)
                    }
                }
            )
        }
    }

    fun settleToAccount() {
        val order = _uiState.value.order ?: return
        if (order.customerId <= 0) {
            val message = "Link this order to a customer before adding it to balance"
            _uiState.update { it.copy(errorMessage = message, settlementErrorMessage = message) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSettling = true, errorMessage = null, settlementErrorMessage = null) }
            runCatching {
                orderRepository.markChargedToAccount(order.id, System.currentTimeMillis())
                completeOrderUseCase(order.id).getOrThrow()
            }.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            showSettlementDialog = false,
                            isSettling = false,
                            settlementErrorMessage = null,
                            successMessage = "Order added to the client balance"
                        )
                    }
                    loadOrder(order.id)
                },
                onFailure = { error ->
                    val message = error.message ?: "Could not add this order to balance"
                    _uiState.update {
                        it.copy(isSettling = false, errorMessage = message, settlementErrorMessage = message)
                    }
                }
            )
        }
    }

    fun completeOrder() {
        val orderId = _uiState.value.order?.id ?: return
        viewModelScope.launch {
            completeOrderUseCase(orderId).fold(
                onSuccess = {
                    loadOrder(orderId)
                },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            )
        }
    }
}

internal fun Order.requiresSettlementConfirmation(): Boolean =
    paymentStatus != PaymentStatus.PAID && paymentMethod != PaymentMethod.CLIENT_ACCOUNT
