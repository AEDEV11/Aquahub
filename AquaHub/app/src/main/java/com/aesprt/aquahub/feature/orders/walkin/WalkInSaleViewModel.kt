package com.aesprt.aquahub.feature.orders.walkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.Container
import com.aesprt.aquahub.domain.model.ContainerTransaction
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.OrderItem
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.Product
import com.aesprt.aquahub.domain.model.ProductType
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.model.unitPriceFor
import com.aesprt.aquahub.domain.model.OfflinePosStatus
import com.aesprt.aquahub.domain.model.OfflinePosReservationItem
import com.aesprt.aquahub.domain.model.OfflinePosRecoveryRecord
import com.aesprt.aquahub.domain.repository.OfflinePosRepository
import com.aesprt.aquahub.sync.ConnectivityMonitor
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.customer.ObserveCustomersUseCase
import com.aesprt.aquahub.domain.usecase.inventory.ObserveContainerInventoryUseCase
import com.aesprt.aquahub.domain.usecase.inventory.ObserveContainerTransactionsUseCase
import com.aesprt.aquahub.domain.usecase.order.CreateWalkInOrderUseCase
import com.aesprt.aquahub.domain.usecase.product.ObserveProductsUseCase
import com.aesprt.aquahub.domain.usecase.station.ObserveStationUseCase
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class WalkInSaleUiState(
    val products: List<Product> = emptyList(),
    val customers: List<Customer> = emptyList(),
    val containers: List<Container> = emptyList(),
    val containerTransactions: List<ContainerTransaction> = emptyList(),
    val selectedQuantities: Map<Long, Int> = emptyMap(),
    val selectedCustomer: Customer? = null,
    val selectedContainerSize: String = "",
    val emptyBottlesReturned: Int = 0,
    val stationContainersIssued: Int = 0,
    val deliveryMode: String = "PICKUP",
    val deliveryRecipientName: String = "",
    val deliveryRecipientPhone: String = "",
    val deliveryAddress: String = "",
    val deliveryLatitude: Double? = null,
    val deliveryLongitude: Double? = null,
    val deliveryFee: Double = 0.0,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val cashPaymentEnabled: Boolean = true,
    val codPaymentEnabled: Boolean = true,
    val gcashPaymentEnabled: Boolean = false,
    val mayaPaymentEnabled: Boolean = false,
    val promotionsAllowed: Boolean = false,
    val deliveryManagementAllowed: Boolean = false,
    val deliveryAccessResolved: Boolean = false,
    val chargeToAccount: Boolean = false,
    val isOnline: Boolean = true,
    val offlinePosStatus: OfflinePosStatus = OfflinePosStatus(),
    val offlineRecoveryRecords: List<OfflinePosRecoveryRecord> = emptyList(),
    val showOfflineRecoveryRecords: Boolean = false,
    val cashReceivedText: String = "",
    val notes: String = "",
    val isSubmitting: Boolean = false,
    val loadErrorMessage: String? = null,
    /** Reused when the same sale is retried after an uncertain network result. */
    val clientOperationId: String = "owner:walk-in:${UUID.randomUUID()}",
    val createdOrderId: Long? = null,
    val errorMessage: String? = null
) {
    val selectedItemsList: List<OrderItem>
        get() = selectedQuantities.mapNotNull { (productId, qty) ->
            if (qty <= 0) return@mapNotNull null
            val product = products.find { it.id == productId } ?: return@mapNotNull null
            val offlineSnapshot = offlinePosStatus.productSnapshots[productId].takeIf { !isOnline }
            val effectiveUnitPrice = offlineSnapshot?.unitPriceCentavos?.div(100.0)
                ?: product.unitPriceFor(qty, promotionsAllowed)
            OrderItem(
                productId = product.id,
                productName = offlineSnapshot?.productName ?: product.name,
                productType = offlineSnapshot?.productType ?: product.type,
                sizeLabel = offlineSnapshot?.sizeLabel ?: product.sizeLabel,
                unitPrice = effectiveUnitPrice,
                quantity = qty,
                subtotal = effectiveUnitPrice * qty
            )
        }

    val regularSubtotalAmount: Double
        get() = selectedQuantities.mapNotNull { (productId, qty) ->
            products.find { it.id == productId }?.let { product -> product.price * qty }
        }.sum()

    val subtotalAmount: Double
        get() = selectedItemsList.sumOf { it.subtotal }

    val promotionSavingsAmount: Double
        get() = (regularSubtotalAmount - subtotalAmount).coerceAtLeast(0.0)

    val totalAmount: Double
        get() = subtotalAmount + if (deliveryMode == "DELIVERY") deliveryFee else 0.0

    val totalItemsCount: Int
        get() = selectedQuantities.values.sum()

    val totalRefillsCount: Int
        get() = selectedItemsList.filter { it.productType == ProductType.REFILL }.sumOf { it.quantity }

    val availablePaymentMethods: List<PaymentMethod>
        get() = buildList {
            if (!isOnline) {
                if (cashPaymentEnabled && offlinePosStatus.canCreateOfflineSale) add(PaymentMethod.CASH)
                return@buildList
            }
            if (deliveryMode == "DELIVERY") {
                if (codPaymentEnabled) add(PaymentMethod.CASH_ON_DELIVERY)
            } else if (cashPaymentEnabled) {
                add(PaymentMethod.CASH)
            }
            if (gcashPaymentEnabled) add(PaymentMethod.GCASH)
            if (mayaPaymentEnabled) add(PaymentMethod.MAYA)
        }
}

