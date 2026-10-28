package com.aesprt.aquahub.data.repository

import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.dao.CustomerDao
import com.aesprt.aquahub.data.local.dao.OrderDao
import com.aesprt.aquahub.data.local.dao.PaymentDao
import com.aesprt.aquahub.data.local.dao.PaymentRefundDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.entity.PaymentEntity
import com.aesprt.aquahub.data.local.entity.PaymentRefundEntity
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.supabase.payment.SupabasePaymentDataSource
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.domain.model.Payment
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.PaymentStatus
import com.aesprt.aquahub.domain.model.PaymentRefund
import com.aesprt.aquahub.domain.model.RefundStatus
import com.aesprt.aquahub.domain.model.RefundObligationSummary
import com.aesprt.aquahub.domain.repository.PaymentCache
import com.aesprt.aquahub.domain.repository.PaymentRepository
import com.aesprt.aquahub.sync.SyncOperation
import com.aesprt.aquahub.sync.SyncQueueWriter
import com.aesprt.aquahub.sync.SyncResourceType
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

class RoomPaymentRepository(
    private val paymentDao: PaymentDao,
    private val paymentRefundDao: PaymentRefundDao,
    private val orderDao: OrderDao,
    private val customerDao: CustomerDao,
    private val syncDao: SyncDao,
    private val database: AquaHubDatabase,
    private val syncQueue: SyncQueueWriter,
    private val supabasePayment: SupabasePaymentDataSource
) : PaymentRepository, PaymentCache {
    override fun observePaymentsForCustomer(customerId: Long): Flow<List<Payment>> =
        paymentDao.observePaymentsForCustomer(customerId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getPaymentsForOrder(orderId: Long): List<Payment> =
        paymentDao.getPaymentsForOrder(orderId).map { it.toDomain() }

    override suspend fun refreshPaymentsForOrder(orderId: Long): List<Payment> {
        return refreshPaymentsForOrders(listOf(orderId))
    }

    override suspend fun refreshPaymentsForOrders(orderIds: List<Long>): List<Payment> {
        val requestedOrderIds = orderIds.distinct()
        if (requestedOrderIds.isEmpty()) return emptyList()
        if (SupabaseClientProvider.client.auth.currentUserOrNull() == null) {
            return getPaymentsForOrders(requestedOrderIds)
        }

        val remoteOrderToLocal = requestedOrderIds.mapNotNull { localId ->
            syncDao.getMetadata(SyncResourceType.ORDER.name, localId)?.remoteId?.let { it to localId }
        }.toMap()
        remoteOrderToLocal.keys.toList().chunked(MAX_REMOTE_ORDER_IDS_PER_QUERY).forEach { remoteOrderIds ->
            supabasePayment.listPaymentsForOrders(remoteOrderIds).forEach rowLoop@ { remote ->
                val localOrderId = remoteOrderToLocal[remote.orderId] ?: return@rowLoop
                cacheRemotePayment(
                    orderId = localOrderId,
                    remoteId = remote.id,
                    method = remote.paymentMethod.toPaymentMethod(),
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
        }
        return getPaymentsForOrders(requestedOrderIds)
    }

    override suspend fun getPaymentsForOrders(orderIds: List<Long>): List<Payment> =
        if (orderIds.isEmpty()) emptyList()
        else orderIds.distinct().chunked(MAX_LOCAL_ORDER_IDS_PER_QUERY)
            .flatMap { paymentDao.getPaymentsForOrders(it) }
            .map { it.toDomain() }

    override suspend fun getRefundsForOrder(orderId: Long): List<PaymentRefund> =
        getRefundsForOrders(listOf(orderId))

    override suspend fun getRefundObligationReport(
        stationId: Long,
        startAt: Long,
        endAt: Long
    ): List<RefundObligationSummary> {
        require(stationId > 0L) { "Choose a station before loading its refund report" }
        require(startAt < endAt) { "The report start must be before its end" }
        check(SupabaseClientProvider.client.auth.currentUserOrNull() != null) {
            "Sign in to load the refund obligation report"
        }
        val remoteStationId = syncDao.getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId
            ?: error("Sync this station before loading its refund report")
        return supabasePayment.refundObligationReport(remoteStationId, startAt, endAt).map { it.toDomain() }
    }

    override suspend fun getRefundsForOrders(orderIds: List<Long>): List<PaymentRefund> {
        if (orderIds.isEmpty()) return emptyList()
        val remoteOrderToLocal = orderIds.mapNotNull { localId ->
            syncDao.getMetadata(SyncResourceType.ORDER.name, localId)?.remoteId?.let { it to localId }
        }.toMap()
        if (remoteOrderToLocal.isNotEmpty() && SupabaseClientProvider.client.auth.currentUserOrNull() != null) {
            val localPayments = paymentDao.getPaymentsForOrders(orderIds)
            val paymentIdByRemote = buildMap {
                localPayments.forEach { payment ->
                    val remoteId = payment.remoteId
                        ?: syncDao.getMetadata(SyncResourceType.PAYMENT.name, payment.id)?.remoteId
                    if (remoteId != null) put(remoteId, payment.id)
                }
            }
            remoteOrderToLocal.keys.toList().chunked(200).forEach { remoteOrderIds ->
                val rows = supabasePayment.listOrderRefundsForOrders(remoteOrderIds)
                database.withTransaction {
                    rows.forEach { row ->
                        val localOrderId = row.orderId?.let(remoteOrderToLocal::get) ?: return@forEach
                        val localPaymentId = row.originalPaymentId?.let(paymentIdByRemote::get)
                        val byRemote = paymentRefundDao.getByRemoteId(row.refundId)
                        val byOperation = row.operationId?.let { operationId ->
                            paymentRefundDao.getByOperationId(operationId)
                        }
                        val existing = byRemote ?: byOperation
                        val operationId = row.operationId ?: existing?.operationId ?: "server-refund:${row.refundId}"
                        val next = PaymentRefundEntity(
                            id = existing?.id ?: 0,
                            remoteId = row.refundId,
                            orderId = localOrderId,
                            originalPaymentId = localPaymentId ?: existing?.originalPaymentId,
                            operationId = operationId,
                            completionOperationId = existing?.completionOperationId,
                            failureOperationId = existing?.failureOperationId,
                            amountCentavos = row.amountCentavos,
                            paymentMethod = row.paymentMethod,
                            status = row.status,
                            reason = row.reason,
                            externalReference = row.externalReference,
                            failureReason = row.failureReason,
                            createdAt = row.createdAt,
                            completedAt = row.completedAt,
                            updatedAt = System.currentTimeMillis()
                        )
                        if (existing == null) paymentRefundDao.insert(next) else paymentRefundDao.update(next)
                    }
                }
            }
        }
        return orderIds.distinct().chunked(MAX_LOCAL_ORDER_IDS_PER_QUERY)
            .flatMap { paymentRefundDao.getForOrders(it) }
            .map { it.toDomain() }
    }

    override suspend fun requestRefund(paymentId: Long, amountCentavos: Long, reason: String): PaymentRefund {
        require(amountCentavos > 0L) { "Refund amount must be greater than zero" }
        val normalizedReason = reason.trim().take(300)
        require(normalizedReason.isNotEmpty()) { "Enter why the payment is being refunded" }
        val payment = requireNotNull(paymentDao.getPaymentById(paymentId)) { "Payment not found" }
        require(payment.status == PaymentStatus.PAID.name) { "Only a confirmed payment can be refunded" }
        val remotePaymentId = payment.remoteId
            ?: syncDao.getMetadata(SyncResourceType.PAYMENT.name, payment.id)?.remoteId
            ?: error("Sync this payment before requesting a refund")
        check(SupabaseClientProvider.client.auth.currentUserOrNull() != null) {
            "Sign in before requesting a refund"
        }

        val localRequest = database.withTransaction {
            paymentRefundDao.getUnconfirmedRequest(payment.orderId, payment.id, amountCentavos, normalizedReason)
                ?: PaymentRefundEntity(
                    orderId = payment.orderId,
                    originalPaymentId = payment.id,
                    operationId = "refund:${UUID.randomUUID()}",
                    amountCentavos = amountCentavos,
                    paymentMethod = payment.paymentMethod,
                    status = RefundStatus.REQUESTING.name,
                    reason = normalizedReason,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                ).let { request -> request.copy(id = paymentRefundDao.insert(request)) }
        }
        val response = supabasePayment.requestRefund(
            paymentId = remotePaymentId,
            operationId = localRequest.operationId,
            amountCentavos = amountCentavos,
            reason = normalizedReason
        )
        check(response.amountCentavos == amountCentavos && response.status in setOf(
            RefundStatus.PENDING.name,
            RefundStatus.COMPLETED.name,
            RefundStatus.FAILED.name
        )) {
            "Supabase did not confirm the requested refund"
        }
        val saved = localRequest.copy(
            remoteId = response.refundId,
            status = response.status,
            updatedAt = System.currentTimeMillis()
        )
        database.withTransaction {
            paymentRefundDao.update(saved)
            refreshLocalOrderPaymentStatus(payment.orderId)
        }
        return requireNotNull(paymentRefundDao.getByRemoteId(response.refundId)).toDomain()
    }

    override suspend fun completeRefund(
        refundId: String,
        actualRefundConfirmed: Boolean,
        externalReference: String?
    ): PaymentRefund {
        require(actualRefundConfirmed) { "Confirm that the money was actually returned" }
        val normalizedReference = externalReference?.trim()?.takeIf(String::isNotEmpty)
        var local = requireNotNull(paymentRefundDao.getByRemoteId(refundId)) { "Refund not found" }
        if (local.status == RefundStatus.COMPLETED.name) return local.toDomain()
        require(local.status == RefundStatus.PENDING.name) { "Only a pending refund can be completed" }
        if (local.paymentMethod !in setOf(PaymentMethod.CASH.name, PaymentMethod.CASH_ON_DELIVERY.name)) {
            require(!normalizedReference.isNullOrBlank()) { "Enter the external refund confirmation reference" }
        }
        val operationId = local.completionOperationId ?: "refund-complete:${UUID.randomUUID()}"
        if (local.completionOperationId != null) {
            require(local.externalReference == normalizedReference) {
                "Retry the refund confirmation with the same external reference"
            }
        }
        local = local.copy(
            completionOperationId = operationId,
            externalReference = normalizedReference,
            updatedAt = System.currentTimeMillis()
        )
        paymentRefundDao.update(local)
        val response = supabasePayment.completeRefund(refundId, operationId, true, normalizedReference)
        check(response.refundId == refundId && response.status == RefundStatus.COMPLETED.name) {
            "Supabase did not confirm the completed refund"
        }
        val saved = local.copy(
            status = response.status,
            completedAt = response.completedAtText?.let { java.time.Instant.parse(it).toEpochMilli() },
            updatedAt = System.currentTimeMillis()
        )
        database.withTransaction {
            paymentRefundDao.update(saved)
            refreshLocalOrderPaymentStatus(local.orderId)
        }
        return saved.toDomain()
    }

    override suspend fun failRefund(refundId: String, failureReason: String): PaymentRefund {
        val normalizedReason = failureReason.trim().take(300)
        require(normalizedReason.isNotEmpty()) { "Explain why the refund failed" }
        var local = requireNotNull(paymentRefundDao.getByRemoteId(refundId)) { "Refund not found" }
        if (local.status == RefundStatus.FAILED.name) return local.toDomain()
        require(local.status == RefundStatus.PENDING.name) { "Only a pending refund can be failed" }
        if (local.failureOperationId != null) require(local.failureReason == normalizedReason) {
            "Retry the failure update with the same reason"
        }
        local = local.copy(
            failureOperationId = local.failureOperationId ?: "refund-fail:${UUID.randomUUID()}",
            failureReason = normalizedReason,
            updatedAt = System.currentTimeMillis()
        )
        paymentRefundDao.update(local)
        val response = supabasePayment.failRefund(refundId, local.failureOperationId!!, normalizedReason)
        check(response.refundId == refundId && response.status == RefundStatus.FAILED.name) {
            "Supabase did not confirm the failed refund state"
        }
        val saved = local.copy(status = response.status, updatedAt = System.currentTimeMillis())
        database.withTransaction {
            paymentRefundDao.update(saved)
            refreshLocalOrderPaymentStatus(local.orderId)
        }
        return saved.toDomain()
    }

    private suspend fun refreshLocalOrderPaymentStatus(orderId: Long) {
        val order = orderDao.getOrderById(orderId)?.order ?: return
        val collected = paymentDao.getPaymentsForOrder(orderId)
            .filter { it.status == PaymentStatus.PAID.name }
            .sumOf { it.amount.toCentavos() }
        val refunded = paymentRefundDao.getForOrder(orderId)
            .filter { it.status == RefundStatus.COMPLETED.name }
            .sumOf { it.amountCentavos }
        val status = when {
            collected > 0 && collected >= order.total.toCentavos() && refunded >= collected -> PaymentStatus.REFUNDED.name
            collected > 0 && collected >= order.total.toCentavos() -> PaymentStatus.PAID.name
            else -> PaymentStatus.PENDING.name
        }
        orderDao.updatePaymentState(orderId, status, order.paymentMethod, System.currentTimeMillis())
    }

    override suspend fun recordPayment(
        orderId: Long,
        amount: Double,
        method: PaymentMethod,
        referenceNumber: String?,
        deliveryProofObjectPath: String?,
        requireRemoteConfirmation: Boolean
    ): Payment {
        require(amount.isFinite() && amount > 0.0) { "Payment amount must be greater than zero" }
        require(method in setOf(PaymentMethod.CASH, PaymentMethod.CASH_ON_DELIVERY, PaymentMethod.GCASH, PaymentMethod.MAYA)) {
            "Unsupported payment method"
        }
        if (requireRemoteConfirmation) {
            require(!deliveryProofObjectPath.isNullOrBlank()) { "Upload proof of payment before recording collection" }
            check(SupabaseClientProvider.client.auth.currentUserOrNull() != null) {
                "Sign in before recording rider payment"
            }
        }

        val normalizedAmount = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toDouble()
        require(kotlin.math.abs(amount - normalizedAmount) < 0.000001) {
            "Payment amount must use at most two decimal places"
        }
        val amountCentavos = normalizedAmount.toCentavos()
        val prepared = database.withTransaction {
            val order = requireNotNull(orderDao.getOrderById(orderId)?.order) { "Order not found" }
            require(order.orderStatus != OrderStatus.CANCELLED.name && order.orderStatus != OrderStatus.REJECTED.name) {
                "Payments cannot be recorded for a cancelled or rejected order"
            }
            if (requireRemoteConfirmation) {
                require(order.deliveryMode.equals("DELIVERY", ignoreCase = true)) {
                    "Proof-confirmed collection is only available for delivery orders"
                }
                require(order.orderStatus in setOf(OrderStatus.DELIVERED.name, OrderStatus.COMPLETED.name)) {
                    "Mark the delivery as delivered before collecting payment"
                }
            }

            val payments = paymentDao.getPaymentsForOrder(orderId)
            val proofMetadata = deliveryProofObjectPath?.let(::deliveryProofMetadata)
            val alreadyPaidCentavos = payments
                .filter { it.status == PaymentStatus.PAID.name }
                .sumOf { it.amount.toCentavos() }
            val remainingCentavos = (order.total.toCentavos() - alreadyPaidCentavos).coerceAtLeast(0L)
            require(remainingCentavos > 0L) { "This order is already fully paid" }
            require(amountCentavos <= remainingCentavos) { "Payment exceeds the remaining balance" }

            if (requireRemoteConfirmation) {
                val matchingPaid = payments.firstOrNull {
                    it.metadataJson == proofMetadata &&
                        it.amount.toCentavos() == amountCentavos &&
                        it.paymentMethod == method.name &&
                        it.status == PaymentStatus.PAID.name
                }
                if (matchingPaid != null) {
                    return@withTransaction PreparedPayment(matchingPaid, order.customerId, alreadyConfirmed = true)
                }

                val pendingProof = payments.firstOrNull {
                    it.status == PaymentStatus.PENDING.name && !it.deliveryPaymentProofObjectPath().isNullOrBlank()
                }
                if (pendingProof != null) {
                    require(
                        pendingProof.amount.toCentavos() == amountCentavos &&
                            pendingProof.paymentMethod == method.name
                    ) {
                        "A previous delivery collection is still syncing. Retry sync before changing its amount or method."
                    }
                    val updatedPending = pendingProof.copy(
                        referenceNumber = referenceNumber?.trim()?.takeIf(String::isNotEmpty),
                        metadataJson = proofMetadata
                    )
                    paymentDao.updatePayment(updatedPending)
                    return@withTransaction PreparedPayment(updatedPending, order.customerId, alreadyConfirmed = false)
                }

                val now = System.currentTimeMillis()
                val operationId = UUID.randomUUID().toString()
                val pending = PaymentEntity(
                    orderId = orderId,
                    paymentMethod = method.name,
                    amount = normalizedAmount,
                    status = PaymentStatus.PENDING.name,
                    referenceNumber = referenceNumber?.trim()?.takeIf(String::isNotEmpty),
                    createdAt = now,
                    paidAt = null,
                    metadataJson = proofMetadata,
                    operationId = operationId
                )
                val paymentId = paymentDao.insertPayment(pending)
                val stored = pending.copy(id = paymentId)
                checkNotNull(syncQueue.enqueue(
                    SyncResourceType.PAYMENT,
                    paymentId,
                    order.stationId,
                    SyncOperation.CREATE,
                    now,
                    stableOperationId = operationId
                )) { "Sign in before recording rider payment" }
                PreparedPayment(stored, order.customerId, alreadyConfirmed = false)
            } else {
                val now = System.currentTimeMillis()
                val operationId = UUID.randomUUID().toString()
                val entity = PaymentEntity(
                    orderId = orderId,
                    paymentMethod = method.name,
                    amount = normalizedAmount,
                    status = PaymentStatus.PAID.name,
                    referenceNumber = referenceNumber?.trim()?.takeIf(String::isNotEmpty),
                    createdAt = now,
                    paidAt = now,
                    operationId = operationId
                )
                val paymentId = paymentDao.insertPayment(entity)
                val payment = entity.copy(id = paymentId)
                orderDao.updatePaymentState(
                    orderId = orderId,
                    status = aggregatePaymentStatus(order.total.toCentavos(), alreadyPaidCentavos + amountCentavos),
                    method = if (order.paymentMethod == PaymentMethod.CLIENT_ACCOUNT.name) PaymentMethod.CLIENT_ACCOUNT.name else method.name,
                    timestamp = now
                )
                if (order.customerId > 0L) customerDao.addPaidSpend(order.customerId, normalizedAmount, now)
                syncQueue.enqueue(
                    SyncResourceType.PAYMENT,
                    paymentId,
                    order.stationId,
                    SyncOperation.CREATE,
                    now,
                    stableOperationId = operationId
                )
                syncQueue.enqueue(SyncResourceType.ORDER, orderId, order.stationId, SyncOperation.UPDATE, now)
                PreparedPayment(payment, order.customerId, alreadyConfirmed = true)
            }
        }

        if (!requireRemoteConfirmation || prepared.alreadyConfirmed) return prepared.entity.toDomain()

        val remoteOrderId = requireNotNull(syncDao.getMetadata(SyncResourceType.ORDER.name, orderId)?.remoteId) {
            "This delivery is not synced yet. Keep it unpaid until it can sync."
        }
        val operationId = requireNotNull(prepared.entity.operationId)
        val remote = supabasePayment.recordPayment(
            orderId = remoteOrderId,
            operationId = operationId,
            amountCentavos = amountCentavos,
            paymentMethod = method.name,
            referenceNumber = referenceNumber?.trim()?.takeIf(String::isNotEmpty),
            deliveryProofObjectPath = deliveryProofObjectPath
        )
        check(
            remote.orderId == remoteOrderId &&
                remote.status == PaymentStatus.PAID.name &&
                remote.amountCentavos == amountCentavos
        ) {
            "Supabase did not confirm the payment; the order remains unpaid"
        }

        val confirmed = database.withTransaction {
            val current = requireNotNull(paymentDao.getPaymentById(prepared.entity.id)) { "Payment record disappeared" }
            if (current.status != PaymentStatus.PAID.name) {
                val now = System.currentTimeMillis()
                val saved = current.copy(
                    status = PaymentStatus.PAID.name,
                    remoteId = remote.paymentId,
                    operationId = operationId,
                    paidAt = now
                )
                paymentDao.updatePayment(saved)
                val order = requireNotNull(orderDao.getOrderById(orderId)?.order) { "Order not found" }
                val paidTotal = paymentDao.getPaymentsForOrder(orderId)
                    .filter { it.status == PaymentStatus.PAID.name }
                    .sumOf { it.amount.toCentavos() }
                orderDao.updatePaymentState(
                    orderId,
                    aggregatePaymentStatus(order.total.toCentavos(), paidTotal),
                    if (order.paymentMethod == PaymentMethod.CLIENT_ACCOUNT.name) PaymentMethod.CLIENT_ACCOUNT.name else method.name,
                    now
                )
                if (prepared.customerId > 0L) customerDao.addPaidSpend(prepared.customerId, normalizedAmount, now)
                saved
            } else {
                val updated = current.copy(remoteId = remote.paymentId, operationId = operationId)
                paymentDao.updatePayment(updated)
                updated
            }
        }
        return confirmed.toDomain()
    }

    override suspend fun cacheRemotePayment(
        orderId: Long,
        remoteId: String,
        method: PaymentMethod,
        amountCentavos: Long,
        status: PaymentStatus,
        referenceNumber: String?,
        createdAt: Long,
        paidAt: Long?,
        provider: String?,
        providerTransactionId: String?,
        metadataJson: String?,
        operationId: String?
    ) = database.withTransaction {
        require(amountCentavos > 0) { "Payment amount must be positive" }
        val order = requireNotNull(orderDao.getOrderById(orderId)?.order) { "Order not found" }
        val existing = paymentDao.getPaymentsForOrder(orderId).firstOrNull {
            it.remoteId == remoteId || (!operationId.isNullOrBlank() && it.operationId == operationId)
        }
        val wasPaid = existing?.status == PaymentStatus.PAID.name
        val row = PaymentEntity(
            id = existing?.id ?: 0,
            orderId = orderId,
            paymentMethod = method.name,
            amount = amountCentavos / 100.0,
            status = status.name,
            referenceNumber = referenceNumber,
            createdAt = createdAt,
            paidAt = paidAt,
            remoteId = remoteId,
            firebaseId = existing?.firebaseId,
            provider = provider,
            providerTransactionId = providerTransactionId,
            metadataJson = metadataJson,
            operationId = operationId
        )
        paymentDao.insertPayment(row)
        if (!wasPaid && status == PaymentStatus.PAID && order.customerId > 0L) {
            customerDao.addPaidSpend(order.customerId, amountCentavos / 100.0, System.currentTimeMillis())
        }
        Unit
    }

    private fun deliveryProofMetadata(path: String): String =
        "{\"delivery_payment_proof_object_path\":\"${path.replace("\\", "\\\\").replace("\"", "\\\"")}\"}"

    private fun PaymentEntity.deliveryPaymentProofObjectPath(): String? = metadataJson?.let { raw ->
        runCatching {
            val metadata = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
            (metadata["payment_proof_object_path"] ?: metadata["delivery_payment_proof_object_path"])
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private data class PreparedPayment(
        val entity: PaymentEntity,
        val customerId: Long,
        val alreadyConfirmed: Boolean
    )

    private fun Double.toCentavos(): Long = BigDecimal.valueOf(this)
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}

private fun String.toPaymentMethod(): PaymentMethod = when (trim().uppercase()) {
    "COD" -> PaymentMethod.CASH_ON_DELIVERY
    else -> PaymentMethod.valueOf(trim().uppercase())
}

private const val MAX_LOCAL_ORDER_IDS_PER_QUERY = 500
private const val MAX_REMOTE_ORDER_IDS_PER_QUERY = 200

internal fun aggregatePaymentStatus(totalCentavos: Long, paidCentavos: Long): String =
    if (paidCentavos >= totalCentavos && totalCentavos > 0L) PaymentStatus.PAID.name
    else PaymentStatus.PENDING.name
