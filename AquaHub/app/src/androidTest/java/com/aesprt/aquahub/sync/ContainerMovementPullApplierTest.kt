package com.aesprt.aquahub.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.supabase.inventory.SupabaseContainerMovementRow
import com.aesprt.aquahub.domain.model.ContainerTransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContainerMovementPullApplierTest {
    @Test
    fun repeatedRemoteLoansStayAdditiveAndRepeatedPullIsIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val customerId = 77L
            db.syncDao().upsertMetadata(
                SyncMetadataEntity(
                    resourceType = SyncResourceType.CUSTOMER.name,
                    localId = customerId,
                    remoteId = "remote-customer",
                    stationLocalId = 1L,
                    lastLocalUpdatedAt = 1L,
                    lastSyncedAt = 1L
                )
            )
            val applier = ContainerMovementPullApplier(
                database = db,
                transactionDao = db.containerTransactionDao(),
                syncDao = db.syncDao()
            )
            val rows = listOf(
                movement("remote-movement-1", "movement-1", "DELIVERED", 2, 1_000L),
                movement("remote-movement-2", "movement-2", "DELIVERED", 3, 2_000L)
            )

            assertEquals(2, applier.apply(stationId = 1L, rows = rows))
            val firstPull = db.containerTransactionDao().observeCustomerTransactions(customerId).first()
            assertEquals(2, firstPull.size)
            assertEquals(5, firstPull.filter { it.type == ContainerTransactionType.DELIVERED.name }.sumOf { it.quantity })

            assertEquals(0, applier.apply(stationId = 1L, rows = rows))
            val secondPull = db.containerTransactionDao().observeCustomerTransactions(customerId).first()
            assertEquals(2, secondPull.size)
            assertEquals(5, secondPull.sumOf { it.quantity })
        } finally {
            db.close()
        }
    }

    private fun movement(
        id: String,
        legacyId: String,
        type: String,
        quantity: Int,
        createdAt: Long
    ) = SupabaseContainerMovementRow(
        id = id,
        legacyId = legacyId,
        stationId = "remote-station",
        customerId = "remote-customer",
        movementType = type,
        quantity = quantity,
        containerSize = "5 Gallon",
        createdAt = java.time.Instant.ofEpochMilli(createdAt).toString()
    )
}
