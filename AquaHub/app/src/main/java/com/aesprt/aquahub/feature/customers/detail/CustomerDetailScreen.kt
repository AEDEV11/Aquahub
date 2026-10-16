package com.aesprt.aquahub.feature.customers.detail

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AssignmentReturn
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.ui.theme.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.math.BigDecimal
import java.math.RoundingMode

private enum class AccountFilter(val label: String) { ALL("All orders"), OUTSTANDING("Billing / Utang"), PAID("Paid") }

@Composable
fun CustomerDetailScreen(
    customerId: Long,
    viewModel: CustomerDetailViewModel,
    onBackClick: () -> Unit,
    onNavigateToOrderDetail: (orderId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(customerId) {
        viewModel.loadCustomer(customerId)
    }

    LaunchedEffect(uiState.statementExportResult) {
        uiState.statementExportResult?.let { result ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = result.mimeType
                putExtra(Intent.EXTRA_STREAM, result.uri)
                putExtra(Intent.EXTRA_SUBJECT, "Statement of Account - ${uiState.customer?.name.orEmpty()}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Send statement to client"))
            viewModel.consumeStatementExport()
        }
    }

    CustomerDetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onOpenReturnDialog = viewModel::openReturnDialog,
        onCloseReturnDialog = viewModel::closeReturnDialog,
        onReturnContainers = { size, qty -> viewModel.returnContainers(size, qty) },
        onNavigateToOrderDetail = onNavigateToOrderDetail,
        onOpenPayment = viewModel::openPaymentDialog,
        onClosePayment = viewModel::closePaymentDialog,
        onRecordPayment = viewModel::recordPayment,
        onExportStatement = { viewModel.exportStatement(context) }
    )
}

