package com.aesprt.aquahub.sync

import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.local.entity.SyncQueueEntity
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.auth.auth
import java.util.UUID

class SyncQueueWriter(
    private val syncDao: SyncDao,
    private val scheduler: SyncScheduler
) {
    suspend fun enqueueInitialIfNeeded(
        resourceType: SyncResourceType,
        localId: Long,
        stationLocalId: Long?,
        localUpdatedAt: Long,
        operation: SyncOperation = SyncOperation.CREATE
    ) {
        val metadata = syncDao.getMetadata(resourceType.name, localId)
        if (metadata?.lastSyncedAt != null && metadata.deletedAt == null) return
        enqueue(resourceType, localId, stationLocalId, operation, localUpdatedAt)
    }

    suspend fun enqueue(
        resourceType: SyncResourceType,
        localId: Long,
        stationLocalId: Long?,
        operation: SyncOperation,
        localUpdatedAt: Long = System.currentTimeMillis(),
        expectedPreviousStatus: String? = null,
        requestedStatus: String? = null,
        stockTarget: Int? = null,
        expectedInventoryVersion: Long? = null,
        stableOperationId: String? = null
    ): String? {
        // Demo/local-only sessions never enter the production cloud outbox.
        if (SupabaseClientProvider.client.auth.currentUserOrNull() == null) return null

        val existing = syncDao.getMetadata(resourceType.name, localId)
        val remoteId = existing?.remoteId ?: UUID.randomUUID().toString()
        val metadata = SyncMetadataEntity(
            resourceType = resourceType.name,
            localId = localId,
            remoteId = remoteId,
            stationLocalId = stationLocalId,
            localVersion = (existing?.localVersion ?: 0L) + 1L,
            remoteVersion = existing?.remoteVersion ?: 0L,
            lastLocalUpdatedAt = localUpdatedAt,
            lastServerUpdatedAt = existing?.lastServerUpdatedAt,
            lastSyncedAt = existing?.lastSyncedAt,
            deletedAt = if (operation == SyncOperation.ARCHIVE) localUpdatedAt else null
        )
        syncDao.upsertMetadata(metadata)
        val operationId = stableOperationId?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString()

        // Inventory adjustments update one container snapshot through several
        // domain operations (available, damaged, lost, or with-customers).
        // Those operations run in one Room transaction, so only the final
        // snapshot needs to be uploaded. The movement ledger remains a
        // separate immutable queue item and is never coalesced.
        if (resourceType == SyncResourceType.CONTAINER &&
            operation in setOf(SyncOperation.CREATE, SyncOperation.UPDATE)
        ) {
            val pendingSnapshot = syncDao.getPendingSnapshotMutation(resourceType.name, localId)
            if (pendingSnapshot != null) {
                syncDao.replacePendingSnapshotMutation(
                    id = pendingSnapshot.id,
                    operationId = operationId,
                    resourceRemoteId = remoteId,
                    stationLocalId = stationLocalId,
                    operation = if (pendingSnapshot.operation == SyncOperation.CREATE.name) {
                        SyncOperation.CREATE.name
                    } else {
                        operation.name
                    },
                    createdAt = localUpdatedAt
                )
                scheduler.enqueueImmediate()
                return operationId
            }
        }
        syncDao.enqueue(
            SyncQueueEntity(
                operationId = operationId,
                resourceType = resourceType.name,
                resourceLocalId = localId,
                resourceRemoteId = remoteId,
                stationLocalId = stationLocalId,
                operation = operation.name,
                expectedPreviousStatus = expectedPreviousStatus,
                requestedStatus = requestedStatus,
                stockTarget = stockTarget,
                expectedInventoryVersion = expectedInventoryVersion,
                createdAt = localUpdatedAt
            )
        )
        scheduler.enqueueImmediate()
        return operationId
    }

    suspend fun ensureMetadata(
        resourceType: SyncResourceType,
        localId: Long,
        stationLocalId: Long?,
        localUpdatedAt: Long
    ): SyncMetadataEntity {
        syncDao.getMetadata(resourceType.name, localId)?.let { return it }
        val metadata = SyncMetadataEntity(
            resourceType = resourceType.name,
            localId = localId,
            remoteId = UUID.randomUUID().toString(),
            stationLocalId = stationLocalId,
            lastLocalUpdatedAt = localUpdatedAt
        )
        syncDao.upsertMetadata(metadata)
        return metadata
    }

    /** Removes outbox rows for a local record that is intentionally replaced. */
    suspend fun removeResource(resourceType: SyncResourceType, localId: Long) {
        syncDao.deleteQueueForResource(resourceType.name, localId)
        syncDao.deleteMetadataForResource(resourceType.name, localId)
    }
}
