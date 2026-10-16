package com.aesprt.aquahub.sync

import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.dao.CustomerDao
import com.aesprt.aquahub.data.local.dao.ExpenseDao
import com.aesprt.aquahub.data.local.dao.OrderDao
import com.aesprt.aquahub.data.local.dao.PaymentDao
import com.aesprt.aquahub.data.local.dao.ProductDao
import com.aesprt.aquahub.data.local.dao.RiderDao
import com.aesprt.aquahub.data.local.dao.StationDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.dao.ContainerDao
import com.aesprt.aquahub.data.local.dao.ContainerTransactionDao
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.local.entity.ContainerEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.supabase.catalog.SupabaseCatalogDataSource
import com.aesprt.aquahub.data.supabase.inventory.SupabaseInventoryDataSource
import com.aesprt.aquahub.data.supabase.inventory.SupabaseContainerDataSource
import com.aesprt.aquahub.data.supabase.settings.SupabaseSettingsDataSource
import com.aesprt.aquahub.data.supabase.delivery.SupabaseDeliveryDataSource
import com.aesprt.aquahub.data.supabase.delivery.SupabaseRiderDataSource
import com.aesprt.aquahub.data.supabase.expense.SupabaseExpenseDataSource
import com.aesprt.aquahub.data.supabase.order.SupabaseOrderDataSource
import com.aesprt.aquahub.data.supabase.payment.SupabasePaymentDataSource
import io.github.jan.supabase.auth.auth
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Replays customer mutations that were committed to Room while Supabase was
 * unreachable. Migrated domains complete here and do not continue to a
 * second writable Firebase authority; blocked domains retain their explicit
 * compatibility path in the coordinator.
 */
