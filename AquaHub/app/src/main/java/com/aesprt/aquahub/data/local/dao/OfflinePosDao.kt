package com.aesprt.aquahub.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.aesprt.aquahub.data.local.entity.OfflinePosGrantEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosLeaseEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosLeaseItemEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosOperationEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosOperationLineEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosReconciliationEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OfflinePosDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGrant(grant: OfflinePosGrantEntity)

    @Query("SELECT * FROM offline_pos_grants WHERE profileUid = :profileUid AND organizationId = :organizationId AND stationId = :stationId AND deviceId = :deviceId AND status = 'ACTIVE' ORDER BY issuedAtMs DESC LIMIT 1")
    suspend fun getLatestActiveGrant(profileUid: String, organizationId: String, stationId: Long, deviceId: String): OfflinePosGrantEntity?

    @Query("SELECT * FROM offline_pos_grants WHERE profileUid = :profileUid AND stationId = :stationId AND deviceId = :deviceId ORDER BY issuedAtMs DESC LIMIT 1")
    suspend fun getLatestGrantForDevice(profileUid: String, stationId: Long, deviceId: String): OfflinePosGrantEntity?

    @Query("SELECT * FROM offline_pos_grants WHERE grantId = :grantId LIMIT 1")
    suspend fun getGrant(grantId: String): OfflinePosGrantEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLease(lease: OfflinePosLeaseEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLeaseItems(items: List<OfflinePosLeaseItemEntity>)

    @Query("SELECT * FROM offline_pos_leases WHERE leaseId = :leaseId LIMIT 1")
    suspend fun getLease(leaseId: String): OfflinePosLeaseEntity?

    @Query("SELECT * FROM offline_pos_leases WHERE grantId = :grantId ORDER BY issuedAtMs DESC LIMIT 1")
    suspend fun getLeaseForGrant(grantId: String): OfflinePosLeaseEntity?

    @Query("SELECT * FROM offline_pos_lease_items WHERE leaseId = :leaseId ORDER BY productRemoteId")
    suspend fun getLeaseItems(leaseId: String): List<OfflinePosLeaseItemEntity>

    @Query("SELECT * FROM offline_pos_lease_items WHERE leaseId = :leaseId AND productRemoteId = :productRemoteId LIMIT 1")
    suspend fun getLeaseItem(leaseId: String, productRemoteId: String): OfflinePosLeaseItemEntity?

    @Query("SELECT * FROM offline_pos_leases WHERE stationId = :stationId AND profileUid = :profileUid AND deviceId = :deviceId AND status = 'ACTIVE' ORDER BY issuedAtMs DESC LIMIT 1")
    suspend fun getLatestActiveLease(stationId: Long, profileUid: String, deviceId: String): OfflinePosLeaseEntity?

    @Update
    suspend fun updateLeaseItem(item: OfflinePosLeaseItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperation(operation: OfflinePosOperationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOperationLines(lines: List<OfflinePosOperationLineEntity>)

    @Query("SELECT * FROM offline_pos_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun getOperation(operationId: String): OfflinePosOperationEntity?

    @Query("SELECT * FROM offline_pos_operations WHERE state IN ('PENDING', 'RETRY') AND (nextAttemptAtMs IS NULL OR nextAttemptAtMs <= :nowMs) ORDER BY createdAtMs LIMIT :limit")
    suspend fun getReadyOperations(nowMs: Long, limit: Int): List<OfflinePosOperationEntity>

    @Query("UPDATE offline_pos_operations SET state = 'SYNCING', attemptCount = attemptCount + 1, nextAttemptAtMs = :claimUntilMs, lastErrorCode = NULL, lastError = NULL WHERE operationId = :operationId AND state IN ('PENDING','RETRY')")
    suspend fun claimOperation(operationId: String, claimUntilMs: Long): Int

    @Query("UPDATE offline_pos_operations SET state = 'RETRY', nextAttemptAtMs = :nowMs, lastErrorCode = 'WORKER_INTERRUPTED', lastError = 'Synchronization was interrupted and will resume.' WHERE state = 'SYNCING' AND nextAttemptAtMs IS NOT NULL AND nextAttemptAtMs <= :nowMs")
    suspend fun recoverInterruptedOperations(nowMs: Long): Int

    @Query("UPDATE offline_pos_operations SET state = :state, nextAttemptAtMs = :nextAttemptAtMs, lastErrorCode = :errorCode, lastError = :errorMessage WHERE operationId = :operationId AND state = 'SYNCING'")
    suspend fun recordFailure(operationId: String, state: String, nextAttemptAtMs: Long?, errorCode: String, errorMessage: String)

    @Query("UPDATE offline_pos_operations SET state = 'COMMITTED', remoteOrderId = :remoteOrderId, reconciledAtMs = :nowMs, nextAttemptAtMs = NULL, lastErrorCode = NULL, lastError = NULL WHERE operationId = :operationId")
    suspend fun markCommitted(operationId: String, remoteOrderId: String, nowMs: Long)

    @Query("UPDATE offline_pos_operations SET state = 'RECONCILIATION_REQUIRED', nextAttemptAtMs = NULL, lastErrorCode = :errorCode, lastError = :errorMessage WHERE operationId = :operationId")
    suspend fun markReconciliationRequired(operationId: String, errorCode: String, errorMessage: String)

    @Query("UPDATE offline_pos_operations SET state = 'RETRY', attemptCount = 0, nextAttemptAtMs = :nowMs, lastErrorCode = NULL, lastError = NULL WHERE operationId = :operationId AND profileUid = :profileUid AND state = 'RECONCILIATION_REQUIRED'")
    suspend fun requestReconciliationRetry(operationId: String, profileUid: String, nowMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReconciliationEvent(event: OfflinePosReconciliationEventEntity)

    @Query("SELECT * FROM offline_pos_reconciliation_events WHERE stationId = :stationId AND actorUid = :actorUid AND eventType = 'RESERVATION_REQUESTED' AND state = 'PENDING' ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun getPendingReservationRequest(stationId: Long, actorUid: String): OfflinePosReconciliationEventEntity?

    @Transaction
    @Query("SELECT * FROM offline_pos_operations WHERE stationId = :stationId AND profileUid = :profileUid AND state <> 'COMMITTED' ORDER BY transactionAtMs DESC LIMIT 20")
    suspend fun getRecoveryOperations(stationId: Long, profileUid: String): List<OfflinePosOperationWithLines>

    @Query("UPDATE offline_pos_reconciliation_events SET state = :state WHERE eventId = :eventId AND eventType = 'RESERVATION_REQUESTED'")
    suspend fun updateReservationRequestState(eventId: String, state: String)

    @Query("SELECT COUNT(*) FROM offline_pos_operations WHERE state NOT IN ('COMMITTED')")
    suspend fun getOutstandingOperationCount(): Int

    @Query("SELECT COUNT(*) FROM offline_pos_operations WHERE stationId = :stationId AND profileUid = :profileUid AND state NOT IN ('COMMITTED')")
    fun observePendingCount(stationId: Long, profileUid: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(totalCentavos), 0) FROM offline_pos_operations WHERE stationId = :stationId AND profileUid = :profileUid AND transactionAtMs >= :startMs AND transactionAtMs < :endMs AND state NOT IN ('COMMITTED')")
    fun observePendingCashSalesCentavos(stationId: Long, profileUid: String, startMs: Long, endMs: Long): Flow<Long>

    @Query("SELECT COALESCE(SUM(totalCentavos), 0) FROM offline_pos_operations WHERE stationId = :stationId AND profileUid = :profileUid AND transactionAtMs >= :startMs AND transactionAtMs < :endMs AND state NOT IN ('COMMITTED')")
    suspend fun getPendingCashSalesCentavos(stationId: Long, profileUid: String, startMs: Long, endMs: Long): Long

    @Query("SELECT COUNT(*) FROM offline_pos_operations WHERE stationId = :stationId AND profileUid = :profileUid AND state NOT IN ('COMMITTED')")
    suspend fun getPendingCount(stationId: Long, profileUid: String): Int

    @Query("SELECT * FROM offline_pos_operations WHERE stationId = :stationId ORDER BY createdAtMs DESC")
    fun observeOperations(stationId: Long): Flow<List<OfflinePosOperationEntity>>

    @Transaction
    @Query("SELECT * FROM offline_pos_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun getOperationWithLines(operationId: String): OfflinePosOperationWithLines?
}

data class OfflinePosOperationWithLines(
    @androidx.room.Embedded val operation: OfflinePosOperationEntity,
    @androidx.room.Relation(parentColumn = "operationId", entityColumn = "operationId")
    val lines: List<OfflinePosOperationLineEntity>
)
