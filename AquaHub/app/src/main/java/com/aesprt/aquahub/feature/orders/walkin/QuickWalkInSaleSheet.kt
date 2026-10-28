package com.aesprt.aquahub.feature.orders.walkin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AssignmentReturn
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.Product
import com.aesprt.aquahub.domain.model.ProductType
import com.aesprt.aquahub.domain.model.OfflinePosRecoveryRecord
import com.aesprt.aquahub.core.config.ReleaseFeatureFlags
import com.aesprt.aquahub.core.config.canSubmitWalkInSale
import com.aesprt.aquahub.domain.model.unitPriceFor
import com.aesprt.aquahub.ui.theme.*
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickWalkInSaleSheet(
    viewModel: WalkInSaleViewModel,
    onDismiss: () -> Unit,
    onSaleCompleted: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToSubscription: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(viewModel) {
        viewModel.refreshDeliveryAccess()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .widthIn(max = 1480.dp)
                        .fillMaxHeight(0.92f)
                        .heightIn(min = 520.dp, max = 1040.dp),
                    shape = AquaHubShapes.dialog,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    WalkInSaleForm(viewModel, uiState, onDismiss, onSaleCompleted, onNavigateToSubscription)
                }
            }
        } else {
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
                dragHandle = { BottomSheetDefaults.DragHandle() },
                modifier = modifier.fillMaxHeight(0.92f)
            ) {
                WalkInSaleForm(viewModel, uiState, onDismiss, onSaleCompleted, onNavigateToSubscription)
            }
        }
    }
}

@Composable
private fun WalkInSaleForm(
    viewModel: WalkInSaleViewModel,
    uiState: WalkInSaleUiState,
    onDismiss: () -> Unit,
    onSaleCompleted: (Long) -> Unit,
    onNavigateToSubscription: () -> Unit
) {
    QuickWalkInSaleContent(
        uiState = uiState,
        onDismiss = onDismiss,
        onRetryLoadData = viewModel::retryLoadData,
        onSetCustomer = viewModel::setCustomer,
        onSetContainerSize = viewModel::setContainerSize,
        onIncrementProduct = viewModel::incrementProduct,
        onDecrementProduct = viewModel::decrementProduct,
        onSetEmptyBottlesReturned = viewModel::setEmptyBottlesReturned,
        onSetStationContainersIssued = viewModel::setStationContainersIssued,
        onSetDeliveryMode = viewModel::setDeliveryMode,
        onSetDeliveryRecipientName = viewModel::setDeliveryRecipientName,
        onSetDeliveryRecipientPhone = viewModel::setDeliveryRecipientPhone,
        onSetDeliveryAddress = viewModel::setDeliveryAddress,
        onSetPaymentMethod = viewModel::setPaymentMethod,
        onSetChargeToAccount = viewModel::setChargeToAccount,
        onPrepareOfflineSales = viewModel::prepareOfflineSales,
        onSetCashReceived = viewModel::setCashReceived,
        onToggleOfflineRecoveryRecords = viewModel::toggleOfflineRecoveryRecords,
        onRetryOfflineReconciliation = viewModel::retryOfflineReconciliation,
        onNavigateToSubscription = onNavigateToSubscription,
        onSubmitSale = {
            viewModel.submitWalkInSale { orderId ->
                onSaleCompleted(orderId)
                onDismiss()
            }
        }
    )
}