class SupabasePendingMutationWriter(
    private val customerDao: CustomerDao,
    private val expenseDao: ExpenseDao,
    private val productDao: ProductDao,
    private val orderDao: OrderDao,
    private val stationDao: StationDao,
    private val riderDao: RiderDao,
    private val paymentDao: PaymentDao,
    private val containerDao: ContainerDao,
    private val containerTransactionDao: ContainerTransactionDao,
    private val syncDao: SyncDao,
    private val database: AquaHubDatabase,
    private val supabaseCatalog: SupabaseCatalogDataSource,
    private val supabaseInventory: SupabaseInventoryDataSource,
    private val supabaseContainer: SupabaseContainerDataSource,
    private val supabaseSettings: SupabaseSettingsDataSource,
    private val supabaseDelivery: SupabaseDeliveryDataSource,
    private val supabaseRider: SupabaseRiderDataSource,
    private val supabasePayment: SupabasePaymentDataSource,
    private val supabaseExpense: SupabaseExpenseDataSource,
    private val supabaseOrder: SupabaseOrderDataSource
) {
    /** Returns true when the migrated Supabase path handled the mutation. */
    suspend fun push(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity): Boolean {
        if (item.resourceType in setOf(
                SyncResourceType.CUSTOMER.name,
                SyncResourceType.CUSTOMER_ADDRESS.name,
                SyncResourceType.PRODUCT.name,
                SyncResourceType.RIDER.name,
                SyncResourceType.ORDER.name,
                SyncResourceType.PAYMENT.name,
                SyncResourceType.EXPENSE.name,
                SyncResourceType.STATION.name
            ) && SupabaseClientProvider.client.auth.currentUserOrNull() == null
        ) return false
        return when (item.resourceType) {
            SyncResourceType.CUSTOMER.name -> { pushCustomer(item); true }
            SyncResourceType.CUSTOMER_ADDRESS.name -> { pushAddress(item); true }
            SyncResourceType.EXPENSE.name -> { pushExpense(item); true }
            SyncResourceType.STATION.name -> if (item.operation in supportedStationOperations) { pushStation(item); true } else false
            SyncResourceType.RIDER.name -> if (item.operation in supportedRiderOperations) { pushRider(item); true } else false
            SyncResourceType.ORDER.name -> pushOrder(item)
            SyncResourceType.PAYMENT.name -> pushPayment(item)
            SyncResourceType.PRODUCT.name -> { pushProduct(item); true }
            SyncResourceType.CONTAINER.name -> { pushContainer(item); true }
            SyncResourceType.CONTAINER_MOVEMENT.name -> { pushContainerMovement(item); true }
            else -> false
        }
    }

    /** Refreshes canonical server bottle counts without re-enqueuing the Room upserts. */
    suspend fun refreshContainerInventory() {
        if (SupabaseClientProvider.client.auth.currentUserOrNull() == null) return
        for (station in stationDao.getAllStations()) {
            val stationRemoteId = syncDao.getMetadata(SyncResourceType.STATION.name, station.id)?.remoteId
                ?: continue
            val remoteRows = supabaseContainer.containers(stationRemoteId)
            if (remoteRows.isEmpty()) continue

            database.withTransaction {
                remoteRows.forEach remoteLoop@ { remote ->
                    val mappedLocalId = syncDao.getMetadataByRemoteId(
                        SyncResourceType.CONTAINER.name,
                        remote.id
                    )?.localId
                    val existing = mappedLocalId?.let { localId -> containerDao.getContainerById(localId) }
                        ?: containerDao.getContainerBySizeLabel(station.id, remote.sizeLabel)

                    if (existing != null && (
                            syncDao.hasOutstandingMutation(SyncResourceType.CONTAINER.name, existing.id) > 0 ||
                                syncDao.hasOutstandingLiveContainerMovement(station.id, remote.sizeLabel)
                            )
                    ) return@remoteLoop

                    val serverUpdatedAt = remote.updatedAt.toEpochMillisOrNow()
                    val local = existing?.copy(
                        sizeLabel = remote.sizeLabel,
                        totalOwned = remote.totalOwned,
                        available = remote.available,
                        withCustomers = remote.withCustomers,
                        damaged = remote.damaged,
                        lost = remote.lost,
                        depositPrice = remote.depositPriceCentavos / 100.0,
                        updatedAt = serverUpdatedAt
                    ) ?: ContainerEntity(
                        stationId = station.id,
                        sizeLabel = remote.sizeLabel,
                        totalOwned = remote.totalOwned,
                        available = remote.available,
                        withCustomers = remote.withCustomers,
                        damaged = remote.damaged,
                        lost = remote.lost,
                        depositPrice = remote.depositPriceCentavos / 100.0,
                        createdAt = remote.createdAt.toEpochMillisOrNow(),
                        updatedAt = serverUpdatedAt
                    )
                    containerDao.upsertContainer(local)
                    val saved = if (local.id > 0) local else containerDao.getContainerBySizeLabel(station.id, remote.sizeLabel)
                        ?: return@remoteLoop
                    val syncedAt = System.currentTimeMillis()
                    val metadata = syncDao.getMetadata(SyncResourceType.CONTAINER.name, saved.id)
                    syncDao.upsertMetadata(
                        metadata?.copy(
                            remoteId = remote.id,
                            stationLocalId = station.id,
                            lastLocalUpdatedAt = serverUpdatedAt,
                            lastServerUpdatedAt = serverUpdatedAt,
                            lastSyncedAt = syncedAt,
                            deletedAt = null
                        ) ?: SyncMetadataEntity(
                            resourceType = SyncResourceType.CONTAINER.name,
                            localId = saved.id,
                            remoteId = remote.id,
                            stationLocalId = station.id,
                            lastLocalUpdatedAt = serverUpdatedAt,
                            lastServerUpdatedAt = serverUpdatedAt,
                            lastSyncedAt = syncedAt
                        )
                    )
                }
            }
        }
    }

    private suspend fun pushDelivery(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity): Boolean {
        if (SupabaseClientProvider.client.auth.currentUserOrNull() == null) return false
        val order = orderDao.getOrderById(item.resourceLocalId)?.order ?: return false
        val orderId = syncDao.getMetadata(SyncResourceType.ORDER.name, order.id)?.remoteId ?: return false
        if (item.requestedStatus == "RIDER_ASSIGNED") {
            val riderId = order.riderId ?: return false
            val remoteRiderId = syncDao.getMetadata(SyncResourceType.RIDER.name, riderId)?.remoteId ?: return false
            supabaseDelivery.assign(orderId, remoteRiderId, item.operationId)
        } else if (item.requestedStatus == "REJECTED") {
            supabaseOrder.rejectOrder(orderId, item.operationId)
        } else if (item.requestedStatus == "CANCELLED") {
            supabaseOrder.cancelOrderWithRefunds(
                remoteOrderId = orderId,
                operationId = "order:cancel:$orderId",
                reason = order.cancellationReason
            )
        } else {
            supabaseDelivery.transition(orderId, item.requestedStatus ?: return false, item.operationId)
        }
        return true
    }

    private companion object {
        // Keep this list aligned with transition_delivery_status. RIDER_ASSIGNED
        // is replayed through assign_delivery; the other states use the status RPC.
        val deliveryStatuses = setOf(
            "ACCEPTED",
            "PREPARING",
            "READY_FOR_PICKUP",
            "RIDER_ASSIGNED",
            "OUT_FOR_DELIVERY",
            "DELIVERED",
            "COMPLETED",
            "REJECTED",
            "CANCELLED",
        )
        val supportedStationOperations = setOf(
            SyncOperation.CREATE.name,
            SyncOperation.UPDATE.name,
            SyncOperation.ARCHIVE.name
        )
        val supportedRiderOperations = setOf(
            SyncOperation.CREATE.name,
            SyncOperation.UPDATE.name,
            SyncOperation.ARCHIVE.name
        )
    }

    private suspend fun pushStation(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity) {
        val remoteId = requireNotNull(syncDao.getMetadata(SyncResourceType.STATION.name, item.resourceLocalId)?.remoteId) {
            "Station remote mapping is missing"
        }
        if (item.operation == SyncOperation.ARCHIVE.name) {
            supabaseSettings.archiveStation(remoteId)
        } else {
            val station = requireNotNull(stationDao.getStation(item.resourceLocalId)) {
                "Station was deleted before Supabase settings replay"
            }.toDomain()
            if (item.operation == SyncOperation.CREATE.name) {
                supabaseSettings.createStation(remoteId, station)
            } else {
                supabaseSettings.updateStationSettings(remoteId, station)
            }
        }
    }

    private suspend fun pushRider(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity) {
        val rider = requireNotNull(riderDao.getRiderById(item.resourceLocalId)) {
            "Rider was deleted before Supabase replay"
        }
        supabaseRider.upsert(stationRemoteId(rider.stationId), item.resourceRemoteId, rider.toDomain())
        recordCanonicalRemoteId(item, item.resourceRemoteId)
    }

    private suspend fun pushOrder(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity): Boolean {
        when (item.operation) {
            SyncOperation.CREATE.name, SyncOperation.IMPORT.name -> {
                val row = requireNotNull(orderDao.getOrderById(item.resourceLocalId)) {
                    "Order was deleted before Supabase replay"
                }
                supabaseOrder.importLocalOrder(
                    order = row.toDomain(),
                    orderId = item.resourceRemoteId,
                    items = row.items.map { it.toDomain() }
                )
                recordCanonicalRemoteId(item, item.resourceRemoteId)
                return true
            }
            SyncOperation.UPDATE.name -> {
                val row = requireNotNull(orderDao.getOrderById(item.resourceLocalId)) {
                    "Order was deleted before Supabase replay"
                }
                val remoteId = requireNotNull(syncDao.getMetadata(SyncResourceType.ORDER.name, row.order.id)?.remoteId) {
                    "Order is not present in Supabase yet"
                }
                supabaseOrder.updateOwnerFields(remoteId, row.toDomain())
                return true
            }
            SyncOperation.ORDER_TRANSITION.name -> {
                if (item.requestedStatus !in deliveryStatuses) return false
                return pushDelivery(item)
            }
            else -> return false
        }
    }

    private suspend fun pushPayment(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity): Boolean {
        if (item.operation !in setOf(SyncOperation.CREATE.name, SyncOperation.IMPORT.name)) return false
        val payment = requireNotNull(paymentDao.getPaymentById(item.resourceLocalId)) {
            "Payment was deleted before Supabase replay"
        }
        // Account-charge rows are not receipts. The only pending receipt replayed
        // is a rider collection with an already-uploaded proof object.
        val proofPath = payment.deliveryPaymentProofObjectPath()
        if (payment.status != "PAID" && !(payment.status == "PENDING" && proofPath != null)) return true
        val orderId = requireNotNull(syncDao.getMetadata(SyncResourceType.ORDER.name, payment.orderId)?.remoteId) {
            "Order must sync before its payment."
        }
        val amountCentavos = BigDecimal.valueOf(payment.amount)
            .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
        val operationId = payment.operationId ?: item.operationId
        val remote = supabasePayment.recordPayment(
            orderId = orderId,
            operationId = operationId,
            amountCentavos = amountCentavos,
            paymentMethod = payment.paymentMethod,
            referenceNumber = payment.referenceNumber,
            deliveryProofObjectPath = proofPath
        )
        check(
            remote.orderId == orderId &&
                remote.status == "PAID" &&
                remote.amountCentavos == amountCentavos
        ) {
            "Supabase did not confirm the payment receipt for this order"
        }
        database.withTransaction {
            val current = paymentDao.getPaymentById(payment.id) ?: payment
            val wasPaid = current.status == "PAID"
            val now = System.currentTimeMillis()
            paymentDao.updatePayment(
                current.copy(
                    status = "PAID",
                    remoteId = remote.paymentId,
                    operationId = operationId,
                    paidAt = current.paidAt ?: now
                )
            )
            if (!wasPaid) {
                val order = requireNotNull(orderDao.getOrderById(payment.orderId)?.order) {
                    "Payment order was deleted before confirmation"
                }
                val paidTotal = paymentDao.getPaymentsForOrder(payment.orderId)
                    .filter { it.status == "PAID" }
                    .sumOf { BigDecimal.valueOf(it.amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact() }
                val totalCentavos = BigDecimal.valueOf(order.total).movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP).longValueExact()
                orderDao.updatePaymentState(
                    payment.orderId,
                    if (paidTotal >= totalCentavos) "PAID" else "PENDING",
                    payment.paymentMethod,
                    now
                )
                if (order.customerId > 0L) customerDao.addPaidSpend(order.customerId, payment.amount, now)
            }
        }
        recordCanonicalRemoteId(item, remote.paymentId)
        return true
    }

    private fun com.aesprt.aquahub.data.local.entity.PaymentEntity.deliveryPaymentProofObjectPath(): String? =
        metadataJson?.let { raw ->
            runCatching {
                Json.parseToJsonElement(raw).jsonObject["delivery_payment_proof_object_path"]
                    ?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }?.takeIf(String::isNotBlank)

    private suspend fun pushStockTarget(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity) {
        val product = requireNotNull(productDao.getProductById(item.resourceLocalId)) {
            "Product was deleted before inventory replay"
        }
        val stationId = stationRemoteId(product.stationId)
        val productId = requireNotNull(supabaseCatalog.resolveProductId(item.resourceRemoteId)) {
            "Product is not present in Supabase"
        }
        val target = item.stockTarget ?: error(
            "Legacy relative stock intent is quarantined; a fresh physical count and revision are required"
        )
        val expectedVersion = item.expectedInventoryVersion ?: error(
            "Stock intent has no server revision and cannot be replayed safely"
        )
        val balance = supabaseInventory.setQuantity(
            stationId = stationId,
            productId = productId,
            quantity = target,
            expectedVersion = expectedVersion,
            operationId = item.operationId,
            reason = "Revision-checked offline stock count"
        )
        productDao.updateProduct(product.copy(stockQuantity = balance.quantity, inventoryVersion = balance.version))
    }

    private suspend fun pushProduct(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity) {
        val row = requireNotNull(productDao.getProductById(item.resourceLocalId)) { "Product was deleted before Supabase replay" }
        if (item.operation == SyncOperation.STOCK_ADJUSTMENT.name) {
            pushStockTarget(item)
        } else if (item.operation == SyncOperation.ARCHIVE.name) {
            supabaseCatalog.archiveProduct(item.resourceRemoteId)
        } else {
            val remote = supabaseCatalog.upsertProduct(stationRemoteId(row.stationId), row.toDomain(), item.resourceRemoteId, includeStock = false)
            recordCanonicalRemoteId(item, remote.id)
        }
    }

    private suspend fun pushContainer(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity) {
        val row = requireNotNull(containerDao.getContainerById(item.resourceLocalId)) {
            "Container was deleted before Supabase replay"
        }
        val hasPendingLiveMovement = syncDao.hasOutstandingLiveContainerMovement(row.stationId, row.sizeLabel)
        val remoteId = supabaseContainer.ensureContainer(
            stationId = stationRemoteId(row.stationId),
            container = row.toDomain(),
            seedSnapshot = !hasPendingLiveMovement
        )
        recordCanonicalRemoteId(item, remoteId)
    }

    private suspend fun pushContainerMovement(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity) {
        val row = requireNotNull(containerTransactionDao.getTransactionById(item.resourceLocalId)) {
            "Container movement was deleted before Supabase replay"
        }.toDomain()
        val customerRemoteId = row.customerId?.let {
            syncDao.getMetadata(SyncResourceType.CUSTOMER.name, it)?.remoteId
                ?.let { reference -> supabaseCatalog.resolveCustomerId(reference) }
        }
        val orderRemoteId = row.orderId?.let {
            syncDao.getMetadata(SyncResourceType.ORDER.name, it)?.remoteId
        }
        val container = containerDao.getContainerBySizeLabel(row.stationId, row.containerSize)
        supabaseContainer.applyMovement(
            stationId = stationRemoteId(row.stationId),
            transaction = row,
            legacyId = item.resourceRemoteId,
            operationId = item.operationId,
            customerId = customerRemoteId,
            orderId = orderRemoteId,
            applyInventory = item.operation != SyncOperation.IMPORT.name,
            tracksCustomerBalance = (row.customerId ?: 0L) > 0L,
            depositPrice = container?.depositPrice ?: 0.0
        )
        recordSyncedPreservingRemoteId(item)
    }

    private suspend fun pushExpense(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity) {
        val row = requireNotNull(expenseDao.getExpense(item.resourceLocalId)) { "Expense was deleted before Supabase replay" }
        if (item.operation == SyncOperation.ARCHIVE.name) {
            row.remoteId?.let { supabaseExpense.archive(it) }
            return
        }
        val expense = com.aesprt.aquahub.domain.model.Expense(
            stationId = row.stationId,
            amountCentavos = row.amountCentavos,
            category = runCatching { com.aesprt.aquahub.domain.model.ExpenseCategory.valueOf(row.category) }.getOrDefault(com.aesprt.aquahub.domain.model.ExpenseCategory.OTHER),
            description = row.description,
            vendor = row.vendor,
            paymentMethod = runCatching { com.aesprt.aquahub.domain.model.ExpensePaymentMethod.valueOf(row.paymentMethod) }.getOrDefault(com.aesprt.aquahub.domain.model.ExpensePaymentMethod.OTHER),
            referenceNumber = row.referenceNumber,
            notes = row.notes,
            incurredAt = row.incurredAt,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
            isActive = row.isActive
        )
        val remote = supabaseExpense.save(
            stationRemoteId(row.stationId), expense, row.remoteId, row.operationId ?: item.operationId
        )
        database.withTransaction {
            expenseDao.getExpense(row.id)?.let { current ->
                expenseDao.updateExpense(
                    current.copy(
                        remoteId = remote.id,
                        firebaseId = remote.firebaseId,
                        organizationRemoteId = remote.organizationId,
                        createdByRemoteId = remote.createdBy,
                        receiptPath = remote.receiptPath,
                        operationId = remote.operationId ?: current.operationId
                    )
                )
            }
            recordCanonicalRemoteId(item, remote.id)
        }
    }

    private suspend fun pushCustomer(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity) {
        if (item.operation == SyncOperation.ARCHIVE.name) {
            supabaseCatalog.archiveCustomer(item.resourceRemoteId)
            return
        }
        val row = requireNotNull(customerDao.getCustomerById(item.resourceLocalId)) {
            "Customer was deleted before Supabase replay"
        }
        val stationRemoteId = stationRemoteId(row.customer.stationId)
        val remote = supabaseCatalog.upsertCustomer(
            stationId = stationRemoteId,
            customer = row.toDomain(),
            legacyId = item.resourceRemoteId
        )
        recordCanonicalRemoteId(item, remote.id)
    }

    private suspend fun pushAddress(item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity) {
        if (item.operation == SyncOperation.ARCHIVE.name) {
            supabaseCatalog.archiveAddress(item.resourceRemoteId)
            return
        }
        val address = requireNotNull(customerDao.getAddressById(item.resourceLocalId)) {
            "Customer address was deleted before Supabase replay"
        }
        val parent = requireNotNull(customerDao.getCustomerById(address.customerId)) {
            "Address customer was deleted before Supabase replay"
        }
        val stationRemoteId = stationRemoteId(parent.customer.stationId)
        val customerLegacyId = requireNotNull(
            syncDao.getMetadata(SyncResourceType.CUSTOMER.name, address.customerId)?.remoteId
        ) { "Customer remote mapping is missing" }
        val customerRemoteId = requireNotNull(
            supabaseCatalog.resolveCustomerId(customerLegacyId)
        ) { "Customer is not present in Supabase yet" }
        val remote = supabaseCatalog.upsertAddress(
            stationId = stationRemoteId,
            customerId = customerRemoteId,
            address = address.toDomain(),
            legacyId = item.resourceRemoteId
        )
        recordCanonicalRemoteId(item, remote.id)
    }

    private suspend fun recordCanonicalRemoteId(
        item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity,
        remoteId: String
    ) {
        syncDao.getMetadata(item.resourceType, item.resourceLocalId)?.let { metadata ->
            syncDao.upsertMetadata(
                metadata.copy(
                    remoteId = remoteId,
                    lastSyncedAt = System.currentTimeMillis(),
                    lastServerUpdatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** Movement firebase_id is the durable client key; never replace it with the UUID returned by RPC. */
    private suspend fun recordSyncedPreservingRemoteId(
        item: com.aesprt.aquahub.data.local.entity.SyncQueueEntity
    ) {
        syncDao.getMetadata(item.resourceType, item.resourceLocalId)?.let { metadata ->
            val syncedAt = System.currentTimeMillis()
            syncDao.upsertMetadata(
                metadata.copy(
                    lastSyncedAt = syncedAt,
                    lastServerUpdatedAt = syncedAt
                )
            )
        }
    }

    private suspend fun stationRemoteId(stationLocalId: Long): String =
        requireNotNull(syncDao.getMetadata(SyncResourceType.STATION.name, stationLocalId)?.remoteId) {
            "Station remote mapping is missing"
        }
}

private fun String.toEpochMillisOrNow(): Long =
    runCatching { Instant.parse(this).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
