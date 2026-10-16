package com.aesprt.aquahub.feature.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.Product
import com.aesprt.aquahub.domain.model.ProductType
import com.aesprt.aquahub.domain.model.ProductPromotion
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.product.*
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.repository.ImageStorageRepository
import com.aesprt.aquahub.domain.usecase.inventory.EnsureContainerSizeUseCase
import com.aesprt.aquahub.domain.usecase.inventory.ObserveContainerInventoryUseCase
import com.aesprt.aquahub.domain.usecase.subscription.CanCreateProductUseCase
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import com.aesprt.aquahub.domain.usecase.subscription.ObserveCurrentSubscriptionUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ProductsUiState(
    val products: List<Product> = emptyList(),
    val availableSizes: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val showAddEditDialog: Boolean = false,
    val editingProduct: Product? = null,
    val canAddProducts: Boolean = true,
    val canManageInventory: Boolean = true,
    val canManagePromotions: Boolean = false,
    val featureRestrictionMessage: String? = null,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProductsViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observeProductsUseCase: ObserveProductsUseCase,
    private val createProductUseCase: CreateProductUseCase,
    private val updateProductUseCase: UpdateProductUseCase,
    private val toggleProductAvailabilityUseCase: ToggleProductAvailabilityUseCase,
    private val updateProductStockUseCase: UpdateProductStockUseCase,
    private val archiveProductUseCase: ArchiveProductUseCase,
    private val canCreateProductUseCase: CanCreateProductUseCase,
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val observeCurrentSubscriptionUseCase: ObserveCurrentSubscriptionUseCase,
    private val observeContainerInventoryUseCase: ObserveContainerInventoryUseCase,
    private val ensureContainerSizeUseCase: EnsureContainerSizeUseCase,
    private val imageStorageRepository: ImageStorageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductsUiState())
    val uiState = _uiState.asStateFlow()

    private var currentStationId: Long = 0L
    private val stagedProductSizes = mutableMapOf<Long, MutableSet<String>>()

    init {
        viewModelScope.launch {
            combine(
                activeStationContext.observeActiveStationId(),
                observeCurrentOwnerUseCase()
            ) { activeId, owner ->
                activeId ?: owner?.stationId ?: 0L
            }.distinctUntilChanged().flatMapLatest { stationId ->
                currentStationId = stationId
                observeCurrentSubscriptionUseCase(stationId).flatMapLatest {
                    val access = canCreateProductUseCase(stationId)
                    val inventoryAccess = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.BASIC_INVENTORY)
                    val containerAccess = checkSubscriptionAccessUseCase(
                        stationId,
                        SubscriptionFeature.BASIC_CONTAINER_TRACKING
                    )
                    val promotionAccess = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.PROMOTIONS)
                    val restriction = when (access) {
                        is SubscriptionAccessResult.Allowed -> null
                        is SubscriptionAccessResult.LimitReached -> "Product limit reached (${access.current}/${access.maximum}). Upgrade your plan to add more."
                        is SubscriptionAccessResult.FeatureNotIncluded -> "Product management is not included in your current plan."
                        is SubscriptionAccessResult.SubscriptionExpired -> "Subscription expired. Renew your plan to manage products."
                        is SubscriptionAccessResult.SubscriptionSuspended -> access.message ?: "Subscription suspended."
                    }
                    _uiState.update {
                        it.copy(
                            canAddProducts = access is SubscriptionAccessResult.Allowed,
                            canManageInventory = inventoryAccess is SubscriptionAccessResult.Allowed,
                            canManagePromotions = promotionAccess is SubscriptionAccessResult.Allowed,
                            featureRestrictionMessage = restriction
                        )
                    }
                    _uiState.update { it.copy(isLoading = true) }
                    combine(
                        observeProductsUseCase(stationId),
                        if (containerAccess is SubscriptionAccessResult.Allowed) {
                            observeContainerInventoryUseCase(stationId)
                        } else {
                            flowOf(emptyList())
                        }
                    ) { products, containers ->
                        Triple(stationId, products, containers)
                    }
                }
            }.collect { (stationId, products, containers) ->
                val sizes = (containers.map { it.sizeLabel } + products.map { it.sizeLabel } +
                    stagedProductSizes[stationId].orEmpty())
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinctBy { it.lowercase() }
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
                _uiState.update {
                    it.copy(products = products, availableSizes = sizes, isLoading = false)
                }
            }
        }
    }

    fun openAddDialog() {
        if (!_uiState.value.canAddProducts) {
            _uiState.update { it.copy(errorMessage = it.featureRestrictionMessage) }
            return
        }
        _uiState.update { it.copy(showAddEditDialog = true, editingProduct = null) }
    }
    fun openEditDialog(product: Product) = _uiState.update { it.copy(showAddEditDialog = true, editingProduct = product) }
    fun closeDialog() = _uiState.update {
        it.copy(showAddEditDialog = false, editingProduct = null, isSaving = false, errorMessage = null)
    }

    fun addSize(sizeLabel: String) {
        viewModelScope.launch {
            val normalized = sizeLabel.trim()
            if (normalized.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Size is required") }
                return@launch
            }
            val containerAccess = checkSubscriptionAccessUseCase(
                currentStationId,
                SubscriptionFeature.BASIC_CONTAINER_TRACKING
            )
            if (containerAccess is SubscriptionAccessResult.Allowed) {
                ensureContainerSizeUseCase(currentStationId, normalized).fold(
                    onSuccess = { _uiState.update { it.copy(errorMessage = null) } },
                    onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
                )
                return@launch
            }

            val productAccess = checkSubscriptionAccessUseCase(
                currentStationId,
                SubscriptionFeature.PRODUCT_MANAGEMENT
            )
            if (productAccess !is SubscriptionAccessResult.Allowed) {
                _uiState.update { it.copy(errorMessage = "Product management is unavailable for your subscription") }
                return@launch
            }
            val stationSizes = stagedProductSizes.getOrPut(currentStationId) { mutableSetOf() }
            stationSizes += normalized
            _uiState.update { state ->
                state.copy(
                    availableSizes = (state.availableSizes + normalized)
                        .distinctBy { it.trim().lowercase() }
                        .sortedWith(String.CASE_INSENSITIVE_ORDER),
                    errorMessage = null
                )
            }
        }
    }

    fun toggleAvailability(product: Product) {
        viewModelScope.launch {
            toggleProductAvailabilityUseCase(product.id, !product.isAvailable).onFailure { error ->
                _uiState.update { it.copy(errorMessage = error.message) }
            }
        }
    }

    fun archiveProduct(product: Product) {
        viewModelScope.launch {
            archiveProductUseCase(product.id).fold(
                onSuccess = { closeDialog() },
                onFailure = { error -> _uiState.update { it.copy(errorMessage = error.message) } }
            )
        }
    }

    fun saveProduct(
        name: String,
        sizeLabel: String,
        type: ProductType,
        price: Double,
        stock: Int,
        isAvailable: Boolean,
        imageSourceUri: String?,
        promotion: ProductPromotion?
    ) {
        viewModelScope.launch {
            if (_uiState.value.availableSizes.none { it.equals(sizeLabel.trim(), ignoreCase = true) }) {
                _uiState.update { it.copy(errorMessage = "Choose a saved size or add a new size first") }
                return@launch
            }
            val editing = _uiState.value.editingProduct
            if (editing == null) {
                val access = canCreateProductUseCase(currentStationId)
                if (access !is SubscriptionAccessResult.Allowed) {
                    _uiState.update { it.copy(errorMessage = "This action is not included in your current subscription plan.") }
                    return@launch
                }
            }
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val uploadedImageUrl = imageSourceUri?.let { uri ->
                imageStorageRepository.uploadProductImage(currentStationId, uri).fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(isSaving = false, errorMessage = error.message ?: "Product image upload failed")
                        }
                        return@launch
                    }
                )
            }
            if (editing != null) {
                val updated = editing.copy(
                    name = name,
                    type = type,
                    sizeLabel = sizeLabel,
                    price = price,
                    stockQuantity = stock,
                    isAvailable = isAvailable,
                    imagePath = uploadedImageUrl ?: editing.imagePath,
                    promotion = promotion
                )
                val stockResult = if (stock != editing.stockQuantity) {
                    updateProductStockUseCase(editing.id, stock)
                } else {
                    Result.success(Unit)
                }
                stockResult.fold(
                    onSuccess = {
                        updateProductUseCase(updated).fold(
                            onSuccess = {
                                if (uploadedImageUrl != null && editing.imagePath != null && editing.imagePath != uploadedImageUrl) {
                                    imageStorageRepository.deleteOwnedImage(editing.imagePath)
                                }
                                closeDialog()
                            },
                            onFailure = { error ->
                                uploadedImageUrl?.let { imageStorageRepository.deleteOwnedImage(it) }
                                _uiState.update { it.copy(isSaving = false, errorMessage = error.message) }
                            }
                        )
                    },
                    onFailure = { error ->
                        uploadedImageUrl?.let { imageStorageRepository.deleteOwnedImage(it) }
                        _uiState.update { it.copy(isSaving = false, errorMessage = error.message) }
                    }
                )
            } else {
                val newProduct = Product(
                    stationId = currentStationId,
                    name = name,
                    type = type,
                    sizeLabel = sizeLabel,
                    price = price,
                    stockQuantity = stock,
                    isAvailable = isAvailable,
                    imagePath = uploadedImageUrl,
                    promotion = promotion
                )
                createProductUseCase(newProduct).fold(
                    onSuccess = { closeDialog() },
                    onFailure = { error ->
                        uploadedImageUrl?.let { imageStorageRepository.deleteOwnedImage(it) }
                        _uiState.update { it.copy(isSaving = false, errorMessage = error.message) }
                    }
                )
            }
        }
    }
}
