package com.aesprt.aquahub.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aesprt.aquahub.data.local.entity.SyncCursorEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(metadata: SyncMetadataEntity)

    @Query("SELECT * FROM sync_metadata WHERE resourceType = :resourceType AND localId = :localId LIMIT 1")
    suspend fun getMetadata(resourceType: String, localId: Long): SyncMetadataEntity?

    @Query("SELECT * FROM sync_metadata WHERE resourceType = :resourceType AND remoteId = :remoteId LIMIT 1")
    suspend fun getMetadataByRemoteId(resourceType: String, remoteId: String): SyncMetadataEntity?

    @Query("SELECT * FROM sync_metadata WHERE resourceType = :resourceType AND stationLocalId = :stationLocalId")
    suspend fun getMetadataForStation(resourceType: String, stationLocalId: Long): List<SyncMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(item: SyncQueueEntity): Long

    @Query(
        """SELECT * FROM sync_queue WHERE state IN ('PENDING', 'RETRY')
           ORDER BY CASE resourceType
               WHEN 'STATION' THEN 0
               WHEN 'CUSTOMER' THEN 10
               WHEN 'CUSTOMER_ADDRESS' THEN 20
               WHEN 'PRODUCT' THEN 30
               WHEN 'CONTAINER' THEN 40
               WHEN 'RIDER' THEN 50
               WHEN 'ORDER' THEN 60
               WHEN 'PAYMENT' THEN 70
               WHEN 'CONTAINER_MOVEMENT' THEN 80
               WHEN 'EXPENSE' THEN 90
               ELSE 100
           END, createdAt ASC, id ASC LIMIT :limit"""
    )
    suspend fun getPending(limit: Int): List<SyncQueueEntity>

    @Query("SELECT (SELECT COUNT(*) FROM sync_queue WHERE state IN ('PENDING', 'RETRY', 'FAILED', 'QUARANTINED')) + (SELECT COUNT(*) FROM offline_pos_operations WHERE state <> 'COMMITTED')")
    fun observeOutstandingCount(): Flow<Int>

    @Query("SELECT (SELECT COUNT(*) FROM sync_queue WHERE state IN ('FAILED', 'QUARANTINED')) + (SELECT COUNT(*) FROM offline_pos_operations WHERE state IN ('RECONCILIATION_REQUIRED','QUARANTINED'))")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE state = 'QUARANTINED'")
    fun observeQuarantinedCount(): Flow<Int>

    @Query("SELECT (SELECT COUNT(*) FROM sync_queue WHERE state IN ('PENDING', 'RETRY')) + (SELECT COUNT(*) FROM offline_pos_operations WHERE state IN ('PENDING','RETRY','SYNCING'))")
    suspend fun getPendingCount(): Int

    @Query("SELECT (SELECT COUNT(*) FROM sync_queue WHERE state IN ('PENDING', 'RETRY', 'FAILED', 'IN_PROGRESS', 'QUARANTINED')) + (SELECT COUNT(*) FROM offline_pos_operations WHERE state <> 'COMMITTED')")
    suspend fun getOutstandingCount(): Int

    /** Preserve pre-upgrade operations while preventing automatic legacy replay. */
    @Query(
        """UPDATE sync_queue
           SET state = 'QUARANTINED',
               lastErrorCode = 'LEGACY_REPLAY_BLOCKED',
               lastError = 'Preserved locally; queued before account-scope validation and not uploaded.'
           WHERE state IN ('PENDING', 'RETRY', 'FAILED', 'IN_PROGRESS')"""
    )
    suspend fun quarantineLegacyOutstanding(): Int

    @Query("SELECT * FROM sync_queue WHERE state = 'QUARANTINED' ORDER BY createdAt ASC, id ASC")
    suspend fun getQuarantined(): List<SyncQueueEntity>

    @Query("SELECT COUNT(*) FROM sync_queue WHERE state = 'QUARANTINED' AND lastErrorCode = 'LEGACY_REPLAY_BLOCKED'")
    suspend fun getLegacyQuarantinedCount(): Int

    /** Re-enables preserved rows only after the caller has verified the original local owner identity. */
    @Query(
        """UPDATE sync_queue
           SET state = 'PENDING', retryCount = 0, lastAttemptAt = NULL,
               lastErrorCode = NULL, lastError = NULL
           WHERE state = 'QUARANTINED' AND lastErrorCode = 'LEGACY_REPLAY_BLOCKED'"""
    )
    suspend fun requeueVerifiedLegacyOutstanding(): Int

    /** Operational Room data must not be silently erased during an account switch. */
    @Query(
        """SELECT
             EXISTS(SELECT 1 FROM stations LIMIT 1)
             OR EXISTS(SELECT 1 FROM station_owners LIMIT 1)
             OR EXISTS(SELECT 1 FROM customers LIMIT 1)
             OR EXISTS(SELECT 1 FROM customer_addresses LIMIT 1)
             OR EXISTS(SELECT 1 FROM products LIMIT 1)
             OR EXISTS(SELECT 1 FROM riders LIMIT 1)
             OR EXISTS(SELECT 1 FROM orders LIMIT 1)
             OR EXISTS(SELECT 1 FROM order_items LIMIT 1)
             OR EXISTS(SELECT 1 FROM containers LIMIT 1)
             OR EXISTS(SELECT 1 FROM container_transactions LIMIT 1)
             OR EXISTS(SELECT 1 FROM payments LIMIT 1)
             OR EXISTS(SELECT 1 FROM expenses LIMIT 1)
             OR EXISTS(SELECT 1 FROM payment_refunds LIMIT 1)
             OR EXISTS(SELECT 1 FROM offline_pos_operations LIMIT 1)
             OR EXISTS(SELECT 1 FROM offline_pos_leases LIMIT 1)"""
    )
    suspend fun hasLocalBusinessData(): Boolean


    @Query("SELECT COUNT(*) FROM sync_queue WHERE resourceType = :resourceType AND resourceLocalId = :localId AND state IN ('PENDING', 'RETRY', 'IN_PROGRESS', 'FAILED')")
    suspend fun hasOutstandingMutation(resourceType: String, localId: Long): Int

    @Query(
        """SELECT EXISTS(
             SELECT 1 FROM sync_queue q
             INNER JOIN container_transactions t ON t.id = q.resourceLocalId
             WHERE q.resourceType = 'CONTAINER_MOVEMENT'
               AND q.operation <> 'IMPORT'
               AND q.state IN ('PENDING', 'RETRY', 'IN_PROGRESS', 'FAILED')
               AND t.stationId = :stationLocalId
               AND LOWER(TRIM(t.containerSize)) = LOWER(TRIM(:sizeLabel))
           )"""
    )
    suspend fun hasOutstandingLiveContainerMovement(stationLocalId: Long, sizeLabel: String): Boolean

    @Query("SELECT * FROM sync_queue WHERE resourceType = :resourceType AND resourceLocalId = :localId AND operation = 'ORDER_TRANSITION' AND state IN ('PENDING', 'RETRY', 'FAILED') ORDER BY createdAt ASC LIMIT 1")
    suspend fun getPendingOrderTransition(resourceType: String, localId: Long): SyncQueueEntity?

    /**
     * Snapshot resources can be changed several times before the worker gets
     * CPU/network time. Keep one durable upload for the latest snapshot rather
     * than uploading the same final Room row once per bucket adjustment.
     */
    @Query("SELECT * FROM sync_queue WHERE resourceType = :resourceType AND resourceLocalId = :localId AND operation IN ('CREATE', 'UPDATE') AND state IN ('PENDING', 'RETRY') ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun getPendingSnapshotMutation(resourceType: String, localId: Long): SyncQueueEntity?

    @Query(
        """UPDATE sync_queue SET operationId = :operationId, resourceRemoteId = :resourceRemoteId,
           stationLocalId = :stationLocalId, operation = :operation, expectedPreviousStatus = NULL,
           requestedStatus = NULL, stockDelta = NULL, stockTarget = NULL,
           expectedInventoryVersion = NULL, state = 'PENDING', retryCount = 0,
           lastAttemptAt = NULL, lastErrorCode = NULL, lastError = NULL, createdAt = :createdAt
           WHERE id = :id"""
    )
    suspend fun replacePendingSnapshotMutation(
        id: Long,
        operationId: String,
        resourceRemoteId: String,
        stationLocalId: Long?,
        operation: String,
        createdAt: Long
    )

    @Query("UPDATE sync_queue SET requestedStatus = :requestedStatus, state = 'PENDING', lastErrorCode = NULL, lastError = NULL WHERE id = :id")
    suspend fun updatePendingOrderTransition(id: Long, requestedStatus: String)

    @Query("UPDATE sync_queue SET state = 'IN_PROGRESS', lastAttemptAt = :attemptedAt WHERE id = :id")
    suspend fun markInProgress(id: Long, attemptedAt: Long)

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun markCompleted(id: Long)

    @Query("DELETE FROM sync_queue WHERE resourceType = :resourceType AND resourceLocalId = :localId")
    suspend fun deleteQueueForResource(resourceType: String, localId: Long)

    @Query("DELETE FROM sync_metadata WHERE resourceType = :resourceType AND localId = :localId")
    suspend fun deleteMetadataForResource(resourceType: String, localId: Long)

    @Query(
        """UPDATE sync_queue SET state = :state, retryCount = retryCount + 1,
           lastAttemptAt = :attemptedAt, lastErrorCode = :errorCode, lastError = :message
           WHERE id = :id"""
    )
    suspend fun markFailed(
        id: Long,
        state: String,
        attemptedAt: Long,
        errorCode: String?,
        message: String?
    )

    /**
     * Re-queues only container snapshots that failed under the retired
     * firebase_id upsert, which could not satisfy the station/size unique key.
     * The current writer uses an insert-only canonical RPC, so these failures
     * are safe to replay without overwriting an existing remote balance.
     */
    @Query(
        """UPDATE sync_queue
           SET state = 'PENDING', lastErrorCode = NULL, lastError = NULL
           WHERE resourceType = 'CONTAINER'
             AND operation IN ('CREATE', 'UPDATE')
             AND state = 'FAILED'
             AND lastErrorCode = 'PostgrestRestException'
             AND lastError LIKE '%on_conflict=firebase_id%'
             AND lastError LIKE '%containers_station_id_size_label_key%'"""
    )
    suspend fun requeueLegacyContainerIdentityConflicts(): Int

    @Query("UPDATE sync_queue SET state = 'PENDING', retryCount = 0, lastAttemptAt = NULL, lastErrorCode = NULL, lastError = NULL WHERE state = 'FAILED'")
    suspend fun resetFailedItems(): Int

    @Query("UPDATE sync_queue SET state = 'PENDING' WHERE state = 'IN_PROGRESS'")
    suspend fun recoverInterruptedWork()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: SyncCursorEntity)

    @Query(
        """SELECT * FROM sync_cursors
           WHERE businessId = :businessId AND stationRemoteId = :stationRemoteId AND resourceType = :resourceType
           LIMIT 1"""
    )
    suspend fun getCursor(
        businessId: String,
        stationRemoteId: String,
        resourceType: String
    ): SyncCursorEntity?
}
