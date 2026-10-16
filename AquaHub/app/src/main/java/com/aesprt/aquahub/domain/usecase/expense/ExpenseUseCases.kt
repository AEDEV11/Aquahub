package com.aesprt.aquahub.domain.usecase.expense

import com.aesprt.aquahub.domain.model.Expense
import com.aesprt.aquahub.domain.model.ExpenseCategory
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.repository.ExpenseRepository
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import java.util.Calendar

class ObserveExpensesUseCase(private val repository: ExpenseRepository) {
    operator fun invoke(stationId: Long, startAt: Long, endAt: Long, category: ExpenseCategory?) =
        repository.observeExpenses(stationId, startAt, endAt, category)
}

class ObserveExpenseTotalUseCase(private val repository: ExpenseRepository) {
    operator fun invoke(stationId: Long, startAt: Long, endAt: Long) =
        repository.observeTotal(stationId, startAt, endAt)
}

class RefreshExpensesUseCase(private val repository: ExpenseRepository) {
    suspend operator fun invoke(stationId: Long): Result<Unit> = repository.refreshExpenses(stationId)
}

class SaveExpenseUseCase(
    private val repository: ExpenseRepository,
    private val access: CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(expense: Expense): Long {
        require(access(expense.stationId, SubscriptionFeature.EXPENSE_TRACKING) is SubscriptionAccessResult.Allowed) {
            "Expense tracking is available on the Business and Pro plans"
        }
        require(expense.amountCentavos in 1..MAX_EXPENSE_CENTAVOS) { "Enter a valid expense amount" }
        require(expense.description.trim().length in 2..120) { "Add a short expense description" }
        require(expense.vendor?.length ?: 0 <= 100) { "Vendor name is too long" }
        require(expense.referenceNumber?.length ?: 0 <= 80) { "Reference is too long" }
        require(expense.notes?.length ?: 0 <= 500) { "Notes are too long" }
        require(expense.incurredAt < startOfTomorrow()) { "Expense date cannot be in the future" }
        return repository.saveExpense(
            expense.copy(
                description = expense.description.trim(),
                vendor = expense.vendor?.trim()?.takeIf(String::isNotBlank),
                referenceNumber = expense.referenceNumber?.trim()?.takeIf(String::isNotBlank),
                notes = expense.notes?.trim()?.takeIf(String::isNotBlank)
            )
        )
    }

    private companion object {
        const val MAX_EXPENSE_CENTAVOS = 10_000_000_000L

        fun startOfTomorrow(): Long = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

class ArchiveExpenseUseCase(
    private val repository: ExpenseRepository,
    private val access: CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(stationId: Long, expenseId: Long) {
        require(access(stationId, SubscriptionFeature.EXPENSE_TRACKING) is SubscriptionAccessResult.Allowed) {
            "Expense tracking is available on the Business and Pro plans"
        }
        val expense = requireNotNull(repository.getExpense(expenseId)) { "Expense no longer exists" }
        require(expense.stationId == stationId) { "Expense does not belong to the active station" }
        repository.archiveExpense(expenseId)
    }
}
