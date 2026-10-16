package com.aesprt.aquahub.feature.expenses

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HomeWork
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.AquaHubEmptyState
import com.aesprt.aquahub.core.designsystem.components.AquaHubGlassCard
import com.aesprt.aquahub.core.designsystem.components.AquaHubLoadingIndicator
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import com.aesprt.aquahub.core.designsystem.components.AquaHubTopBar
import com.aesprt.aquahub.core.designsystem.components.AdaptiveContentFrame
import com.aesprt.aquahub.domain.model.Expense
import com.aesprt.aquahub.domain.model.ExpenseCategory
import com.aesprt.aquahub.domain.model.ExpensePaymentMethod
import com.aesprt.aquahub.ui.theme.AquaHubShapes
import com.aesprt.aquahub.ui.theme.AquaHubSpacing
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import java.math.BigDecimal
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpensesScreen(
    viewModel: ExpensesViewModel,
    onBackClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Operating Expenses",
                subtitle = "Track spending and daily net cash",
                onBackClick = onBackClick
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.canUseExpenses) {
                ExtendedFloatingActionButton(
                    text = { Text("Add expense", fontWeight = FontWeight.Bold) },
                    icon = { Icon(Icons.Rounded.ReceiptLong, null) },
                    onClick = viewModel::openAdd,
                    elevation = FloatingActionButtonDefaults.elevation(8.dp)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                AquaHubLoadingIndicator()
            }
        } else {
            AdaptiveContentFrame(
                modifier = Modifier.fillMaxSize().padding(padding),
                maxWidth = 1440.dp
            ) {
                ExpensesContent(
                    state = state,
                    onPeriodSelected = viewModel::setPeriod,
                    onCategorySelected = viewModel::setCategory,
                    onEdit = viewModel::openEdit,
                    onDelete = viewModel::requestDelete,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (state.showEditor) {
        ExpenseEditorSheet(
            existing = state.editingExpense,
            isSaving = state.isSaving,
            onDismiss = viewModel::closeEditor,
            onSave = viewModel::save
        )
    }

    state.pendingDelete?.let { expense ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            icon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Remove this expense?") },
            text = { Text("${expense.description} will be removed from expense totals. Synced history remains safely archived.") },
            confirmButton = {
                Button(onClick = viewModel::confirmDelete) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text("Keep") } }
        )
    }
}

