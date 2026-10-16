package com.aesprt.aquahub.sync

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.repository.RoomInventoryRepository
import com.aesprt.aquahub.domain.model.ContainerTransaction
import com.aesprt.aquahub.domain.model.ContainerTransactionType
import com.aesprt.aquahub.domain.repository.InventoryRepository.ContainerField
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryTransactionTest {
    @Test fun invalidMovementRollsBackLedgerAndStock() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val queue = SyncQueueWriter(db.syncDao(), SyncScheduler(context))
            val repository = RoomInventoryRepository(db.containerDao(), db.containerTransactionDao(), db, queue)
            repository.adjustContainer(1, "5 Gallon", 2, ContainerField.AVAILABLE)
            try {
                db.withTransaction {
                    repository.insertTransaction(ContainerTransaction(stationId = 1, containerSize = "5 Gallon",
                        type = ContainerTransactionType.DAMAGED, quantity = 3))
                    repository.adjustContainer(1, "5 Gallon", -3, ContainerField.AVAILABLE)
                    repository.adjustContainer(1, "5 Gallon", 3, ContainerField.DAMAGED)
                }
                fail("Overdraw should fail")
            } catch (_: IllegalArgumentException) { }
            assertEquals(2, repository.getContainerBySizeLabel(1, "5 Gallon")?.available)
            assertTrue(repository.observeContainerTransactions(1).first().isEmpty())
            db.withTransaction {
                repository.adjustContainer(1, "5 Gallon", -1, ContainerField.AVAILABLE)
                repository.adjustContainer(1, "5 Gallon", 1, ContainerField.WITH_CUSTOMERS)
            }
            db.withTransaction {
                repository.adjustContainer(1, "5 Gallon", 1, ContainerField.AVAILABLE)
                repository.adjustContainer(1, "5 Gallon", -1, ContainerField.WITH_CUSTOMERS)
            }
            assertEquals(2, repository.getContainerBySizeLabel(1, "5 Gallon")?.totalOwned)
        } finally {
            db.close()
        }
    }
}
