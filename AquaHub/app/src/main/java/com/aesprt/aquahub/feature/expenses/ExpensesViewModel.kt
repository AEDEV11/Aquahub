package com.aesprt.aquahub.feature.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.Expense
import com.aesprt.aquahub.domain.model.ExpenseCategory
import com.aesprt.aquahub.domain.model.ExpensePaymentMethod
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.usecase.auth.ObserveCurrentOwnerUseCase
import com.aesprt.aquahub.domain.usecase.expense.ArchiveExpenseUseCase
import com.aesprt.aquahub.domain.usecase.expense.ObserveExpenseTotalUseCase
import com.aesprt.aquahub.domain.usecase.expense.ObserveExpensesUseCase
import com.aesprt.aquahub.domain.usecase.expense.SaveExpenseUseCase
import com.aesprt.aquahub.domain.usecase.report.ObserveDashboardSummaryUseCase
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Calendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ExpensePeriod(val label: String) { TODAY("Today"), LAST_7_DAYS("7 days"), THIS_MONTH("This month") }

data class ExpenseDraft(
    val amount: String = "",
    val category: ExpenseCategory = ExpenseCategory.SUPPLIES,
    val description: String = "",
    val vendor: String = "",
    val paymentMethod: ExpensePaymentMethod = ExpensePaymentMethod.CASH,
    val referenceNumber: String = "",
    val notes: String = "",
    val incurredAt: Long = System.currentTimeMillis()
)

data class ExpensesUiState(
    val stationId: Long = 0,
    val expenses: List<Expense> = emptyList(),
    val period: ExpensePeriod = ExpensePeriod.THIS_MONTH,
    val category: ExpenseCategory? = null,
    val selectedPeriodTotalCentavos: Long = 0,
    val todayExpenseCentavos: Long = 0,
    val monthExpenseCentavos: Long = 0,
    val todayRevenueCentavos: Long = 0,
    val canUseExpenses: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val editingExpense: Expense? = null,
    val showEditor: Boolean = false,
    val pendingDelete: Expense? = null,
    val freshnessWarning: String? = null,
    val message: String? = null
) {
    val todayNetCentavos: Long get() = todayRevenueCentavos - todayExpenseCentavos
}

private data class ExpenseFilter(val period: ExpensePeriod, val category: ExpenseCategory?)

private data class ExpenseLiveData(
    val stationId: Long,
    val expenses: List<Expense>,
    val periodTotal: Long,
    val todayTotal: Long,
    val monthTotal: Long,
    val todayRevenue: Long,
    val allowed: Boolean
)

