package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.Container
import com.aesprt.aquahub.domain.model.ContainerTransaction
import kotlinx.coroutines.flow.Flow
import androidx.paging.PagingSource

interface InventoryRepository {
    fun pagingContainerTransactions(stationId: Long): PagingSource<Int, ContainerTransaction>
    fun observeContainers(stationId: Long): Flow<List<Container>>
    fun observeContainerTransactions(stationId: Long): Flow<List<ContainerTransaction>>
    fun observeCustomerTransactions(customerId: Long): Flow<List<ContainerTransaction>>
    suspend fun getContainerBySizeLabel(stationId: Long, sizeLabel: String): Container?
    suspend fun upsertContainer(container: Container)
    suspend fun insertTransaction(transaction: ContainerTransaction): Long
    suspend fun adjustContainer(stationId: Long, sizeLabel: String, delta: Int, field: ContainerField)

    enum class ContainerField { AVAILABLE, WITH_CUSTOMERS, DAMAGED, LOST }
}
