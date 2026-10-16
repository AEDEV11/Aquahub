package com.aesprt.aquahub.domain.usecase.product

import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.domain.model.Product
import com.aesprt.aquahub.domain.repository.ProductRepository
import com.aesprt.aquahub.domain.usecase.subscription.CanCreateProductUseCase
import kotlinx.coroutines.flow.Flow
import com.aesprt.aquahub.domain.model.PromotionType
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import com.aesprt.aquahub.domain.repository.WorkspaceNotProvisionedException

class ObserveProductsUseCase(private val productRepository: ProductRepository) {
    operator fun invoke(stationId: Long): Flow<List<Product>> =
        productRepository.observeProducts(stationId)
}

class CreateProductUseCase(
    private val productRepository: ProductRepository,
    private val canCreateProductUseCase: CanCreateProductUseCase,
    private val transactionRunner: com.aesprt.aquahub.domain.common.LocalTransactionRunner,
    private val access: CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(product: Product): Result<Long> = transactionRunner.run {
        if (product.name.isBlank()) return@run Result.failure(AppError.ValidationError("Product name is required"))
        if (!product.price.isFinite() || product.price <= 0) return@run Result.failure(AppError.ValidationError("Price must be greater than 0"))
        if (product.sizeLabel.isBlank()) return@run Result.failure(AppError.ValidationError("Size label is required"))
        if (canCreateProductUseCase(product.stationId) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed) {
            return@run Result.failure(AppError.ValidationError("Product management is not available for this subscription"))
        }
        if (product.stockQuantity < 0) return@run Result.failure(AppError.ValidationError("Stock cannot be negative"))
        validatePromotion(product, access)?.let { return@run Result.failure(it) }
        return@run runCatching { productRepository.insertProduct(product) }
            .fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    if (it is WorkspaceNotProvisionedException) {
                        Result.failure(AppError.ValidationError("We're finishing your AquaHub workspace setup. Please retry in a moment."))
                    } else {
                        Result.failure(it)
                    }
                }
            )
    }
}

class UpdateProductUseCase(private val productRepository: ProductRepository, private val access: com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(product: Product): Result<Unit> {
        if (access(product.stationId, com.aesprt.aquahub.domain.model.SubscriptionFeature.PRODUCT_MANAGEMENT) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed)
            return Result.failure(AppError.ValidationError("This feature is unavailable for your subscription"))
        if (product.name.isBlank()) return Result.failure(AppError.ValidationError("Product name is required"))
        if (!product.price.isFinite() || product.price <= 0) return Result.failure(AppError.ValidationError("Price must be greater than 0"))
        if (product.sizeLabel.isBlank()) return Result.failure(AppError.ValidationError("Size label is required"))
        if (product.stockQuantity < 0) return Result.failure(AppError.ValidationError("Stock cannot be negative"))
        validatePromotion(product, access)?.let { return Result.failure(it) }
        productRepository.updateProduct(product)
        return Result.success(Unit)
    }
}

private suspend fun validatePromotion(
    product: Product,
    access: CheckSubscriptionAccessUseCase
): AppError.ValidationError? {
    val promotion = product.promotion ?: return null
    if (access(product.stationId, SubscriptionFeature.PROMOTIONS) !is SubscriptionAccessResult.Allowed) {
        return AppError.ValidationError("Promotions are available on Business and Pro plans")
    }
    if (promotion.label.trim().length !in 2..80) {
        return AppError.ValidationError("Promotion name must be 2 to 80 characters")
    }
    if (promotion.minimumQuantity !in 1..99) {
        return AppError.ValidationError("Promotion minimum quantity must be between 1 and 99")
    }
    if (promotion.endsAt != null && promotion.endsAt <= promotion.startsAt) {
        return AppError.ValidationError("Promotion end time must be after its start time")
    }
    when (promotion.type) {
        PromotionType.PERCENTAGE, PromotionType.QUANTITY_BREAK -> {
            if (promotion.percentBps == null || promotion.percentBps !in 100..9_000) {
                return AppError.ValidationError("Promotion discount must be between 1% and 90%")
            }
        }
        PromotionType.FIXED_PRICE -> {
            val promoPrice = promotion.promotionalPrice
            if (promoPrice == null || !promoPrice.isFinite() || promoPrice <= 0 || promoPrice >= product.price) {
                return AppError.ValidationError("Promotional price must be lower than the regular price")
            }
        }
    }
    return null
}

class ToggleProductAvailabilityUseCase(
    private val productRepository: ProductRepository,
    private val access: com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(productId: Long, isAvailable: Boolean): Result<Unit> {
        val product = productRepository.getProductById(productId)
            ?: return Result.failure(AppError.NotFoundError("Product", productId))
        if (access(product.stationId, com.aesprt.aquahub.domain.model.SubscriptionFeature.PRODUCT_MANAGEMENT) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed)
            return Result.failure(AppError.ValidationError("Product management is unavailable for your subscription"))
        productRepository.toggleAvailability(productId, isAvailable)
        return Result.success(Unit)
    }
}

class UpdateProductStockUseCase(
    private val productRepository: ProductRepository,
    private val access: com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(productId: Long, newStock: Int): Result<Unit> {
        if (newStock < 0) return Result.failure(AppError.ValidationError("Stock cannot be negative"))
        val product = productRepository.getProductById(productId)
            ?: return Result.failure(AppError.NotFoundError("Product", productId))
        if (access(product.stationId, com.aesprt.aquahub.domain.model.SubscriptionFeature.BASIC_INVENTORY) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed)
            return Result.failure(AppError.ValidationError("Inventory is unavailable for your subscription"))
        return runCatching { productRepository.updateStock(productId, newStock) }
            .map { Unit }
    }
}

class ArchiveProductUseCase(
    private val productRepository: ProductRepository,
    private val access: com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(productId: Long): Result<Unit> {
        val product = productRepository.getProductById(productId)
            ?: return Result.failure(AppError.NotFoundError("Product", productId))
        if (access(product.stationId, com.aesprt.aquahub.domain.model.SubscriptionFeature.PRODUCT_MANAGEMENT) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed) {
            return Result.failure(AppError.ValidationError("Product management is unavailable for your subscription"))
        }
        productRepository.archive(productId)
        return Result.success(Unit)
    }
}