class ExpensesViewModel(
    private val observeCurrentOwnerUseCase: ObserveCurrentOwnerUseCase,
    private val activeStationContext: ActiveStationContext,
    private val observeExpensesUseCase: ObserveExpensesUseCase,
    private val observeExpenseTotalUseCase: ObserveExpenseTotalUseCase,
    private val observeDashboardSummaryUseCase: ObserveDashboardSummaryUseCase,
    private val saveExpenseUseCase: SaveExpenseUseCase,
    private val archiveExpenseUseCase: ArchiveExpenseUseCase,
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val refreshExpensesUseCase: com.aesprt.aquahub.domain.usecase.expense.RefreshExpensesUseCase
) : ViewModel() {
    private val filter = MutableStateFlow(ExpenseFilter(ExpensePeriod.THIS_MONTH, null))
    private val _uiState = MutableStateFlow(ExpensesUiState())
    val uiState: StateFlow<ExpensesUiState> = _uiState.asStateFlow()
    private var expenseRefreshJob: kotlinx.coroutines.Job? = null

    init { observeData() }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeData() {
        viewModelScope.launch {
            val stationIds = combine(
                activeStationContext.observeActiveStationId(),
                observeCurrentOwnerUseCase()
            ) { active, owner -> active ?: owner?.stationId ?: 0L }.distinctUntilChanged()

            combine(stationIds, filter) { stationId, selected -> stationId to selected }
                .flatMapLatest { (stationId, selected) ->
                    expenseRefreshJob?.cancel()
                    if (stationId > 0) {
                        expenseRefreshJob = viewModelScope.launch {
                            val result = refreshExpensesUseCase(stationId)
                            _uiState.update { current ->
                                if (current.stationId != 0L && current.stationId != stationId) current
                                else current.copy(
                                    freshnessWarning = result.exceptionOrNull()?.let {
                                        "Could not refresh expenses from the server. Showing this device's saved records."
                                    }
                                )
                            }
                        }
                    }
                    val allowed = stationId > 0 && checkSubscriptionAccessUseCase(
                        stationId,
                        SubscriptionFeature.EXPENSE_TRACKING
                    ) is SubscriptionAccessResult.Allowed
                    val periodRange = rangeFor(selected.period)
                    val today = rangeFor(ExpensePeriod.TODAY)
                    val month = rangeFor(ExpensePeriod.THIS_MONTH)
                    combine(
                        observeExpensesUseCase(stationId, periodRange.first, periodRange.second, selected.category),
                        observeExpenseTotalUseCase(stationId, periodRange.first, periodRange.second),
                        observeExpenseTotalUseCase(stationId, today.first, today.second),
                        observeExpenseTotalUseCase(stationId, month.first, month.second),
                        observeDashboardSummaryUseCase(stationId)
                    ) { expenses, periodTotal, todayTotal, monthTotal, dashboard ->
                        ExpenseLiveData(
                            stationId = stationId,
                            expenses = expenses,
                            periodTotal = periodTotal,
                            todayTotal = todayTotal,
                            monthTotal = monthTotal,
                            todayRevenue = BigDecimal.valueOf(dashboard.todaySales)
                                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact(),
                            allowed = allowed
                        )
                    }
                }.collect { live ->
                    _uiState.update {
                        it.copy(
                            stationId = live.stationId,
                            expenses = live.expenses,
                            selectedPeriodTotalCentavos = live.periodTotal,
                            todayExpenseCentavos = live.todayTotal,
                            monthExpenseCentavos = live.monthTotal,
                            todayRevenueCentavos = live.todayRevenue,
                            canUseExpenses = live.allowed,
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun setPeriod(period: ExpensePeriod) {
        filter.update { it.copy(period = period) }
        _uiState.update { it.copy(period = period) }
    }

    fun setCategory(category: ExpenseCategory?) {
        filter.update { it.copy(category = category) }
        _uiState.update { it.copy(category = category) }
    }

    fun openAdd() = _uiState.update { it.copy(showEditor = true, editingExpense = null, message = null) }
    fun openEdit(expense: Expense) = _uiState.update { it.copy(showEditor = true, editingExpense = expense, message = null) }
    fun closeEditor() = _uiState.update { it.copy(showEditor = false, editingExpense = null) }
    fun requestDelete(expense: Expense) = _uiState.update { it.copy(pendingDelete = expense) }
    fun cancelDelete() = _uiState.update { it.copy(pendingDelete = null) }
    fun clearMessage() = _uiState.update { it.copy(message = null) }

    fun save(draft: ExpenseDraft) {
        val state = _uiState.value
        val amountCentavos = parseAmountCentavos(draft.amount)
        if (amountCentavos == null) {
            _uiState.update { it.copy(message = "Enter a valid amount greater than zero") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, message = null) }
            runCatching {
                saveExpenseUseCase(
                    Expense(
                        id = state.editingExpense?.id ?: 0L,
                        stationId = state.stationId,
                        amountCentavos = amountCentavos,
                        category = draft.category,
                        description = draft.description,
                        vendor = draft.vendor,
                        paymentMethod = draft.paymentMethod,
                        referenceNumber = draft.referenceNumber,
                        notes = draft.notes,
                        incurredAt = draft.incurredAt,
                        createdAt = state.editingExpense?.createdAt ?: System.currentTimeMillis()
                    )
                )
            }.onSuccess {
                _uiState.update { it.copy(isSaving = false, showEditor = false, editingExpense = null, message = "Expense saved") }
            }.onFailure { error ->
                _uiState.update { it.copy(isSaving = false, message = error.message ?: "Unable to save expense") }
            }
        }
    }

    fun confirmDelete() {
        val state = _uiState.value
        val expense = state.pendingDelete ?: return
        viewModelScope.launch {
            runCatching { archiveExpenseUseCase(state.stationId, expense.id) }
                .onSuccess { _uiState.update { it.copy(pendingDelete = null, message = "Expense removed") } }
                .onFailure { error -> _uiState.update { it.copy(pendingDelete = null, message = error.message ?: "Unable to remove expense") } }
        }
    }
}

private fun parseAmountCentavos(value: String): Long? = runCatching {
    BigDecimal(value.trim().replace(",", ""))
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
        .takeIf { it > 0 }
}.getOrNull()

private fun rangeFor(period: ExpensePeriod, now: Long = System.currentTimeMillis()): Pair<Long, Long> {
    val endOfToday = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
    val start = Calendar.getInstance().apply {
        timeInMillis = endOfToday
        when (period) {
            ExpensePeriod.TODAY -> add(Calendar.DAY_OF_MONTH, -1)
            ExpensePeriod.LAST_7_DAYS -> add(Calendar.DAY_OF_MONTH, -7)
            ExpensePeriod.THIS_MONTH -> {
                add(Calendar.DAY_OF_MONTH, -1)
                set(Calendar.DAY_OF_MONTH, 1)
            }
        }
    }.timeInMillis
    return start to endOfToday
}
