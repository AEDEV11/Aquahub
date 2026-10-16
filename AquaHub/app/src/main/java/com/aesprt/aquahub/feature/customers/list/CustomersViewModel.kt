package com.aesprt.aquahub.feature.customers.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.CustomerAddress
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.customer.CreateCustomerUseCase
import com.aesprt.aquahub.domain.usecase.customer.ObserveCustomersUseCase
import com.aesprt.aquahub.domain.usecase.customer.SearchCustomersUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import com.aesprt.aquahub.domain.repository.WorkspaceNotProvisionedException

data class CustomersUiState(
    val searchQuery: String = "",
    val customers: List<Customer> = emptyList(),
    val isLoading: Boolean = false,
    val showAddDialog: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class CustomersViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observeCustomersUseCase: ObserveCustomersUseCase,
    private val searchCustomersUseCase: SearchCustomersUseCase,
    private val createCustomerUseCase: CreateCustomerUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(CustomersUiState())
    val uiState = _uiState.asStateFlow()

    private val searchQueryFlow = MutableStateFlow("")
    private var currentStationId: Long = 0L

    init {
        viewModelScope.launch {
            combine(
                activeStationContext.observeActiveStationId(),
                observeCurrentOwnerUseCase(),
                searchQueryFlow
            ) { activeId, owner, query ->
                val stationId = activeId ?: owner?.stationId ?: 0L
                stationId to query
            }.distinctUntilChanged().flatMapLatest { (stationId, query) ->
                currentStationId = stationId
                _uiState.update { it.copy(isLoading = true, searchQuery = query) }
                if (query.isBlank()) {
                    observeCustomersUseCase(stationId)
                } else {
                    searchCustomersUseCase(stationId, query.trim())
                }
            }.collect { list ->
                _uiState.update { it.copy(customers = list, isLoading = false) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        searchQueryFlow.value = query
    }

    fun openAddDialog() = _uiState.update { it.copy(showAddDialog = true) }
    fun closeAddDialog() = _uiState.update { it.copy(showAddDialog = false, errorMessage = null) }

    fun addCustomer(name: String, phone: String, address: CustomerAddress?) {
        viewModelScope.launch {
            val customer = Customer(
                stationId = currentStationId,
                name = name.trim(),
                phone = phone.trim(),
                addresses = address?.let {
                    listOf(
                        it.copy(
                            label = "Delivery",
                            addressLine = it.addressLine.trim(),
                            isDefault = true
                        )
                    )
                }.orEmpty()
            )
            createCustomerUseCase(customer).fold(
                onSuccess = { closeAddDialog() },
                onFailure = { error ->
                    _uiState.update { it.copy(errorMessage = customerErrorMessage(error)) }
                }
            )
        }
    }

    private fun customerErrorMessage(error: Throwable): String {
        if (error is WorkspaceNotProvisionedException) {
            return "This station is not available for your account yet. Refresh your workspace and try again."
        }
        if (error.isConnectivityFailure()) {
            return "Saved offline. It will sync automatically when your connection returns."
        }
        val details = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        if ("42501" in details || "row-level security" in details || "permission" in details || "forbidden" in details) {
            return "You do not have permission to add customers for this station. Refresh your station and try again."
        }
        return "We could not save this customer. Please try again."
    }

    private fun Throwable.isConnectivityFailure(): Boolean =
        this is IOException || this is UnknownHostException || this is ConnectException ||
            this is SocketTimeoutException || this is UnresolvedAddressException ||
            cause?.isConnectivityFailure() == true
}
