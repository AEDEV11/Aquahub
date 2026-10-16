package com.aesprt.aquahub.sync

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.local.entity.SyncQueueEntity
import com.aesprt.aquahub.data.local.entity.ProductEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncQueuePersistenceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun pendingMutationAndStableRemoteIdSurviveDatabaseRestart() = runBlocking {
        val remoteId = "remote-product-42"
        val database = openDatabase()
        try {
            database.syncDao().upsertMetadata(
                SyncMetadataEntity(
                    resourceType = SyncResourceType.PRODUCT.name,
                    localId = 42,
                    remoteId = remoteId,
                    stationLocalId = 7,
                    lastLocalUpdatedAt = 100
                )
            )
            database.syncDao().enqueue(
                SyncQueueEntity(
                    operationId = "operation-product-42",
                    resourceType = SyncResourceType.PRODUCT.name,
                    resourceLocalId = 42,
                    resourceRemoteId = remoteId,
                    stationLocalId = 7,
                    operation = SyncOperation.CREATE.name,
                    createdAt = 100
                )
            )
        } finally {
            database.close()
        }

        val restarted = openDatabase()
        try {
            val metadata = restarted.syncDao().getMetadata(SyncResourceType.PRODUCT.name, 42)
            val pending = restarted.syncDao().getPending(10)
            assertNotNull(metadata)
            assertEquals(remoteId, metadata?.remoteId)
            assertEquals(1, pending.size)
            assertEquals("operation-product-42", pending.single().operationId)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun interruptedUploadReturnsToPendingAfterRestartRecovery() = runBlocking {
        val database = openDatabase()
        try {
            val id = database.syncDao().enqueue(
                SyncQueueEntity(
                    operationId = "walk-in-operation",
                    resourceType = SyncResourceType.ORDER.name,
                    resourceLocalId = 9,
                    resourceRemoteId = "remote-order-9",
                    stationLocalId = 7,
                    operation = SyncOperation.CREATE.name,
                    createdAt = 200
                )
            )
            database.syncDao().markInProgress(id, 201)
        } finally {
            database.close()
        }

        val restarted = openDatabase()
        try {
            restarted.syncDao().recoverInterruptedWork()
            val pending = restarted.syncDao().getPending(10)
            assertEquals(1, pending.size)
            assertEquals("walk-in-operation", pending.single().operationId)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun manualRetryResetsExhaustedRetryWindowWithoutChangingOperationIdentity() = runBlocking {
        val database = openDatabase()
        try {
            val id = database.syncDao().enqueue(
                SyncQueueEntity(
                    operationId = "stable-operation-after-retry",
                    resourceType = SyncResourceType.ORDER.name,
                    resourceLocalId = 19,
                    resourceRemoteId = "remote-order-19",
                    stationLocalId = 7,
                    operation = SyncOperation.CREATE.name,
                    createdAt = 500
                )
            )
            database.syncDao().markFailed(id, "FAILED", 900, "RETRY_LIMIT_REACHED", "temporary outage")

            assertEquals(1, database.syncDao().resetFailedItems())
            val pending = database.syncDao().getPending(10).single()
            assertEquals("stable-operation-after-retry", pending.operationId)
            assertEquals(0, pending.retryCount)
            assertEquals(null, pending.lastAttemptAt)
            assertEquals(null, pending.lastErrorCode)
            assertEquals(null, pending.lastError)
        } finally {
            database.close()
        }
    }

    @Test
    fun stockAdjustmentDeltaSurvivesDatabaseRestart() = runBlocking {
        val database = openDatabase()
        try {
            database.syncDao().enqueue(
                SyncQueueEntity(
                    operationId = "stock-adjustment-operation",
                    resourceType = SyncResourceType.PRODUCT.name,
                    resourceLocalId = 42,
                    resourceRemoteId = "remote-product-42",
                    stationLocalId = 7,
                    operation = SyncOperation.STOCK_ADJUSTMENT.name,
                    stockDelta = -3,
                    createdAt = 300
                )
            )
        } finally {
            database.close()
        }

        val restarted = openDatabase()
        try {
            val pending = restarted.syncDao().getPending(10).single()
            assertEquals(SyncOperation.STOCK_ADJUSTMENT.name, pending.operation)
            assertEquals(-3, pending.stockDelta)
        } finally {
            restarted.close()
        }
    }

    @Test
    fun archivedProductStaysInactiveAfterDatabaseRestart() = runBlocking {
        val database = openDatabase()
        val productId = try {
            database.productDao().insertProduct(
                ProductEntity(
                    stationId = 7,
                    name = "Test Refill",
                    type = "REFILL",
                    sizeLabel = "20 Liter",
                    price = 35.0,
                    stockQuantity = 4,
                    imagePath = null,
                    isAvailable = true,
                    createdAt = 400,
                    updatedAt = 400
                )
            ).also { database.productDao().archive(it, 401) }
        } finally {
            database.close()
        }

        val restarted = openDatabase()
        try {
            val product = restarted.productDao().getProductById(productId)
            assertNotNull(product)
            assertEquals(false, product?.isActive)
            assertEquals(false, product?.isAvailable)
        } finally {
            restarted.close()
        }
    }

    private fun openDatabase(): AquaHubDatabase =
        Room.databaseBuilder(context, AquaHubDatabase::class.java, TEST_DATABASE)
            .addMigrations(
                AquaHubDatabase.MIGRATION_1_2,
                AquaHubDatabase.MIGRATION_2_3,
                AquaHubDatabase.MIGRATION_3_4,
                AquaHubDatabase.MIGRATION_4_5,
                AquaHubDatabase.MIGRATION_5_6,
                AquaHubDatabase.MIGRATION_6_7,
                AquaHubDatabase.MIGRATION_7_8,
            )
            .build()

    companion object {
        private const val TEST_DATABASE = "aquahub-sync-persistence-test.db"
    }
}
