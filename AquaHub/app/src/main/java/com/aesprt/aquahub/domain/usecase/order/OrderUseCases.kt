package com.aesprt.aquahub.domain.usecase.order

import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderFilter
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.domain.repository.CustomerRepository
import com.aesprt.aquahub.domain.repository.InventoryRepository
import com.aesprt.aquahub.domain.repository.OrderRepository
import com.aesprt.aquahub.domain.repository.ProductRepository
import com.aesprt.aquahub.domain.usecase.subscription.CanCreateOrderUseCase
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.unitPriceFor
import com.aesprt.aquahub.data.supabase.order.SupabaseOrderDataSource
import com.aesprt.aquahub.data.supabase.payment.SupabasePaymentDataSource
import com.aesprt.aquahub.domain.repository.PaymentCache
import com.aesprt.aquahub.domain.repository.OfflinePosRepository
import com.aesprt.aquahub.domain.model.OfflineCashSaleRequest
import com.aesprt.aquahub.domain.model.OfflinePosReservationItem
import com.aesprt.aquahub.sync.ConnectivityMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import com.aesprt.aquahub.domain.common.LocalTransactionRunner
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData

class ObserveOrdersUseCase(private val orderRepository: OrderRepository) {
    operator fun invoke(stationId: Long): Flow<List<Order>> =
        orderRepository.observeOrders(stationId)
}

class ObserveFilteredOrdersUseCase(private val orderRepository: OrderRepository) {
    operator fun invoke(stationId: Long, filter: OrderFilter, riderId: Long? = null): Flow<List<Order>> {
        if (filter == OrderFilter.ALL) return riderId?.let {
            orderRepository.observeOrdersForRider(stationId, it)
        } ?: orderRepository.observeOrders(stationId)
        val statuses = OrderStatus.entries.filter { filter.matchesStatus(it) }
        return riderId?.let {
            orderRepository.observeOrdersForRiderByStatuses(stationId, it, statuses)
        } ?: orderRepository.observeOrdersByStatuses(stationId, statuses)
    }
}

class ObservePagedOrdersUseCase(private val orderRepository: OrderRepository) {
    operator fun invoke(stationId: Long, filter: OrderFilter, riderId: Long? = null): Flow<PagingData<Order>> {
        val statuses = if (filter == OrderFilter.ALL) {
            OrderStatus.entries
        } else {
            OrderStatus.entries.filter(filter::matchesStatus)
        }
        return Pager(
            config = PagingConfig(pageSize = 20, initialLoadSize = 20, prefetchDistance = 5, enablePlaceholders = false),
            pagingSourceFactory = { orderRepository.pagingOrders(stationId, riderId, statuses) }
        ).flow
    }
}

class GetOrderDetailUseCase(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(orderId: Long): Order? = orderRepository.getOrderById(orderId)
}

class ObserveRecentOrdersUseCase(private val orderRepository: OrderRepository) {
    operator fun invoke(stationId: Long, limit: Int = 10): Flow<List<Order>> =
        orderRepository.observeRecentOrders(stationId, limit)
}

class ObserveUncompletedOrdersCountUseCase(private val orderRepository: OrderRepository) {
    operator fun invoke(stationId: Long): Flow<Int> =
        orderRepository.observeUncompletedOrdersCount(stationId)
}

private suspend fun validateAndTransition(
    orderRepository: OrderRepository,
    orderId: Long,
    newStatus: OrderStatus,
    access: CheckSubscriptionAccessUseCase,
    timestamp: Long = System.currentTimeMillis()
): Result<Unit> {
    val order = orderRepository.getOrderById(orderId)
        ?: return Result.failure(AppError.NotFoundError("Order", orderId))
    val requiredFeature = if (newStatus in setOf(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED))
        SubscriptionFeature.DELIVERY_MANAGEMENT else SubscriptionFeature.ORDER_MANAGEMENT
    if (access(order.stationId, requiredFeature) !is SubscriptionAccessResult.Allowed)
        return Result.failure(AppError.ValidationError("This action is unavailable for your subscription"))
    if (!order.orderStatus.canTransitionTo(newStatus)) {
        return Result.failure(
            AppError.InvalidOrderTransitionError(
                order.orderStatus.displayName(),
                newStatus.displayName()
            )
        )
    }
    orderRepository.updateOrderStatus(orderId, newStatus, timestamp)
    return Result.success(Unit)
}