@Composable
fun QuickWalkInSaleContent(
    modifier: Modifier = Modifier,
    uiState: WalkInSaleUiState,
    onDismiss: () -> Unit,
    onRetryLoadData: () -> Unit = {},
    onSetCustomer: (Customer?) -> Unit,
    onSetContainerSize: (String) -> Unit,
    onIncrementProduct: (Long) -> Unit,
    onDecrementProduct: (Long) -> Unit,
    onSetEmptyBottlesReturned: (Int) -> Unit,
    onSetStationContainersIssued: (Int) -> Unit,
    onSetDeliveryMode: (String) -> Unit,
    onSetDeliveryRecipientName: (String) -> Unit,
    onSetDeliveryRecipientPhone: (String) -> Unit,
    onSetDeliveryAddress: (String) -> Unit,
    onSetPaymentMethod: (PaymentMethod) -> Unit,
    onSetChargeToAccount: (Boolean) -> Unit,
    onSubmitSale: () -> Unit,
    onNavigateToSubscription: () -> Unit = {},
    onPrepareOfflineSales: () -> Unit = {},
    onSetCashReceived: (String) -> Unit = {},
    onToggleOfflineRecoveryRecords: () -> Unit = {},
    onRetryOfflineReconciliation: (String) -> Unit = {}
) {
    var showCustomerPicker by remember { mutableStateOf(false) }

    val selectedRefillSizes = uiState.selectedItemsList
        .filter { it.productType == ProductType.REFILL }
        .map { it.sizeLabel }
        .distinct()
    val selectedContainer = uiState.containers.firstOrNull {
        it.sizeLabel.trim().equals(uiState.selectedContainerSize.trim(), ignoreCase = true)
    }
    val customerHeldBalance = uiState.selectedCustomer?.let { customer ->
        uiState.containerTransactions
            .filter { it.customerId == customer.id && it.containerSize.trim().equals(uiState.selectedContainerSize.trim(), ignoreCase = true) }
            .sumOf { transaction ->
                when (transaction.type) {
                    com.aesprt.aquahub.domain.model.ContainerTransactionType.DELIVERED -> transaction.quantity
                    com.aesprt.aquahub.domain.model.ContainerTransactionType.RETURNED,
                    com.aesprt.aquahub.domain.model.ContainerTransactionType.LOST -> -transaction.quantity
                    else -> 0
                }
            }
    } ?: 0
    val movementContextReady = uiState.selectedCustomer != null && selectedRefillSizes.size == 1
    val canRecordReturn = movementContextReady && customerHeldBalance > 0
    val maxLoanQuantity = minOf(
        uiState.totalRefillsCount,
        (selectedContainer?.available ?: 0) + uiState.emptyBottlesReturned
    )
    val canRecordLoan = movementContextReady && selectedContainer != null && maxLoanQuantity > 0
    val stationBottleHint = when {
        uiState.selectedCustomer == null ->
            "Select a saved customer. Leave both counts at zero for customer-owned bottles."
        selectedRefillSizes.isEmpty() ->
            "Add a refill product first. Customer-owned bottle refills can stay at zero."
        selectedRefillSizes.size > 1 ->
            "Use one refill size in this sale when recording station bottle movements."
        selectedContainer == null ->
            "No station bottle size is registered for this selection. Add it in Inventory or choose a listed size below."
        else ->
            "${selectedContainer.available} available here • ${customerHeldBalance} held by this customer"
    }

    val locale = LocalLocale.current.platformLocale
    val currencyFormat = remember(locale) {
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = java.util.Currency.getInstance("PHP")
            maximumFractionDigits = 2
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isTabletWorkspace = maxWidth >= 840.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // Header
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AquaHubSpacing.medium, vertical = AquaHubSpacing.small)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PointOfSale,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(AquaHubSpacing.mediumSmall))
                    Column {
                        Text(
                            text = "Walk-in Station Sale",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Direct On-Station Refill & Purchase",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(AquaHubSpacing.medium))
                        Text("Loading walk-in sale data…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
            ) {
            LazyColumn(
                contentPadding = PaddingValues(AquaHubSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                modifier = Modifier
                    .weight(if (isTabletWorkspace) 0.62f else 1f)
                    .fillMaxHeight()
            ) {
                if (uiState.offlinePosEnabled) {
                    item {
                        Surface(
                            color = if (uiState.offlinePosStatus.canCreateOfflineSale && uiState.isOnline)
                                MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            shape = AquaHubShapes.cardSecondary,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(AquaHubSpacing.medium),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        if (uiState.isOnline) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                                        contentDescription = null,
                                        tint = if (uiState.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = if (uiState.isOnline) "Offline cash sales" else "Offline mode · cash pickup only",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Text(
                                    text = when {
                                        uiState.offlinePosStatus.canCreateOfflineSale && uiState.isOnline ->
                                            "Inventory is reserved until ${uiState.offlinePosStatus.leaseExpiresAtMs?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(java.util.Date(it)) } ?: "expiry"}. Pending local cash is kept separate from confirmed revenue."
                                        uiState.isOnline -> uiState.offlinePosStatus.message ?: "Prepare a server inventory lease now to allow cash walk-in sales during a connection loss."
                                        else -> uiState.offlinePosStatus.message ?: "Offline sales require a valid server-issued authorization and inventory lease."
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                if (uiState.isOnline) {
                                    TextButton(
                                        onClick = onPrepareOfflineSales,
                                        enabled = !uiState.isSubmitting && uiState.totalItemsCount > 0
                                    ) {
                                        Text(if (uiState.offlinePosStatus.canCreateOfflineSale) "Use current reserved stock" else "Reserve selected stock")
                                    }
                                }
                                if (uiState.offlinePosStatus.pendingCashSalesCentavos > 0) {
                                    Text(
                                        "Pending cash collected: ${currencyFormat.format(uiState.offlinePosStatus.pendingCashSalesCentavos / 100.0)} · not confirmed revenue",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                if (uiState.offlinePosStatus.pendingOperationCount > 0) {
                                    TextButton(
                                        onClick = onToggleOfflineRecoveryRecords,
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            if (uiState.showOfflineRecoveryRecords) "Hide saved cash sales"
                                            else "Review ${uiState.offlinePosStatus.pendingOperationCount} saved cash sale(s)"
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (!uiState.isOnline || uiState.offlinePosStatus.pendingOperationCount > 0) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = AquaHubShapes.cardSecondary,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(AquaHubSpacing.medium),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (uiState.isOnline) "Saved offline receipts need review" else "Internet connection required",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = if (uiState.isOnline) ReleaseFeatureFlags.OFFLINE_POS_RECOVERY_MESSAGE
                                    else "Reconnect to complete this sale online. No offline sale will be recorded.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                if (uiState.offlinePosStatus.pendingCashSalesCentavos > 0) {
                                    Text(
                                        "Saved cash awaiting review: ${currencyFormat.format(uiState.offlinePosStatus.pendingCashSalesCentavos / 100.0)} · not confirmed revenue",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                if (uiState.offlinePosStatus.pendingOperationCount > 0) {
                                    TextButton(
                                        onClick = onToggleOfflineRecoveryRecords,
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            if (uiState.showOfflineRecoveryRecords) "Hide saved receipts"
                                            else "Review ${uiState.offlinePosStatus.pendingOperationCount} saved receipt(s)"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (uiState.showOfflineRecoveryRecords) {
                    item {
                        OfflineCashRecoveryCard(
                            records = uiState.offlineRecoveryRecords,
                            isOnline = uiState.isOnline,
                            offlinePosEnabled = uiState.offlinePosEnabled,
                            onRetry = onRetryOfflineReconciliation,
                            currencyFormat = currencyFormat
                        )
                    }
                }
                if (uiState.loadErrorMessage != null) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = AquaHubShapes.cardSecondary,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = uiState.loadErrorMessage.orEmpty(),
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TextButton(onClick = onRetryLoadData) { Text("Retry") }
                            }
                        }
                    }
                }
                // 1. Customer Selection Card
                item {
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Customer",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (uiState.selectedCustomer != null) Icons.Rounded.Person else Icons.Rounded.Storefront,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = uiState.selectedCustomer?.name ?: "Walk-in Guest",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val selectedCust = uiState.selectedCustomer
                                if (selectedCust != null && selectedCust.phone.isNotBlank()) {
                                    Text(
                                        text = selectedCust.phone,
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }
                            }

                            TextButton(
                                onClick = { showCustomerPicker = !showCustomerPicker },
                                shape = AquaHubShapes.chip
                            ) {
                                Text(
                                    text = if (showCustomerPicker) "Done" else if (uiState.selectedCustomer != null) "Change" else "Select Customer",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Inline Customer Picker
                        AnimatedVisibility(visible = showCustomerPicker) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = AquaHubSpacing.small)
                            ) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                                // Walk-in Guest Option
                                Surface(
                                    color = if (uiState.selectedCustomer == null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    shape = AquaHubShapes.cardSecondary,
                                    border = BorderStroke(1.dp, if (uiState.selectedCustomer == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSetCustomer(null)
                                            showCustomerPicker = false
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Storefront,
                                            contentDescription = null,
                                            tint = if (uiState.selectedCustomer == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Walk-in Guest (No record)",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Customer List
                                uiState.customers.forEach { customer ->
                                    val isSelected = uiState.selectedCustomer?.id == customer.id
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        shape = AquaHubShapes.cardSecondary,
                                        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                            .clickable {
                                                onSetCustomer(customer)
                                                showCustomerPicker = false
                                            }
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(10.dp)
                                        ) {
                                            Column {
                                                Text(
                                                    text = customer.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                                )
                                                Text(
                                                    text = customer.phone,
                                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                )
                                            }
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Fulfillment
                item {
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Fulfillment",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                "PICKUP" to "Station Pick-up",
                                "DELIVERY" to "Deliver to customer"
                            ).forEach { (mode, label) ->
                                val isSelected = uiState.deliveryMode == mode
                                val deliveryAllowed = canSelectWalkInDelivery(
                                    uiState.deliveryAccessResolved,
                                    uiState.deliveryManagementAllowed
                                )
                                val enabled = mode != "DELIVERY" || deliveryAllowed
                                Surface(
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        enabled -> MaterialTheme.colorScheme.surface
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                    },
                                    shape = AquaHubShapes.button,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = enabled) { onSetDeliveryMode(mode) }
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                                    ) {
                                        Text(
                                            text = if (mode == "DELIVERY" && !uiState.deliveryAccessResolved) {
                                                "Checking…"
                                            } else {
                                                label
                                            },
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = when {
                                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                                    enabled -> MaterialTheme.colorScheme.onSurface
                                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        )
                                        if (mode == "DELIVERY" && !enabled) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Rounded.Lock,
                                                contentDescription = if (uiState.deliveryAccessResolved) {
                                                    "Requires an eligible plan"
                                                } else {
                                                    "Checking plan access"
                                                },
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.deliveryAccessResolved && !uiState.deliveryManagementAllowed) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                shape = AquaHubShapes.cardSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = AquaHubSpacing.small)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                                    modifier = Modifier.padding(AquaHubSpacing.medium)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LocalShipping,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Delivery orders and rider assignment require a plan with delivery management.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = onNavigateToSubscription) {
                                        Text("View plans")
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(visible = uiState.deliveryMode == "DELIVERY") {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            ) {
                                OutlinedTextField(
                                    value = uiState.deliveryRecipientName,
                                    onValueChange = onSetDeliveryRecipientName,
                                    label = { Text("Recipient name") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = uiState.deliveryRecipientPhone,
                                    onValueChange = onSetDeliveryRecipientPhone,
                                    label = { Text("Recipient phone") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = uiState.deliveryAddress,
                                    onValueChange = onSetDeliveryAddress,
                                    label = { Text("Delivery address") },
                                    minLines = 2,
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = if (uiState.deliveryFee > 0.0) {
                                        "Delivery fee is ${currencyFormat.format(uiState.deliveryFee)}. The order will be ready for rider assignment after saving."
                                    } else {
                                        "Free delivery. The order will be ready for rider assignment after saving."
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }

                // 3. Products Selection Section
                item {
                    AquaHubSectionHeader(title = "Select Products")
                }

                if (uiState.products.isEmpty()) {
                    item {
                        AquaHubEmptyState(
                            icon = Icons.Rounded.Inventory2,
                            message = "No Available Products",
                            description = "Add products to your catalog to make sales"
                        )
                    }
                } else {
                    items(uiState.products, key = { it.id }) { product ->
                        val qty = uiState.selectedQuantities[product.id] ?: 0
                        val activePromotion = product.promotion?.takeIf {
                            uiState.promotionsAllowed && it.appliesAt()
                        }
                        val promotionalUnitPrice = activePromotion?.let {
                            product.unitPriceFor(it.minimumQuantity, promotionsAllowed = true)
                        }
                        ProductSaleItem(
                            product = product,
                            quantity = qty,
                            formattedPrice = currencyFormat.format(promotionalUnitPrice ?: product.price),
                            regularFormattedPrice = activePromotion?.let { currencyFormat.format(product.price) },
                            promotionText = activePromotion?.let {
                                if (it.minimumQuantity > 1) "${it.label} • Buy ${it.minimumQuantity}+"
                                else "${it.label} • Applied automatically"
                            },
                            onIncrement = { onIncrementProduct(product.id) },
                            onDecrement = { onDecrementProduct(product.id) },
                            canIncrement = qty < product.stockQuantity
                        )
                    }
                }

                // 4. Bottle Exchange (Container Return)
                item {
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.AssignmentReturn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Station Bottles Returned",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = "Only count bottles owned by the station. Customer-owned bottles stay at zero.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                                if (movementContextReady && uiState.containers.isNotEmpty()) {
                                    Text(
                                        text = "Bottle size for movement",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState())
                                    ) {
                                        uiState.containers.forEach { container ->
                                            FilterChip(
                                                selected = container.sizeLabel == uiState.selectedContainerSize,
                                                onClick = { onSetContainerSize(container.sizeLabel) },
                                                label = { Text(container.sizeLabel) }
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = stationBottleHint,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = if (movementContextReady && (selectedContainer != null || selectedRefillSizes.isEmpty())) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilledTonalIconButton(
                                    onClick = { onSetEmptyBottlesReturned(uiState.emptyBottlesReturned - 1) },
                                    enabled = uiState.emptyBottlesReturned > 0,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.Remove, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
                                }

                                Text(
                                    text = "${uiState.emptyBottlesReturned}",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                FilledTonalIconButton(
                                    onClick = { onSetEmptyBottlesReturned(uiState.emptyBottlesReturned + 1) },
                                    enabled = canRecordReturn && uiState.emptyBottlesReturned < customerHeldBalance,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = "Increase", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                item {
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text("Station Bottles Loaned", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Text("Leave at zero for customer-owned bottles. A loan moves one bottle from station stock to this customer.", style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = if (selectedContainer != null) "${selectedContainer.available} ${selectedContainer.sizeLabel} bottles available" else "Choose a listed station bottle size above",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { onSetStationContainersIssued(uiState.stationContainersIssued - 1) },
                                enabled = uiState.stationContainersIssued > 0
                            ) { Text("−") }
                            Text(uiState.stationContainersIssued.toString())
                            TextButton(
                                onClick = { onSetStationContainersIssued(uiState.stationContainersIssued + 1) },
                                enabled = canRecordLoan && uiState.stationContainersIssued < maxLoanQuantity
                            ) { Text("+") }
                        }
                    }
                }

                // 5. Settlement
                item {
                    AquaHubSectionHeader(title = "Settlement")
                }

                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SettlementChoiceCard(
                            title = "Collect now",
                            subtitle = "Record payment with this sale",
                            icon = Icons.Rounded.Payments,
                            selected = !uiState.chargeToAccount,
                            enabled = true,
                            onClick = { onSetChargeToAccount(false) },
                            modifier = Modifier.weight(1f)
                        )
                        SettlementChoiceCard(
                            title = "Client account",
                            subtitle = "Add to balance due",
                            icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                            selected = uiState.chargeToAccount,
                            enabled = uiState.selectedCustomer != null,
                            onClick = { onSetChargeToAccount(true) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (uiState.selectedCustomer == null) {
                        Text(
                            "Select a saved customer to use monthly billing or credit terms.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }

                if (!uiState.chargeToAccount) {
                    item {
                        AquaHubSectionHeader(title = "Payment Method")
                    }
                }

                if (!uiState.chargeToAccount) item {
                    if (uiState.availablePaymentMethods.isEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = AquaHubShapes.cardSecondary,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(14.dp)) {
                                Icon(Icons.Rounded.Settings, null, tint = MaterialTheme.colorScheme.error)
                                Text("No payment method is active for this order type. Open Settings → Payment Methods.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            uiState.availablePaymentMethods.forEach { method ->
                                PaymentMethodChoice(
                                    method = method,
                                    selected = uiState.paymentMethod == method,
                                    onClick = { onSetPaymentMethod(method) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                if (!uiState.isOnline && !uiState.chargeToAccount) item {
                    OutlinedTextField(
                        value = uiState.cashReceivedText,
                        onValueChange = onSetCashReceived,
                        label = { Text("Cash received (PHP)") },
                        supportingText = { Text("The exact cash amount is saved with this sale. Electronic payments require internet.") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Error Message if any
                if (uiState.errorMessage != null) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = AquaHubShapes.cardSecondary,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = uiState.errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            if (isTabletWorkspace) {
                WalkInCartPanel(
                    uiState = uiState,
                    onIncrementProduct = onIncrementProduct,
                    onDecrementProduct = onDecrementProduct,
                    onSubmitSale = onSubmitSale,
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxHeight()
                        .padding(end = AquaHubSpacing.medium, top = AquaHubSpacing.medium, bottom = AquaHubSpacing.medium)
                )
            }
            }

            // Bottom Sticky Pricing and Checkout Area
            if (!isTabletWorkspace && !uiState.isLoading) Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
                tonalElevation = 4.dp,
                border = BorderStroke(0.5.dp, GlassBorderAqua),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AquaHubSpacing.medium, vertical = AquaHubSpacing.mediumSmall)
                ) {
                    // Summary Rows
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Subtotal (${uiState.totalItemsCount} items)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = currencyFormat.format(uiState.regularSubtotalAmount),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    AnimatedVisibility(visible = uiState.promotionSavingsAmount > 0.004) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                        ) {
                            Text(
                                text = "Promotion savings",
                                style = MaterialTheme.typography.bodySmall,
                                color = SuccessGreen
                            )
                            Text(
                                text = "−${currencyFormat.format(uiState.promotionSavingsAmount)}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = SuccessGreen
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (uiState.deliveryMode == "DELIVERY") "Delivery Fee" else "Delivery Fee (Station Pick-up)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (uiState.deliveryMode == "DELIVERY") {
                                if (uiState.deliveryFee > 0.0) currencyFormat.format(uiState.deliveryFee) else "₱0.00 FREE"
                            } else {
                                "₱0.00 FREE"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(6.dp))

                    // Total Row
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Total Amount",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = currencyFormat.format(uiState.totalAmount),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                    // Full-width Complete Sale Action Button
                    AquaHubButton(
                        text = if (uiState.totalItemsCount > 0) {
                            if (uiState.chargeToAccount) {
                                "Save Account Charge • ${currencyFormat.format(uiState.totalAmount)}"
                            } else if (!uiState.isOnline && !uiState.offlinePosEnabled) {
                                "Reconnect to Complete Sale"
                            } else if (!uiState.isOnline) {
                                "Record Offline Cash • ${currencyFormat.format(uiState.totalAmount)}"
                            } else {
                                "Complete Sale • ${currencyFormat.format(uiState.totalAmount)}"
                            }
                        } else {
                            "Select items to complete sale"
                        },
                        icon = Icons.Rounded.CheckCircle,
                        enabled = !uiState.isLoading && uiState.totalItemsCount > 0 && !uiState.isSubmitting && canSubmitWalkInSale(
                            isOnline = uiState.isOnline,
                            offlinePosEnabled = uiState.offlinePosEnabled,
                            hasValidOfflineLease = uiState.offlinePosStatus.canCreateOfflineSale,
                        ),
                        isLoading = uiState.isSubmitting,
                        onClick = onSubmitSale,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineCashRecoveryCard(
    records: List<OfflinePosRecoveryRecord>,
    isOnline: Boolean,
    offlinePosEnabled: Boolean,
    onRetry: (String) -> Unit,
    currencyFormat: NumberFormat,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AquaHubShapes.cardSecondary,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(AquaHubSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
        ) {
            Text("Saved offline cash sales", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                if (offlinePosEnabled) {
                    "These local receipts and item snapshots stay unchanged until the server confirms them."
                } else {
                    ReleaseFeatureFlags.OFFLINE_POS_RECOVERY_MESSAGE
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (records.isEmpty()) {
                Text("No saved sales need recovery for this station.", style = MaterialTheme.typography.bodyMedium)
            }
            records.forEach { record ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when (record.state) {
                                "PENDING" -> "Waiting to sync"
                                "RETRY" -> "Retry scheduled"
                                "SYNCING" -> "Verifying on server"
                                "RECONCILIATION_REQUIRED" -> "Needs reconciliation"
                                else -> record.state.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (record.state == "RECONCILIATION_REQUIRED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            currencyFormat.format(record.totalCentavos / 100.0),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Text(
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(record.transactionAtMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    record.lines.forEach { line ->
                        Text(
                            "${line.quantity} × ${line.productName} (${line.sizeLabel}) · ${currencyFormat.format(line.unitPriceCentavos / 100.0)} each · ${currencyFormat.format(line.lineTotalCentavos / 100.0)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Cash received ${currencyFormat.format(record.cashReceivedCentavos / 100.0)} · change ${currencyFormat.format(record.changeCentavos / 100.0)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Receipt ID ${record.operationId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    record.lastError?.let { reason ->
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (offlinePosEnabled && record.state == "RECONCILIATION_REQUIRED" && record.lastErrorCode == "RETRY_LIMIT_REACHED") {
                        TextButton(
                            onClick = { onRetry(record.operationId) },
                            enabled = isOnline,
                        ) {
                            Text(if (isOnline) "Retry server verification" else "Reconnect to retry verification")
                        }
                    } else if (record.state == "RECONCILIATION_REQUIRED") {
                        Text(
                            "This receipt and its original prices are preserved. The server has not confirmed this sale; review the conflict before taking further action.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (records.size >= 20) {
                Text(
                    "Showing the 20 most recent saved sales. Older records remain stored on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WalkInCartPanel(
    uiState: WalkInSaleUiState,
    onIncrementProduct: (Long) -> Unit,
    onDecrementProduct: (Long) -> Unit,
    onSubmitSale: () -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = LocalLocale.current
    val currencyFormat = remember(locale) {
        NumberFormat.getCurrencyInstance(locale.platformLocale).apply {
            currency = java.util.Currency.getInstance("PHP")
            maximumFractionDigits = 2
        }
    }
    Surface(
        modifier = modifier,
        shape = AquaHubShapes.cardPrimary,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(AquaHubSpacing.medium)) {
            Text("Current order", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                text = uiState.selectedCustomer?.name ?: "Walk-in Guest",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(AquaHubSpacing.small))
            HorizontalDivider()
            if (uiState.selectedItemsList.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.ShoppingCart, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(AquaHubSpacing.small))
                        Text("Choose products to build this order", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = AquaHubSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)
                ) {
                    items(uiState.selectedItemsList, key = { it.productId ?: it.productName }) { item ->
                        val productId = checkNotNull(item.productId)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.productName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${item.sizeLabel} · ${currencyFormat.format(item.unitPrice)} each",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalIconButton(
                                onClick = { onDecrementProduct(productId) },
                                modifier = Modifier.size(36.dp)
                            ) { Icon(Icons.Rounded.Remove, contentDescription = "Remove one ${item.productName}") }
                            Text(item.quantity.toString(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            FilledTonalIconButton(
                                onClick = { onIncrementProduct(productId) },
                                enabled = item.quantity < (uiState.products.firstOrNull { it.id == productId }?.stockQuantity ?: item.quantity),
                                modifier = Modifier.size(36.dp)
                            ) { Icon(Icons.Rounded.Add, contentDescription = "Add one ${item.productName}") }
                            Text(
                                currencyFormat.format(item.subtotal),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            Spacer(Modifier.height(AquaHubSpacing.small))
            CartAmountRow("Subtotal", currencyFormat.format(uiState.regularSubtotalAmount))
            AnimatedVisibility(visible = uiState.promotionSavingsAmount > 0.004) {
                CartAmountRow("Promotion savings", "−${currencyFormat.format(uiState.promotionSavingsAmount)}", MaterialTheme.colorScheme.tertiary)
            }
            CartAmountRow(
                "Delivery fee",
                if (uiState.deliveryMode == "DELIVERY") currencyFormat.format(uiState.deliveryFee) else "₱0.00"
            )
            Spacer(Modifier.height(AquaHubSpacing.small))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(currencyFormat.format(uiState.totalAmount), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(AquaHubSpacing.medium))
            AquaHubButton(
                text = if (uiState.chargeToAccount) "Save account charge"
                else if (!uiState.isOnline && !uiState.offlinePosEnabled) "Reconnect to complete sale"
                else if (!uiState.isOnline) "Record offline cash"
                else "Complete sale",
                icon = Icons.Rounded.CheckCircle,
                enabled = uiState.totalItemsCount > 0 && !uiState.isSubmitting && canSubmitWalkInSale(
                    isOnline = uiState.isOnline,
                    offlinePosEnabled = uiState.offlinePosEnabled,
                    hasValidOfflineLease = uiState.offlinePosStatus.canCreateOfflineSale,
                ),
                isLoading = uiState.isSubmitting,
                onClick = onSubmitSale,
                modifier = Modifier.fillMaxWidth()
            )
            uiState.errorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(top = AquaHubSpacing.small),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CartAmountRow(label: String, amount: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
        Text(amount, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun ProductSaleItem(
    product: Product,
    quantity: Int,
    formattedPrice: String,
    regularFormattedPrice: String?,
    promotionText: String?,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    canIncrement: Boolean,
    modifier: Modifier = Modifier
) {
    AquaHubGlassCard(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (quantity > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            AquaHubShapes.cardSecondary
                        )
                ) {
                    Icon(
                        imageVector = if (product.type == ProductType.REFILL) Icons.Rounded.WaterDrop else Icons.Rounded.Inventory2,
                        contentDescription = null,
                        tint = if (quantity > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(AquaHubSpacing.mediumSmall))

                Column {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    if (promotionText != null && regularFormattedPrice != null) {
                        Text(
                            text = "$formattedPrice promo • was $regularFormattedPrice",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = SuccessGreen,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "$promotionText • ${product.sizeLabel}",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary)
                        )
                    } else {
                        Text(
                            text = "$formattedPrice • ${product.sizeLabel}",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                    Text(
                        text = if (canIncrement) "${product.stockQuantity - quantity} available" else "Stock limit reached",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (canIncrement) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            // Quantity Selector
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (quantity > 0) {
                    FilledTonalIconButton(
                        onClick = onDecrement,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Rounded.Remove, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
                    }

                    Text(
                        text = "$quantity",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }

                FilledIconButton(
                    onClick = onIncrement,
                    enabled = canIncrement,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (quantity > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (quantity > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SettlementChoiceCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            enabled -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f)
        },
        shape = AquaHubShapes.cardSecondary,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.heightIn(min = 104.dp).clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Surface(shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                    Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp).padding(8.dp))
                }
                Icon(
                    if (selected) Icons.Rounded.CheckCircle else if (enabled) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.Lock,
                    null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaymentMethodChoice(
    method: PaymentMethod,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when (method) {
        PaymentMethod.CASH -> Icons.Rounded.Payments
        PaymentMethod.CASH_ON_DELIVERY -> Icons.Rounded.LocalShipping
        PaymentMethod.GCASH, PaymentMethod.MAYA -> Icons.Rounded.PhoneAndroid
        else -> Icons.Rounded.AccountBalanceWallet
    }
    val label = when (method) {
        PaymentMethod.CASH_ON_DELIVERY -> "COD"
        else -> method.displayName()
    }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = AquaHubShapes.cardSecondary,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 76.dp).clickable(onClick = onClick)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(10.dp)) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun QuickWalkInSaleSheetPreview() {
    AquaHubTheme {
        QuickWalkInSaleContent(
            uiState = WalkInSaleUiState(
                products = listOf(
                    Product(
                        id = 1L,
                        stationId = 1L,
                        name = "5-Gallon Round Water Refill",
                        price = 35.0,
                        type = ProductType.REFILL,
                        sizeLabel = "5-Gallon Slim/Round",
                        isAvailable = true,
                        stockQuantity = 100,
                        imagePath = null
                    ),
                    Product(
                        id = 2L,
                        stationId = 1L,
                        name = "New 5-Gallon Slim Container + Cap",
                        price = 220.0,
                        type = ProductType.NEW_CONTAINER,
                        sizeLabel = "5-Gallon Slim",
                        isAvailable = true,
                        stockQuantity = 45,
                        imagePath = null
                    )
                ),
                selectedQuantities = mapOf(1L to 3, 2L to 1)
            ),
            onDismiss = {},
            onSetCustomer = {},
            onSetContainerSize = {},
            onIncrementProduct = {},
            onDecrementProduct = {},
            onSetEmptyBottlesReturned = {},
            onSetStationContainersIssued = {},
            onSetDeliveryMode = {},
            onSetDeliveryRecipientName = {},
            onSetDeliveryRecipientPhone = {},
            onSetDeliveryAddress = {},
            onSetPaymentMethod = {},
            onSetChargeToAccount = {},
            onSubmitSale = {}
        )
    }
}