private data class WalkInLiveData(
    val products: List<Product>,
    val customers: List<Customer>,
    val deliveryFee: Double,
    val containers: List<Container>,
    val containerTransactions: List<ContainerTransaction>,
    val cashPaymentEnabled: Boolean,
    val codPaymentEnabled: Boolean,
    val gcashPaymentEnabled: Boolean,
    val mayaPaymentEnabled: Boolean,
    val promotionsAllowed: Boolean,
    val deliveryManagementAllowed: Boolean
)

internal fun canSelectWalkInDelivery(
    deliveryAccessResolved: Boolean,
    deliveryManagementAllowed: Boolean
): Boolean = deliveryAccessResolved && deliveryManagementAllowed

internal data class WalkInSaleRequestFingerprint(
    val stationId: Long,
    val customerId: Long,
    val customerName: String,
    val customerPhone: String,
    val items: List<Pair<Long, Int>>,
    val paymentMethod: PaymentMethod,
    val notes: String?,
    val deliveryMode: String,
    val deliveryAddress: String,
    val deliveryLatitude: Double?,
    val deliveryLongitude: Double?,
    val deliveryFeeCentavos: Long,
    val chargeToAccount: Boolean,
    val emptyBottlesReturned: Int,
    val containerSize: String,
    val stationContainersIssued: Int
)

class WalkInSaleViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observeProductsUseCase: ObserveProductsUseCase,
    private val observeCustomersUseCase: ObserveCustomersUseCase,
    private val observeStationUseCase: ObserveStationUseCase,
    private val observeContainerInventoryUseCase: ObserveContainerInventoryUseCase,
    private val observeContainerTransactionsUseCase: ObserveContainerTransactionsUseCase,
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val createWalkInOrderUseCase: CreateWalkInOrderUseCase,
    private val offlinePosRepository: OfflinePosRepository,
    private val connectivityMonitor: ConnectivityMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(WalkInSaleUiState())
    val uiState: StateFlow<WalkInSaleUiState> = _uiState.asStateFlow()

    private var currentStationId: Long = 0L
    private var dataObservationJob: kotlinx.coroutines.Job? = null
    private val submissionGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val submissionIdentity = WalkInOperationIdentity()

    init {
        loadData()
        viewModelScope.launch {
            connectivityMonitor.isOnline.collect { online ->
                _uiState.update { it.copy(isOnline = online) }
                refreshOfflineStatus()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadData() {
        dataObservationJob?.cancel()
        dataObservationJob = viewModelScope.launch {
            combine(
                activeStationContext.observeActiveStationId(),
                observeCurrentOwnerUseCase()
            ) { activeId, owner ->
                activeId ?: owner?.stationId ?: 0L
            }.distinctUntilChanged().flatMapLatest { stationId ->
                currentStationId = stationId
                refreshOfflineStatus()
                _uiState.update {
                    it.copy(
                        deliveryManagementAllowed = false,
                        deliveryAccessResolved = false,
                        deliveryMode = if (it.deliveryMode == "DELIVERY") "PICKUP" else it.deliveryMode
                    )
                }
                val promotionsAllowed = checkSubscriptionAccessUseCase(
                    stationId,
                    SubscriptionFeature.PROMOTIONS
                ) is SubscriptionAccessResult.Allowed
                val deliveryManagementAllowed = checkSubscriptionAccessUseCase(
                    stationId,
                    SubscriptionFeature.DELIVERY_MANAGEMENT
                ) is SubscriptionAccessResult.Allowed
                combine(
                    observeProductsUseCase(stationId),
                    observeCustomersUseCase(stationId),
                    observeStationUseCase(stationId),
                    observeContainerInventoryUseCase(stationId),
                    observeContainerTransactionsUseCase(stationId)
                ) { products, customers, station, containers, transactions ->
                    val availableProducts = products.filter { it.isAvailable && it.stockQuantity > 0 }
                    WalkInLiveData(
                        products = availableProducts,
                        customers = customers,
                        deliveryFee = station?.deliveryFeeCentavos?.toDouble()?.div(100.0) ?: 0.0,
                        containers = containers,
                        containerTransactions = transactions,
                        cashPaymentEnabled = station?.cashPaymentEnabled ?: true,
                        codPaymentEnabled = station?.codPaymentEnabled ?: true,
                        gcashPaymentEnabled = station?.gcashPaymentEnabled ?: false,
                        mayaPaymentEnabled = station?.mayaPaymentEnabled ?: false,
                        promotionsAllowed = promotionsAllowed,
                        deliveryManagementAllowed = deliveryManagementAllowed
                    )
                }
            }.catch { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update {
                    it.copy(loadErrorMessage = "Could not load Walk-in Sales data. Check your connection and retry.")
                }
            }.collect { live ->
                _uiState.update { current ->
                    val selectedRefillSize = current.selectedQuantities.keys
                        .mapNotNull { id -> live.products.firstOrNull { it.id == id } }
                        .firstOrNull { it.type == ProductType.REFILL }
                        ?.sizeLabel
                    val selectedSizeStillExists = live.containers.any { it.sizeLabel == current.selectedContainerSize }
                    val defaultSize = live.containers.firstOrNull { it.sizeLabel == selectedRefillSize }?.sizeLabel
                        ?: live.containers.singleOrNull()?.sizeLabel
                    val deliveryMode = if (
                        current.deliveryMode == "DELIVERY" && !live.deliveryManagementAllowed
                    ) "PICKUP" else current.deliveryMode
                    val availableMethods = buildList {
                        if (deliveryMode == "DELIVERY") {
                            if (live.codPaymentEnabled) add(PaymentMethod.CASH_ON_DELIVERY)
                        } else if (live.cashPaymentEnabled) add(PaymentMethod.CASH)
                        if (live.gcashPaymentEnabled) add(PaymentMethod.GCASH)
                        if (live.mayaPaymentEnabled) add(PaymentMethod.MAYA)
                    }
                    current.copy(
                        products = live.products,
                        customers = live.customers,
                        containers = live.containers,
                        containerTransactions = live.containerTransactions,
                        cashPaymentEnabled = live.cashPaymentEnabled,
                        codPaymentEnabled = live.codPaymentEnabled,
                        gcashPaymentEnabled = live.gcashPaymentEnabled,
                        mayaPaymentEnabled = live.mayaPaymentEnabled,
                        promotionsAllowed = live.promotionsAllowed,
                        deliveryManagementAllowed = live.deliveryManagementAllowed,
                        deliveryAccessResolved = true,
                        deliveryMode = deliveryMode,
                        paymentMethod = current.paymentMethod.takeIf { it in availableMethods }
                            ?: availableMethods.firstOrNull()
                            ?: current.paymentMethod,
                        selectedContainerSize = if (selectedSizeStillExists) current.selectedContainerSize else defaultSize.orEmpty(),
                        deliveryFee = live.deliveryFee,
                        loadErrorMessage = null
                    )
                }
            }
        }
    }

    fun retryLoadData() {
        _uiState.update { it.copy(loadErrorMessage = null) }
        loadData()
    }

    fun refreshDeliveryAccess() {
        val stationId = currentStationId.takeIf { it > 0L } ?: return
        _uiState.update { it.copy(deliveryAccessResolved = false) }
        viewModelScope.launch {
            try {
                val deliveryAllowed = checkSubscriptionAccessUseCase(
                    stationId,
                    SubscriptionFeature.DELIVERY_MANAGEMENT
                ) is SubscriptionAccessResult.Allowed
                _uiState.update { current ->
                    val deliveryMode = if (current.deliveryMode == "DELIVERY" && !deliveryAllowed) {
                        "PICKUP"
                    } else {
                        current.deliveryMode
                    }
                    val availableMethods = buildList {
                        if (deliveryMode == "DELIVERY") {
                            if (current.codPaymentEnabled) add(PaymentMethod.CASH_ON_DELIVERY)
                        } else if (current.cashPaymentEnabled) {
                            add(PaymentMethod.CASH)
                        }
                        if (current.gcashPaymentEnabled) add(PaymentMethod.GCASH)
                        if (current.mayaPaymentEnabled) add(PaymentMethod.MAYA)
                    }
                    current.copy(
                        deliveryManagementAllowed = deliveryAllowed,
                        deliveryAccessResolved = true,
                        deliveryMode = deliveryMode,
                        paymentMethod = current.paymentMethod.takeIf { it in availableMethods }
                            ?: availableMethods.firstOrNull()
                            ?: current.paymentMethod,
                        errorMessage = null
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        deliveryAccessResolved = false,
                        errorMessage = "Could not verify delivery access. Check your connection and try again."
                    )
                }
            }
        }
    }

    fun refreshOfflineStatus() {
        val stationId = currentStationId.takeIf { it > 0L } ?: return
        viewModelScope.launch {
            val status = try {
                offlinePosRepository.getStatus(stationId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                OfflinePosStatus(state = "UNAVAILABLE", message = "Offline POS status could not be loaded.")
            }
            val recoveryRecords = runCatching { offlinePosRepository.getRecoveryRecords(stationId) }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(offlinePosStatus = status, offlineRecoveryRecords = recoveryRecords) }
        }
    }

    fun toggleOfflineRecoveryRecords() {
        _uiState.update { it.copy(showOfflineRecoveryRecords = !it.showOfflineRecoveryRecords) }
        if (_uiState.value.showOfflineRecoveryRecords) refreshOfflineStatus()
    }

    fun retryOfflineReconciliation(operationId: String) {
        val state = _uiState.value
        if (!state.isOnline) {
            _uiState.update { it.copy(errorMessage = "Reconnect to the internet before retrying server verification.") }
            return
        }
        if (state.isSubmitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            try {
                offlinePosRepository.retryReconciliation(operationId)
                refreshOfflineStatus()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(errorMessage = error.message ?: "The saved sale could not be queued for verification.") }
            }
        }
    }

    fun prepareOfflineSales() {
        val stationId = currentStationId.takeIf { it > 0L } ?: return
        val state = _uiState.value
        if (!state.isOnline) {
            _uiState.update { it.copy(errorMessage = "Reconnect to the internet to reserve inventory for offline sales.") }
            return
        }
        val requested = state.selectedQuantities.map { (productId, quantity) ->
            OfflinePosReservationItem(productId, quantity)
        }
        if (requested.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Choose products and quantities to reserve for offline sales.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }
            try {
                val status = offlinePosRepository.prepareReservation(stationId, requested)
                _uiState.update { it.copy(isSubmitting = false, offlinePosStatus = status) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(isSubmitting = false, errorMessage = error.message ?: "Inventory could not be reserved.") }
            }
        }
    }

    fun setCashReceived(value: String) {
        _uiState.update { it.copy(cashReceivedText = value.filter { c -> c.isDigit() || c == '.' }.take(12)) }
    }

    fun incrementProduct(productId: Long) {
        _uiState.update { current ->
            val currentQty = current.selectedQuantities[productId] ?: 0
            val product = current.products.firstOrNull { it.id == productId }
            val stock = if (!current.isOnline) {
                current.offlinePosStatus.productSnapshots[productId]?.quantityRemaining ?: 0
            } else product?.stockQuantity ?: 0
            if (currentQty >= stock) {
                return@update current.copy(errorMessage = "Only $stock ${product?.sizeLabel ?: "units"} available")
            }
            val newMap = current.selectedQuantities.toMutableMap()
            newMap[productId] = currentQty + 1
            val defaultContainerSize = if (product?.type == ProductType.REFILL && current.selectedContainerSize.isBlank()) {
                current.containers.firstOrNull {
                    it.sizeLabel.trim().equals(product.sizeLabel.trim(), ignoreCase = true)
                }?.sizeLabel ?: current.containers.singleOrNull()?.sizeLabel.orEmpty()
            } else {
                current.selectedContainerSize
            }
            current.copy(
                selectedQuantities = newMap,
                selectedContainerSize = defaultContainerSize,
                errorMessage = null
            )
        }
    }

    fun decrementProduct(productId: Long) {
        _uiState.update { current ->
            val currentQty = current.selectedQuantities[productId] ?: 0
            val newMap = current.selectedQuantities.toMutableMap()
            if (currentQty <= 1) {
                newMap.remove(productId)
            } else {
                newMap[productId] = currentQty - 1
            }

            current.copy(
                selectedQuantities = newMap,

                errorMessage = null
            )
        }
    }

    fun setCustomer(customer: Customer?) {
        val address = customer?.addresses?.firstOrNull { it.isDefault }
            ?: customer?.addresses?.firstOrNull()
        _uiState.update {
            it.copy(
                selectedCustomer = customer,
                emptyBottlesReturned = 0,
                stationContainersIssued = 0,
                deliveryRecipientName = customer?.name.orEmpty(),
                deliveryRecipientPhone = customer?.phone.orEmpty(),
                deliveryAddress = address?.addressLine.orEmpty(),
                deliveryLatitude = address?.latitude,
                deliveryLongitude = address?.longitude,
                chargeToAccount = if (customer == null) false else it.chargeToAccount
            )
        }
    }

    fun setContainerSize(sizeLabel: String) {
        _uiState.update { it.copy(selectedContainerSize = sizeLabel, errorMessage = null) }
    }

    fun setEmptyBottlesReturned(count: Int) {
        _uiState.update { it.copy(emptyBottlesReturned = count.coerceAtLeast(0)) }
    }

    fun setStationContainersIssued(count: Int) {
        _uiState.update { it.copy(stationContainersIssued = count.coerceAtLeast(0)) }
    }

    fun setDeliveryMode(mode: String) {
        val normalized = if (mode.equals("DELIVERY", ignoreCase = true)) "DELIVERY" else "PICKUP"
        _uiState.update {
            if (!it.isOnline && normalized == "DELIVERY") {
                return@update it.copy(errorMessage = "Delivery requires internet. Offline sales are cash pickup only.")
            }
            if (normalized == "DELIVERY" && !canSelectWalkInDelivery(
                    it.deliveryAccessResolved,
                    it.deliveryManagementAllowed
                )
            ) {
                return@update it.copy(
                    errorMessage = if (it.deliveryAccessResolved) {
                        "Delivery orders require an active plan with delivery management."
                    } else {
                        "Checking plan access. Try again in a moment."
                    }
                )
            }
            val available = buildList {
                if (normalized == "DELIVERY") {
                    if (it.codPaymentEnabled) add(PaymentMethod.CASH_ON_DELIVERY)
                } else if (it.cashPaymentEnabled) add(PaymentMethod.CASH)
                if (it.gcashPaymentEnabled) add(PaymentMethod.GCASH)
                if (it.mayaPaymentEnabled) add(PaymentMethod.MAYA)
            }
            it.copy(
                deliveryMode = normalized,
                paymentMethod = it.paymentMethod.takeIf { method -> method in available }
                    ?: available.firstOrNull()
                    ?: it.paymentMethod,
                errorMessage = if (available.isEmpty()) "Enable a payment method for $normalized orders in Settings" else null
            )
        }
    }

    fun setDeliveryRecipientName(value: String) {
        _uiState.update { it.copy(deliveryRecipientName = value) }
    }

    fun setDeliveryRecipientPhone(value: String) {
        _uiState.update { it.copy(deliveryRecipientPhone = value) }
    }

    fun setDeliveryAddress(value: String) {
        _uiState.update {
            it.copy(deliveryAddress = value, deliveryLatitude = null, deliveryLongitude = null)
        }
    }

    fun setPaymentMethod(method: PaymentMethod) {
        _uiState.update {
            if (method in it.availablePaymentMethods) it.copy(paymentMethod = method, errorMessage = null)
            else it.copy(errorMessage = "This payment method is not active for the station")
        }
    }

    fun setChargeToAccount(enabled: Boolean) {
        _uiState.update {
            if (enabled && it.selectedCustomer == null) {
                it.copy(errorMessage = "Select a saved customer before charging this sale to an account")
            } else {
                it.copy(chargeToAccount = enabled, errorMessage = null)
            }
        }
    }

    fun setNotes(notes: String) {
        _uiState.update { it.copy(notes = notes) }
    }

    fun submitWalkInSale(onSuccess: (Long) -> Unit) {
        val state = _uiState.value
        if (state.isSubmitting || !submissionGuard.compareAndSet(false, true)) return
        if (state.deliveryMode == "DELIVERY" && !canSelectWalkInDelivery(
                state.deliveryAccessResolved,
                state.deliveryManagementAllowed
            )
        ) {
            _uiState.update {
                it.copy(errorMessage = "Delivery orders require an active plan with delivery management.")
            }
            submissionGuard.set(false)
            return
        }
        val items = state.selectedItemsList
        if (items.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Please add at least one product") }
            submissionGuard.set(false)
            return
        }
        if (!state.chargeToAccount && state.availablePaymentMethods.isEmpty()) {
            _uiState.update { it.copy(errorMessage = if (!it.isOnline) it.offlinePosStatus.message ?: "Prepare a valid offline inventory lease while online before recording cash." else "Enable a payment method in Settings before completing this sale") }
            submissionGuard.set(false)
            return
        }

        val deliveryMode = if (state.deliveryMode == "DELIVERY") "DELIVERY" else "PICKUP"
        val customerId = state.selectedCustomer?.id ?: 0L
        val customerName = if (deliveryMode == "DELIVERY") {
            state.deliveryRecipientName
        } else {
            state.selectedCustomer?.name ?: "Walk-in Customer"
        }
        val customerPhone = if (deliveryMode == "DELIVERY") {
            state.deliveryRecipientPhone
        } else {
            state.selectedCustomer?.phone ?: ""
        }
        val appliedPaymentMethod = if (state.chargeToAccount) {
            PaymentMethod.CLIENT_ACCOUNT
        } else if (deliveryMode == "DELIVERY" && state.paymentMethod == PaymentMethod.CASH) {
            PaymentMethod.CASH_ON_DELIVERY
        } else {
            state.paymentMethod
        }
        val selectedContainerSize = state.selectedContainerSize.ifBlank {
            items.firstOrNull { it.productType == ProductType.REFILL }?.sizeLabel.orEmpty()
        }
        val fingerprint = WalkInSaleRequestFingerprint(
            stationId = currentStationId,
            customerId = customerId,
            customerName = customerName,
            customerPhone = customerPhone,
            items = items.map { (it.productId ?: 0L) to it.quantity }.sortedBy { it.first },
            paymentMethod = appliedPaymentMethod,
            notes = state.notes.takeIf { it.isNotBlank() },
            deliveryMode = deliveryMode,
            deliveryAddress = state.deliveryAddress,
            deliveryLatitude = state.deliveryLatitude,
            deliveryLongitude = state.deliveryLongitude,
            deliveryFeeCentavos = Math.round(state.deliveryFee * 100.0),
            chargeToAccount = state.chargeToAccount,
            emptyBottlesReturned = state.emptyBottlesReturned,
            containerSize = selectedContainerSize,
            stationContainersIssued = state.stationContainersIssued
        )
        val clientOperationId = submissionIdentity.forRequest(fingerprint, state.clientOperationId)
        val cashReceivedCentavos = state.cashReceivedText.trim().takeIf { it.isNotEmpty() }?.let { text ->
            runCatching { java.math.BigDecimal(text).movePointRight(2).longValueExact() }.getOrNull()
        }
        if (state.cashReceivedText.isNotBlank() && cashReceivedCentavos == null) {
            _uiState.update { it.copy(errorMessage = "Enter cash received to two decimal places.") }
            submissionGuard.set(false)
            return
        }
        _uiState.update { it.copy(isSubmitting = true, errorMessage = null, clientOperationId = clientOperationId) }

        viewModelScope.launch {
            try {
                val result = try {
                    createWalkInOrderUseCase(
                        stationId = currentStationId,
                        customerId = customerId,
                        customerName = customerName,
                        customerPhone = customerPhone,
                        items = items,
                        paymentMethod = state.paymentMethod,
                        notes = state.notes.takeIf { it.isNotBlank() },
                        emptyBottlesReturned = state.emptyBottlesReturned,
                        containerSize = selectedContainerSize,
                        stationContainersIssued = state.stationContainersIssued,
                        deliveryMode = deliveryMode,
                        deliveryAddress = state.deliveryAddress,
                        deliveryLatitude = state.deliveryLatitude,
                        deliveryLongitude = state.deliveryLongitude,
                        deliveryFee = state.deliveryFee,
                        chargeToAccount = state.chargeToAccount,
                        clientOperationId = clientOperationId,
                        cashReceivedCentavos = cashReceivedCentavos
                    )
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }

                result.fold(
                    onSuccess = { orderId ->
                        submissionIdentity.reset()
                        _uiState.update {
                            it.copy(
                                isSubmitting = false,
                                clientOperationId = "owner:walk-in:${UUID.randomUUID()}",
                                createdOrderId = orderId,
                                selectedQuantities = emptyMap(),
                                notes = "",
                                deliveryMode = "PICKUP",
                                deliveryRecipientName = "",
                                deliveryRecipientPhone = "",
                                deliveryAddress = "",
                                deliveryLatitude = null,
                                deliveryLongitude = null,
                                selectedCustomer = null,
                                emptyBottlesReturned = 0,
                                stationContainersIssued = 0,
                                paymentMethod = PaymentMethod.CASH,
                                chargeToAccount = false,
                                cashReceivedText = ""
                            )
                        }
                        refreshOfflineStatus()
                        onSuccess(orderId)
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                isSubmitting = false,
                                errorMessage = err.message ?: "Failed to process walk-in sale"
                            )
                        }
                    }
                )
            } finally {
                if (_uiState.value.isSubmitting) {
                    _uiState.update {
                        it.copy(isSubmitting = false)
                    }
                }
                submissionGuard.set(false)
            }
        }
    }

    fun resetState() {
        submissionIdentity.reset()
        _uiState.update {
            it.copy(
                selectedQuantities = emptyMap(),
                selectedCustomer = null,
                emptyBottlesReturned = 0,
                stationContainersIssued = 0,
                deliveryMode = "PICKUP",
                deliveryRecipientName = "",
                deliveryRecipientPhone = "",
                deliveryAddress = "",
                deliveryLatitude = null,
                deliveryLongitude = null,
                deliveryFee = 0.0,
                paymentMethod = PaymentMethod.CASH,
                chargeToAccount = false,
                cashReceivedText = "",
                notes = "",
                isSubmitting = false,
                clientOperationId = "owner:walk-in:${UUID.randomUUID()}",
                createdOrderId = null,
                errorMessage = null
            )
        }
    }
}
