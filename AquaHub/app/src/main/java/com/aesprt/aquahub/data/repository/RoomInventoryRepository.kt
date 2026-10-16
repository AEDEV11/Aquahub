package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.local.dao.ContainerDao
import com.aesprt.aquahub.data.local.dao.ContainerTransactionDao
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.local.mapper.toEntity
import com.aesprt.aquahub.domain.model.Container
import com.aesprt.aquahub.domain.model.ContainerTransaction
import com.aesprt.aquahub.domain.repository.InventoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.sync.*
import androidx.paging.PagingSource

class RoomInventoryRepository(
    private val containerDao: ContainerDao,
    private val transactionDao: ContainerTransactionDao,
    private val database: AquaHubDatabase,
    private val syncQueue: SyncQueueWriter
) : InventoryRepository {

    override fun pagingContainerTransactions(stationId: Long): PagingSource<Int, ContainerTransaction> =
        MappingPagingSource(
            source = transactionDao.pagingTransactions(stationId),
            transform = { it.toDomain() }
        )

    override fun observeContainers(stationId: Long): Flow<List<Container>> =
        containerDao.observeContainers(stationId).map { list -> list.map { it.toDomain() } }

    override fun observeContainerTransactions(stationId: Long): Flow<List<ContainerTransaction>> =
        transactionDao.observeTransactions(stationId).map { list -> list.map { it.toDomain() } }

    override fun observeCustomerTransactions(customerId: Long): Flow<List<ContainerTransaction>> =
        transactionDao.observeCustomerTransactions(customerId).map { list -> list.map { it.toDomain() } }

    override suspend fun getContainerBySizeLabel(stationId: Long, sizeLabel: String): Container? =
        containerDao.getContainerBySizeLabel(stationId, sizeLabel)?.toDomain()

    override suspend fun upsertContainer(container: Container) = database.withTransaction {
        val now = System.currentTimeMillis()
        containerDao.upsertContainer(container.copy(updatedAt = now).toEntity())
        val saved = containerDao.getContainerBySizeLabel(container.stationId, container.sizeLabel)
            ?: return@withTransaction
        syncQueue.enqueue(
            SyncResourceType.CONTAINER, saved.id, saved.stationId,
            if (container.id == 0L) SyncOperation.CREATE else SyncOperation.UPDATE, now
        )
    }

    override suspend fun insertTransaction(transaction: ContainerTransaction): Long = database.withTransaction {
        val id = transactionDao.insertTransaction(transaction.toEntity())
        syncQueue.enqueue(
            SyncResourceType.CONTAINER_MOVEMENT, id, transaction.stationId,
            SyncOperation.CREATE, transaction.createdAt
        )
        id
    }

    override suspend fun adjustContainer(
        stationId: Long,
        sizeLabel: String,
        delta: Int,
        field: InventoryRepository.ContainerField
    ) = database.withTransaction {
        require(stationId > 0) { "A valid station is required" }
        require(sizeLabel.isNotBlank()) { "Container size is required" }
        if (delta == 0) return@withTransaction
        val existing = containerDao.getContainerBySizeLabel(stationId, sizeLabel)
        val oldValue = when (field) {
            InventoryRepository.ContainerField.AVAILABLE -> existing?.available ?: 0
            InventoryRepository.ContainerField.WITH_CUSTOMERS -> existing?.withCustomers ?: 0
            InventoryRepository.ContainerField.DAMAGED -> existing?.damaged ?: 0
            InventoryRepository.ContainerField.LOST -> existing?.lost ?: 0
        }
        val newValue = oldValue.toLong() + delta
        require(newValue in 0..Int.MAX_VALUE.toLong()) { "Insufficient container stock or quantity too large" }
        val current = existing ?: run {
            val now = System.currentTimeMillis()
            val newEntity = com.aesprt.aquahub.data.local.entity.ContainerEntity(
                stationId = stationId,
                sizeLabel = sizeLabel,
                totalOwned = 0,
                available = 0,
                withCustomers = 0,
                damaged = 0,
                lost = 0,
                depositPrice = 200.0,
                createdAt = now,
                updatedAt = now
            )
            containerDao.upsertContainer(newEntity)
            containerDao.getContainerBySizeLabel(stationId, sizeLabel)
                ?: error("Container insert failed")
        }
        val next = when (field) {
            InventoryRepository.ContainerField.AVAILABLE -> current.copy(available = newValue.toInt())
            InventoryRepository.ContainerField.WITH_CUSTOMERS -> current.copy(withCustomers = newValue.toInt())
            InventoryRepository.ContainerField.DAMAGED -> current.copy(damaged = newValue.toInt())
            InventoryRepository.ContainerField.LOST -> current.copy(lost = newValue.toInt())
        }
        val now = System.currentTimeMillis()
        val totalLong = next.available.toLong() + next.withCustomers + next.damaged + next.lost
        require(totalLong <= Int.MAX_VALUE) { "Container total is too large" }
        val total = totalLong.toInt()
        val saved = next.copy(totalOwned = total, updatedAt = now)
        containerDao.upsertContainer(saved)
        // The linked CONTAINER_MOVEMENT outbox item is the authoritative
        // cross-device delta. Uploading this absolute snapshot as well can
        // overwrite another device's count or apply the same change twice.
    }
}