class AcceptOrderUseCase(private val orderRepository: OrderRepository, private val access: CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(orderId: Long): Result<Unit> =
        validateAndTransition(orderRepository, orderId, OrderStatus.ACCEPTED, access)
}

class RejectOrderUseCase(private val orderRepository: OrderRepository, private val access: CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(orderId: Long): Result<Unit> =
        validateAndTransition(orderRepository, orderId, OrderStatus.REJECTED, access)
}

class StartPreparingOrderUseCase(private val orderRepository: OrderRepository, private val access: CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(orderId: Long): Result<Unit> =
        validateAndTransition(orderRepository, orderId, OrderStatus.PREPARING, access)
}

class MarkOrderReadyUseCase(private val orderRepository: OrderRepository, private val access: CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(orderId: Long): Result<Unit> =
        validateAndTransition(orderRepository, orderId, OrderStatus.READY_FOR_PICKUP, access)
}

class MarkOutForDeliveryUseCase(private val orderRepository: OrderRepository, private val access: CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(orderId: Long): Result<Unit> =
        validateAndTransition(orderRepository, orderId, OrderStatus.OUT_FOR_DELIVERY, access)
}

class MarkDeliveredUseCase(private val orderRepository: OrderRepository, private val access: CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(orderId: Long): Result<Unit> =
        validateAndTransition(orderRepository, orderId, OrderStatus.DELIVERED, access)
}

class CompleteOrderUseCase(private val orderRepository: OrderRepository, private val access: CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(orderId: Long): Result<Unit> =
        validateAndTransition(orderRepository, orderId, OrderStatus.COMPLETED, access)
}

