package com.aesprt.aquahub.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aesprt.aquahub.data.local.entity.ExpenseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExpenseDao {
    @Query(
        """
        SELECT * FROM expenses
        WHERE stationId = :stationId AND isActive = 1
          AND incurredAt >= :startAt AND incurredAt < :endAt
          AND (:category IS NULL OR category = :category)
        ORDER BY incurredAt DESC, createdAt DESC
        """
    )
    fun observeExpenses(
        stationId: Long,
        startAt: Long,
        endAt: Long,
        category: String?
    ): Flow<List<ExpenseEntity>>

    @Query(
        """
        SELECT COALESCE(SUM(amountCentavos), 0) FROM expenses
        WHERE stationId = :stationId AND isActive = 1
          AND incurredAt >= :startAt AND incurredAt < :endAt
        """
    )
    fun observeTotal(stationId: Long, startAt: Long, endAt: Long): Flow<Long>

    @Query("SELECT * FROM expenses WHERE id = :expenseId LIMIT 1")
    suspend fun getExpense(expenseId: Long): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getExpenseByRemoteId(remoteId: String): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE stationId = :stationId ORDER BY incurredAt DESC, createdAt DESC")
    suspend fun getExpensesForStation(stationId: Long): List<ExpenseEntity>

    @Query("SELECT * FROM expenses ORDER BY id ASC")
    suspend fun getAllExpenses(): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Update
    suspend fun updateExpense(expense: ExpenseEntity)

    @Query("UPDATE expenses SET isActive = 0, updatedAt = :updatedAt WHERE id = :expenseId")
    suspend fun archiveExpense(expenseId: Long, updatedAt: Long)
}
