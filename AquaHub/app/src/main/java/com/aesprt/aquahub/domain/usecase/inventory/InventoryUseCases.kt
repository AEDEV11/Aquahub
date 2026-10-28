package com.aesprt.aquahub.domain.usecase.inventory

import com.aesprt.aquahub.domain.model.Container
import com.aesprt.aquahub.domain.model.ContainerTransaction
import com.aesprt.aquahub.domain.model.ContainerTransactionType
import com.aesprt.aquahub.domain.repository.InventoryRepository
import com.aesprt.aquahub.domain.common.LocalTransactionRunner
import kotlinx.coroutines.flow.Flow
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.first
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase

class ObserveContainerInventoryUseCase(private val inventoryRepository: InventoryRepository) {
    operator fun invoke(stationId: Long): Flow<List<Container>> =
        inventoryRepository.observeContainers(stationId)
}

class ObserveContainerTransactionsUseCase(private val inventoryRepository: InventoryRepository) {
    operator fun invoke(stationId: Long): Flow<List<ContainerTransaction>> =
        inventoryRepository.observeContainerTransactions(stationId)
}

class ObservePagedContainerTransactionsUseCase(private val inventoryRepository: InventoryRepository) {
    operator fun invoke(stationId: Long): Flow<PagingData<ContainerTransaction>> = Pager(
        config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 5, enablePlaceholders = false),
        pagingSourceFactory = { inventoryRepository.pagingContainerTransactions(stationId) }
    ).flow
}

/**
 * Registers a station-owned bottle size without inventing stock. Saleable
 * product sizes are product metadata and do not require container tracking.
 */
class EnsureContainerSizeUseCase(
    private val inventoryRepository: InventoryRepository,
    private val transactionRunner: LocalTransactionRunner,
    private val access: CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(stationId: Long, sizeLabel: String): Result<Unit> = transactionRunner.run {
        val normalized = sizeLabel.trim()
        if (stationId <= 0) return@run Result.failure(IllegalArgumentException("A valid station is required"))
        if (normalized.isBlank()) return@run Result.failure(IllegalArgumentException("Size is required"))
        if (access(stationId, SubscriptionFeature.BASIC_CONTAINER_TRACKING) !is SubscriptionAccessResult.Allowed) {
            return@run Result.failure(IllegalStateException("Station-owned container tracking is unavailable for your subscription"))
        }
        if (inventoryRepository.getContainerBySizeLabel(stationId, normalized) == null) {
            inventoryRepository.upsertContainer(
                Container(
                    stationId = stationId,
                    sizeLabel = normalized,
                    totalOwned = 0,
                    available = 0,
                    withCustomers = 0,
                    damaged = 0,
                    lost = 0,
                    depositPrice = 200.0
                )
            )
        }
        Result.success(Unit)
    }
}

class ObserveCustomerContainerTransactionsUseCase(private val inventoryRepository: InventoryRepository) {
    operator fun invoke(customerId: Long): Flow<List<ContainerTransaction>> =
        inventoryRepository.observeCustomerTransactions(customerId)
}

class RecordContainerDeliveryUseCase(
    private val inventoryRepository: InventoryRepository,
    private val transactionRunner: LocalTransactionRunner,
    private val access: CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(
        stationId: Long,
        customerId: Long?,
        customerName: String?,
        orderId: Long?,
        sizeLabel: String,
        quantity: Int
    ) = transactionRunner.run {
        require(stationId > 0) { "A valid station is required" }
        require(sizeLabel.isNotBlank() && quantity > 0) { "Size and a positive quantity are required" }
        check(access(stationId, SubscriptionFeature.BASIC_CONTAINER_TRACKING) is SubscriptionAccessResult.Allowed) {
            "Station-owned container tracking is unavailable for your subscription"
        }
        require(customerId != null && customerId > 0) { "Select the customer borrowing station containers" }
        val container = inventoryRepository.getContainerBySizeLabel(stationId, sizeLabel)
        require(container != null) {
            "No $sizeLabel station-bottle inventory is configured for this station"
        }
        if (orderId != null && orderId > 0) {
            val alreadyRecorded = inventoryRepository.observeCustomerTransactions(customerId).first()
                .any {
                    it.stationId == stationId &&
                        it.orderId == orderId &&
                        it.type == ContainerTransactionType.DELIVERED
                }
            require(!alreadyRecorded) {
                "A station bottle loan is already recorded for this order"
            }
        }
        require(container.available >= quantity) {
            "Only ${container.available} $sizeLabel station bottle(s) are available"
        }
        inventoryRepository.insertTransaction(
            ContainerTransaction(
                stationId = stationId,
                customerId = customerId,
                customerName = customerName,
                orderId = orderId,
                type = ContainerTransactionType.DELIVERED,
                quantity = quantity,
                containerSize = sizeLabel,
                createdAt = System.currentTimeMillis()
            )
        )
        inventoryRepository.adjustContainer(stationId, sizeLabel, -quantity, InventoryRepository.ContainerField.AVAILABLE)
        inventoryRepository.adjustContainer(stationId, sizeLabel, quantity, InventoryRepository.ContainerField.WITH_CUSTOMERS)
    }
}

