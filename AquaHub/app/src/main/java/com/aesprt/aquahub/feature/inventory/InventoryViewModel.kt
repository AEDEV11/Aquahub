package com.aesprt.aquahub.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.Container
import com.aesprt.aquahub.domain.model.ContainerTransaction
import com.aesprt.aquahub.domain.model.ContainerTransactionType
import com.aesprt.aquahub.domain.model.ProductType
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.inventory.AdjustContainerInventoryUseCase
import com.aesprt.aquahub.domain.usecase.inventory.ObserveContainerInventoryUseCase
import com.aesprt.aquahub.domain.usecase.inventory.ObservePagedContainerTransactionsUseCase
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import com.aesprt.aquahub.domain.usecase.subscription.ObserveCurrentSubscriptionUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.paging.PagingData
import androidx.paging.cachedIn

data class InventoryUiState(
    val products: List<com.aesprt.aquahub.domain.model.Product> = emptyList(),
    val containers: List<Container> = emptyList(),
    val availableSizes: List<String> = emptyList(),
    val totalOwned: Int = 0,
    val inCirculation: Int = 0,
    val available: Int = 0,
    val damagedOrLost: Int = 0,
    val canTrackContainers: Boolean = false,
    val isLoading: Boolean = true,
    val showAdjustDialog: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observeContainerInventoryUseCase: ObserveContainerInventoryUseCase,
    private val observePagedContainerTransactionsUseCase: ObservePagedContainerTransactionsUseCase,
    private val adjustContainerInventoryUseCase: AdjustContainerInventoryUseCase,
    private val observeProductsUseCase: com.aesprt.aquahub.domain.usecase.product.ObserveProductsUseCase,
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val observeCurrentSubscriptionUseCase: ObserveCurrentSubscriptionUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(InventoryUiState())
    val uiState = _uiState.asStateFlow()

    private var currentStationId: Long = 0L

    val transactions: Flow<PagingData<ContainerTransaction>> = combine(
        activeStationContext.observeActiveStationId(),
        observeCurrentOwnerUseCase()
    ) { activeId, owner -> activeId ?: owner?.stationId ?: 0L }
        .distinctUntilChanged()
        .flatMapLatest { stationId ->
            observeCurrentSubscriptionUseCase(stationId).flatMapLatest {
                flow {
                    val canTrack = runCatching {
                        checkSubscriptionAccessUseCase(
                            stationId,
                            SubscriptionFeature.BASIC_CONTAINER_TRACKING
                        ) is SubscriptionAccessResult.Allowed
                    }.getOrDefault(false)
                    emitAll(
                        if (canTrack) observePagedContainerTransactionsUseCase(stationId)
                        else flowOf(PagingData.empty())
                    )
                }
            }
        }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            combine(
                activeStationContext.observeActiveStationId(),
                observeCurrentOwnerUseCase()
            ) { activeId, owner ->
                activeId ?: owner?.stationId ?: 0L
            }.distinctUntilChanged().collectLatest { stationId ->
                currentStationId = stationId
                observeCurrentSubscriptionUseCase(stationId).collectLatest {
                    val canTrackContainers = runCatching {
                        checkSubscriptionAccessUseCase(
                            stationId,
                            SubscriptionFeature.BASIC_CONTAINER_TRACKING
                        ) is SubscriptionAccessResult.Allowed
                    }.getOrDefault(false)
                    _uiState.update {
                        InventoryUiState(isLoading = true, canTrackContainers = canTrackContainers)
                    }

                    combine(
                        observeProductsUseCase(stationId),
                        if (canTrackContainers) observeContainerInventoryUseCase(stationId)
                        else flowOf(emptyList())
                    ) { products, containers ->
                        val total = containers.sumOf { it.totalOwned }
                        val circulation = containers.sumOf { it.withCustomers }
                        val available = containers.sumOf { it.available }
                        val damagedOrLost = containers.sumOf { it.damaged + it.lost }
                        val sizes = (containers.map { it.sizeLabel } + products
                            .filter { it.type == ProductType.REFILL || it.type == ProductType.NEW_CONTAINER }
                            .map { it.sizeLabel })
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .distinctBy { it.lowercase() }
                            .sortedWith(String.CASE_INSENSITIVE_ORDER)
                        InventoryUiState(
                            products = products,
                            containers = containers,
                            availableSizes = sizes,
                            totalOwned = total,
                            inCirculation = circulation,
                            available = available,
                            damagedOrLost = damagedOrLost,
                            canTrackContainers = canTrackContainers,
                            isLoading = false
                        )
                    }.collect { snapshot ->
                        _uiState.value = snapshot.copy(
                            showAdjustDialog = _uiState.value.showAdjustDialog,
                            errorMessage = _uiState.value.errorMessage
                        )
                    }
                }
            }
        }
    }

    fun openAdjustDialog() = _uiState.update { it.copy(showAdjustDialog = true) }
    fun closeAdjustDialog() = _uiState.update { it.copy(showAdjustDialog = false, errorMessage = null) }

    fun adjustInventory(sizeLabel: String, type: ContainerTransactionType, quantity: Int, notes: String?) {
        if (sizeLabel.isBlank() || quantity <= 0) {
            _uiState.update { it.copy(errorMessage = "Choose a size and enter a quantity greater than zero") }
            return
        }
        viewModelScope.launch {
            try {
            adjustContainerInventoryUseCase(
                stationId = currentStationId,
                sizeLabel = sizeLabel,
                type = type,
                quantity = quantity,
                notes = notes
            )
            closeAdjustDialog()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Inventory adjustment failed") }
            }
        }
    }
}
