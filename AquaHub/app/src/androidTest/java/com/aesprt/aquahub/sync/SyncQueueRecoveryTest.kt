package com.aesprt.aquahub.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncQueueRecoveryTest {
    @Test
    fun legacyOutboxRowsAreQuarantinedWithoutDeletingThemAndNewRowsRemainSyncable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val dao = database.syncDao()
            enqueue(dao, "legacy-pending")
            val legacyRetry = enqueue(dao, "legacy-retry")
            dao.markFailed(legacyRetry, "RETRY", 2, "timeout", "response unknown")
            val legacyFailed = enqueue(dao, "legacy-failed")
            dao.markFailed(legacyFailed, "FAILED", 2, "denied", "old tenant")
            val legacyInProgress = enqueue(dao, "legacy-in-progress")
            dao.markInProgress(legacyInProgress, 2)

            assertEquals(4, dao.quarantineLegacyOutstanding())
            assertEquals(0, dao.quarantineLegacyOutstanding())
            assertTrue(dao.getPending(20).isEmpty())
            assertEquals(4, dao.getOutstandingCount())
            assertEquals(4, dao.observeFailedCount().first())
            assertEquals(
                setOf("legacy-pending", "legacy-retry", "legacy-failed", "legacy-in-progress"),
                dao.getQuarantined().map { it.operationId }.toSet()
            )
            assertTrue(dao.getQuarantined().all { it.lastErrorCode == "LEGACY_REPLAY_BLOCKED" })

            enqueue(dao, "new-after-safety-boundary")
            assertEquals(listOf("new-after-safety-boundary"), dao.getPending(20).map { it.operationId })
        } finally {
            database.close()
        }
    }

    @Test
    fun onlyLegacyContainerIdentityConflictsAreRequeued() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val dao = database.syncDao()
            val legacyConflict = "duplicate key value violates unique constraint " +
                "containers_station_id_size_label_key; on_conflict=firebase_id"

            val oldCreate = failedMutation(
                dao = dao,
                operationId = "legacy-container-create",
                resourceType = SyncResourceType.CONTAINER,
                operation = SyncOperation.CREATE,
                errorCode = "PostgrestRestException",
                error = legacyConflict
            )
            val oldUpdate = failedMutation(
                dao = dao,
                operationId = "legacy-container-update",
                resourceType = SyncResourceType.CONTAINER,
                operation = SyncOperation.UPDATE,
                errorCode = "PostgrestRestException",
                error = legacyConflict
            )
            failedMutation(
                dao = dao,
                operationId = "unrelated-container-failure",
                resourceType = SyncResourceType.CONTAINER,
                operation = SyncOperation.CREATE,
                errorCode = "PostgrestRestException",
                error = "permission denied"
            )
            failedMutation(
                dao = dao,
                operationId = "unrelated-resource-failure",
                resourceType = SyncResourceType.PRODUCT,
                operation = SyncOperation.CREATE,
                errorCode = "PostgrestRestException",
                error = legacyConflict
            )

            assertEquals(2, dao.requeueLegacyContainerIdentityConflicts())

            val pending = dao.getPending(10).associateBy { it.operationId }
            assertEquals(setOf("legacy-container-create", "legacy-container-update"), pending.keys)
            assertEquals("PENDING", pending.getValue("legacy-container-create").state)
            assertNull(pending.getValue("legacy-container-create").lastErrorCode)
            assertEquals(2, dao.observeFailedCount().first())
            assertTrue(oldCreate > 0 && oldUpdate > 0)
        } finally {
            database.close()
        }
    }

    @Test
    fun verifiedOwnerRecoveryRequeuesOnlyRowsMarkedAsLegacyBlocked() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val dao = database.syncDao()
            enqueue(dao, "legacy-preserved")
            dao.quarantineLegacyOutstanding()
            val unrelated = enqueue(dao, "other-quarantine")
            dao.markFailed(unrelated, "QUARANTINED", 3, "OTHER_POLICY", "Kept for a separate recovery path")

            assertEquals(1, dao.getLegacyQuarantinedCount())
            assertEquals(1, dao.requeueVerifiedLegacyOutstanding())
            assertEquals(0, dao.getLegacyQuarantinedCount())
            assertEquals(listOf("legacy-preserved"), dao.getPending(10).map { it.operationId })
            assertEquals("OTHER_POLICY", dao.getQuarantined().single().lastErrorCode)
        } finally {
            database.close()
        }
    }

    private suspend fun failedMutation(
        dao: com.aesprt.aquahub.data.local.dao.SyncDao,
        operationId: String,
        resourceType: SyncResourceType,
        operation: SyncOperation,
        errorCode: String,
        error: String
    ): Long {
        val id = dao.enqueue(
            SyncQueueEntity(
                operationId = operationId,
                resourceType = resourceType.name,
                resourceLocalId = 1,
                resourceRemoteId = "remote-$operationId",
                stationLocalId = 1,
                operation = operation.name,
                createdAt = 1
            )
        )
        dao.markFailed(id, "FAILED", 2, errorCode, error)
        return id
    }

    private suspend fun enqueue(
        dao: com.aesprt.aquahub.data.local.dao.SyncDao,
        operationId: String
    ): Long = dao.enqueue(
        SyncQueueEntity(
            operationId = operationId,
            resourceType = SyncResourceType.PRODUCT.name,
            resourceLocalId = operationId.hashCode().toLong(),
            resourceRemoteId = "remote-$operationId",
            stationLocalId = 1,
            operation = SyncOperation.CREATE.name,
            createdAt = 1
        )
    )
}
