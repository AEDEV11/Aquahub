package com.aesprt.aquahub.feature.customers.detail

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.export.ReportExportResult
import com.aesprt.aquahub.domain.model.ContainerTransaction
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.CustomerAccountSnapshot
import com.aesprt.aquahub.domain.model.CustomerContainerBalance
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.usecase.customer.ExportCustomerStatementUseCase
import com.aesprt.aquahub.domain.usecase.customer.GetCustomerContainerBalanceUseCase
import com.aesprt.aquahub.domain.usecase.customer.GetCustomerDetailUseCase
import com.aesprt.aquahub.domain.usecase.customer.ObserveCustomerAccountUseCase
import com.aesprt.aquahub.domain.usecase.customer.RefreshCustomerOrderHistoryUseCase
import com.aesprt.aquahub.domain.usecase.customer.RecordCustomerPaymentUseCase
import com.aesprt.aquahub.domain.usecase.inventory.ObserveContainerTransactionsUseCase
import com.aesprt.aquahub.domain.usecase.inventory.RecordContainerReturnUseCase
import com.aesprt.aquahub.domain.usecase.station.GetStationUseCase
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CustomerDetailUiState(
    val isLoading: Boolean = true,
    val customer: Customer? = null,
    val balances: List<CustomerContainerBalance> = emptyList(),
    val orderHistory: List<Order> = emptyList(),
    val transactions: List<ContainerTransaction> = emptyList(),
    val accountSnapshot: CustomerAccountSnapshot? = null,
    val accountFeatureAvailable: Boolean = false,
    val stationName: String = "Water Station",
    val availableReceiptMethods: List<PaymentMethod> = listOf(PaymentMethod.CASH),
    val showReturnDialog: Boolean = false,
    val paymentOrderId: Long? = null,
    val paymentRemaining: Double = 0.0,
    val isSavingPayment: Boolean = false,
    val isExportingStatement: Boolean = false,
    val statementExportResult: ReportExportResult? = null,
    val successMessage: String? = null,
    val errorMessage: String? = null
)

class CustomerDetailViewModel(
    private val getCustomerDetailUseCase: GetCustomerDetailUseCase,
    private val getCustomerContainerBalanceUseCase: GetCustomerContainerBalanceUseCase,
    private val recordContainerReturnUseCase: RecordContainerReturnUseCase,
    private val observeContainerTransactionsUseCase: ObserveContainerTransactionsUseCase,
    private val observeCustomerAccountUseCase: ObserveCustomerAccountUseCase,
    private val refreshCustomerOrderHistoryUseCase: RefreshCustomerOrderHistoryUseCase,
    private val recordCustomerPaymentUseCase: RecordCustomerPaymentUseCase,
    private val exportCustomerStatementUseCase: ExportCustomerStatementUseCase,
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val getStationUseCase: GetStationUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(CustomerDetailUiState())
    val uiState = _uiState.asStateFlow()
    private var accountJob: Job? = null

    fun loadCustomer(customerId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val customer = getCustomerDetailUseCase(customerId)
            val balance = getCustomerContainerBalanceUseCase(customerId)
            val accountAvailable = customer?.let {
                checkSubscriptionAccessUseCase(it.stationId, SubscriptionFeature.ACCOUNTS_RECEIVABLE) is SubscriptionAccessResult.Allowed
            } ?: false
            val station = customer?.let { getStationUseCase(it.stationId) }
            val stationName = station?.name ?: "Water Station"
            val receiptMethods = buildList {
                if (station?.cashPaymentEnabled != false) add(PaymentMethod.CASH)
                if (station?.gcashPaymentEnabled == true) add(PaymentMethod.GCASH)
                if (station?.mayaPaymentEnabled == true) add(PaymentMethod.MAYA)
            }
            _uiState.update {
                it.copy(
                    customer = customer,
                    balances = balance,
                    accountFeatureAvailable = accountAvailable,
                    stationName = stationName,
                    availableReceiptMethods = receiptMethods,
                    isLoading = false
                )
            }
            accountJob?.cancel()
            if (customer != null) {
                accountJob = viewModelScope.launch {
                    launch {
                        observeCustomerAccountUseCase(customer).collect { snapshot ->
                            _uiState.update {
                                it.copy(
                                    accountSnapshot = snapshot,
                                    orderHistory = snapshot.allOrders
                                )
                            }
                        }
                    }
                    launch {
                        refreshCustomerOrderHistoryUseCase(customer.stationId).onFailure {
                            _uiState.update { state ->
                                state.copy(errorMessage = "Couldn't refresh online order history. Showing saved history.")
                            }
                        }
                    }
                }
            }
        }
    }

    fun openReturnDialog() = _uiState.update { it.copy(showReturnDialog = true) }
    fun closeReturnDialog() = _uiState.update { it.copy(showReturnDialog = false) }

    fun openPaymentDialog(orderId: Long) {
        val entry = _uiState.value.accountSnapshot?.entries?.firstOrNull { it.order.id == orderId } ?: return
        if (entry.balance <= 0.005) return
        _uiState.update { it.copy(paymentOrderId = orderId, paymentRemaining = entry.balance, errorMessage = null) }
    }

    fun closePaymentDialog() = _uiState.update { it.copy(paymentOrderId = null, paymentRemaining = 0.0) }

    fun recordPayment(amount: Double, method: PaymentMethod, referenceNumber: String?) {
        val orderId = _uiState.value.paymentOrderId ?: return
        val remainingBeforePayment = _uiState.value.paymentRemaining
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingPayment = true, errorMessage = null) }
            recordCustomerPaymentUseCase(orderId, amount, method, referenceNumber).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isSavingPayment = false,
                            paymentOrderId = null,
                            paymentRemaining = 0.0,
                            successMessage = if (amount + 0.005 >= remainingBeforePayment) {
                                "Payment recorded • Account fully paid"
                            } else {
                                "Partial payment recorded • Balance remains"
                            }
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSavingPayment = false, errorMessage = error.message) }
                }
            )
        }
    }

    fun exportStatement(context: Context) {
        val snapshot = _uiState.value.accountSnapshot ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingStatement = true, errorMessage = null) }
            exportCustomerStatementUseCase(
                context = context,
                stationId = snapshot.customer.stationId,
                stationName = _uiState.value.stationName,
                snapshot = snapshot
            ).fold(
                onSuccess = { result ->
                    _uiState.update { it.copy(isExportingStatement = false, statementExportResult = result) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isExportingStatement = false, errorMessage = error.message) }
                }
            )
        }
    }

    fun consumeStatementExport() = _uiState.update { it.copy(statementExportResult = null) }
    fun clearMessage() = _uiState.update { it.copy(successMessage = null, errorMessage = null) }

    fun returnContainers(sizeLabel: String, quantity: Int, notes: String? = null) {
        val customer = _uiState.value.customer ?: return
        viewModelScope.launch {
            recordContainerReturnUseCase(
                stationId = customer.stationId,
                customerId = customer.id,
                customerName = customer.name,
                sizeLabel = sizeLabel,
                quantity = quantity,
                notes = notes
            )
            closeReturnDialog()
            loadCustomer(customer.id)
        }
    }
}
