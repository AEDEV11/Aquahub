package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.local.dao.OrderDao
import com.aesprt.aquahub.data.local.dao.RiderDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.local.mapper.toEntity
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.domain.repository.OrderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.sync.*
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.supabase.delivery.SupabaseDeliveryDataSource
import com.aesprt.aquahub.data.supabase.delivery.SupabaseRiderDataSource
import com.aesprt.aquahub.data.supabase.order.SupabaseOrderDataSource
import io.github.jan.supabase.auth.auth
import com.aesprt.aquahub.data.compatibility.LegacyCompatibilityResolver
import androidx.paging.PagingSource
import java.time.Instant
import com.aesprt.aquahub.data.local.entity.OrderEntity
import com.aesprt.aquahub.data.local.entity.OrderItemEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity

class RoomOrderRepository(
    private val orderDao: OrderDao,
    private val riderDao: RiderDao,
    private val database: AquaHubDatabase,
    private val syncQueue: SyncQueueWriter,
    private val syncDao: SyncDao,
    private val supabaseOrder: SupabaseOrderDataSource,
    private val supabaseDelivery: SupabaseDeliveryDataSource,
    private val supabaseRider: SupabaseRiderDataSource,
    private val compatibilityResolver: LegacyCompatibilityResolver
) : OrderRepository {

    override suspend fun refreshDeliveryReads(stationId: Long): Result<Unit> = try {
        val authenticatedUid = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
            ?: return Result.failure(NoAuthenticatedCloudSessionException())
        val stationRemoteId = syncDao.getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId
            ?: return Result.failure(IllegalStateException("Station has not synced to Supabase yet."))
        val remoteOrders = supabaseDelivery.loadStationOrders(stationRemoteId)
        val itemsByOrder = supabaseDelivery.loadOrderItems(remoteOrders.map { it.id }).groupBy { it.orderId }
        database.withTransaction {
            remoteOrders.forEach { remote ->
                val metadata = syncDao.getMetadataByRemoteId(SyncResourceType.ORDER.name, remote.id)
                // Offline POS creates its durable local order before the server
                // accepts it. A remote refresh can observe the committed order
                // before OfflinePosSyncCoordinator has written the ORDER
                // mapping. Match by the server's stable client operation UUID
                // so that race updates the existing Room row instead of
                // inserting a second order.
                val offlineLocalOrderId = remote.clientOperationId
                    ?.let { database.offlinePosDao().getOperation(it) }
                    ?.takeIf { it.profileUid == authenticatedUid && it.stationId == stationId }
                    ?.localOrderId
                val localId = metadata?.localId ?: offlineLocalOrderId ?: 0L
                if (localId > 0 && syncDao.hasOutstandingMutation(SyncResourceType.ORDER.name, localId) > 0) return@forEach
                val existingOrder = if (localId > 0) orderDao.getOrderEntityById(localId) else null
                val mappedCustomerId = remote.customerId?.let {
                    syncDao.getMetadataByRemoteId(SyncResourceType.CUSTOMER.name, it)?.localId
                }
                val localCustomerId = resolveRefreshedOrderCustomerId(
                    remoteCustomerId = remote.customerId,
                    mappedLocalCustomerId = mappedCustomerId,
                    cachedLocalCustomerId = existingOrder?.customerId
                )
                val localRiderId = remote.riderId?.let {
                    syncDao.getMetadataByRemoteId(SyncResourceType.RIDER.name, it)?.localId
                }
                val localAddressId = remote.deliveryAddressId?.let {
                    syncDao.getMetadataByRemoteId(SyncResourceType.CUSTOMER_ADDRESS.name, it)?.localId
                }
                val entity = OrderEntity(
                    id = localId,
                    orderNumber = remote.orderNumber,
                    stationId = stationId,
                    customerId = localCustomerId,
                    customerName = remote.customerName,
                    customerPhone = remote.customerPhone,
                    riderId = localRiderId,
                    riderName = localRiderId?.let { riderDao.getRiderById(it)?.name },
                    deliveryAddressId = localAddressId,
                    deliveryAddress = remote.deliveryAddress,
                    deliveryLatitude = remote.deliveryLatitude,
                    deliveryLongitude = remote.deliveryLongitude,
                    deliveryMode = remote.deliveryMode,
                    subtotal = remote.subtotalCentavos / 100.0,
                    deliveryFee = remote.deliveryFeeCentavos / 100.0,
                    discount = remote.discountCentavos / 100.0,
                    total = remote.totalCentavos / 100.0,
                    paymentMethod = resolveRefreshedOrderPaymentMethod(
                        remotePaymentMethod = remote.paymentMethod,
                        cachedPaymentMethod = existingOrder?.paymentMethod
                    ),
                    paymentStatus = remote.paymentStatus,
                    orderStatus = remote.status,
                    scheduledDeliveryStart = remote.scheduledDeliveryStart,
                    scheduledDeliveryEnd = remote.scheduledDeliveryEnd,
                    customerNote = remote.customerNote,
                    stationNote = remote.stationNote,
                    createdAt = remote.requestedAt ?: epochMillis(remote.createdAt),
                    acceptedAt = remote.acceptedAt,
                    readyAt = remote.readyAt,
                    assignedAt = remote.assignedAt,
                    outForDeliveryAt = remote.outForDeliveryAt,
                    deliveredAt = remote.deliveredAt,
                    updatedAt = epochMillis(remote.updatedAt),
                    completedAt = remote.completedAt,
                    cancelledAt = remote.cancelledAt,
                    cancellationReason = remote.cancellationReason
                )
                val savedId = orderDao.upsertOrderPreservingChildren(entity)
                val localItems = itemsByOrder[remote.id].orEmpty().map { item ->
                    OrderItemEntity(
                        orderId = savedId,
                        productId = item.productId?.let { syncDao.getMetadataByRemoteId(SyncResourceType.PRODUCT.name, it)?.localId },
                        productName = item.productName,
                        productType = item.productType,
                        sizeLabel = item.sizeLabel,
                        unitPrice = item.unitPriceCentavos / 100.0,
                        quantity = item.quantity,
                        subtotal = item.subtotalCentavos / 100.0,
                        containerSource = item.containerSource
                    )
                }
                orderDao.deleteOrderItems(savedId)
                if (localItems.isNotEmpty()) orderDao.insertOrderItems(localItems)
                syncDao.upsertMetadata(SyncMetadataEntity(
                    resourceType = SyncResourceType.ORDER.name,
                    localId = savedId,
                    remoteId = remote.id,
                    stationLocalId = stationId,
                    lastLocalUpdatedAt = entity.updatedAt ?: entity.createdAt,
                    lastServerUpdatedAt = entity.updatedAt,
                    lastSyncedAt = System.currentTimeMillis()
                ))
            }
        }
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun epochMillis(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }
        .getOrDefault(System.currentTimeMillis())

    override fun pagingOrders(
        stationId: Long,
        riderId: Long?,
        statuses: List<OrderStatus>
    ): PagingSource<Int, Order> = MappingPagingSource(
        source = orderDao.pagingOrders(stationId, riderId, statuses.map { it.name }),
        transform = { it.toDomain() }
    )

    override fun observeOrders(stationId: Long): Flow<List<Order>> =
        orderDao.observeOrders(stationId).map { list -> list.map { it.toDomain() } }

    override fun observeOrdersForCustomer(customerId: Long): Flow<List<Order>> =
        orderDao.observeOrdersForCustomer(customerId).map { list -> list.map { it.toDomain() } }

    override fun observeOrdersForRider(stationId: Long, riderId: Long): Flow<List<Order>> =
        orderDao.observeOrdersForRider(stationId, riderId).map { list -> list.map { it.toDomain() } }

    override fun observeOrdersByStatus(stationId: Long, status: OrderStatus): Flow<List<Order>> =
        orderDao.observeOrdersByStatus(stationId, status.name).map { list -> list.map { it.toDomain() } }

    override fun observeOrdersByStatuses(stationId: Long, statuses: List<OrderStatus>): Flow<List<Order>> =
        orderDao.observeOrdersByStatuses(stationId, statuses.map { it.name }).map { list -> list.map { it.toDomain() } }

    override fun observeOrdersForRiderByStatuses(
        stationId: Long,
        riderId: Long,
        statuses: List<OrderStatus>
    ): Flow<List<Order>> = orderDao
        .observeOrdersForRiderByStatuses(stationId, riderId, statuses.map { it.name })
        .map { list -> list.map { it.toDomain() } }

    override fun observeTodayOrders(stationId: Long, startOfDay: Long, endOfDay: Long): Flow<List<Order>> =
        orderDao.observeTodayOrders(stationId, startOfDay, endOfDay).map { list -> list.map { it.toDomain() } }

    override fun observeRecentOrders(stationId: Long, limit: Int): Flow<List<Order>> =
        orderDao.observeRecentOrders(stationId, limit).map { list -> list.map { it.toDomain() } }

    override fun observeUncompletedOrdersCount(stationId: Long): Flow<Int> =
        orderDao.observeUncompletedOrdersCount(stationId)

    override suspend fun getOrderById(orderId: Long): Order? =
        orderDao.getOrderById(orderId)?.toDomain()

    override suspend fun countOrdersBetween(stationId: Long, startAt: Long, endAt: Long): Int =
        orderDao.countOrdersBetween(stationId, startAt, endAt)

    override suspend fun insertOrder(order: Order): Long = database.withTransaction {
        val now = order.updatedAt ?: order.createdAt
        val entity = order.copy(updatedAt = now).toEntity()
        val orderId = orderDao.insertOrder(entity)
        val items = order.items.map { it.toEntity(orderId) }
        if (items.isNotEmpty()) orderDao.insertOrderItems(items)
        order.riderId?.let { refreshRiderActiveOrderCount(it) }
        syncQueue.enqueue(SyncResourceType.ORDER, orderId, order.stationId, SyncOperation.CREATE, now)
        orderId
    }

    override suspend fun insertOrderFromSupabase(order: Order, remoteId: String?): Long = database.withTransaction {
        val now = order.updatedAt ?: order.createdAt
        val orderId = orderDao.insertOrder(order.copy(updatedAt = now).toEntity())
        val items = order.items.map { it.toEntity(orderId) }
        if (items.isNotEmpty()) orderDao.insertOrderItems(items)
        if (remoteId != null) {
            syncDao.upsertMetadata(SyncMetadataEntity(
                resourceType = SyncResourceType.ORDER.name,
                localId = orderId,
                remoteId = remoteId,
                stationLocalId = order.stationId,
                lastLocalUpdatedAt = now,
                lastSyncedAt = System.currentTimeMillis()
            ))
        }
        order.riderId?.let { refreshRiderActiveOrderCount(it) }
        orderId
    }

    override suspend fun updateOrderStatus(orderId: Long, status: OrderStatus, timestamp: Long) {
        val previous = orderDao.getOrderById(orderId)?.order ?: return
        val orderMetadata = syncDao.getMetadata(SyncResourceType.ORDER.name, orderId)
        val remoteOrderId = orderMetadata?.remoteId
        val hasPersistedRemoteOrder = orderMetadata?.lastSyncedAt != null
        val operationId = "order:${remoteOrderId ?: orderId}:status:${status.name}"
        val useSupabaseDelivery = hasPersistedRemoteOrder &&
            status in supabaseDeliveryStatuses &&
            compatibilityResolver.deliveryPath(
                hasSupabaseSession = SupabaseClientProvider.client.auth.currentUserOrNull() != null,
                hasCanonicalOrderId = remoteOrderId != null && hasPersistedRemoteOrder,
        ) == LegacyCompatibilityResolver.DeliveryPath.SUPABASE_PRIMARY
        if (useSupabaseDelivery) {
            val remoteSynced = runCatching {
                if (status == OrderStatus.REJECTED) {
                    supabaseOrder.rejectOrder(requireNotNull(remoteOrderId), operationId)
                } else {
                    supabaseDelivery.transition(requireNotNull(remoteOrderId), status.name, operationId)
                }
            }.onFailure { error ->
                if (!error.isDeliveryRetryable()) throw error
            }.isSuccess
            database.withTransaction {
                when (status) {
                    OrderStatus.OUT_FOR_DELIVERY -> orderDao.setOutForDeliveryAt(orderId, timestamp)
                    OrderStatus.DELIVERED -> orderDao.setDeliveredAt(orderId, timestamp)
                    OrderStatus.COMPLETED -> orderDao.setCompletedAt(orderId, timestamp)
                    else -> orderDao.updateOrderStatus(orderId, status.name, timestamp)
                }
                previous.riderId?.let { refreshRiderActiveOrderCount(it) }
                if (!remoteSynced) {
                    syncQueue.enqueue(
                        SyncResourceType.ORDER, orderId, previous.stationId,
                        SyncOperation.ORDER_TRANSITION, timestamp,
                        expectedPreviousStatus = previous.orderStatus,
                        requestedStatus = status.name,
                        stableOperationId = operationId
                    )
                }
            }
            return
        }
        database.withTransaction {
        when (status) {
            OrderStatus.ACCEPTED -> orderDao.setAcceptedAt(orderId, timestamp)
            OrderStatus.READY_FOR_PICKUP -> orderDao.setReadyAt(orderId, timestamp)
            OrderStatus.OUT_FOR_DELIVERY -> orderDao.setOutForDeliveryAt(orderId, timestamp)
            OrderStatus.DELIVERED -> orderDao.setDeliveredAt(orderId, timestamp)
            OrderStatus.COMPLETED -> orderDao.setCompletedAt(orderId, timestamp)
            OrderStatus.REJECTED -> orderDao.setRejected(orderId, timestamp)
            else -> orderDao.updateOrderStatus(orderId, status.name, timestamp)
        }
        previous.riderId?.let { refreshRiderActiveOrderCount(it) }
        syncQueue.enqueue(
            SyncResourceType.ORDER, orderId, previous.stationId,
            SyncOperation.ORDER_TRANSITION, timestamp,
            expectedPreviousStatus = previous.orderStatus,
            requestedStatus = status.name,
            stableOperationId = operationId
        )
        }
    }

    override suspend fun assignRider(orderId: Long, riderId: Long, riderName: String) {
        val previous = orderDao.getOrderById(orderId)?.order ?: return
        val now = System.currentTimeMillis()
        val orderMetadata = syncDao.getMetadata(SyncResourceType.ORDER.name, orderId)
        val remoteOrderId = orderMetadata?.remoteId
        val rider = riderDao.getRiderById(riderId) ?: return
        val riderMetadata = syncQueue.ensureMetadata(
            SyncResourceType.RIDER,
            riderId,
            rider.stationId,
            rider.updatedAt
        )
        val remoteRiderId = riderMetadata.remoteId
        val operationId = "order:${remoteOrderId ?: orderId}:assign:$remoteRiderId:$now"
        if (orderMetadata?.lastSyncedAt != null &&
            compatibilityResolver.deliveryPath(
                hasSupabaseSession = SupabaseClientProvider.client.auth.currentUserOrNull() != null,
                hasCanonicalOrderId = true,
                hasCanonicalRiderId = true,
                requiresRiderMapping = true,
        ) == LegacyCompatibilityResolver.DeliveryPath.SUPABASE_PRIMARY
        ) {
            val remoteSynced = runCatching {
                supabaseRider.upsert(
                    stationId = requireNotNull(syncDao.getMetadata(SyncResourceType.STATION.name, rider.stationId)?.remoteId) {
                        "Station has no Supabase identity."
                    },
                    riderId = remoteRiderId,
                    rider = rider.toDomain()
                )
                supabaseDelivery.assign(requireNotNull(remoteOrderId), requireNotNull(remoteRiderId), operationId)
            }.onFailure { error ->
                if (!error.isDeliveryRetryable()) throw error
            }.isSuccess
            database.withTransaction {
                orderDao.assignRider(orderId, riderId, riderName, now)
                setOfNotNull(previous.riderId, riderId).forEach { refreshRiderActiveOrderCount(it) }
                if (!remoteSynced) {
                    syncQueue.enqueue(SyncResourceType.RIDER, riderId, rider.stationId, SyncOperation.UPDATE, now)
                    syncQueue.enqueue(
                        SyncResourceType.ORDER, orderId, previous.stationId,
                        SyncOperation.ORDER_TRANSITION, now,
                        expectedPreviousStatus = previous.orderStatus,
                        requestedStatus = OrderStatus.RIDER_ASSIGNED.name,
                        stableOperationId = operationId
                    )
                }
            }
            return
        }
        database.withTransaction {
        orderDao.assignRider(orderId, riderId, riderName, now)
        setOfNotNull(previous.riderId, riderId).forEach { refreshRiderActiveOrderCount(it) }
        syncQueue.enqueue(SyncResourceType.RIDER, riderId, rider.stationId, SyncOperation.UPDATE, now)
        syncQueue.enqueue(
            SyncResourceType.ORDER, orderId, previous.stationId,
            SyncOperation.ORDER_TRANSITION, now,
            expectedPreviousStatus = previous.orderStatus,
            requestedStatus = OrderStatus.RIDER_ASSIGNED.name,
            stableOperationId = operationId
        )
        }
    }

    override suspend fun updateStationNote(orderId: Long, note: String) = database.withTransaction {
        val row = orderDao.getOrderById(orderId)?.order ?: return@withTransaction
        orderDao.updateStationNote(orderId, note)
        syncQueue.enqueue(SyncResourceType.ORDER, orderId, row.stationId, SyncOperation.UPDATE)
    }

    override suspend fun markChargedToAccount(orderId: Long, timestamp: Long) = database.withTransaction {
        val row = orderDao.getOrderById(orderId)?.order ?: error("Order not found")
        orderDao.updatePaymentState(
            orderId,
            com.aesprt.aquahub.domain.model.PaymentStatus.PENDING.name,
            com.aesprt.aquahub.domain.model.PaymentMethod.CLIENT_ACCOUNT.name,
            timestamp,
        )
        syncQueue.enqueue(SyncResourceType.ORDER, orderId, row.stationId, SyncOperation.UPDATE, timestamp)
        Unit
    }

    override suspend fun cancelOrder(orderId: Long, timestamp: Long, reason: String) {
        val previous = orderDao.getOrderById(orderId)?.order ?: return
        val orderMetadata = syncDao.getMetadata(SyncResourceType.ORDER.name, orderId)
        if (SupabaseClientProvider.client.auth.currentUserOrNull() == null) {
            throw IllegalStateException("Sign in to cancel this order.")
        }
        var remoteSynced = false
        if (orderMetadata?.remoteId != null && orderMetadata.lastSyncedAt != null) {
            remoteSynced = runCatching {
                supabaseOrder.cancelOrderWithRefunds(
                    remoteOrderId = orderMetadata.remoteId,
                    operationId = "order:cancel:${orderMetadata.remoteId}",
                    reason = reason.trim().take(300)
                )
            }.onFailure { error ->
                if (!error.isDeliveryRetryable()) throw error
            }.isSuccess
        }
        database.withTransaction {
            orderDao.setCancelledAt(orderId, timestamp, reason.trim().take(300))
            previous.riderId?.let { refreshRiderActiveOrderCount(it) }
            if (!remoteSynced) {
                syncQueue.enqueue(
                    SyncResourceType.ORDER,
                    orderId,
                    previous.stationId,
                    SyncOperation.ORDER_TRANSITION,
                    timestamp,
                    expectedPreviousStatus = previous.orderStatus,
                    requestedStatus = OrderStatus.CANCELLED.name,
                    stableOperationId = "order:${orderMetadata?.remoteId ?: orderId}:status:CANCELLED"
                )
            }
        }
    }

    private suspend fun refreshRiderActiveOrderCount(riderId: Long) {
        val rider = riderDao.getRiderById(riderId) ?: return
        val activeOrderCount = riderDao.countActiveOrders(riderId)
        if (rider.activeOrderCount == activeOrderCount) return

        val now = System.currentTimeMillis()
        riderDao.updateRider(rider.copy(activeOrderCount = activeOrderCount, updatedAt = now))
    }

    private companion object {
        /** Delivery preparation and rider lifecycle states handled by Supabase. */
        val supabaseDeliveryStatuses = setOf(
            OrderStatus.ACCEPTED,
            OrderStatus.PREPARING,
            OrderStatus.READY_FOR_PICKUP,
            OrderStatus.OUT_FOR_DELIVERY,
            OrderStatus.DELIVERED,
            OrderStatus.COMPLETED,
            OrderStatus.REJECTED,
        )
    }
}

private fun Throwable.isDeliveryRetryable(): Boolean = generateSequence(this) { it.cause }
    .any {
        it is java.io.IOException ||
            it is java.net.ConnectException ||
            it is java.net.SocketTimeoutException ||
            it is java.net.UnknownHostException ||
            it is java.nio.channels.UnresolvedAddressException
    }

internal fun resolveRefreshedOrderCustomerId(
    remoteCustomerId: String?,
    mappedLocalCustomerId: Long?,
    cachedLocalCustomerId: Long?
): Long = when {
    remoteCustomerId == null -> 0L
    mappedLocalCustomerId != null && mappedLocalCustomerId > 0L -> mappedLocalCustomerId
    cachedLocalCustomerId != null && cachedLocalCustomerId > 0L -> cachedLocalCustomerId
    else -> 0L
}

internal fun resolveRefreshedOrderPaymentMethod(
    remotePaymentMethod: String,
    cachedPaymentMethod: String?
): String = if (cachedPaymentMethod == PaymentMethod.CLIENT_ACCOUNT.name) {
    PaymentMethod.CLIENT_ACCOUNT.name
} else {
    remotePaymentMethod
}
