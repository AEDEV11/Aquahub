package com.aesprt.aquahub.data.repository

import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.dao.ExpenseDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.entity.ExpenseEntity
import com.aesprt.aquahub.data.supabase.expense.SupabaseExpenseDataSource
import com.aesprt.aquahub.domain.model.Expense
import com.aesprt.aquahub.domain.model.ExpenseCategory
import com.aesprt.aquahub.domain.model.ExpensePaymentMethod
import com.aesprt.aquahub.domain.repository.ExpenseRepository
import com.aesprt.aquahub.sync.SyncOperation
import com.aesprt.aquahub.sync.SyncQueueWriter
import com.aesprt.aquahub.sync.SyncResourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.github.jan.supabase.auth.auth
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import java.util.UUID
import java.time.Instant
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity

class RoomExpenseRepository(
    private val expenseDao: ExpenseDao,
    private val database: AquaHubDatabase,
    private val syncQueue: SyncQueueWriter,
    private val syncDao: SyncDao,
    private val supabaseExpenseDataSource: SupabaseExpenseDataSource
) : ExpenseRepository {
    override fun observeExpenses(
        stationId: Long,
        startAt: Long,
        endAt: Long,
        category: ExpenseCategory?
    ): Flow<List<Expense>> = expenseDao.observeExpenses(stationId, startAt, endAt, category?.name)
        .map { rows -> rows.map(ExpenseEntity::toDomain) }

    override fun observeTotal(stationId: Long, startAt: Long, endAt: Long): Flow<Long> =
        expenseDao.observeTotal(stationId, startAt, endAt)

    override suspend fun getExpense(expenseId: Long): Expense? =
        expenseDao.getExpense(expenseId)?.toDomain()

    override suspend fun saveExpense(expense: Expense): Long = database.withTransaction {
        val now = System.currentTimeMillis()
        val existing = if (expense.id > 0) {
            requireNotNull(expenseDao.getExpense(expense.id)) { "Expense no longer exists" }
        } else {
            null
        }
        require(existing == null || existing.stationId == expense.stationId) {
            "Expense does not belong to the active station"
        }
        val saved = expense.copy(
            createdAt = existing?.createdAt ?: expense.createdAt,
            updatedAt = now,
            isActive = true
        )
        val operationId = existing?.operationId ?: "expense:${UUID.randomUUID()}"
        val local = saved.toEntity().copy(
            remoteId = existing?.remoteId,
            firebaseId = existing?.firebaseId,
            organizationRemoteId = existing?.organizationRemoteId,
            createdByRemoteId = existing?.createdByRemoteId,
            receiptPath = existing?.receiptPath,
            operationId = operationId
        )
        val id = if (existing == null) {
            expenseDao.insertExpense(local.copy(id = 0))
        } else {
            expenseDao.updateExpense(local.copy(id = saved.id))
            saved.id
        }
        syncQueue.enqueue(
            SyncResourceType.EXPENSE,
            id,
            saved.stationId,
            if (existing == null) SyncOperation.CREATE else SyncOperation.UPDATE,
            now,
            stableOperationId = operationId
        )
        id
    }

    override suspend fun archiveExpense(expenseId: Long) = database.withTransaction {
        val row = expenseDao.getExpense(expenseId) ?: return@withTransaction
        if (!row.isActive) return@withTransaction
        val now = System.currentTimeMillis()
        expenseDao.archiveExpense(expenseId, now)
        syncQueue.enqueue(SyncResourceType.EXPENSE, expenseId, row.stationId, SyncOperation.ARCHIVE, now)
    }

    override suspend fun refreshExpenses(stationId: Long): Result<Unit> {
        if (SupabaseClientProvider.client.auth.currentUserOrNull() == null) return Result.success(Unit)
        val stationRemoteId = syncDao.getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId
            ?: return Result.failure(IllegalStateException("Station is not synchronized to the server."))
        return try {
            val remoteRows = supabaseExpenseDataSource.expenses(stationRemoteId)
            database.withTransaction {
                remoteRows.forEach { remote ->
                    if (remote.stationId != stationRemoteId) return@forEach
                    val metadata = syncDao.getMetadataByRemoteId(SyncResourceType.EXPENSE.name, remote.id)
                    val existing = metadata?.localId?.let { localId -> expenseDao.getExpense(localId) }
                        ?: expenseDao.getExpenseByRemoteId(remote.id)
                    if (existing != null && syncDao.hasOutstandingMutation(SyncResourceType.EXPENSE.name, existing.id) > 0) return@forEach
                    val createdAt = Instant.parse(remote.createdAt).toEpochMilli()
                    val updatedAt = Instant.parse(remote.updatedAt).toEpochMilli()
                    val local = ExpenseEntity(
                        id = existing?.id ?: 0,
                        stationId = stationId,
                        amountCentavos = remote.amountCentavos,
                        category = remote.category,
                        description = remote.description,
                        vendor = remote.vendor,
                        paymentMethod = remote.paymentMethod,
                        referenceNumber = remote.referenceNumber,
                        notes = remote.notes,
                        incurredAt = remote.incurredAt,
                        createdAt = createdAt,
                        updatedAt = updatedAt,
                        isActive = remote.isActive,
                        remoteId = remote.id,
                        firebaseId = remote.firebaseId,
                        organizationRemoteId = remote.organizationId,
                        createdByRemoteId = remote.createdBy,
                        receiptPath = remote.receiptPath,
                        operationId = remote.operationId
                    )
                    val localId = if (existing == null) expenseDao.insertExpense(local) else {
                        expenseDao.updateExpense(local)
                        existing.id
                    }
                    syncDao.upsertMetadata(
                        SyncMetadataEntity(
                            resourceType = SyncResourceType.EXPENSE.name,
                            localId = localId,
                            remoteId = remote.id,
                            stationLocalId = stationId,
                            localVersion = metadata?.localVersion ?: 1L,
                            remoteVersion = maxOf(metadata?.remoteVersion ?: 0L, 1L),
                            lastLocalUpdatedAt = updatedAt,
                            lastServerUpdatedAt = updatedAt,
                            lastSyncedAt = System.currentTimeMillis(),
                            deletedAt = if (remote.isActive) null else updatedAt
                        )
                    )
                }
            }
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}

private fun ExpenseEntity.toDomain() = Expense(
    id = id,
    stationId = stationId,
    amountCentavos = amountCentavos,
    category = runCatching { ExpenseCategory.valueOf(category) }.getOrDefault(ExpenseCategory.OTHER),
    description = description,
    vendor = vendor,
    paymentMethod = runCatching { ExpensePaymentMethod.valueOf(paymentMethod) }.getOrDefault(ExpensePaymentMethod.OTHER),
    referenceNumber = referenceNumber,
    notes = notes,
    incurredAt = incurredAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isActive = isActive
)

private fun Expense.toEntity() = ExpenseEntity(
    id = id,
    stationId = stationId,
    amountCentavos = amountCentavos,
    category = category.name,
    description = description,
    vendor = vendor,
    paymentMethod = paymentMethod.name,
    referenceNumber = referenceNumber,
    notes = notes,
    incurredAt = incurredAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isActive = isActive
)