@Composable
fun CustomerDetailContent(
    uiState: CustomerDetailUiState,
    onBackClick: () -> Unit,
    onOpenReturnDialog: () -> Unit,
    onCloseReturnDialog: () -> Unit,
    onReturnContainers: (String, Int) -> Unit,
    onNavigateToOrderDetail: (orderId: Long) -> Unit,
    onOpenPayment: (Long) -> Unit,
    onClosePayment: () -> Unit,
    onRecordPayment: (Double, PaymentMethod, String?) -> Unit,
    onExportStatement: () -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))
    val dateFormat = SimpleDateFormat("MMM dd, yyyy • h:mm a", LocalLocale.current.platformLocale)

    var returnSize by remember { mutableStateOf("") }
    var returnQtyText by remember { mutableStateOf("1") }
    var accountFilter by remember { mutableStateOf(AccountFilter.ALL) }
    LaunchedEffect(uiState.showReturnDialog, uiState.balances) {
        if (uiState.showReturnDialog && returnSize.isBlank()) {
            returnSize = uiState.balances.firstOrNull()?.containerSize.orEmpty()
        }
    }

    Scaffold(
        topBar = {
            val customer = uiState.customer
            AquaHubTopBar(
                title = customer?.name ?: "Customer Profile",
                subtitle = customer?.phone ?: "Account details & history",
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (uiState.isLoading) {
            AquaHubLoadingOverlay(modifier = Modifier.padding(paddingValues))
        } else {
            val customer = uiState.customer
            if (customer == null) {
                AquaHubEmptyState(
                    message = "Customer not found",
                    description = uiState.errorMessage,
                    modifier = Modifier.padding(paddingValues)
                )
            } else {
                AdaptiveContentFrame(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    maxWidth = 1320.dp
                ) {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = AquaHubSpacing.medium,
                        end = AquaHubSpacing.medium,
                        top = AquaHubSpacing.medium,
                        bottom = 32.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 1. Profile Header Card
                    item {
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                ) {
                                    Text(
                                        text = customer.name.take(1).uppercase(),
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.width(AquaHubSpacing.medium))
                                Column {
                                    Text(
                                        text = customer.name,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.Phone,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = customer.phone,
                                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                }
                            }

                            val primaryAddress = customer.addresses.firstOrNull()?.addressLine
                            if (!primaryAddress.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Rounded.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = primaryAddress,
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
                                    )
                                }
                            }
                        }
                    }

                    // 2. Summary Metric Tiles
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AquaHubMetricCard(
                                title = "Total Orders",
                                value = customer.totalOrders.toString(),
                                icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                                iconBgColor = MaterialTheme.colorScheme.primaryContainer,
                                iconColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            AquaHubMetricCard(
                                title = "Total Spent",
                                value = currencyFormat.format(customer.totalSpent),
                                icon = Icons.Rounded.Payments,
                                iconBgColor = SuccessGreenLight,
                                iconColor = SuccessGreen,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    item {
                        val snapshot = uiState.accountSnapshot
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            AquaHubSectionHeader(
                                title = "Client Account",
                                actionText = if (uiState.accountFeatureAvailable) "PDF Statement" else null,
                                onActionClick = if (uiState.accountFeatureAvailable) onExportStatement else null
                            )
                            Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                            if (!uiState.accountFeatureAvailable) {
                                Text(
                                    "Monthly billing, partial-payment history, and PDF statements are available on Business and Pro.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            } else {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AquaHubMetricCard(
                                        title = "Total Billed",
                                        value = currencyFormat.format(snapshot?.totalBilled ?: 0.0),
                                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                                        iconBgColor = MaterialTheme.colorScheme.primaryContainer,
                                        iconColor = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    AquaHubMetricCard(
                                        title = "Balance Due",
                                        value = currencyFormat.format(snapshot?.outstandingBalance ?: 0.0),
                                        icon = Icons.Rounded.AccountBalanceWallet,
                                        iconBgColor = WarningAmberLight,
                                        iconColor = WarningAmber,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (uiState.isExportingStatement) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                }
                            }
                        }
                    }

                    if (uiState.successMessage != null || uiState.errorMessage != null) {
                        item {
                            Surface(
                                color = if (uiState.errorMessage != null) MaterialTheme.colorScheme.errorContainer else SuccessGreenLight,
                                shape = AquaHubShapes.cardSecondary,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    uiState.errorMessage ?: uiState.successMessage.orEmpty(),
                                    modifier = Modifier.padding(12.dp),
                                    color = if (uiState.errorMessage != null) MaterialTheme.colorScheme.onErrorContainer else SuccessGreen
                                )
                            }
                        }
                    }

                    // 3. Container Borrow Balance Section
                    item {
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            AquaHubSectionHeader(
                                title = "Borrowed Containers",
                                actionText = "Record Return",
                                onActionClick = onOpenReturnDialog
                            )

                            Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                            if (uiState.balances.isEmpty()) {
                                Text(
                                    text = "No outstanding containers with this customer",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            } else {
                                uiState.balances.forEach { bal ->
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = bal.containerSize,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                        )
                                        Surface(
                                            shape = AquaHubShapes.chip,
                                            color = if (bal.quantityWithCustomer > 0) WarningAmberLight else SuccessGreenLight
                                        ) {
                                            Text(
                                                text = "${bal.quantityWithCustomer} bottles held",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (bal.quantityWithCustomer > 0) WarningAmber else SuccessGreen
                                                ),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 4. Order History
                    item {
                        AquaHubSectionHeader(title = "Sales & Billing History")
                    }

                    if (uiState.accountFeatureAvailable) {
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            ) {
                                AccountFilter.entries.forEach { filter ->
                                    FilterChip(
                                        selected = accountFilter == filter,
                                        onClick = { accountFilter = filter },
                                        label = { Text(filter.label) }
                                    )
                                }
                            }
                        }
                    }

                    val selectedAccountFilter =
                        if (uiState.accountFeatureAvailable) accountFilter else AccountFilter.ALL
                    val accountEntries = uiState.accountSnapshot?.entries.orEmpty().filter { entry ->
                        when (selectedAccountFilter) {
                            AccountFilter.ALL -> true
                            AccountFilter.OUTSTANDING -> entry.balance > 0.005
                            AccountFilter.PAID -> entry.balance <= 0.005
                        }
                    }
                    val orders = when (selectedAccountFilter) {
                        AccountFilter.ALL -> uiState.orderHistory
                        AccountFilter.OUTSTANDING, AccountFilter.PAID -> accountEntries.map { it.order }
                    }
                    if (orders.isEmpty()) {
                        item {
                            AquaHubEmptyState(
                                icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                                message = "No orders yet",
                                description = "Past orders for this customer will be listed here"
                            )
                        }
                    } else {
                        items(orders, key = { it.id }) { order ->
                            val accountEntry = uiState.accountSnapshot?.entries?.firstOrNull { it.order.id == order.id }
                            AquaHubGlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onNavigateToOrderDetail(order.id) }
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column {
                                        Text(
                                            text = order.orderNumber,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                        Text(
                                            text = dateFormat.format(Date(order.createdAt)),
                                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = currencyFormat.format(order.total),
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        AquaHubStatusChip(status = order.orderStatus)
                                    }
                                }
                                if (uiState.accountFeatureAvailable && accountEntry != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column {
                                            Text("Paid ${currencyFormat.format(accountEntry.paidAmount)}", style = MaterialTheme.typography.labelSmall)
                                            Text(
                                                if (accountEntry.balance > 0.005) "Balance ${currencyFormat.format(accountEntry.balance)}" else "Fully paid",
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                color = if (accountEntry.balance > 0.005) WarningAmber else SuccessGreen
                                            )
                                        }
                                        if (accountEntry.balance > 0.005) {
                                            TextButton(onClick = { onOpenPayment(order.id) }) { Text("Record Payment") }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val accountSnapshot = uiState.accountSnapshot
                    if (uiState.accountFeatureAvailable && accountSnapshot?.payments?.isNotEmpty() == true) {
                        item { AquaHubSectionHeader(title = "Payment History") }
                        items(accountSnapshot.payments, key = { "payment-${it.id}" }) { payment ->
                            val orderNumber = accountSnapshot.entries.firstOrNull { it.order.id == payment.orderId }?.order?.orderNumber ?: "Order"
                            ListItem(
                                headlineContent = { Text(currencyFormat.format(payment.amount), fontWeight = FontWeight.Bold) },
                                supportingContent = { Text("$orderNumber • ${payment.paymentMethod.displayName()}${payment.referenceNumber?.let { " • $it" }.orEmpty()}") },
                                trailingContent = { Text(dateFormat.format(Date(payment.paidAt ?: payment.createdAt)), style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
                }
            }
        }
    }

    // Return Containers Dialog
    if (uiState.showReturnDialog) {
        AlertDialog(
            onDismissRequest = onCloseReturnDialog,
            title = {
                Text(
                    "Record Returned Containers",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = returnSize,
                        onValueChange = { returnSize = it },
                        label = { Text("Container Size") },
                        shape = AquaHubShapes.cardSecondary,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    OutlinedTextField(
                        value = returnQtyText,
                        onValueChange = { returnQtyText = it },
                        label = { Text("Quantity Returned") },
                        shape = AquaHubShapes.cardSecondary,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                AquaHubButton(
                    text = "Save Return",
                    onClick = {
                        val qty = returnQtyText.toIntOrNull() ?: 1
                        if (returnSize.isNotBlank()) onReturnContainers(returnSize.trim(), qty)
                    }
                )
            },
            dismissButton = {
                AquaHubOutlinedButton(
                    text = "Cancel",
                    onClick = onCloseReturnDialog
                )
            }
        )
    }

    if (uiState.paymentOrderId != null) {
        var amountText by remember(uiState.paymentOrderId) { mutableStateOf(String.format(Locale.US, "%.2f", uiState.paymentRemaining)) }
        var method by remember(uiState.paymentOrderId) { mutableStateOf(uiState.availableReceiptMethods.firstOrNull() ?: PaymentMethod.CASH) }
        var reference by remember(uiState.paymentOrderId) { mutableStateOf("") }
        RecordClientPaymentDialog(
            remainingBalance = uiState.paymentRemaining,
            amountText = amountText,
            onAmountChange = { candidate ->
                if (candidate.matches(Regex("^\\d{0,9}(\\.\\d{0,2})?$"))) amountText = candidate
            },
            availableMethods = uiState.availableReceiptMethods,
            selectedMethod = method,
            onMethodSelected = { method = it },
            reference = reference,
            onReferenceChange = { reference = it.take(80) },
            isSaving = uiState.isSavingPayment,
            onDismiss = onClosePayment,
            onSave = { amount -> onRecordPayment(amount, method, reference.ifBlank { null }) },
        )
    }
}

@Composable
private fun RecordClientPaymentDialog(
    remainingBalance: Double,
    amountText: String,
    onAmountChange: (String) -> Unit,
    availableMethods: List<PaymentMethod>,
    selectedMethod: PaymentMethod,
    onMethodSelected: (PaymentMethod) -> Unit,
    reference: String,
    onReferenceChange: (String) -> Unit,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    val currency = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) }
    val balanceCentavos = remember(remainingBalance) { remainingBalance.toCentavosOrZero() }
    val enteredCentavos = remember(amountText) {
        runCatching {
            amountText.toBigDecimalOrNull()
                ?.movePointRight(2)
                ?.setScale(0, RoundingMode.UNNECESSARY)
                ?.longValueExact()
        }.getOrNull()
    }
    val amountError = when {
        amountText.isBlank() -> null
        enteredCentavos == null || enteredCentavos <= 0L -> "Enter an amount greater than zero."
        enteredCentavos > balanceCentavos -> "Amount cannot exceed the remaining balance."
        else -> null
    }
    val canSave = enteredCentavos != null && enteredCentavos in 1L..balanceCentavos && !isSaving
    val afterPaymentCentavos = (balanceCentavos - (enteredCentavos ?: 0L)).coerceAtLeast(0L)
    val isFullPayment = canSave && afterPaymentCentavos == 0L

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding(),
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 18.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(15.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Payments,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(
                            "Record payment",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            "Apply a receipt to this client balance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "BALANCE DUE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                currency.format(remainingBalance),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                        Icon(
                            Icons.Rounded.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Amount received", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = onAmountChange,
                        modifier = Modifier.fillMaxWidth(),
                        prefix = { Text("₱") },
                        singleLine = true,
                        isError = amountError != null,
                        supportingText = {
                            when {
                                amountError != null -> Text(amountError)
                                enteredCentavos != null && enteredCentavos > 0L -> Text(
                                    if (isFullPayment) "This will fully settle the account."
                                    else "Balance after payment: ${currency.format(afterPaymentCentavos / 100.0)}",
                                    color = if (isFullPayment) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        shape = RoundedCornerShape(16.dp),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Payment method", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        availableMethods.forEach { option ->
                            val selected = option == selectedMethod
                            Surface(
                                onClick = { onMethodSelected(option) },
                                shape = RoundedCornerShape(16.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    if (selected) 2.dp else 1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (option == PaymentMethod.CASH) Icons.Rounded.Payments else Icons.Rounded.AccountBalanceWallet,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        option.displayName(),
                                        modifier = Modifier.padding(start = 8.dp),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = reference,
                    onValueChange = onReferenceChange,
                    label = { Text("Reference or note") },
                    placeholder = { Text("Optional") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ReceiptLong, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (canSave) onSave(requireNotNull(enteredCentavos) / 100.0)
                    }),
                    shape = RoundedCornerShape(16.dp),
                )

                Button(
                    onClick = { onSave(requireNotNull(enteredCentavos) / 100.0) },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    }
                    Text(
                        if (isSaving) "Recording payment…" else if (isFullPayment) "Record full payment" else "Record partial payment",
                        modifier = Modifier.padding(start = 9.dp),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private fun Double.toCentavosOrZero(): Long = runCatching {
    BigDecimal.valueOf(this).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}.getOrDefault(0L)

@AquaHubPhoneAndTabletPreview
@Composable
private fun CustomerDetailScreenPreview() {
    AquaHubTheme {
        CustomerDetailContent(
            uiState = CustomerDetailUiState(
                customer = Customer(
                    id = 1,
                    name = "Juan Dela Cruz",
                    phone = "09171234567",
                    addresses = listOf(CustomerAddress(id = 1, customerId = 1, addressLine = "120 Sampaguita St.", isDefault = true)),
                    totalOrders = 8,
                    totalSpent = 1320.0
                ),
                balances = listOf(CustomerContainerBalance(containerSize = "5 Gallon", quantityWithCustomer = 4)),
                orderHistory = listOf(
                    Order(id = 1, orderNumber = "AQH-20240518-0012", total = 180.0, orderStatus = OrderStatus.DELIVERED)
                )
            ),
            onBackClick = {},
            onOpenReturnDialog = {},
            onCloseReturnDialog = {},
            onReturnContainers = { _, _ -> },
            onNavigateToOrderDetail = {},
            onOpenPayment = {},
            onClosePayment = {},
            onRecordPayment = { _, _, _ -> },
            onExportStatement = {}
        )
    }
}
