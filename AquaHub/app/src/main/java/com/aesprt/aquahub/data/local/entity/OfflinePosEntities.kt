package com.aesprt.aquahub.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/** Signed, server-issued authority cached for this installation. */
@Entity(
    tableName = "offline_pos_grants",
    indices = [Index(value = ["profileUid", "organizationId", "stationId", "deviceId"]), Index("expiresAtMs")]
)
data class OfflinePosGrantEntity(
    @androidx.room.PrimaryKey val grantId: String,
    val profileUid: String,
    val organizationId: String,
    val stationId: Long,
    val deviceId: String,
    val signedGrant: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val serverTimeAnchorMs: Long,
    val elapsedRealtimeAnchorMs: Long,
    val bootCount: Int,
    val status: String = "ACTIVE",
    val storedAtMs: Long
)

@Entity(
    tableName = "offline_pos_leases",
    indices = [Index("grantId"), Index(value = ["organizationId", "stationId", "deviceId"]), Index("expiresAtMs")]
)
data class OfflinePosLeaseEntity(
    @androidx.room.PrimaryKey val leaseId: String,
    val grantId: String,
    val profileUid: String,
    val organizationId: String,
    val stationId: Long,
    val deviceId: String,
    val signedLease: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val catalogSnapshotAtMs: Long,
    val status: String = "ACTIVE",
    val storedAtMs: Long
)

/** Price/name/type are immutable lease-time snapshots; only consumed quantity changes. */
@Entity(
    tableName = "offline_pos_lease_items",
    primaryKeys = ["leaseId", "productRemoteId"],
    indices = [Index("productLocalId")]
)
data class OfflinePosLeaseItemEntity(
    val leaseId: String,
    val productLocalId: Long,
    val productRemoteId: String,
    val quantityReserved: Int,
    val quantityConsumed: Int = 0,
    val quantityReleased: Int = 0,
    val productName: String,
    val productType: String,
    val sizeLabel: String,
    val unitPriceCentavos: Long,
    val productUpdatedAtMs: Long,
    val inventoryVersion: Long = 0L
) {
    val quantityRemaining: Int get() = (quantityReserved - quantityConsumed - quantityReleased).coerceAtLeast(0)
}

/**
 * Immutable payload plus mutable delivery state. Rows are retained after server
 * acceptance so receipts and reconciliation evidence survive later upgrades.
 */
@Entity(
    tableName = "offline_pos_operations",
    indices = [Index(value = ["stationId", "transactionAtMs"]), Index(value = ["state", "nextAttemptAtMs"]), Index("leaseId")]
)
data class OfflinePosOperationEntity(
    @androidx.room.PrimaryKey val operationId: String,
    val profileUid: String,
    val organizationId: String,
    val stationId: Long,
    val deviceId: String,
    val grantId: String,
    val leaseId: String,
    val localOrderId: Long?,
    val transactionAtMs: Long,
    val elapsedRealtimeAtMs: Long,
    val bootCount: Int,
    val totalCentavos: Long,
    val cashReceivedCentavos: Long,
    val changeCentavos: Long,
    val payloadDigest: String,
    val canonicalPayload: String,
    val deviceSignature: String,
    val state: String = "PENDING",
    val attemptCount: Int = 0,
    val nextAttemptAtMs: Long? = null,
    val lastErrorCode: String? = null,
    val lastError: String? = null,
    val remoteOrderId: String? = null,
    val createdAtMs: Long,
    val reconciledAtMs: Long? = null
)

@Entity(
    tableName = "offline_pos_operation_lines",
    primaryKeys = ["operationId", "lineNumber"],
    indices = [Index("productRemoteId")]
)
data class OfflinePosOperationLineEntity(
    val operationId: String,
    val lineNumber: Int,
    val productLocalId: Long,
    val productRemoteId: String,
    val productName: String,
    val productType: String,
    val sizeLabel: String,
    val quantity: Int,
    val unitPriceCentavos: Long,
    val lineTotalCentavos: Long
)

@Entity(
    tableName = "offline_pos_reconciliation_events",
    indices = [Index("operationId"), Index("leaseId"), Index(value = ["stationId", "createdAtMs"])]
)
data class OfflinePosReconciliationEventEntity(
    @androidx.room.PrimaryKey val eventId: String,
    val operationId: String?,
    val leaseId: String?,
    val stationId: Long,
    val actorUid: String,
    val eventType: String,
    val reason: String,
    val payloadJson: String,
    val state: String = "PENDING",
    val createdAtMs: Long
)
