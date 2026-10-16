package com.aesprt.aquahub.sync

import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.dao.OfflinePosDao
import com.aesprt.aquahub.data.local.dao.OrderDao
import com.aesprt.aquahub.data.local.dao.PaymentDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.offline.OfflinePosCryptography
import com.aesprt.aquahub.data.offline.SupabaseOfflinePosDataSource
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.supabase.payment.SupabasePaymentDataSource
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Network reconciliation for durable cash operations. Never removes a local record. */
class OfflinePosSyncCoordinator(
    private val database: AquaHubDatabase,
    private val offlinePosDao: OfflinePosDao,
    private val orderDao: OrderDao,
    private val paymentDao: PaymentDao,
    private val syncDao: SyncDao,
    private val remote: SupabaseOfflinePosDataSource,
    private val paymentRemote: SupabasePaymentDataSource,
    private val cryptography: OfflinePosCryptography = OfflinePosCryptography()
) {
    suspend fun syncPending(): Boolean {
        val profileUid = SupabaseClientProvider.client.auth.currentUserOrNull()?.id ?: return false
        val now = System.currentTimeMillis()
        offlinePosDao.recoverInterruptedOperations(now)
        val ready = offlinePosDao.getReadyOperations(now, 20)
        var retryableFailure = false
        for (operation in ready) {
            if (operation.profileUid != profileUid) continue
            if (offlinePosDao.claimOperation(operation.operationId, now + CLAIM_TIMEOUT_MS) != 1) continue
            try {
                val grant = offlinePosDao.getGrant(operation.grantId)
                    ?: error("LOCAL_GRANT_MISSING")
                val lease = offlinePosDao.getLease(operation.leaseId)
                    ?: error("LOCAL_LEASE_MISSING")
                val grantClaims = cryptography.verifyServerToken(grant.signedGrant).claims
                val leaseClaims = cryptography.verifyServerToken(lease.signedLease).claims
                check(grant.profileUid == profileUid && lease.profileUid == profileUid && operation.deviceId == grant.deviceId && operation.deviceId == lease.deviceId) {
                    "LOCAL_AUTHORIZATION_SCOPE_MISMATCH"
                }
                check(grantClaims["sub"]?.jsonPrimitive?.contentOrNull == profileUid &&
                    grantClaims["org"]?.jsonPrimitive?.contentOrNull == operation.organizationId &&
                    grantClaims["station"]?.jsonPrimitive?.contentOrNull == syncDao.getMetadata(SyncResourceType.STATION.name, operation.stationId)?.remoteId &&
                    grantClaims["device"]?.jsonPrimitive?.contentOrNull == operation.deviceId &&
                    leaseClaims["jti"]?.jsonPrimitive?.contentOrNull == operation.leaseId) {
                    "LOCAL_SIGNED_SCOPE_MISMATCH"
                }
                check(cryptography.digestHex(operation.canonicalPayload) == operation.payloadDigest) {
                    "LOCAL_PAYLOAD_DIGEST_MISMATCH"
                }
                val result = remote.commit(
                    deviceId = operation.deviceId,
                    grantToken = grant.signedGrant,
                    leaseToken = lease.signedLease,
                    canonicalPayload = operation.canonicalPayload,
                    payloadDigest = operation.payloadDigest,
                    deviceSignature = operation.deviceSignature
                )
                check(result.status == "COMMITTED" && result.operationId == operation.operationId) {
                    "SERVER_COMMIT_RESULT_MISMATCH"
                }
                val localOrderId = operation.localOrderId ?: error("LOCAL_ORDER_MISSING")
                val localOrder = orderDao.getOrderEntityById(localOrderId) ?: error("LOCAL_ORDER_MISSING")
                val payment = paymentDao.getPaymentByOperationId("offline-pos-payment:${operation.operationId}")
                    ?: error("LOCAL_PAYMENT_MISSING")
                val remotePayment = paymentRemote.getPaymentForOrder(result.orderId)
                    ?: error("SERVER_PAYMENT_NOT_HYDRATED")
                check(remotePayment.status == "PAID" && remotePayment.amountCentavos == operation.totalCentavos) {
                    "SERVER_PAYMENT_AMOUNT_MISMATCH"
                }
                val completedAt = remotePayment.paidAt ?: System.currentTimeMillis()
                database.withTransaction {
                    orderDao.updateOrder(
                        localOrder.copy(
                            orderNumber = result.orderNumber ?: localOrder.orderNumber,
                            paymentStatus = "PAID",
                            orderStatus = "COMPLETED",
                            stationNote = "Offline cash sale verified by the server.",
                            updatedAt = completedAt
                        )
                    )
                    paymentDao.updatePayment(
                        payment.copy(
                            status = "PAID",
                            remoteId = remotePayment.id,
                            amount = remotePayment.amountCentavos / 100.0,
                            referenceNumber = remotePayment.referenceNumber,
                            createdAt = remotePayment.createdAt,
                            paidAt = remotePayment.paidAt,
                            provider = remotePayment.provider,
                            providerTransactionId = remotePayment.providerTransactionId,
                            metadataJson = remotePayment.metadataJson
                        )
                    )
                    val current = orderDao.getOrderEntityById(localOrderId)
                    val savedPayment = paymentDao.getPaymentById(payment.id)
                    syncDao.upsertMetadata(
                        SyncMetadataEntity(
                            resourceType = SyncResourceType.ORDER.name,
                            localId = localOrderId,
                            remoteId = result.orderId,
                            stationLocalId = operation.stationId,
                            lastLocalUpdatedAt = completedAt,
                            lastServerUpdatedAt = completedAt,
                            lastSyncedAt = completedAt
                        )
                    )
                    if (savedPayment != null) {
                        syncDao.upsertMetadata(
                            SyncMetadataEntity(
                                resourceType = SyncResourceType.PAYMENT.name,
                                localId = savedPayment.id,
                                remoteId = remotePayment.id,
                                stationLocalId = operation.stationId,
                                lastLocalUpdatedAt = completedAt,
                                lastServerUpdatedAt = completedAt,
                                lastSyncedAt = completedAt
                            )
                        )
                    }
                    offlinePosDao.markCommitted(operation.operationId, result.orderId, completedAt)
                    offlinePosDao.insertReconciliationEvent(
                        com.aesprt.aquahub.data.local.entity.OfflinePosReconciliationEventEntity(
                            eventId = java.util.UUID.randomUUID().toString(),
                            operationId = operation.operationId,
                            leaseId = operation.leaseId,
                            stationId = operation.stationId,
                            actorUid = profileUid,
                            eventType = "CASH_SALE_CONFIRMED",
                            reason = "Server accepted the idempotent operation and confirmed its payment.",
                            payloadJson = "{\"remote_order_id\":\"${result.orderId}\",\"payload_digest\":\"${operation.payloadDigest}\"}",
                            createdAtMs = completedAt
                        )
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                if (isPermanent(message)) {
                    offlinePosDao.markReconciliationRequired(
                        operation.operationId,
                        errorCode(message),
                        "This locally recorded cash sale needs owner review. Its original sale details are preserved."
                    )
                } else {
                    val attempt = operation.attemptCount + 1
                    if (attempt >= MAX_ATTEMPTS) {
                        offlinePosDao.markReconciliationRequired(
                            operation.operationId,
                            "RETRY_LIMIT_REACHED",
                            "Automatic retries stopped. The locally recorded cash sale is preserved for reconciliation."
                        )
                    } else {
                        val delayMs = (INITIAL_RETRY_MS * (1L shl (attempt - 1).coerceAtMost(8)))
                            .coerceAtMost(MAX_RETRY_MS)
                        offlinePosDao.recordFailure(
                            operation.operationId,
                            state = "RETRY",
                            nextAttemptAtMs = System.currentTimeMillis() + delayMs,
                            errorCode = "TEMPORARY_SYNC_FAILURE",
                            errorMessage = "The sale is saved on this device and will retry when the service is available."
                        )
                        retryableFailure = true
                    }
                }
            }
        }
        return retryableFailure
    }

    private fun isPermanent(message: String): Boolean = listOf(
        "LOCAL_GRANT_MISSING", "LOCAL_LEASE_MISSING", "LOCAL_AUTHORIZATION_SCOPE_MISMATCH",
        "LOCAL_SIGNED_SCOPE_MISMATCH", "LOCAL_PAYLOAD_DIGEST_MISMATCH", "SERVER_COMMIT_RESULT_MISMATCH",
        "LOCAL_ORDER_MISSING", "LOCAL_PAYMENT_MISSING", "SERVER_PAYMENT_AMOUNT_MISMATCH",
        "RECONCILIATION_REQUIRED", "OFFLINE_AUTHORIZATION_SCOPE_MISMATCH",
        "OFFLINE_OPERATION_OUTSIDE_AUTHORIZATION_WINDOW", "OFFLINE_DEVICE_SIGNATURE_INVALID",
        "OFFLINE_PAYLOAD_DIGEST_MISMATCH", "409", "400", "401", "403"
    ).any(message::contains)

    private fun errorCode(message: String) = when {
        "PRICE" in message || "SNAPSHOT" in message -> "CATALOG_SNAPSHOT_CONFLICT"
        "AUTHORIZATION" in message || "SCOPE" in message || "REVOKED" in message -> "AUTHORIZATION_REVOKED_OR_CHANGED"
        "INVENTORY" in message || "LEASE" in message || "STOCK" in message -> "INVENTORY_RECONCILIATION_REQUIRED"
        "DIGEST" in message || "SIGNATURE" in message -> "INTEGRITY_CHECK_FAILED"
        else -> "SERVER_REJECTED_OPERATION"
    }

    companion object {
        private const val CLAIM_TIMEOUT_MS = 5 * 60 * 1000L
        private const val MAX_ATTEMPTS = 8
        private const val INITIAL_RETRY_MS = 60 * 1000L
        private const val MAX_RETRY_MS = 6 * 60 * 60 * 1000L
    }
}
