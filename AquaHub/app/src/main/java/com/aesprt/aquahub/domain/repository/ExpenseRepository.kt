package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.Expense
import com.aesprt.aquahub.domain.model.ExpenseCategory
import kotlinx.coroutines.flow.Flow

interface ExpenseRepository {
    fun observeExpenses(
        stationId: Long,
        startAt: Long,
        endAt: Long,
        category: ExpenseCategory? = null
    ): Flow<List<Expense>>

    fun observeTotal(stationId: Long, startAt: Long, endAt: Long): Flow<Long>
    suspend fun getExpense(expenseId: Long): Expense?
    suspend fun refreshExpenses(stationId: Long): Result<Unit> = Result.success(Unit)
    suspend fun saveExpense(expense: Expense): Long
    suspend fun archiveExpense(expenseId: Long)
}