@Composable
private fun ExpensesContent(
    state: ExpensesUiState,
    onPeriodSelected: (ExpensePeriod) -> Unit,
    onCategorySelected: (ExpenseCategory?) -> Unit,
    onEdit: (Expense) -> Unit,
    onDelete: (Expense) -> Unit,
    modifier: Modifier = Modifier
) {
    val currency = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 480.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = AquaHubSpacing.medium,
            end = AquaHubSpacing.medium,
            top = AquaHubSpacing.medium,
            bottom = 104.dp
        ),
        verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
        horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
    ) {
        if (state.freshnessWarning != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = AquaHubShapes.cardSecondary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        state.freshnessWarning,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                Row(horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small), modifier = Modifier.fillMaxWidth()) {
                    ExpenseMetricCard(
                        label = "Spent today",
                        value = currency.format(state.todayExpenseCentavos / 100.0),
                        icon = Icons.Rounded.TrendingDown,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    ExpenseMetricCard(
                        label = "Net cash today",
                        value = currency.format(state.todayNetCentavos / 100.0),
                        icon = if (state.todayNetCentavos >= 0) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown,
                        color = if (state.todayNetCentavos >= 0) Color(0xFF16A36A) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f),
                    shape = AquaHubShapes.cardSecondary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Rounded.Savings, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text("This month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(currency.format(state.monthExpenseCentavos / 100.0), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        }
                        Text("Paid revenue − expenses", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpensePeriod.entries.forEach { period ->
                        FilterChip(selected = state.period == period, onClick = { onPeriodSelected(period) }, label = { Text(period.label) })
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = state.category == null, onClick = { onCategorySelected(null) }, label = { Text("All categories") })
                    ExpenseCategory.entries.forEach { category ->
                        FilterChip(
                            selected = state.category == category,
                            onClick = { onCategorySelected(category) },
                            label = { Text(category.displayName) },
                            leadingIcon = { Icon(category.icon(), null, Modifier.size(16.dp)) }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.expenses.size} entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(currency.format(state.selectedPeriodTotalCentavos / 100.0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (state.expenses.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AquaHubEmptyState(
                    icon = Icons.Rounded.ReceiptLong,
                    message = "No expenses in this period",
                    description = "Tap Add expense to record electricity, fuel, supplies, salaries, and other daily costs."
                )
            }
        } else {
            items(state.expenses, key = { it.id }) { expense ->
                ExpenseCard(expense, currency, { onEdit(expense) }, { onDelete(expense) })
            }
        }
    }
}

@Composable
private fun ExpenseMetricCard(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    AquaHubGlassCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Surface(shape = CircleShape, color = color.copy(alpha = .12f)) {
                Icon(icon, null, tint = color, modifier = Modifier.padding(9.dp).size(20.dp))
            }
        }
    }
}

@Composable
private fun ExpenseCard(expense: Expense, currency: NumberFormat, onEdit: () -> Unit, onDelete: () -> Unit) {
    AquaHubGlassCard(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(expense.category.icon(), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(11.dp).size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(expense.description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(expense.category.displayName)
                        expense.vendor?.let { append(" • ").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(expense.incurredAt))} • ${expense.paymentMethod.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(currency.format(expense.amountCentavos / 100.0), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Edit, "Edit", Modifier.size(18.dp)) }
                    IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.DeleteOutline, "Remove", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseEditorSheet(
    existing: Expense?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (ExpenseDraft) -> Unit
) {
    var draft by remember(existing?.id) {
        mutableStateOf(
            existing?.let {
                ExpenseDraft(
                    amount = BigDecimal.valueOf(it.amountCentavos, 2).stripTrailingZeros().toPlainString(),
                    category = it.category,
                    description = it.description,
                    vendor = it.vendor.orEmpty(),
                    paymentMethod = it.paymentMethod,
                    referenceNumber = it.referenceNumber.orEmpty(),
                    notes = it.notes.orEmpty(),
                    incurredAt = it.incurredAt
                )
            } ?: ExpenseDraft()
        )
    }
    var showDatePicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxHeight(.94f).imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(if (existing == null) "Add operating expense" else "Edit expense", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text("Keep daily costs accurate for useful net-cash reporting.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = draft.amount,
                onValueChange = { draft = draft.copy(amount = it.filter { char -> char.isDigit() || char == '.' || char == ',' }) },
                label = { Text("Amount") },
                prefix = { Text("₱") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Category", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpenseCategory.entries.forEach { category ->
                    FilterChip(
                        selected = draft.category == category,
                        onClick = { draft = draft.copy(category = category) },
                        label = { Text(category.displayName) },
                        leadingIcon = { Icon(category.icon(), null, Modifier.size(17.dp)) }
                    )
                }
            }

            OutlinedTextField(
                value = draft.description,
                onValueChange = { if (it.length <= 120) draft = draft.copy(description = it) },
                label = { Text("What was this expense for?") },
                placeholder = { Text("Example: Filter replacement") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.vendor,
                onValueChange = { if (it.length <= 100) draft = draft.copy(vendor = it) },
                label = { Text("Vendor or payee (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Surface(
                onClick = { showDatePicker = true },
                shape = AquaHubShapes.cardSecondary,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Expense date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(DateFormat.getDateInstance(DateFormat.LONG).format(Date(draft.incurredAt)), fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Text("Paid using", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpensePaymentMethod.entries.forEach { method ->
                    FilterChip(
                        selected = draft.paymentMethod == method,
                        onClick = { draft = draft.copy(paymentMethod = method) },
                        label = { Text(method.displayName) }
                    )
                }
            }
            OutlinedTextField(
                value = draft.referenceNumber,
                onValueChange = { if (it.length <= 80) draft = draft.copy(referenceNumber = it) },
                label = { Text("Receipt/reference number (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { if (it.length <= 500) draft = draft.copy(notes = it) },
                label = { Text("Notes (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { onSave(draft) },
                enabled = !isSaving && draft.amount.isNotBlank() && draft.description.trim().length >= 2,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text(if (isSaving) "Saving…" else if (existing == null) "Save expense" else "Save changes", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = draft.incurredAt)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selected ->
                        val localNoon = Calendar.getInstance().apply {
                            timeInMillis = selected
                            set(Calendar.HOUR_OF_DAY, 12)
                        }.timeInMillis
                        draft = draft.copy(incurredAt = localNoon)
                    }
                    showDatePicker = false
                }) { Text("Use date") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(datePickerState) }
    }
}

private fun ExpenseCategory.icon(): ImageVector = when (this) {
    ExpenseCategory.ELECTRICITY -> Icons.Rounded.Bolt
    ExpenseCategory.WATER -> Icons.Rounded.WaterDrop
    ExpenseCategory.FUEL -> Icons.Rounded.LocalGasStation
    ExpenseCategory.SUPPLIES -> Icons.Rounded.Inventory2
    ExpenseCategory.SALARIES -> Icons.Rounded.Payments
    ExpenseCategory.MAINTENANCE -> Icons.Rounded.Build
    ExpenseCategory.RENT -> Icons.Rounded.HomeWork
    ExpenseCategory.PERMITS -> Icons.Rounded.Badge
    ExpenseCategory.OTHER -> Icons.Rounded.MoreHoriz
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun ExpensesScreenPreview() {
    AquaHubTheme {
        Scaffold(
            topBar = {
                AquaHubTopBar(
                    title = "Operating Expenses",
                    subtitle = "AquaPure Main Station",
                    onBackClick = {}
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            ExpensesContent(
                state = ExpensesUiState(
                    expenses = emptyList(),
                    period = ExpensePeriod.THIS_MONTH,
                    selectedPeriodTotalCentavos = 124_500,
                    todayExpenseCentavos = 8_500,
                    monthExpenseCentavos = 124_500,
                    todayRevenueCentavos = 32_000,
                    canUseExpenses = true,
                    isLoading = false
                ),
                onPeriodSelected = {},
                onCategorySelected = {},
                onEdit = {},
                onDelete = {},
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }
}