class RecordContainerReturnUseCase(
    private val inventoryRepository: InventoryRepository,
    private val transactionRunner: LocalTransactionRunner,
    private val access: CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(
        stationId: Long,
        customerId: Long,
        customerName: String,
        sizeLabel: String,
        quantity: Int,
        notes: String? = null
    ) = transactionRunner.run {
        require(stationId > 0) { "A valid station is required" }
        require(sizeLabel.isNotBlank() && quantity > 0) { "Size and a positive quantity are required" }
        check(access(stationId, SubscriptionFeature.BASIC_CONTAINER_TRACKING) is SubscriptionAccessResult.Allowed) {
            "Station-owned container tracking is unavailable for your subscription"
        }
        require(customerId > 0) { "Select the customer returning station containers" }
        val balance = inventoryRepository.observeCustomerTransactions(customerId).first()
            .filter { it.stationId == stationId && it.containerSize == sizeLabel }
            .sumOf { when (it.type) {
                ContainerTransactionType.DELIVERED -> it.quantity.toLong()
                ContainerTransactionType.RETURNED, ContainerTransactionType.LOST -> -it.quantity.toLong()
                else -> 0L
            } }
        require(quantity <= balance) { "Returns exceed this customer's borrowed containers" }
        inventoryRepository.insertTransaction(
            ContainerTransaction(
                stationId = stationId,
                customerId = customerId.takeIf { it > 0 },
                customerName = customerName,
                type = ContainerTransactionType.RETURNED,
                quantity = quantity,
                containerSize = sizeLabel,
                notes = notes,
                createdAt = System.currentTimeMillis()
            )
        )
        inventoryRepository.adjustContainer(stationId, sizeLabel, quantity, InventoryRepository.ContainerField.AVAILABLE)
        if (customerId > 0) {
            inventoryRepository.adjustContainer(stationId, sizeLabel, -quantity, InventoryRepository.ContainerField.WITH_CUSTOMERS)
        }
    }
}

class AdjustContainerInventoryUseCase(
    private val inventoryRepository: InventoryRepository,
    private val transactionRunner: LocalTransactionRunner,
    private val access: CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(
        stationId: Long,
        sizeLabel: String,
        type: ContainerTransactionType,
        quantity: Int,
        notes: String? = null
    ) = transactionRunner.run {
        require(stationId > 0) { "A valid station is required" }
        require(sizeLabel.isNotBlank() && quantity > 0) { "Size and a positive quantity are required" }
        check(access(stationId, SubscriptionFeature.BASIC_CONTAINER_TRACKING) is SubscriptionAccessResult.Allowed) {
            "Station-owned container tracking is unavailable for your subscription"
        }
        require(type in setOf(ContainerTransactionType.ADJUSTMENT, ContainerTransactionType.DAMAGED, ContainerTransactionType.LOST)) {
            "Use the customer delivery or return workflow for this movement"
        }
        inventoryRepository.insertTransaction(
            ContainerTransaction(
                stationId = stationId,
                type = type,
                quantity = quantity,
                containerSize = sizeLabel,
                notes = notes,
                createdAt = System.currentTimeMillis()
            )
        )
        when (type) {
            ContainerTransactionType.DAMAGED -> {
                inventoryRepository.adjustContainer(stationId, sizeLabel, -quantity, InventoryRepository.ContainerField.AVAILABLE)
                inventoryRepository.adjustContainer(stationId, sizeLabel, quantity, InventoryRepository.ContainerField.DAMAGED)
            }
            ContainerTransactionType.LOST -> {
                inventoryRepository.adjustContainer(stationId, sizeLabel, -quantity, InventoryRepository.ContainerField.AVAILABLE)
                inventoryRepository.adjustContainer(stationId, sizeLabel, quantity, InventoryRepository.ContainerField.LOST)
            }
            ContainerTransactionType.ADJUSTMENT ->
                inventoryRepository.adjustContainer(stationId, sizeLabel, quantity, InventoryRepository.ContainerField.AVAILABLE)
            else -> {}
        }
    }
}