class CancelOrderUseCase(private val orderRepository: OrderRepository, private val access: CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(orderId: Long, reason: String): Result<Unit> {
        val order = orderRepository.getOrderById(orderId)
            ?: return Result.failure(AppError.NotFoundError("Order", orderId))
        val normalizedReason = reason.trim().take(300)
        if (normalizedReason.isBlank()) {
            return Result.failure(AppError.ValidationError("Enter why this order is being cancelled"))
        }
        if (access(order.stationId, SubscriptionFeature.ORDER_MANAGEMENT) !is SubscriptionAccessResult.Allowed)
            return Result.failure(AppError.ValidationError("This action is unavailable for your subscription"))
        val cancellableStatuses = setOf(
            OrderStatus.PENDING,
            OrderStatus.ACCEPTED,
            OrderStatus.PREPARING,
            OrderStatus.READY_FOR_PICKUP,
            OrderStatus.RIDER_ASSIGNED,
            OrderStatus.OUT_FOR_DELIVERY
        )
        if (order.orderStatus !in cancellableStatuses) {
            return Result.failure(
                AppError.InvalidOrderTransitionError(order.orderStatus.displayName(), "Cancelled")
            )
        }
        return try {
            orderRepository.cancelOrder(orderId, System.currentTimeMillis(), normalizedReason)
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}

class AssignRiderToOrderUseCase(
    private val orderRepository: OrderRepository,
    private val access: CheckSubscriptionAccessUseCase,
    private val riderRepository: com.aesprt.aquahub.domain.repository.RiderRepository,
    private val checkLimit: com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionLimitUseCase
) {
    suspend operator fun invoke(orderId: Long, riderId: Long, riderName: String): Result<Unit> {
        val order = orderRepository.getOrderById(orderId)
            ?: return Result.failure(AppError.NotFoundError("Order", orderId))
        if (access(order.stationId, SubscriptionFeature.DELIVERY_MANAGEMENT) !is SubscriptionAccessResult.Allowed)
            return Result.failure(AppError.ValidationError("This action is unavailable for your subscription"))
        val riders = riderRepository.observeRiders(order.stationId).firstOrNull().orEmpty().sortedBy { it.id }
        val riderIndex = riders.indexOfFirst { it.id == riderId }
        val rider = riders.getOrNull(riderIndex)
        if (rider == null || !rider.isActive || !rider.isAvailable ||
            checkLimit(order.stationId, com.aesprt.aquahub.domain.model.SubscriptionLimit.RIDER_COUNT, riderIndex.toLong()) !is SubscriptionAccessResult.Allowed) {
            return Result.failure(AppError.ValidationError("Rider is unavailable or exceeds the subscription rider limit"))
        }
        if (!order.orderStatus.canTransitionTo(OrderStatus.RIDER_ASSIGNED)) {
            return Result.failure(
                AppError.InvalidOrderTransitionError(order.orderStatus.displayName(), "Rider Assigned")
            )
        }
        orderRepository.assignRider(orderId, riderId, rider.name)
        return Result.success(Unit)
    }
}

internal fun Order.withCommittedSupabaseSnapshot(
    snapshot: com.aesprt.aquahub.data.supabase.order.SupabaseCreatedOrder
): Order = copy(
    orderNumber = snapshot.orderNumber,
    items = snapshot.items,
    subtotal = snapshot.subtotalCentavos / 100.0,
    deliveryFee = snapshot.deliveryFeeCentavos / 100.0,
    discount = snapshot.discountCentavos / 100.0,
    total = snapshot.totalCentavos / 100.0,
    paymentStatus = com.aesprt.aquahub.domain.model.PaymentStatus.valueOf(snapshot.paymentStatus.uppercase()),
    orderStatus = OrderStatus.valueOf(snapshot.status.uppercase()),
    createdAt = snapshot.requestedAt ?: createdAt,
    acceptedAt = snapshot.acceptedAt ?: acceptedAt,
    readyAt = snapshot.readyAt ?: readyAt,
    completedAt = snapshot.completedAt
)

class CreateWalkInOrderUseCase(
    private val orderRepository: OrderRepository,
    private val customerRepository: CustomerRepository,
    private val inventoryRepository: InventoryRepository,
    private val transactionRunner: LocalTransactionRunner,
    private val productRepository: ProductRepository,
    private val canCreateOrderUseCase: CanCreateOrderUseCase,
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val paymentRepository: com.aesprt.aquahub.domain.repository.PaymentRepository,
    private val supabaseOrderDataSource: SupabaseOrderDataSource? = null,
    private val paymentCache: PaymentCache? = null,
    private val supabasePaymentDataSource: SupabasePaymentDataSource? = null,
    /** Only unit tests may opt into the legacy local calculation path. */
    private val allowLocalTestPath: Boolean = false,
    private val offlinePosRepository: OfflinePosRepository? = null,
    private val connectivityMonitor: ConnectivityMonitor? = null
) {
    suspend operator fun invoke(
        stationId: Long = 1,
        customerId: Long = 0,
        customerName: String,
        customerPhone: String = "",
        items: List<com.aesprt.aquahub.domain.model.OrderItem>,
        paymentMethod: com.aesprt.aquahub.domain.model.PaymentMethod = com.aesprt.aquahub.domain.model.PaymentMethod.CASH,
        notes: String? = null,
        emptyBottlesReturned: Int = 0,
        containerSize: String,
        stationContainersIssued: Int = 0,
        deliveryMode: String = "PICKUP",
        deliveryAddress: String = "",
        deliveryLatitude: Double? = null,
        deliveryLongitude: Double? = null,
        deliveryFee: Double = 0.0,
        chargeToAccount: Boolean = false,
        clientOperationId: String = java.util.UUID.randomUUID().toString(),
        cashReceivedCentavos: Long? = null
    ): Result<Long> {
        val normalizedDeliveryMode = deliveryMode.trim().uppercase()
        if (normalizedDeliveryMode != "PICKUP" && normalizedDeliveryMode != "DELIVERY") {
            return Result.failure(IllegalArgumentException("Invalid delivery mode"))
        }
        if (normalizedDeliveryMode == "DELIVERY" &&
            (customerName.isBlank() || customerPhone.isBlank() || deliveryAddress.isBlank() ||
                !deliveryFee.isFinite() || deliveryFee < 0.0)
        ) {
            return Result.failure(IllegalArgumentException("Delivery requires recipient name, phone, address, and a valid fee"))
        }
        if (items.isEmpty()) {
            return Result.failure(IllegalArgumentException("Order must have at least one item"))
        }
        if (!chargeToAccount && paymentMethod !in setOf(
                com.aesprt.aquahub.domain.model.PaymentMethod.CASH,
                com.aesprt.aquahub.domain.model.PaymentMethod.CASH_ON_DELIVERY,
                com.aesprt.aquahub.domain.model.PaymentMethod.GCASH,
                com.aesprt.aquahub.domain.model.PaymentMethod.MAYA
            )
        ) {
            return Result.failure(IllegalArgumentException("Unsupported payment method"))
        }
        if (!chargeToAccount && normalizedDeliveryMode == "PICKUP" && paymentMethod == com.aesprt.aquahub.domain.model.PaymentMethod.CASH_ON_DELIVERY) {
            return Result.failure(IllegalArgumentException("Cash on Delivery is available only for delivery orders"))
        }
        if (stationId <= 0 || items.any { it.quantity <= 0 || it.subtotal < 0.0 }) {
            return Result.failure(IllegalArgumentException("Invalid station or order quantity"))
        }

        return try { transactionRunner.run {
        if (canCreateOrderUseCase(stationId) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed) {
            return@run Result.failure(IllegalStateException("Order feature is not available for this subscription"))
        }
        if (normalizedDeliveryMode == "DELIVERY" &&
            checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.DELIVERY_MANAGEMENT) !is SubscriptionAccessResult.Allowed
        ) {
            return@run Result.failure(IllegalStateException("Delivery orders are not available for this subscription"))
        }
        if (items.map { it.productId }.distinct().size != items.size) {
            return@run Result.failure(IllegalArgumentException("Combine duplicate products into one order line"))
        }
        if (customerId > 0 && customerRepository.getCustomerById(customerId)?.stationId != stationId) {
            return@run Result.failure(IllegalArgumentException("Customer does not belong to this station"))
        }
        if (chargeToAccount && customerId <= 0) {
            return@run Result.failure(IllegalArgumentException("Select a saved customer before charging a sale to an account"))
        }
        if (chargeToAccount &&
            checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ACCOUNTS_RECEIVABLE) !is SubscriptionAccessResult.Allowed
        ) {
            return@run Result.failure(IllegalStateException("Client credit accounts require the Business or Pro plan"))
        }
        // Offline POS must use the signed lease price and reserved quantity.
        // Room catalog/stock may have changed while disconnected, so do not
        // reprice or reject a leased line from the mutable catalog cache.
        if (connectivityMonitor?.isCurrentlyOnline() == false) {
            if (offlinePosRepository == null || normalizedDeliveryMode != "PICKUP" ||
                chargeToAccount || paymentMethod != com.aesprt.aquahub.domain.model.PaymentMethod.CASH ||
                emptyBottlesReturned != 0 || stationContainersIssued != 0
            ) {
                return@run Result.failure(IllegalStateException(
                    "Offline sales require a valid inventory lease and support cash pickup only. Electronic payments, account charges, delivery, and container movements require internet."
                ))
            }
            val reservedLines = items.map { item ->
                val productId = item.productId
                    ?: return@run Result.failure(IllegalArgumentException("Every item must reference a product"))
                if (item.quantity !in 1..99) {
                    return@run Result.failure(IllegalArgumentException("Offline sale quantity is outside the supported range"))
                }
                OfflinePosReservationItem(productId, item.quantity)
            }
            return@run try {
                val localId = offlinePosRepository.recordCashSale(
                    OfflineCashSaleRequest(
                        stationId = stationId,
                        customerId = customerId,
                        customerName = customerName,
                        customerPhone = customerPhone,
                        notes = notes,
                        items = reservedLines,
                        cashReceivedCentavos = cashReceivedCentavos
                    )
                )
                Result.success(localId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
        val productRows = items.map { item ->
            val productId = item.productId ?: return@run Result.failure(IllegalArgumentException("Every item must reference a product"))
            val product = productRepository.getProductById(productId)
                ?: return@run Result.failure(IllegalArgumentException("Product $productId not found"))
            if (product.stationId != stationId || !product.isAvailable || !product.price.isFinite() || product.price <= 0 || product.stockQuantity < item.quantity) {
                return@run Result.failure(IllegalStateException("Insufficient stock for ${product.name}"))
            }
            product
        }
        val promotionsAllowed = checkSubscriptionAccessUseCase(
            stationId,
            SubscriptionFeature.PROMOTIONS
        ) is SubscriptionAccessResult.Allowed
        val validatedItems = productRows.zip(items).map { (product, item) ->
            val unitPrice = product.unitPriceFor(item.quantity, promotionsAllowed)
            item.copy(
                productId = product.id,
                productName = product.name,
                productType = product.type,
                sizeLabel = product.sizeLabel,
                unitPrice = unitPrice,
                subtotal = unitPrice * item.quantity
            )
        }
        if (emptyBottlesReturned < 0 || (emptyBottlesReturned > 0 && containerSize.isBlank())) {
            return@run Result.failure(IllegalArgumentException("Invalid returned bottle quantity or size"))
        }
        if (stationContainersIssued < 0 || ((emptyBottlesReturned > 0 || stationContainersIssued > 0) && customerId <= 0)) {
            return@run Result.failure(IllegalArgumentException("Select a customer for station-container loans and returns"))
        }
        val refillItems = validatedItems.filter { it.productType == com.aesprt.aquahub.domain.model.ProductType.REFILL }
        if ((stationContainersIssued > 0 || emptyBottlesReturned > 0) && refillItems.map { it.sizeLabel }.distinct().size != 1) {
            return@run Result.failure(IllegalArgumentException("Use one refill size per sale when recording station bottles"))
        }
        if (stationContainersIssued > refillItems.sumOf { it.quantity }) {
            return@run Result.failure(IllegalArgumentException("You can loan only as many station bottles as the refill quantity"))
        }
        val container = inventoryRepository.getContainerBySizeLabel(stationId, containerSize)
        if ((stationContainersIssued > 0 || emptyBottlesReturned > 0) && container == null) {
            return@run Result.failure(IllegalStateException("No station bottles are registered for $containerSize. Choose a listed bottle size or add it in Inventory."))
        }
        val availableForLoan = (container?.available ?: 0) + emptyBottlesReturned
        if (emptyBottlesReturned > (container?.withCustomers ?: 0)) {
            return@run Result.failure(IllegalStateException("Returned bottles exceed this customer's $containerSize loan balance"))
        }
        if (stationContainersIssued > availableForLoan) {
            return@run Result.failure(IllegalStateException("Only $availableForLoan $containerSize station bottles are available for this sale"))
        }
        if (emptyBottlesReturned > 0 && customerId > 0) {
            val held = inventoryRepository.observeCustomerTransactions(customerId).firstOrNull().orEmpty()
                .filter { it.stationId == stationId && it.containerSize == containerSize }
                .sumOf { txn ->
                    when (txn.type) {
                        com.aesprt.aquahub.domain.model.ContainerTransactionType.DELIVERED -> txn.quantity
                        com.aesprt.aquahub.domain.model.ContainerTransactionType.RETURNED,
                        com.aesprt.aquahub.domain.model.ContainerTransactionType.LOST -> -txn.quantity
                        else -> 0
                    }
                }
            if (emptyBottlesReturned > held) {
                return@run Result.failure(IllegalStateException("Returned bottles exceed the customer's balance"))
            }
        }
        val now = System.currentTimeMillis()
        val dateStr = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now))
        val randomSuffix = kotlin.random.Random.nextInt(1000, 9999)
        val orderNumber = "AQH-$dateStr-$randomSuffix"

        val subtotal = productRows.zip(items).sumOf { (product, item) -> product.price * item.quantity }
        val discount = subtotal - validatedItems.sumOf { it.subtotal }
        val appliedDeliveryFee = if (normalizedDeliveryMode == "DELIVERY") deliveryFee else 0.0
        val total = subtotal - discount + appliedDeliveryFee
        val appliedPaymentMethod = if (chargeToAccount) {
            com.aesprt.aquahub.domain.model.PaymentMethod.CLIENT_ACCOUNT
        } else if (normalizedDeliveryMode == "DELIVERY" && paymentMethod == com.aesprt.aquahub.domain.model.PaymentMethod.CASH) {
            com.aesprt.aquahub.domain.model.PaymentMethod.CASH_ON_DELIVERY
        } else {
            paymentMethod
        }
        val isCashOnDelivery = appliedPaymentMethod == com.aesprt.aquahub.domain.model.PaymentMethod.CASH_ON_DELIVERY

        val order = Order(
            orderNumber = orderNumber,
            stationId = stationId,
            customerId = customerId,
            customerName = customerName.ifBlank { "Walk-in Customer" },
            customerPhone = customerPhone,
            riderId = null,
            riderName = null,
            deliveryAddressId = null,
            deliveryAddress = if (normalizedDeliveryMode == "DELIVERY") deliveryAddress.trim() else "Station Walk-in / Pick-up",
            deliveryLatitude = if (normalizedDeliveryMode == "DELIVERY") deliveryLatitude else null,
            deliveryLongitude = if (normalizedDeliveryMode == "DELIVERY") deliveryLongitude else null,
            deliveryMode = normalizedDeliveryMode,
            items = validatedItems,
            subtotal = subtotal,
            deliveryFee = appliedDeliveryFee,
            discount = discount,
            total = total,
            paymentMethod = appliedPaymentMethod,
            paymentStatus = if (normalizedDeliveryMode == "DELIVERY" || isCashOnDelivery || chargeToAccount) {
                com.aesprt.aquahub.domain.model.PaymentStatus.PENDING
            } else {
                com.aesprt.aquahub.domain.model.PaymentStatus.PAID
            },
            orderStatus = if (normalizedDeliveryMode == "DELIVERY") OrderStatus.READY_FOR_PICKUP else OrderStatus.COMPLETED,
            customerNote = notes,
            stationNote = if (normalizedDeliveryMode == "DELIVERY") "Walk-in delivery order" else "Over-the-counter walk-in sale",
            createdAt = now,
            acceptedAt = now,
            readyAt = now,
            completedAt = if (normalizedDeliveryMode == "DELIVERY") null else now
        )

        // Production must have Supabase available. A local committed order is not
        // a safe fallback because it cannot atomically reserve authoritative stock.
        val supabaseCreatedOrder = if (supabaseOrderDataSource != null) {
            supabaseOrderDataSource.createWalkInOrder(
                stationLocalId = stationId,
                customerLocalId = customerId,
                customerName = order.customerName,
                customerPhone = customerPhone,
                items = validatedItems,
                paymentMethod = appliedPaymentMethod.name,
                notes = notes,
                deliveryMode = normalizedDeliveryMode,
                deliveryAddress = deliveryAddress,
                deliveryLatitude = deliveryLatitude,
                deliveryLongitude = deliveryLongitude,
                deliveryFeeCentavos = Math.round(appliedDeliveryFee * 100.0),
                clientOperationId = clientOperationId,
            )
        } else if (allowLocalTestPath) {
            productRows.zip(items).forEach { (product, item) ->
                productRepository.updateStock(product.id, product.stockQuantity - item.quantity)
            }
            null
        } else {
            return@run Result.failure(IllegalStateException("Order service is unavailable; no offline order was committed."))
        }
        val committedOrder = supabaseCreatedOrder?.let(order::withCommittedSupabaseSnapshot) ?: order
        val orderId = if (supabaseCreatedOrder != null) {
            orderRepository.insertOrderFromSupabase(committedOrder, supabaseCreatedOrder.orderId)
        } else {
            orderRepository.insertOrder(committedOrder)
        }

        if (committedOrder.paymentStatus == com.aesprt.aquahub.domain.model.PaymentStatus.PAID) {
            if (supabaseCreatedOrder != null && paymentCache != null && supabasePaymentDataSource != null) {
                supabasePaymentDataSource.getPaymentForOrder(supabaseCreatedOrder.orderId)?.let { remote ->
                    paymentCache.cacheRemotePayment(
                        orderId = orderId,
                        remoteId = remote.id,
                        method = appliedPaymentMethod,
                        amountCentavos = remote.amountCentavos,
                        status = remote.paymentStatus,
                        referenceNumber = remote.referenceNumber,
                        createdAt = remote.createdAt,
                        paidAt = remote.paidAt,
                        provider = remote.provider,
                        providerTransactionId = remote.providerTransactionId,
                        metadataJson = remote.metadataJson,
                        operationId = remote.operationId
                    )
                }
            } else {
                paymentRepository.recordPayment(orderId, committedOrder.total, appliedPaymentMethod, null)
            }
        }

        // Increment customer stats if existing customer
        if (customerId > 0) {
            customerRepository.incrementOrderCount(customerId)
        }

        // Record container return transaction if empty bottles returned
        if (emptyBottlesReturned > 0) {
            inventoryRepository.insertTransaction(
                com.aesprt.aquahub.domain.model.ContainerTransaction(
                    stationId = stationId,
                    customerId = if (customerId > 0) customerId else null,
                    customerName = customerName.ifBlank { "Walk-in Customer" },
                    orderId = orderId,
                    type = com.aesprt.aquahub.domain.model.ContainerTransactionType.RETURNED,
                    quantity = emptyBottlesReturned,
                    containerSize = containerSize,
                    notes = "Returned during walk-in order $orderNumber",
                    createdAt = now
                )
            )
            inventoryRepository.adjustContainer(
                stationId, containerSize, emptyBottlesReturned,
                InventoryRepository.ContainerField.AVAILABLE
            )
            if (customerId > 0) {
                inventoryRepository.adjustContainer(
                    stationId, containerSize, -emptyBottlesReturned,
                    InventoryRepository.ContainerField.WITH_CUSTOMERS
                )
            }
        }

        // Refilling a customer's own bottle never moves station-owned containers.
        if (stationContainersIssued > 0) {
            val refillSize = containerSize
            val refillQty = stationContainersIssued
            inventoryRepository.insertTransaction(
                com.aesprt.aquahub.domain.model.ContainerTransaction(
                    stationId = stationId,
                    customerId = if (customerId > 0) customerId else null,
                    customerName = customerName.ifBlank { "Walk-in Customer" },
                    orderId = orderId,
                    type = com.aesprt.aquahub.domain.model.ContainerTransactionType.DELIVERED,
                    quantity = refillQty,
                    containerSize = refillSize,
                    notes = "Station containers loaned on order $orderNumber",
                    createdAt = now
                )
            )
            inventoryRepository.adjustContainer(
                stationId, refillSize, -refillQty,
                InventoryRepository.ContainerField.AVAILABLE
            )
            if (customerId > 0) {
                inventoryRepository.adjustContainer(
                    stationId, refillSize, refillQty,
                    InventoryRepository.ContainerField.WITH_CUSTOMERS
                )
            }
        }

        Result.success(orderId)
        } } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { Result.failure(e) }
    }
}
