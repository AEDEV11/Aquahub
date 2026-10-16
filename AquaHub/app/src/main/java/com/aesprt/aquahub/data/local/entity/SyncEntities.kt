package com.aesprt.aquahub.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Stable mapping between this installation's Room IDs and globally unique
 * Supabase row IDs. Keeping this separate avoids a destructive rewrite
 * of every existing operational table.
 */
@Entity(
    tableName = "sync_metadata",
    primaryKeys = ["resourceType", "localId"],
    indices = [Index(value = ["resourceType", "remoteId"], unique = true)]
)
data class SyncMetadataEntity(
    val resourceType: String,
    val localId: Long,
    val remoteId: String,
    val stationLocalId: Long?,
    val localVersion: Long = 1,
    val remoteVersion: Long = 0,
    val lastLocalUpdatedAt: Long,
    val lastServerUpdatedAt: Long? = null,
    val lastSyncedAt: Long? = null,
    val deletedAt: Long? = null
)

/** Durable outbox. A mutation and this row are committed in one Room transaction. */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["operationId"], unique = true),
        Index(value = ["state", "createdAt"]),
        Index(value = ["resourceType", "resourceLocalId"]),
        Index(value = ["stationLocalId"])
    ]
)
data class SyncQueueEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: String,
    val resourceType: String,
    val resourceLocalId: Long,
    val resourceRemoteId: String,
    val stationLocalId: Long?,
    val operation: String,
    val expectedPreviousStatus: String? = null,
    val requestedStatus: String? = null,
    /** Relative product-stock change; only populated for STOCK_ADJUSTMENT. */
    val stockDelta: Int? = null,
    /** Absolute target guarded by expectedInventoryVersion; new writes never use stockDelta. */
    val stockTarget: Int? = null,
    val expectedInventoryVersion: Long? = null,
    val state: String = "PENDING",
    val retryCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastErrorCode: String? = null,
    val lastError: String? = null,
    val createdAt: Long
)

/** Per business/station/resource high-water mark used for incremental pulls. */
@Entity(
    tableName = "sync_cursors",
    primaryKeys = ["businessId", "stationRemoteId", "resourceType"]
)
data class SyncCursorEntity(
    val businessId: String,
    /** Account-scoped resources use an empty string. */
    val stationRemoteId: String,
    val resourceType: String,
    val lastServerUpdatedAt: Long,
    val initialSyncComplete: Boolean,
    val updatedAt: Long
)
