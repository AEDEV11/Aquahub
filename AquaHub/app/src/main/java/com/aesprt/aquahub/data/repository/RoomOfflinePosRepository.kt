package com.aesprt.aquahub.data.repository

import android.content.Context
import android.os.SystemClock
import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.core.config.ReleaseFeatureFlags
import com.aesprt.aquahub.data.local.dao.OfflinePosDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.entity.OfflinePosGrantEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosLeaseEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosLeaseItemEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosOperationEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosOperationLineEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosReconciliationEventEntity
import com.aesprt.aquahub.data.local.entity.OrderEntity
import com.aesprt.aquahub.data.local.entity.OrderItemEntity
import com.aesprt.aquahub.data.local.entity.PaymentEntity
import com.aesprt.aquahub.data.offline.OfflineDeviceIdentity
import com.aesprt.aquahub.data.offline.OfflinePosCryptography
import com.aesprt.aquahub.data.offline.OfflinePosTimePolicy
import com.aesprt.aquahub.data.offline.SupabaseOfflinePosDataSource
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.local.dao.OrderDao
import com.aesprt.aquahub.data.local.dao.OrderItemDao
import com.aesprt.aquahub.data.local.dao.PaymentDao
import com.aesprt.aquahub.data.local.dao.ProductDao
import com.aesprt.aquahub.domain.model.OfflineCashSaleRequest
import com.aesprt.aquahub.domain.model.OfflineLeaseProductSnapshot
import com.aesprt.aquahub.domain.model.OfflinePosReservationItem
import com.aesprt.aquahub.domain.model.OfflinePosRecoveryLine
import com.aesprt.aquahub.domain.model.OfflinePosRecoveryRecord
import com.aesprt.aquahub.domain.model.OfflinePosStatus
import com.aesprt.aquahub.domain.model.ProductType
import com.aesprt.aquahub.domain.repository.OfflinePosRepository
import com.aesprt.aquahub.sync.SyncResourceType
import com.aesprt.aquahub.sync.SyncScheduler
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** Durable local side of server-authorized, cash-only offline walk-in POS. */
class RoomOfflinePosRepository(
    private val context: Context,
    private val database: AquaHubDatabase,
    private val offlinePosDao: OfflinePosDao,
    private val syncDao: SyncDao,
    private val productDao: ProductDao,
    private val orderDao: OrderDao,
    private val orderItemDao: OrderItemDao,
    private val paymentDao: PaymentDao,
    private val remote: SupabaseOfflinePosDataSource,
    private val scheduler: SyncScheduler,
    private val cryptography: OfflinePosCryptography = OfflinePosCryptography(),
    private val offlinePosEnabled: Boolean = ReleaseFeatureFlags.offlinePosEnabled,
) : OfflinePosRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    override suspend fun prepareReservation(
        stationId: Long,
        items: List<OfflinePosReservationItem>
    ): OfflinePosStatus {
        check(offlinePosEnabled) { ReleaseFeatureFlags.OFFLINE_POS_DISABLED_MESSAGE }
        require(items.isNotEmpty() && items.all { it.quantity in 1..500 }) { "Select valid products and quantities." }
        require(items.map { it.productLocalId }.distinct().size == items.size) { "Combine duplicate products before reserving stock." }
        val profileUid = requireNotNull(SupabaseClientProvider.client.auth.currentUserOrNull()?.id) {
            "Sign in while online before preparing offline sales."
        }
        val stationRemoteId = syncDao.getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId
            ?: error("This station has not synchronized to the server yet.")
        val organizationId = remote.getStationOrganizationId(stationRemoteId)
            ?: error("The server could not verify this station's organization.")
        val requested = items.map { item ->
            val metadata = syncDao.getMetadata(SyncResourceType.PRODUCT.name, item.productLocalId)
                ?: error("A selected product has not synchronized to the server.")
            val local = productDao.getProductById(item.productLocalId)
                ?: error("A selected product is no longer available locally.")
            require(local.stationId == stationId && local.isActive && local.isAvailable) {
                "A selected product is not available at this station."
            }
            metadata.remoteId to item.quantity
        }.sortedBy { it.first }
        val identity = OfflinePosCryptography.deviceIdentity(context)
        val bootCount = requireNotNull(identity.bootCount) { "This device cannot verify its boot session for offline authorization." }
        val currentLease = getStatus(stationId)
        if (currentLease.canCreateOfflineSale) {
            val covered = items.all { item ->
                currentLease.productSnapshots[item.productLocalId]?.quantityRemaining?.let { it >= item.quantity } == true
            }
            if (covered) return currentLease
            error("This device already has an active lease with unconsumed stock. Use its reserved quantities or reconcile the expired lease before preparing another allocation.")
        }
        val elapsedAtStart = SystemClock.elapsedRealtime()
        val nowMs = System.currentTimeMillis()

        val previousRequest = offlinePosDao.getPendingReservationRequest(stationId, profileUid)
        val reusable = previousRequest?.let { request ->
            val prior = runCatching { json.parseToJsonElement(request.payloadJson).jsonObject }.getOrNull()
            val priorItems = prior?.get("items")?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                (obj["product_id"]?.jsonPrimitive?.contentOrNull) to (obj["quantity"]?.jsonPrimitive?.intOrNull)
            }?.filter { it.first != null && it.second != null }?.map { it.first!! to it.second!! }
            val priorBoot = prior?.get("boot_count")?.jsonPrimitive?.intOrNull
            if (priorItems == requested && priorBoot == bootCount) prior else null
        }
        val requestId = reusable?.get("event_id")?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val grantId = reusable?.get("grant_id")?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val leaseId = reusable?.get("lease_id")?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val elapsedAnchor = reusable?.get("elapsed_realtime_anchor_ms")?.jsonPrimitive?.longOrNull ?: elapsedAtStart
        val requestBoot = reusable?.get("boot_count")?.jsonPrimitive?.intOrNull ?: bootCount
        val requestPayload = buildJsonObject {
            put("event_id", requestId)
            put("grant_id", grantId)
            put("lease_id", leaseId)
            put("boot_count", requestBoot)
            put("elapsed_realtime_anchor_ms", elapsedAnchor)
            put("items", buildJsonArray { requested.forEach { (id, qty) -> add(buildJsonObject { put("product_id", id); put("quantity", qty) }) } })
        }.toString()
        if (reusable == null) {
            database.withTransaction {
                previousRequest?.let {
                    offlinePosDao.updateReservationRequestState(it.eventId, "RECONCILIATION_REQUIRED")
                }
                offlinePosDao.insertReconciliationEvent(
                    OfflinePosReconciliationEventEntity(
                        eventId = requestId,
                        operationId = null,
                        leaseId = leaseId,
                        stationId = stationId,
                        actorUid = profileUid,
                        eventType = "RESERVATION_REQUESTED",
                        reason = "Owner requested a bounded offline inventory lease.",
                        payloadJson = requestPayload,
                        createdAtMs = nowMs
                    )
                )
            }
        }
        val response = remote.authorize(
            organizationId = organizationId,
            stationId = stationRemoteId,
            deviceId = identity.deviceId,
            publicKeyDerBase64 = identity.publicKeyDerBase64,
            verificationPublicKeySha256 = cryptography.serverVerificationKeyFingerprint(),
            grantId = grantId,
            leaseId = leaseId,
            items = requested,
            elapsedRealtimeAnchorMs = elapsedAnchor,
            bootCount = requestBoot
        )
        val grant = cryptography.verifyServerToken(response.grantToken).claims
        val lease = cryptography.verifyServerToken(response.leaseToken).claims
        check(grant["sub"]?.jsonPrimitive?.contentOrNull == profileUid) { "Server grant is bound to a different account." }
        check(grant["org"]?.jsonPrimitive?.contentOrNull == organizationId && lease["org"]?.jsonPrimitive?.contentOrNull == organizationId) { "Server grant is bound to a different organization." }
        check(grant["station"]?.jsonPrimitive?.contentOrNull == stationRemoteId && lease["station"]?.jsonPrimitive?.contentOrNull == stationRemoteId) { "Server grant is bound to a different station." }
        check(grant["device"]?.jsonPrimitive?.contentOrNull == identity.deviceId && lease["device"]?.jsonPrimitive?.contentOrNull == identity.deviceId) { "Server grant is bound to a different device." }
        check(grant["jti"]?.jsonPrimitive?.contentOrNull == grantId && lease["jti"]?.jsonPrimitive?.contentOrNull == leaseId) { "Server returned an unexpected grant or lease." }
        check(lease["grant_id"]?.jsonPrimitive?.contentOrNull == grantId) { "Server lease does not match its authorization grant." }
        val serverAnchor = grant["server_time_anchor_ms"]?.jsonPrimitive?.longOrNull ?: error("Server time anchor is missing.")
        val grantIssued = (grant["iat"]?.jsonPrimitive?.longOrNull ?: error("Grant issue time is missing.")) * 1000
        val grantExpiry = (grant["exp"]?.jsonPrimitive?.longOrNull ?: error("Grant expiry is missing.")) * 1000
        val leaseIssued = (lease["iat"]?.jsonPrimitive?.longOrNull ?: error("Lease issue time is missing.")) * 1000
        val leaseExpiry = (lease["exp"]?.jsonPrimitive?.longOrNull ?: error("Lease expiry is missing.")) * 1000
        val leaseItems = lease["items"]?.jsonArray ?: error("Server lease has no product snapshots.")
        val localByRemote = requested.associate { (remoteId, _) ->
            remoteId to (syncDao.getMetadataByRemoteId(SyncResourceType.PRODUCT.name, remoteId)?.localId
                ?: error("A reserved product is missing from this local account."))
        }
        val cachedItems = leaseItems.map { element ->
            val row = element.jsonObject
            val productRemoteId = row.requiredString("product_id")
            val localId = localByRemote[productRemoteId] ?: error("Server reserved an unrequested product.")
            OfflinePosLeaseItemEntity(
                leaseId = leaseId,
                productLocalId = localId,
                productRemoteId = productRemoteId,
                quantityReserved = row.requiredInt("quantity_reserved"),
                productName = row.requiredString("product_name"),
                productType = row.requiredString("product_type"),
                sizeLabel = row.requiredString("size_label"),
                unitPriceCentavos = row.requiredLong("unit_price_centavos"),
                productUpdatedAtMs = Instant.parse(row.requiredString("product_updated_at")).toEpochMilli(),
                inventoryVersion = row.requiredLong("inventory_version")
            )
        }
        val grantEntity = OfflinePosGrantEntity(
            grantId = grantId,
            profileUid = profileUid,
            organizationId = organizationId,
            stationId = stationId,
            deviceId = identity.deviceId,
            signedGrant = response.grantToken,
            issuedAtMs = grantIssued,
            expiresAtMs = grantExpiry,
            serverTimeAnchorMs = serverAnchor,
            elapsedRealtimeAnchorMs = elapsedAnchor,
            bootCount = requestBoot,
            storedAtMs = nowMs
        )
        val leaseEntity = OfflinePosLeaseEntity(
            leaseId = leaseId,
            grantId = grantId,
            profileUid = profileUid,
            organizationId = organizationId,
            stationId = stationId,
            deviceId = identity.deviceId,
            signedLease = response.leaseToken,
            issuedAtMs = leaseIssued,
            expiresAtMs = leaseExpiry,
            catalogSnapshotAtMs = lease["catalog_snapshot_at"]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)?.toEpochMilli() ?: leaseIssued,
            storedAtMs = nowMs
        )
        database.withTransaction {
            if (offlinePosDao.getGrant(grantId) == null) offlinePosDao.insertGrant(grantEntity)
            if (offlinePosDao.getLease(leaseId) == null) {
                offlinePosDao.insertLease(leaseEntity)
                offlinePosDao.insertLeaseItems(cachedItems)
            }
            offlinePosDao.updateReservationRequestState(requestId, "COMPLETED")
        }
        scheduler.enqueueImmediate(stationRemoteId)
        return getStatus(stationId)
    }

    override suspend fun getStatus(stationId: Long): OfflinePosStatus {
        val profileUid = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
            ?: return OfflinePosStatus(state = "ACCOUNT_REQUIRED", message = "Sign in to use offline POS.")
        // Pending cash stays visible even when a restart, expiry or key issue
        // invalidates permission to start any new offline transaction.
        val pendingCash = offlinePosDao.getPendingCashSalesCentavos(stationId, profileUid, 0L, Long.MAX_VALUE)
        val pendingCount = offlinePosDao.getPendingCount(stationId, profileUid)
        if (!offlinePosEnabled) {
            return OfflinePosStatus(
                pendingCashSalesCentavos = pendingCash,
                pendingOperationCount = pendingCount,
                state = "DISABLED",
                message = if (pendingCount > 0) {
                    ReleaseFeatureFlags.OFFLINE_POS_RECOVERY_MESSAGE
                } else {
                    ReleaseFeatureFlags.OFFLINE_POS_DISABLED_MESSAGE
                },
            )
        }
        val identity = runCatching { OfflinePosCryptography.deviceIdentity(context) }.getOrNull()
            ?: return OfflinePosStatus(
                pendingCashSalesCentavos = pendingCash,
                pendingOperationCount = pendingCount,
                state = "DEVICE_KEY_UNAVAILABLE",
                message = "This device cannot securely authorize new offline sales. Saved cash records remain available for recovery."
            )
        val grant = offlinePosDao.getLatestGrantForDevice(profileUid, stationId, identity.deviceId)
            ?: return OfflinePosStatus(
                pendingCashSalesCentavos = pendingCash,
                pendingOperationCount = pendingCount,
                state = "NOT_PREPARED",
                message = "Prepare an inventory lease while online before starting offline sales. Previously recorded cash remains saved."
            )
        val lease = offlinePosDao.getLeaseForGrant(grant.grantId)
            ?: return OfflinePosStatus(
                pendingCashSalesCentavos = pendingCash,
                pendingOperationCount = pendingCount,
                state = "NOT_PREPARED",
                message = "The server inventory lease is unavailable. Previously recorded cash remains saved."
            )
        if (grant.status != "ACTIVE" || lease.status != "ACTIVE") {
            return OfflinePosStatus(
                pendingCashSalesCentavos = pendingCash,
                pendingOperationCount = pendingCount,
                state = "AUTHORIZATION_INVALID",
                message = "This offline authorization has been revoked. Existing cash records remain saved."
            )
        }
        val boot = identity.bootCount
        val trustedNow = OfflinePosTimePolicy.trustedServerNowMs(
            issuedAtMs = grant.issuedAtMs,
            grantExpiresAtMs = grant.expiresAtMs,
            leaseExpiresAtMs = lease.expiresAtMs,
            serverTimeAnchorMs = grant.serverTimeAnchorMs,
            elapsedRealtimeAnchorMs = grant.elapsedRealtimeAnchorMs,
            currentElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            currentWallClockMs = System.currentTimeMillis(),
            grantBootCount = grant.bootCount,
            currentBootCount = boot
        )
        val operations = grant.signedGrant.let { runCatching { cryptography.verifyServerToken(it).claims }.getOrNull() }
        val leaseClaims = lease.signedLease.let { runCatching { cryptography.verifyServerToken(it).claims }.getOrNull() }
        val stationRemoteId = syncDao.getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId
        val scopeValid = operations?.get("sub")?.jsonPrimitive?.contentOrNull == profileUid &&
            operations["org"]?.jsonPrimitive?.contentOrNull == grant.organizationId &&
            operations["station"]?.jsonPrimitive?.contentOrNull == stationRemoteId &&
            operations["device"]?.jsonPrimitive?.contentOrNull == identity.deviceId &&
            operations["jti"]?.jsonPrimitive?.contentOrNull == grant.grantId &&
            leaseClaims?.get("sub")?.jsonPrimitive?.contentOrNull == profileUid &&
            leaseClaims["org"]?.jsonPrimitive?.contentOrNull == grant.organizationId &&
            leaseClaims["station"]?.jsonPrimitive?.contentOrNull == stationRemoteId &&
            leaseClaims["device"]?.jsonPrimitive?.contentOrNull == identity.deviceId &&
            leaseClaims["jti"]?.jsonPrimitive?.contentOrNull == lease.leaseId &&
            leaseClaims["grant_id"]?.jsonPrimitive?.contentOrNull == grant.grantId
        if (trustedNow == null || !scopeValid || operations?.get("operations")?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "OFFLINE_WALKIN_CASH" } != true) {
            return OfflinePosStatus(
                grantExpiresAtMs = grant.expiresAtMs,
                leaseExpiresAtMs = lease.expiresAtMs,
                pendingCashSalesCentavos = pendingCash,
                pendingOperationCount = pendingCount,
                state = "AUTHORIZATION_INVALID",
                message = "Offline authorization expired or this device restarted. Existing cash records remain saved; prepare a new lease online."
            )
        }
        val snapshots = offlinePosDao.getLeaseItems(lease.leaseId).filter { it.quantityRemaining > 0 }.associate { item ->
            item.productLocalId to OfflineLeaseProductSnapshot(
                productLocalId = item.productLocalId,
                productRemoteId = item.productRemoteId,
                productName = item.productName,
                productType = runCatching { ProductType.valueOf(item.productType.uppercase()) }.getOrDefault(ProductType.OTHER),
                sizeLabel = item.sizeLabel,
                quantityRemaining = item.quantityRemaining,
                unitPriceCentavos = item.unitPriceCentavos
            )
        }
        return OfflinePosStatus(
            grantExpiresAtMs = grant.expiresAtMs,
            leaseExpiresAtMs = lease.expiresAtMs,
            productSnapshots = snapshots,
            pendingCashSalesCentavos = pendingCash,
            pendingOperationCount = pendingCount,
            state = if (snapshots.isEmpty()) "LEASE_EMPTY" else "READY",
            message = null,
            trustedServerNowMs = trustedNow
        )
    }

    override suspend fun recordCashSale(request: OfflineCashSaleRequest): Long {
        check(offlinePosEnabled) { ReleaseFeatureFlags.OFFLINE_POS_DISABLED_MESSAGE }
        require(request.items.isNotEmpty() && request.items.map { it.productLocalId }.distinct().size == request.items.size) { "Choose valid products for this cash sale." }
        val status = getStatus(request.stationId)
        check(status.canCreateOfflineSale) { status.message ?: "Offline authorization is not valid." }
        val profileUid = requireNotNull(SupabaseClientProvider.client.auth.currentUserOrNull()?.id) { "The authenticated account changed. Sign in online to continue." }
        val identity = OfflinePosCryptography.deviceIdentity(context)
        val grant = offlinePosDao.getLatestGrantForDevice(profileUid, request.stationId, identity.deviceId)
            ?: error("Offline authorization is unavailable.")
        val lease = offlinePosDao.getLeaseForGrant(grant.grantId) ?: error("Offline inventory lease is unavailable.")
        val grantClaims = cryptography.verifyServerToken(grant.signedGrant).claims
        val leaseClaims = cryptography.verifyServerToken(lease.signedLease).claims
        check(grantClaims["sub"]?.jsonPrimitive?.contentOrNull == profileUid && grantClaims["device"]?.jsonPrimitive?.contentOrNull == identity.deviceId) {
            "Offline authorization does not match the current account or device."
        }
        check(leaseClaims["sub"]?.jsonPrimitive?.contentOrNull == profileUid &&
            leaseClaims["org"]?.jsonPrimitive?.contentOrNull == grant.organizationId &&
            leaseClaims["station"]?.jsonPrimitive?.contentOrNull == syncDao.getMetadata(SyncResourceType.STATION.name, request.stationId)?.remoteId &&
            leaseClaims["device"]?.jsonPrimitive?.contentOrNull == identity.deviceId &&
            leaseClaims["jti"]?.jsonPrimitive?.contentOrNull == lease.leaseId &&
            leaseClaims["grant_id"]?.jsonPrimitive?.contentOrNull == grant.grantId) {
            "Offline inventory lease does not match the current account, station, or device."
        }
        val snapshots = offlinePosDao.getLeaseItems(lease.leaseId).associateBy { it.productLocalId }
        val lines = request.items.map { item ->
            val reserved = snapshots[item.productLocalId] ?: error("This product is not part of the active inventory lease.")
            require(item.quantity in 1..reserved.quantityRemaining) { "The reserved quantity for ${reserved.productName} is no longer available." }
            reserved to item.quantity
        }.sortedBy { it.first.productRemoteId }
        val totalCentavos = lines.sumOf { (product, qty) -> Math.multiplyExact(product.unitPriceCentavos, qty.toLong()) }
        val cashReceivedCentavos = request.cashReceivedCentavos ?: totalCentavos
        require(totalCentavos > 0 && cashReceivedCentavos >= totalCentavos) { "Cash received must cover the exact sale total." }
        val changeCentavos = cashReceivedCentavos - totalCentavos
        val operationId = UUID.randomUUID().toString()
        val elapsedAt = SystemClock.elapsedRealtime()
        val bootCount = requireNotNull(identity.bootCount) { "Device boot state cannot be verified." }
        val transactionAtMs = OfflinePosTimePolicy.trustedServerNowMs(
            issuedAtMs = grant.issuedAtMs,
            grantExpiresAtMs = grant.expiresAtMs,
            leaseExpiresAtMs = lease.expiresAtMs,
            serverTimeAnchorMs = grant.serverTimeAnchorMs,
            elapsedRealtimeAnchorMs = grant.elapsedRealtimeAnchorMs,
            currentElapsedRealtimeMs = elapsedAt,
            currentWallClockMs = System.currentTimeMillis(),
            grantBootCount = grant.bootCount,
            currentBootCount = identity.bootCount
        ) ?: error("The device clock or offline grant could not be trusted.")
        val remoteCustomerId = if (request.customerId > 0) {
            syncDao.getMetadata(SyncResourceType.CUSTOMER.name, request.customerId)?.remoteId
                ?: error("This customer has not synchronized. Use a walk-in sale or reconnect before selecting the customer.")
        } else null
        val payload = buildJsonObject {
            put("operation_id", operationId)
            put("profile_id", profileUid)
            put("organization_id", grant.organizationId)
            put("station_id", syncDao.getMetadata(SyncResourceType.STATION.name, request.stationId)?.remoteId ?: error("Station identity is not synchronized."))
            put("device_id", identity.deviceId)
            put("grant_id", grant.grantId)
            put("lease_id", lease.leaseId)
            put("transaction_at_ms", transactionAtMs)
            put("elapsed_realtime_at_ms", elapsedAt)
            put("boot_count", bootCount)
            put("payment_method", "CASH")
            put("delivery_mode", "PICKUP")
            put("customer_id", remoteCustomerId)
            put("customer_name", request.customerName.ifBlank { "Walk-in Customer" })
            put("customer_phone", request.customerPhone)
            put("notes", request.notes)
            put("cash_received_centavos", cashReceivedCentavos)
            put("change_centavos", changeCentavos)
            put("items", buildJsonArray { lines.forEach { (product, quantity) ->
                add(buildJsonObject {
                    put("product_id", product.productRemoteId)
                    put("quantity", quantity)
                    put("unit_price_centavos", product.unitPriceCentavos)
                })
            } })
        }.toString()
        val digest = cryptography.digestHex(payload)
        val signature = OfflinePosCryptography.signDevicePayloadDigest(identity, digest)
        var localOrderId = 0L
        check(OfflinePosTimePolicy.trustedServerNowMs(
            issuedAtMs = grant.issuedAtMs,
            grantExpiresAtMs = grant.expiresAtMs,
            leaseExpiresAtMs = lease.expiresAtMs,
            serverTimeAnchorMs = grant.serverTimeAnchorMs,
            elapsedRealtimeAnchorMs = grant.elapsedRealtimeAnchorMs,
            currentElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            currentWallClockMs = System.currentTimeMillis(),
            grantBootCount = grant.bootCount,
            currentBootCount = identity.bootCount
        ) != null) { "Offline authorization expired before the sale could be saved." }
        database.withTransaction {
            // Recheck the lease under the same Room transaction that records
            // the immutable cash journal and decrements local lease quantities.
            lines.forEach { (snapshot, quantity) ->
                val current = offlinePosDao.getLeaseItem(lease.leaseId, snapshot.productRemoteId)
                    ?: error("The local lease changed while creating this sale.")
                check(current.quantityRemaining >= quantity) { "Reserved inventory changed; refresh the cart." }
            }
            val now = System.currentTimeMillis()
            localOrderId = orderDao.insertOrder(
                OrderEntity(
                    orderNumber = "OFF-${operationId.take(8).uppercase()}",
                    stationId = request.stationId,
                    customerId = request.customerId,
                    customerName = request.customerName.ifBlank { "Walk-in Customer" },
                    customerPhone = request.customerPhone,
                    riderId = null,
                    riderName = null,
                    deliveryAddressId = null,
                    deliveryAddress = "Station Walk-in / Pick-up",
                    deliveryMode = "PICKUP",
                    subtotal = totalCentavos / 100.0,
                    deliveryFee = 0.0,
                    discount = 0.0,
                    total = totalCentavos / 100.0,
                    paymentMethod = "CASH",
                    paymentStatus = "PENDING",
                    orderStatus = "COMPLETED",
                    scheduledDeliveryStart = null,
                    scheduledDeliveryEnd = null,
                    customerNote = request.notes,
                    stationNote = "Locally recorded cash; pending server verification.",
                    createdAt = transactionAtMs,
                    acceptedAt = transactionAtMs,
                    readyAt = transactionAtMs,
                    assignedAt = null,
                    outForDeliveryAt = null,
                    deliveredAt = null,
                    updatedAt = now,
                    completedAt = transactionAtMs,
                    cancelledAt = null
                )
            )
            orderDao.insertOrderItems(lines.map { (snapshot, quantity) ->
                OrderItemEntity(
                    orderId = localOrderId,
                    productId = snapshot.productLocalId,
                    productName = snapshot.productName,
                    productType = snapshot.productType,
                    sizeLabel = snapshot.sizeLabel,
                    unitPrice = snapshot.unitPriceCentavos / 100.0,
                    quantity = quantity,
                    subtotal = snapshot.unitPriceCentavos * quantity / 100.0
                )
            })
            paymentDao.insertPayment(
                PaymentEntity(
                    orderId = localOrderId,
                    paymentMethod = "CASH",
                    amount = totalCentavos / 100.0,
                    status = "PENDING",
                    referenceNumber = null,
                    createdAt = transactionAtMs,
                    paidAt = null,
                    metadataJson = "{\"offline_cash_pending\":true,\"operation_id\":\"$operationId\"}",
                    operationId = "offline-pos-payment:$operationId"
                )
            )
            offlinePosDao.insertOperation(
                OfflinePosOperationEntity(
                    operationId = operationId,
                    profileUid = profileUid,
                    organizationId = grant.organizationId,
                    stationId = request.stationId,
                    deviceId = identity.deviceId,
                    grantId = grant.grantId,
                    leaseId = lease.leaseId,
                    localOrderId = localOrderId,
                    transactionAtMs = transactionAtMs,
                    elapsedRealtimeAtMs = elapsedAt,
                    bootCount = bootCount,
                    totalCentavos = totalCentavos,
                    cashReceivedCentavos = cashReceivedCentavos,
                    changeCentavos = changeCentavos,
                    payloadDigest = digest,
                    canonicalPayload = payload,
                    deviceSignature = signature,
                    createdAtMs = now
                )
            )
            offlinePosDao.insertOperationLines(lines.mapIndexed { index, (snapshot, quantity) ->
                OfflinePosOperationLineEntity(
                    operationId = operationId,
                    lineNumber = index,
                    productLocalId = snapshot.productLocalId,
                    productRemoteId = snapshot.productRemoteId,
                    productName = snapshot.productName,
                    productType = snapshot.productType,
                    sizeLabel = snapshot.sizeLabel,
                    quantity = quantity,
                    unitPriceCentavos = snapshot.unitPriceCentavos,
                    lineTotalCentavos = Math.multiplyExact(snapshot.unitPriceCentavos, quantity.toLong())
                )
            })
            lines.forEach { (snapshot, quantity) ->
                val current = offlinePosDao.getLeaseItem(lease.leaseId, snapshot.productRemoteId)!!
                offlinePosDao.updateLeaseItem(current.copy(quantityConsumed = current.quantityConsumed + quantity))
            }
            offlinePosDao.insertReconciliationEvent(
                OfflinePosReconciliationEventEntity(
                    eventId = UUID.randomUUID().toString(),
                    operationId = operationId,
                    leaseId = lease.leaseId,
                    stationId = request.stationId,
                    actorUid = profileUid,
                    eventType = "CASH_SALE_RECORDED",
                    reason = "Cash was received locally; server validation is pending.",
                    payloadJson = payload,
                    createdAtMs = now
                )
            )
        }
        syncDao.getMetadata(SyncResourceType.STATION.name, request.stationId)?.remoteId?.let(scheduler::enqueueImmediate)
        return localOrderId
    }

    override suspend fun getRecoveryRecords(stationId: Long): List<OfflinePosRecoveryRecord> {
        val profileUid = SupabaseClientProvider.client.auth.currentUserOrNull()?.id ?: return emptyList()
        return offlinePosDao.getRecoveryOperations(stationId, profileUid).map { operationWithLines ->
            val operation = operationWithLines.operation
            OfflinePosRecoveryRecord(
                operationId = operation.operationId,
                transactionAtMs = operation.transactionAtMs,
                totalCentavos = operation.totalCentavos,
                cashReceivedCentavos = operation.cashReceivedCentavos,
                changeCentavos = operation.changeCentavos,
                state = operation.state,
                lastErrorCode = operation.lastErrorCode,
                lastError = operation.lastError,
                lines = operationWithLines.lines.map { line ->
                    OfflinePosRecoveryLine(
                        productName = line.productName,
                        sizeLabel = line.sizeLabel,
                        quantity = line.quantity,
                        unitPriceCentavos = line.unitPriceCentavos,
                        lineTotalCentavos = line.lineTotalCentavos,
                    )
                },
            )
        }
    }

    override suspend fun retryReconciliation(operationId: String) {
        check(offlinePosEnabled) { ReleaseFeatureFlags.OFFLINE_POS_RECOVERY_MESSAGE }
        val profileUid = requireNotNull(SupabaseClientProvider.client.auth.currentUserOrNull()?.id) {
            "Sign in to the original account before retrying a saved cash sale."
        }
        val operation = requireNotNull(offlinePosDao.getOperation(operationId)) {
            "The saved cash sale could not be found."
        }
        check(operation.profileUid == profileUid) { "This saved cash sale belongs to a different account." }
        check(operation.state == "RECONCILIATION_REQUIRED") { "This sale is not waiting for owner reconciliation." }
        val stationRemoteId = syncDao.getMetadata(SyncResourceType.STATION.name, operation.stationId)?.remoteId
            ?: error("Reconnect and synchronize the original station before retrying this sale.")
        val retriedAt = System.currentTimeMillis()
        database.withTransaction {
            check(offlinePosDao.requestReconciliationRetry(operationId, profileUid, retriedAt) == 1) {
                "The sale changed while retry was being requested. Refresh its status and try again."
            }
            offlinePosDao.insertReconciliationEvent(
                OfflinePosReconciliationEventEntity(
                    eventId = UUID.randomUUID().toString(),
                    operationId = operationId,
                    leaseId = operation.leaseId,
                    stationId = operation.stationId,
                    actorUid = profileUid,
                    eventType = "RETRY_REQUESTED",
                    reason = "Owner requested server verification again; the original cash receipt and payload are unchanged.",
                    payloadJson = buildJsonObject {
                        put("previous_error_code", operation.lastErrorCode)
                        put("previous_error", operation.lastError)
                        put("payload_digest", operation.payloadDigest)
                    }.toString(),
                    createdAtMs = retriedAt,
                ),
            )
        }
        scheduler.enqueueImmediate(stationRemoteId)
    }

    override fun observePendingCashSalesCentavos(stationId: Long, startMs: Long, endMs: Long) = kotlinx.coroutines.flow.flow {
        val profileUid = SupabaseClientProvider.client.auth.currentUserOrNull()?.id
        if (profileUid == null) emit(0L)
        else offlinePosDao.observePendingCashSalesCentavos(stationId, profileUid, startMs, endMs)
            .collect { emit(it) }
    }
}

private fun JsonObject.requiredString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: error("Offline authorization is missing $key.")

private fun JsonObject.requiredInt(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: error("Offline authorization is missing $key.")

private fun JsonObject.requiredLong(key: String): Long =
    this[key]?.jsonPrimitive?.longOrNull ?: error("Offline authorization is missing $key.")
