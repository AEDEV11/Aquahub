package com.aesprt.aquahub.feature.orders.detail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderItem
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.Payment
import com.aesprt.aquahub.domain.model.PaymentRefund
import com.aesprt.aquahub.domain.model.PaymentStatus
import com.aesprt.aquahub.domain.model.RefundStatus
import com.aesprt.aquahub.ui.theme.*
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.UUID
import coil.compose.AsyncImage

@Composable
fun OrderDetailScreen(
    orderId: Long,
    viewModel: OrderDetailViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingPaymentProofUri by remember(orderId) { mutableStateOf<Uri?>(null) }
    val paymentProofCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = pendingPaymentProofUri
        if (captured && uri != null) viewModel.setDeliveryPaymentProofUri(uri.toString())
        else viewModel.paymentProofCaptureFailed()
    }

    LaunchedEffect(orderId) {
        viewModel.setDeliveryPaymentProofUri(null)
        viewModel.loadOrder(orderId)
    }

    val capturePaymentProof: () -> Unit = {
        runCatching {
            val proofFile = File(context.cacheDir, "delivery-payment-proofs/$orderId-${UUID.randomUUID()}.jpg")
            requireNotNull(proofFile.parentFile).mkdirs()
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", proofFile)
            pendingPaymentProofUri = uri
            paymentProofCapture.launch(uri)
        }.onFailure { viewModel.paymentProofCaptureFailed() }
    }

    OrderDetailContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onAcceptOrder = viewModel::acceptOrder,
        onRejectOrder = viewModel::rejectOrder,
        onCancelOrder = viewModel::cancelOrder,
        onStartPreparing = viewModel::startPreparing,
        onMarkReadyForPickup = viewModel::markReadyForPickup,
        onOpenAssignRiderDialog = viewModel::openAssignRiderDialog,
        onCloseAssignRiderDialog = viewModel::closeAssignRiderDialog,
        onAssignRider = viewModel::assignRider,
        onMarkOutForDelivery = viewModel::markOutForDelivery,
        onMarkDelivered = viewModel::markDelivered,
        onCompleteOrder = viewModel::completeOrder,
        onOpenSettlement = viewModel::openSettlementDialog,
        onCloseSettlement = viewModel::closeSettlementDialog,
        onSettleAsPaid = viewModel::settleAsPaid,
        onSettleToAccount = viewModel::settleToAccount,
        onOpenContainerLoanDialog = viewModel::openContainerLoanDialog,
        onCloseContainerLoanDialog = viewModel::closeContainerLoanDialog,
        onRecordContainerLoan = viewModel::recordContainerLoan,
        onCapturePaymentProof = capturePaymentProof,
        onRetryPaymentHistory = viewModel::retryPaymentHistory,
        onRequestRefund = viewModel::requestRefund,
        onCompleteRefund = viewModel::completeRefund,
        onFailRefund = viewModel::failRefund
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailContent(
    uiState: OrderDetailUiState,
    onBackClick: () -> Unit,
    onAcceptOrder: () -> Unit,
    onRejectOrder: () -> Unit,
    onCancelOrder: (String) -> Unit,
    onStartPreparing: () -> Unit,
    onMarkReadyForPickup: () -> Unit,
    onOpenAssignRiderDialog: () -> Unit,
    onCloseAssignRiderDialog: () -> Unit,
    onAssignRider: (Long, String) -> Unit,
    onMarkOutForDelivery: () -> Unit,
    onMarkDelivered: () -> Unit,
    onCompleteOrder: () -> Unit,
    onOpenSettlement: () -> Unit,
    onCloseSettlement: () -> Unit,
    onSettleAsPaid: (PaymentMethod, String?) -> Unit,
    onSettleToAccount: () -> Unit,
    onOpenContainerLoanDialog: () -> Unit,
    onCloseContainerLoanDialog: () -> Unit,
    onRecordContainerLoan: (String, Int) -> Unit,
    onCapturePaymentProof: () -> Unit = {},
    onRetryPaymentHistory: () -> Unit = {},
    onRequestRefund: (Long, Long, String) -> Unit = { _, _, _ -> },
    onCompleteRefund: (String, String?) -> Unit = { _, _ -> },
    onFailRefund: (String, String) -> Unit = { _, _ -> }
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))
    val dateFormat = SimpleDateFormat("MMM dd, yyyy • h:mm a", LocalLocale.current.platformLocale)
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    if (uiState.showSettlementDialog) {
        PaymentSettlementDialog(
            methods = uiState.availablePaymentMethods,
            isSaving = uiState.isSettling,
            allowAccountCharge = (uiState.order?.customerId ?: 0) > 0,
            requiresDeliveryProof = uiState.isRider && uiState.order?.deliveryMode.equals("DELIVERY", ignoreCase = true),
            paymentProofCaptured = !uiState.deliveryPaymentProofUri.isNullOrBlank(),
            paymentProofUri = uiState.deliveryPaymentProofUri,
            errorMessage = uiState.settlementErrorMessage,
            onCaptureProof = onCapturePaymentProof,
            amount = uiState.order?.total ?: 0.0,
            onDismiss = onCloseSettlement,
            onPaid = onSettleAsPaid,
            onChargeAccount = onSettleToAccount
        )
    }

    var showRejectConfirmDialog by remember { mutableStateOf(false) }
    var showCancelReasonDialog by remember { mutableStateOf(false) }
    var cancelReason by remember(uiState.order?.id) { mutableStateOf("") }
    var loanSize by remember(uiState.order?.id) { mutableStateOf("") }
    var loanQuantityText by remember(uiState.order?.id) { mutableStateOf("1") }
    val refillSizes = uiState.order?.items
        ?.filter { it.productType == com.aesprt.aquahub.domain.model.ProductType.REFILL && it.sizeLabel.isNotBlank() }
        ?.map { it.sizeLabel }
        ?.distinct()
        .orEmpty()
    val configuredRefillSizes = refillSizes.filter { orderSize ->
        uiState.stationContainers.any { container ->
            container.sizeLabel.trim().equals(orderSize.trim(), ignoreCase = true)
        }
    }
    val cancellableStatuses = setOf(
        OrderStatus.PENDING,
        OrderStatus.ACCEPTED,
        OrderStatus.PREPARING,
        OrderStatus.READY_FOR_PICKUP,
        OrderStatus.RIDER_ASSIGNED,
        OrderStatus.OUT_FOR_DELIVERY
    )
    LaunchedEffect(uiState.showContainerLoanDialog, configuredRefillSizes) {
        if (uiState.showContainerLoanDialog) {
            loanSize = configuredRefillSizes.firstOrNull().orEmpty()
            loanQuantityText = "1"
        }
    }

    if (showRejectConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRejectConfirmDialog = false },
            title = {
                Text(
                    "Reject Order?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    "Are you sure you want to reject this order? This action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                AquaHubButton(
                    text = "Yes, Reject",
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = {
                        showRejectConfirmDialog = false
                        onRejectOrder()
                    }
                )
            },
            dismissButton = {
                AquaHubOutlinedButton(
                    text = "Cancel",
                    onClick = { showRejectConfirmDialog = false }
                )
            }
        )
    }

    if (showCancelReasonDialog) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isCancelling) showCancelReasonDialog = false },
            title = { Text("Cancel order?") },
            text = {
                Column {
                    Text("This order is still in progress. Add a reason; the customer will be notified for online orders.")
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { cancelReason = it.take(300) },
                        enabled = !uiState.isCancelling,
                        label = { Text("Reason for cancellation") },
                        placeholder = { Text("For example: customer requested cancellation") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                AquaHubButton(
                    text = if (uiState.isCancelling) "Cancelling…" else "Cancel order",
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    enabled = !uiState.isCancelling && cancelReason.isNotBlank(),
                    onClick = {
                        showCancelReasonDialog = false
                        onCancelOrder(cancelReason.trim())
                        cancelReason = ""
                    }
                )
            },
            dismissButton = {
                AquaHubOutlinedButton(
                    text = "Keep order",
                    enabled = !uiState.isCancelling,
                    onClick = { showCancelReasonDialog = false }
                )
            }
        )
    }

    Scaffold(
        topBar = {
            val order = uiState.order
            AquaHubTopBar(
                title = order?.orderNumber ?: "Order Details",
                subtitle = if (order != null) "Customer: ${order.customerName}" else "Fulfillment details",
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        if (uiState.isLoading) {
            AquaHubLoadingOverlay(modifier = Modifier.padding(paddingValues))
        } else {
            val order = uiState.order
            if (order == null) {
                AquaHubEmptyState(
                    message = "Order not found",
                    description = uiState.errorMessage,
                    modifier = Modifier.padding(paddingValues)
                )
            } else {
                AdaptiveContentFrame(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    maxWidth = 1320.dp
                ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            start = AquaHubSpacing.medium,
                            end = AquaHubSpacing.medium,
                            top = AquaHubSpacing.medium,
                            bottom = 120.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 1. Order ID & Placed time
                        item {
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = order.orderNumber,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val isPickup = order.deliveryMode.equals("PICKUP", ignoreCase = true)
                                        Surface(
                                            color = if (isPickup) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = if (isPickup) "PICKUP" else "DELIVERY",
                                                maxLines = 1,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isPickup) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                                                )
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    AquaHubStatusChip(
                                        status = order.orderStatus,
                                        modifier = Modifier.wrapContentWidth()
                                    )
                                }
                                Spacer(modifier = Modifier.height(AquaHubSpacing.extraSmall))
                                Text(
                                    text = "Placed on ${dateFormat.format(Date(order.createdAt))}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }

                        // 2. Customer Info Card
                        item {
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Customer Information",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column {
                                        Text(
                                            text = order.customerName,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        Text(
                                            text = order.customerPhone,
                                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { /* Call action */ },
                                        shape = AquaHubShapes.chip,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Phone,
                                            contentDescription = "Call",
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Call", style = MaterialTheme.typography.labelMedium)
                                    }
                                }

                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                                val isPickupOrder = order.deliveryMode.equals("PICKUP", ignoreCase = true)
                                Text(
                                    text = if (isPickupOrder) "Fulfillment Type" else "Delivery Address",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = if (isPickupOrder) Icons.Rounded.Storefront else Icons.Rounded.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isPickupOrder) "Station Pickup" else order.deliveryAddress,
                                        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface)
                                    )
                                }
                                if (!isPickupOrder && order.deliveryLatitude != null && order.deliveryLongitude != null) {
                                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                                    FilledTonalButton(
                                        onClick = {
                                            uriHandler.openUri(
                                                "https://www.google.com/maps/search/?api=1&query=${order.deliveryLatitude},${order.deliveryLongitude}",
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Navigation,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Open rider delivery pin")
                                    }
                                }
                            }
                        }

                        // 3. Order Items Snapshot
                        item {
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Order Items",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                                order.items.forEach { item ->
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Column {
                                            Text(
                                                text = "${item.quantity}x ${item.productName}",
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            )
                                            Text(
                                                text = "${currencyFormat.format(item.unitPrice)} each",
                                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                            item.containerSource?.let { source ->
                                                val sourceLabel = when (source) {
                                                    "CUSTOMER_OWNED" -> "Customer’s own container"
                                                    "STATION_EXCHANGE" -> "Station container exchange"
                                                    "STATION_BORROW" -> "Borrowed station container"
                                                    else -> source.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
                                                }
                                                Text(
                                                    text = sourceLabel,
                                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary)
                                                )
                                            }
                                        }
                                        Text(
                                            text = currencyFormat.format(item.subtotal),
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                                // Pricing Summary
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Subtotal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(currencyFormat.format(order.subtotal), color = MaterialTheme.colorScheme.onSurface)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                if (order.discount > 0.004) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Promotion savings", color = SuccessGreen)
                                        Text(
                                            "−${currencyFormat.format(order.discount)}",
                                            color = SuccessGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Delivery Fee", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(currencyFormat.format(order.deliveryFee), color = MaterialTheme.colorScheme.onSurface)
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "Total Amount",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        currencyFormat.format(order.total),
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            }

                        }

                        val refillItems = order.items.filter {
                            it.productType == com.aesprt.aquahub.domain.model.ProductType.REFILL && it.sizeLabel.isNotBlank()
                        }
                        if (order.customerId > 0 && refillItems.isNotEmpty() &&
                            order.orderStatus !in setOf(OrderStatus.CANCELLED, OrderStatus.REJECTED)) {
                            item {
                                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.Top) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.size(40.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Rounded.SwapHoriz,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                "Station bottle handoff",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                "Record a loan only when the station provides its own bottle. Customer-owned bottles stay out of this count.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                                    when {
                                        uiState.existingContainerLoanQuantity > 0 -> {
                                            Text(
                                                "A station bottle loan is already recorded for this order.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        configuredRefillSizes.isEmpty() -> {
                                            Text(
                                                "No station-bottle inventory is configured for this order's refill size.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = onOpenContainerLoanDialog,
                                        enabled = uiState.existingContainerLoanQuantity == 0 && configuredRefillSizes.isNotEmpty(),
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = AquaHubShapes.button
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Record station bottle loan")
                                    }
                                }
                            }
                        }

                        // 4. Operational Progress Timeline
                        item {
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Order Progress",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                                Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

                                val isPickup = order.deliveryMode.equals("PICKUP", ignoreCase = true)
                                TimelineStep(
                                    title = "Order Accepted",
                                    time = order.acceptedAt?.let { dateFormat.format(Date(it)) },
                                    isCompleted = order.orderStatus.ordinal >= OrderStatus.ACCEPTED.ordinal,
                                    isCurrent = order.orderStatus == OrderStatus.ACCEPTED,
                                    isLast = false
                                )
                                TimelineStep(
                                    title = "Preparing Water Refill",
                                    time = null,
                                    isCompleted = order.orderStatus.ordinal >= OrderStatus.PREPARING.ordinal,
                                    isCurrent = order.orderStatus == OrderStatus.PREPARING,
                                    isLast = false
                                )
                                if (isPickup) {
                                    TimelineStep(
                                        title = "Ready for Pickup",
                                        time = order.readyAt?.let { dateFormat.format(Date(it)) },
                                        isCompleted = order.orderStatus.ordinal >= OrderStatus.READY_FOR_PICKUP.ordinal,
                                        isCurrent = order.orderStatus == OrderStatus.READY_FOR_PICKUP,
                                        isLast = false
                                    )
                                    TimelineStep(
                                        title = "Order Picked Up & Completed",
                                        time = order.completedAt?.let { dateFormat.format(Date(it)) },
                                        isCompleted = order.orderStatus == OrderStatus.COMPLETED,
                                        isCurrent = order.orderStatus == OrderStatus.COMPLETED,
                                        isLast = true
                                    )
                                } else {
                                    TimelineStep(
                                        title = "Ready for Delivery",
                                        time = order.readyAt?.let { dateFormat.format(Date(it)) },
                                        isCompleted = order.orderStatus.ordinal >= OrderStatus.READY_FOR_PICKUP.ordinal,
                                        isCurrent = order.orderStatus == OrderStatus.READY_FOR_PICKUP,
                                        isLast = false
                                    )
                                    TimelineStep(
                                        title = "Rider Assigned: ${order.riderName ?: "Pending"}",
                                        time = order.assignedAt?.let { dateFormat.format(Date(it)) },
                                        isCompleted = order.orderStatus.ordinal >= OrderStatus.RIDER_ASSIGNED.ordinal,
                                        isCurrent = order.orderStatus == OrderStatus.RIDER_ASSIGNED,
                                        isLast = false
                                    )
                                    TimelineStep(
                                        title = "Out for Delivery",
                                        time = order.outForDeliveryAt?.let { dateFormat.format(Date(it)) },
                                        isCompleted = order.orderStatus.ordinal >= OrderStatus.OUT_FOR_DELIVERY.ordinal,
                                        isCurrent = order.orderStatus == OrderStatus.OUT_FOR_DELIVERY,
                                        isLast = false
                                    )
                                    TimelineStep(
                                        title = "Delivered",
                                        time = order.deliveredAt?.let { dateFormat.format(Date(it)) },
                                        isCompleted = order.orderStatus.ordinal >= OrderStatus.DELIVERED.ordinal,
                                        isCurrent = order.orderStatus == OrderStatus.DELIVERED,
                                        isLast = false
                                    )
                                    TimelineStep(
                                        title = "Completed",
                                        time = order.completedAt?.let { dateFormat.format(Date(it)) },
                                        isCompleted = order.orderStatus == OrderStatus.COMPLETED,
                                        isCurrent = order.orderStatus == OrderStatus.COMPLETED,
                                        isLast = true
                                    )
                                }
                            }
                        }
                    if (!uiState.isRider) {
                        item {
                            PaymentRefundSection(
                                payments = uiState.payments,
                                refunds = uiState.refunds,
                                canManage = uiState.canManageRefunds,
                                isLoading = uiState.isRefundHistoryLoading,
                                isPaymentsLoading = uiState.isPaymentsLoading,
                                isSaving = uiState.isRefunding,
                                error = uiState.refundHistoryError,
                                paymentError = uiState.paymentHistoryError,
                                onRetryPayments = onRetryPaymentHistory,
                                onRequestRefund = onRequestRefund,
                                onCompleteRefund = onCompleteRefund,
                                onFailRefund = onFailRefund,
                            )
                        }
                    }
                    }

                    // Sticky Bottom Action Area
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 12.dp,
                        border = BorderStroke(0.5.dp, GlassBorderAqua),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(AquaHubSpacing.medium)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                              val isPickup = order.deliveryMode.equals("PICKUP", ignoreCase = true)
                              when (order.orderStatus) {
                                OrderStatus.PENDING -> {
                                    if (uiState.isRider) {
                                        RiderWaitingMessage("Waiting for the station to prepare this delivery.")
                                    } else Column(modifier = Modifier.fillMaxWidth()) {
                                        AquaHubButton(
                                            text = "Accept Order",
                                            icon = Icons.Rounded.Check,
                                            onClick = onAcceptOrder,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                                        AquaHubOutlinedButton(
                                            text = "Reject Order",
                                            contentColor = MaterialTheme.colorScheme.error,
                                            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                            onClick = { showRejectConfirmDialog = true },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                OrderStatus.ACCEPTED -> {
                                    if (uiState.isRider) RiderWaitingMessage("The station is preparing this delivery.") else AquaHubButton(
                                        text = "Start Preparing",
                                        icon = Icons.Rounded.WaterDrop,
                                        onClick = onStartPreparing,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                OrderStatus.PREPARING -> {
                                    if (uiState.isRider) RiderWaitingMessage("The station is preparing this delivery.") else AquaHubButton(
                                        text = if (isPickup) "Mark as Ready for Pickup" else "Mark as Ready for Delivery",
                                        icon = Icons.Rounded.Inventory2,
                                        onClick = onMarkReadyForPickup,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                OrderStatus.READY_FOR_PICKUP -> {
                                    if (uiState.isRider) {
                                        RiderWaitingMessage("This delivery is ready and will be assigned to a rider.")
                                    } else if (isPickup) {
                                        AquaHubButton(
                                            text = if (order.requiresSettlementConfirmation()) "Settle & complete pickup" else "Complete pickup",
                                            icon = if (order.requiresSettlementConfirmation()) Icons.Rounded.Payments else Icons.Rounded.CheckCircle,
                                            onClick = onOpenSettlement,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        AquaHubButton(
                                            text = "Assign Delivery Rider",
                                            icon = Icons.Rounded.PersonAdd,
                                            onClick = onOpenAssignRiderDialog,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                                OrderStatus.RIDER_ASSIGNED -> {
                                    AquaHubButton(
                                        text = "Mark Out for Delivery",
                                        icon = Icons.Rounded.LocalShipping,
                                        onClick = onMarkOutForDelivery,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                OrderStatus.OUT_FOR_DELIVERY -> {
                                    AquaHubButton(
                                        text = "Mark Delivered",
                                        icon = Icons.Rounded.CheckCircle,
                                        onClick = onMarkDelivered,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                OrderStatus.DELIVERED -> {
                                    AquaHubButton(
                                        text = if (order.requiresSettlementConfirmation()) "Settle & complete order" else "Complete order",
                                        icon = if (order.requiresSettlementConfirmation()) Icons.Rounded.Payments else Icons.Rounded.CheckCircle,
                                        onClick = onOpenSettlement,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                OrderStatus.COMPLETED -> {
                                    Surface(
                                        color = SuccessGreenLight,
                                        shape = AquaHubShapes.button,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                            modifier = Modifier.padding(AquaHubSpacing.medium)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                tint = SuccessGreen
                                            )
                                            Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                                            Text(
                                                text = if (isPickup) "Pickup Order Completed" else "Order Completed",
                                                color = SuccessGreen,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                                OrderStatus.REJECTED,
                                OrderStatus.CANCELLED -> {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = AquaHubShapes.button,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(AquaHubSpacing.medium)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Cancel,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                                Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                                                Text(
                                                    text = "Order ${order.orderStatus.displayName()}",
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                                )
                                            }
                                            if (order.orderStatus == OrderStatus.CANCELLED && !order.cancellationReason.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = "Cancellation reason: ${order.cancellationReason}",
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                              }
                              if (!uiState.isRider && order.orderStatus in cancellableStatuses) {
                                  Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                                  AquaHubOutlinedButton(
                                      text = "Cancel order",
                                      contentColor = MaterialTheme.colorScheme.error,
                                      borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                      enabled = !uiState.isCancelling,
                                      onClick = {
                                          cancelReason = ""
                                          showCancelReasonDialog = true
                                      },
                                      modifier = Modifier.fillMaxWidth()
                                  )
                              }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    if (uiState.showContainerLoanDialog) {
        val maxQuantity = uiState.order?.items
            ?.filter {
                it.productType == com.aesprt.aquahub.domain.model.ProductType.REFILL &&
                    it.sizeLabel.trim().equals(loanSize.trim(), ignoreCase = true)
            }
            ?.sumOf { it.quantity } ?: 0
        val enteredQuantity = loanQuantityText.toIntOrNull() ?: 0
        val selectedContainer = uiState.stationContainers.firstOrNull {
            it.sizeLabel.trim().equals(loanSize.trim(), ignoreCase = true)
        }
        val canSaveLoan = uiState.existingContainerLoanQuantity == 0 &&
            selectedContainer != null &&
            loanSize.isNotBlank() &&
            enteredQuantity in 1..maxQuantity &&
            enteredQuantity <= selectedContainer.available

        val isSavingLoan = uiState.isRecordingContainerLoan
        Dialog(
            onDismissRequest = {
                if (!isSavingLoan) onCloseContainerLoanDialog()
            },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = !isSavingLoan,
                dismissOnClickOutside = !isSavingLoan
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.84f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = AquaHubSpacing.medium,
                                top = AquaHubSpacing.medium,
                                end = AquaHubSpacing.small,
                                bottom = AquaHubSpacing.mediumSmall
                            )
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Inventory2,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Station bottle loan",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Link a station-owned bottle to this order",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onCloseContainerLoanDialog,
                            enabled = !isSavingLoan
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close"
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    if (isSavingLoan) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Saving the loan and updating station inventory…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                horizontal = AquaHubSpacing.medium,
                                vertical = AquaHubSpacing.small
                            )
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(AquaHubSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                modifier = Modifier.padding(AquaHubSpacing.medium)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                                Text(
                                    text = "Use this when the customer receives a bottle owned by the station. Customer-owned bottles stay outside the station loan balance.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        uiState.errorMessage?.let { message ->
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.padding(AquaHubSpacing.medium)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                            Text(
                                text = "Bottle size",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (refillSizes.size > 1) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                ) {
                                    refillSizes.forEach { size ->
                                        val isSelected = loanSize == size
                                        Surface(
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                            },
                                            contentColor = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.clickable(enabled = !isSavingLoan) {
                                                loanSize = size
                                                loanQuantityText = "1"
                                            }
                                        ) {
                                            Text(
                                                text = size,
                                                style = MaterialTheme.typography.labelLarge.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                ),
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                            )
                                        }
                                    }
                                }
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Straighten,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                                        Text(
                                            text = loanSize.ifBlank { "From order" },
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = loanQuantityText,
                            onValueChange = { loanQuantityText = it.filter(Char::isDigit).take(3) },
                            readOnly = isSavingLoan,
                            label = { Text("Quantity") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Numbers, contentDescription = null)
                            },
                            supportingText = {
                                Text("Up to $maxQuantity bottle(s) for this order")
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (maxQuantity == 0) {
                            Text(
                                text = "There are no refill bottles on this order that can be loaned.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (selectedContainer == null) {
                            Text(
                                text = "No station-bottle inventory is configured for this refill size.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (selectedContainer.available < enteredQuantity) {
                            Text(
                                text = "Only ${selectedContainer.available} bottle(s) are available at the station.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (uiState.existingContainerLoanQuantity > 0) {
                            Text(
                                text = "A station bottle loan is already recorded for this order.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AquaHubSpacing.medium)
                    ) {
                        AquaHubOutlinedButton(
                            text = "Cancel",
                            icon = Icons.Rounded.Close,
                            onClick = onCloseContainerLoanDialog,
                            enabled = !isSavingLoan,
                            modifier = Modifier.weight(0.8f)
                        )
                        AquaHubPrimaryButton(
                            text = if (isSavingLoan) "Saving loan" else "Save loan",
                            icon = Icons.Rounded.Check,
                            enabled = canSaveLoan && !isSavingLoan,
                            isLoading = isSavingLoan,
                            onClick = { onRecordContainerLoan(loanSize, enteredQuantity) },
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                }
            }
        }
    }

    // Rider Assignment Dialog (Star Icon, no emoji)
    if (uiState.showAssignRiderDialog) {
        AlertDialog(
            onDismissRequest = onCloseAssignRiderDialog,
            title = {
                Text(
                    text = "Assign Rider",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = "Select available rider (ranked by distance & workload):",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                    if (uiState.recommendedRiders.isEmpty()) {
                        Text(
                            text = "No available riders at the moment",
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    } else {
                        uiState.recommendedRiders.forEach { rec ->
                            Card(
                                shape = AquaHubShapes.cardSecondary,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable {
                                        onAssignRider(rec.rider.id, rec.rider.name)
                                    }
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(AquaHubSpacing.mediumSmall)
                                ) {
                                    Column {
                                        Text(
                                            text = rec.rider.name,
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Rounded.Star,
                                                contentDescription = "Rating",
                                                tint = WarningAmber,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "${rec.rider.rating} • ${rec.rider.activeOrderCount} active orders",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                )
                                            )
                                        }
                                    }
                                    if (rec.distanceKm != null) {
                                        Surface(
                                            shape = AquaHubShapes.chip,
                                            color = MaterialTheme.colorScheme.surface
                                        ) {
                                            Text(
                                                text = "${String.format(LocalLocale.current.platformLocale, "%.1f", rec.distanceKm)} km",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                AquaHubOutlinedButton(
                    text = "Cancel",
                    onClick = onCloseAssignRiderDialog
                )
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaymentSettlementDialog(
    methods: List<PaymentMethod>,
    isSaving: Boolean,
    allowAccountCharge: Boolean,
    requiresDeliveryProof: Boolean,
    paymentProofCaptured: Boolean,
    paymentProofUri: String?,
    errorMessage: String?,
    onCaptureProof: () -> Unit,
    amount: Double,
    onDismiss: () -> Unit,
    onPaid: (PaymentMethod, String?) -> Unit,
    onChargeAccount: () -> Unit
) {
    var paidNow by remember { mutableStateOf(true) }
    var selectedMethod by remember(methods) { mutableStateOf(methods.firstOrNull()) }
    var reference by remember { mutableStateOf("") }
    val currencyFormat = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = !isSaving,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 24.dp)
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Icon(Icons.Rounded.Payments, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Complete this order", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Record how ${currencyFormat.format(amount)} was settled.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettlementChoiceCard(
                    title = "Paid in full",
                    description = "Record the payment now and count it as collected revenue.",
                    icon = Icons.Rounded.Payments,
                    selected = paidNow,
                    enabled = true,
                    onClick = { paidNow = true }
                )
                SettlementChoiceCard(
                    title = "Add to client balance",
                    description = if (allowAccountCharge) {
                        "Complete fulfillment now and collect this amount later."
                    } else {
                        "Link a saved customer to this order to use client balance."
                    },
                    icon = Icons.Rounded.ReceiptLong,
                    selected = !paidNow,
                    enabled = allowAccountCharge,
                    onClick = { paidNow = false }
                )
                if (paidNow) {
                    if (requiresDeliveryProof) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Rider payment proof required",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "Take a photo of the customer's payment. It uploads privately and payment is confirmed only after the server accepts it.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(onClick = onCaptureProof, enabled = !isSaving) {
                                    Icon(Icons.Rounded.CameraAlt, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (paymentProofCaptured) "Retake payment photo" else "Capture payment photo")
                                }
                                if (paymentProofCaptured && !paymentProofUri.isNullOrBlank()) {
                                    AsyncImage(
                                        model = Uri.parse(paymentProofUri),
                                        contentDescription = "Captured payment proof preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 160.dp, max = 240.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                    )
                                    Text(
                                        "Photo ready. It will upload privately when you confirm payment.",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                    Text("Payment method", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        methods.forEach { method ->
                            PaymentMethodChoiceCard(
                                method = method,
                                selected = selectedMethod == method,
                                onClick = { selectedMethod = method }
                            )
                        }
                    }
                    if (selectedMethod == PaymentMethod.GCASH || selectedMethod == PaymentMethod.MAYA) {
                        OutlinedTextField(
                            value = reference,
                            onValueChange = { reference = it.take(80) },
                            label = { Text("Reference number (optional)") },
                            supportingText = { Text("Useful when reconciling e-wallet payments") },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp)) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(
                                "This amount stays unpaid and will not count as revenue until a client payment is recorded.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
                if (requiresDeliveryProof) {
                    Text(
                        "If payment was not collected, choose Finish later. The order stays unpaid and is excluded from sales.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                errorMessage?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Payment was not confirmed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(message, style = MaterialTheme.typography.bodySmall)
                                TextButton(
                                    onClick = {
                                        if (paidNow) selectedMethod?.let { onPaid(it, reference) }
                                        else onChargeAccount()
                                    },
                                    enabled = !isSaving && (!paidNow || (selectedMethod != null && (!requiresDeliveryProof || paymentProofCaptured)))
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
                Button(
                    onClick = { if (paidNow) selectedMethod?.let { onPaid(it, reference) } else onChargeAccount() },
                    enabled = !isSaving && (!paidNow || (selectedMethod != null && (!requiresDeliveryProof || paymentProofCaptured))),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else {
                        Icon(if (paidNow) Icons.Rounded.CheckCircle else Icons.Rounded.ReceiptLong, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (paidNow) "Confirm paid & complete" else "Add balance & complete")
                    }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text("Finish later")
                }
            }
        }
    }
}

@Composable
private fun PaymentMethodChoiceCard(
    method: PaymentMethod,
    selected: Boolean,
    onClick: () -> Unit
) {
    val icon = when (method) {
        PaymentMethod.GCASH, PaymentMethod.MAYA -> Icons.Rounded.AccountBalanceWallet
        else -> Icons.Rounded.Payments
    }
    val description = when (method) {
        PaymentMethod.CASH_ON_DELIVERY -> "Cash collected from the customer"
        PaymentMethod.CASH -> "Cash received at the station"
        PaymentMethod.GCASH -> "E-wallet transfer through GCash"
        PaymentMethod.MAYA -> "E-wallet transfer through Maya"
        PaymentMethod.CLIENT_ACCOUNT -> "Record this amount on the customer balance"
        PaymentMethod.CARD -> "Card payment received"
        PaymentMethod.ONLINE_PAYMENT -> "Online payment received"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp).size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(method.displayName(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
private fun SettlementChoiceCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
                Icon(
                    icon,
                    null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp).size(22.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
    }
}

@Composable
private fun RiderWaitingMessage(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AquaHubShapes.button,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(AquaHubSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Rounded.LocalShipping, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(AquaHubSpacing.small))
            Text(message, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun TimelineStep(
    title: String,
    time: String?,
    isCompleted: Boolean,
    isCurrent: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier
) {
    // Pulsing animation for the active "In Progress" step
    val infiniteTransition = rememberInfiniteTransition(label = "pulseTimeline")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    // Animated node colors and sizes
    val targetCircleColor = when {
        isCompleted -> SuccessGreen
        isCurrent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val circleColor by animateColorAsState(
        targetValue = targetCircleColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "circleColor"
    )

    val targetLineColor = if (isCompleted) SuccessGreen else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val lineColor by animateColorAsState(
        targetValue = targetLineColor,
        animationSpec = tween(500),
        label = "lineColor"
    )

    val targetTextColor = when {
        isCurrent -> MaterialTheme.colorScheme.primary
        isCompleted -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }
    val textColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(300),
        label = "textColor"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(36.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp)
            ) {
                // Pulsing halo ring when in progress
                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .scale(pulseScale)
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha),
                                shape = CircleShape
                            )
                    )
                }

                // Main circular indicator
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .background(color = circleColor, shape = CircleShape)
                ) {
                    AnimatedContent(
                        targetState = when {
                            isCompleted -> 1
                            isCurrent -> 2
                            else -> 0
                        },
                        transitionSpec = {
                            (scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn())
                                .togetherWith(scaleOut() + fadeOut())
                        },
                        label = "timelineIconAnim"
                    ) { state ->
                        when (state) {
                            1 -> {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Completed",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            2 -> {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(Color.White, CircleShape)
                                )
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape)
                                )
                            }
                        }
                    }
                }
            }

            // Connecting vertical line
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .height(30.dp)
                        .background(color = lineColor, shape = RoundedCornerShape(1.dp))
                )
            }
        }

        Spacer(modifier = Modifier.width(AquaHubSpacing.small))

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isCurrent || isCompleted) FontWeight.Bold else FontWeight.Normal,
                    color = textColor
                ),
                modifier = Modifier.weight(1f)
            )

            AnimatedContent(
                targetState = Pair(time, isCurrent),
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "timelineStatusAnim"
            ) { (timestamp, current) ->
                when {
                    timestamp != null -> {
                        Text(
                            text = timestamp,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }
                    current -> {
                        Surface(
                            shape = AquaHubShapes.chip,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "In Progress",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                    else -> Spacer(modifier = Modifier.width(0.dp))
                }
            }
        }
    }
}

@Composable
private fun PaymentRefundSection(
    payments: List<Payment>,
    refunds: List<PaymentRefund>,
    canManage: Boolean,
    isLoading: Boolean,
    isPaymentsLoading: Boolean,
    isSaving: Boolean,
    error: String?,
    paymentError: String?,
    onRetryPayments: () -> Unit,
    onRequestRefund: (Long, Long, String) -> Unit,
    onCompleteRefund: (String, String?) -> Unit,
    onFailRefund: (String, String) -> Unit
) {
    var requestFor by remember { mutableStateOf<Payment?>(null) }
    var requestAmount by remember { mutableStateOf("") }
    var requestReason by remember { mutableStateOf("") }
    var completeFor by remember { mutableStateOf<PaymentRefund?>(null) }
    var externalReference by remember { mutableStateOf("") }
    var failFor by remember { mutableStateOf<PaymentRefund?>(null) }
    var failureReason by remember { mutableStateOf("") }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var proofToShow by remember { mutableStateOf<String?>(null) }
    val currency = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) }

    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AquaHubSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)
        ) {
            Text("Payments & refunds", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (isPaymentsLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            paymentError?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetryPayments) { Text("Retry") }
                    }
                }
            }

            if (payments.isEmpty() && refunds.isEmpty() && !isPaymentsLoading && paymentError == null) {
                Text("No collected payments or refunds yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            payments.filter { it.status == PaymentStatus.PAID }.forEach { payment ->
                val reserved = refunds.asSequence()
                    .filter {
                        it.originalPaymentId == payment.id &&
                            it.status in setOf(RefundStatus.REQUESTING, RefundStatus.PENDING, RefundStatus.COMPLETED)
                    }
                    .sumOf { it.amountCentavos }
                val collected = payment.amount.toCentavosForRefundUi()
                val remaining = (collected - reserved).coerceAtLeast(0)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(payment.paymentMethod.displayName(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("Collected ${currency.format(payment.amount)} · refundable ${formatCentavos(remaining, currency)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        payment.referenceNumber?.takeIf(String::isNotBlank)?.let { reference ->
                            Text("Reference: $reference", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        payment.deliveryPaymentProofObjectPath?.let { objectPath ->
                            val reference = "supabase://payment-assets/$objectPath"
                            val proofLabel = if (objectPath.contains("/customer-payment-proofs/")) {
                                "Customer-submitted proof"
                            } else {
                                "Rider payment proof"
                            }
                            Surface(
                                onClick = { proofToShow = reference },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StorageAssetImage(
                                        reference = reference,
                                        contentDescription = "$proofLabel thumbnail",
                                        contentScale = ContentScale.Crop,
                                        privateByDefault = true,
                                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                    Column {
                                        Text(proofLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                        Text("Tap to view", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    if (canManage && remaining > 0 && !isSaving && !isLoading) {
                        TextButton(onClick = {
                            requestFor = payment
                            requestAmount = java.math.BigDecimal.valueOf(remaining, 2).toPlainString()
                            requestReason = ""
                            dialogError = null
                        }) { Text("Refund") }
                    }
                }
            }

            refunds.sortedByDescending { it.createdAt }.forEach { refund ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${refund.status.displayName()} · ${formatCentavos(refund.amountCentavos, currency)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(refund.reason, style = MaterialTheme.typography.bodySmall)
                        refund.externalReference?.let { Text("Confirmation: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        refund.failureReason?.let { Text("Failure: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                    }
                    if (canManage && refund.status == RefundStatus.PENDING && !isSaving) {
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = {
                                completeFor = refund
                                externalReference = ""
                                dialogError = null
                            }) { Text("Confirm returned") }
                            TextButton(onClick = {
                                failFor = refund
                                failureReason = ""
                                dialogError = null
                            }) { Text("Mark failed") }
                        }
                    }
                }
            }
        }
    }

    proofToShow?.let { reference ->
        Dialog(onDismissRequest = { proofToShow = null }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Payment proof", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    StorageAssetImage(
                        reference = reference,
                        contentDescription = "Payment proof",
                        contentScale = ContentScale.Fit,
                        privateByDefault = true,
                        modifier = Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(16.dp))
                    )
                    TextButton(onClick = { proofToShow = null }, modifier = Modifier.align(Alignment.End)) {
                        Text("Close")
                    }
                }
            }
        }
    }

    requestFor?.let { payment ->
        AlertDialog(
            onDismissRequest = { if (!isSaving) requestFor = null },
            title = { Text("Request refund") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                    Text("This reserves part of the confirmed collection. It does not transfer money or complete a refund.")
                    OutlinedTextField(
                        value = requestAmount,
                        onValueChange = { requestAmount = it; dialogError = null },
                        label = { Text("Amount (₱)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !isSaving
                    )
                    OutlinedTextField(
                        value = requestReason,
                        onValueChange = { requestReason = it; dialogError = null },
                        label = { Text("Reason") },
                        minLines = 2,
                        enabled = !isSaving
                    )
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = !isSaving, onClick = {
                    val amount = runCatching { parseCentavos(requestAmount) }.getOrNull()
                    val max = payment.amount.toCentavosForRefundUi() - refunds.asSequence()
                        .filter { it.originalPaymentId == payment.id && it.status in setOf(RefundStatus.REQUESTING, RefundStatus.PENDING, RefundStatus.COMPLETED) }
                        .sumOf { it.amountCentavos }
                    when {
                        amount == null || amount <= 0 -> dialogError = "Enter an amount with up to two decimal places."
                        amount > max -> dialogError = "The refund cannot exceed the remaining collected amount."
                        requestReason.isBlank() -> dialogError = "Enter a reason for this refund."
                        else -> {
                            onRequestRefund(payment.id, amount, requestReason.trim())
                            requestFor = null
                        }
                    }
                }) { Text(if (isSaving) "Saving…" else "Request") }
            },
            dismissButton = { TextButton(enabled = !isSaving, onClick = { requestFor = null }) { Text("Cancel") } }
        )
    }

    completeFor?.let { refund ->
        AlertDialog(
            onDismissRequest = { if (!isSaving) completeFor = null },
            title = { Text("Confirm refund returned") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                    Text("Only confirm after ₱${java.math.BigDecimal.valueOf(refund.amountCentavos, 2).toPlainString()} has actually been returned to the customer. This records the event; it does not send money through a payment provider.")
                    if (refund.paymentMethod !in setOf(PaymentMethod.CASH, PaymentMethod.CASH_ON_DELIVERY)) {
                        OutlinedTextField(
                            value = externalReference,
                            onValueChange = { externalReference = it; dialogError = null },
                            label = { Text("External refund confirmation reference") },
                            singleLine = true,
                            enabled = !isSaving
                        )
                    }
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = !isSaving, onClick = {
                    if (refund.paymentMethod !in setOf(PaymentMethod.CASH, PaymentMethod.CASH_ON_DELIVERY) && externalReference.isBlank()) {
                        dialogError = "Enter the external confirmation reference."
                    } else {
                        onCompleteRefund(refund.id.orEmpty(), externalReference.takeIf { it.isNotBlank() })
                        completeFor = null
                    }
                }) { Text("Confirm returned") }
            },
            dismissButton = { TextButton(enabled = !isSaving, onClick = { completeFor = null }) { Text("Cancel") } }
        )
    }

    failFor?.let { refund ->
        AlertDialog(
            onDismissRequest = { if (!isSaving) failFor = null },
            title = { Text("Mark refund failed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                    Text("This releases the reserved amount. Use only when the refund was not completed.")
                    OutlinedTextField(value = failureReason, onValueChange = { failureReason = it; dialogError = null }, label = { Text("Reason") }, minLines = 2, enabled = !isSaving)
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(enabled = !isSaving, onClick = {
                    if (failureReason.isBlank()) dialogError = "Enter why the refund failed."
                    else {
                        onFailRefund(refund.id.orEmpty(), failureReason.trim())
                        failFor = null
                    }
                }) { Text("Mark failed") }
            },
            dismissButton = { TextButton(enabled = !isSaving, onClick = { failFor = null }) { Text("Cancel") } }
        )
    }
}

private fun Double.toCentavosForRefundUi(): Long = runCatching {
    java.math.BigDecimal.valueOf(this).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
}.getOrDefault(0L)

private fun parseCentavos(amount: String): Long = java.math.BigDecimal(amount.trim())
    .setScale(2, java.math.RoundingMode.UNNECESSARY)
    .movePointRight(2)
    .longValueExact()

private fun formatCentavos(amountCentavos: Long, currency: NumberFormat): String =
    currency.format(java.math.BigDecimal.valueOf(amountCentavos, 2))

@AquaHubPhoneAndTabletPreview
@Composable
private fun OrderDetailScreenPreview() {
    AquaHubTheme {
        OrderDetailContent(
            uiState = OrderDetailUiState(
                isLoading = false,
                order = Order(
                    id = 1,
                    orderNumber = "AQH-20240518-0012",
                    customerName = "Juan Dela Cruz",
                    customerPhone = "09171234567",
                    deliveryAddress = "120 Sampaguita St., Divisoria, CDO",
                    subtotal = 160.0,
                    deliveryFee = 20.0,
                    total = 180.0,
                    orderStatus = OrderStatus.PENDING,
                    items = listOf(
                        OrderItem(id = 1, productName = "5 Gallon Refill", unitPrice = 40.0, quantity = 4, subtotal = 160.0)
                    )
                )
            ),
            onBackClick = {},
            onAcceptOrder = {},
            onRejectOrder = {},
            onCancelOrder = {},
            onStartPreparing = {},
            onMarkReadyForPickup = {},
            onOpenAssignRiderDialog = {},
            onCloseAssignRiderDialog = {},
            onAssignRider = { _, _ -> },
            onMarkOutForDelivery = {},
            onMarkDelivered = {},
            onCompleteOrder = {},
            onOpenSettlement = {},
            onCloseSettlement = {},
            onSettleAsPaid = { _, _ -> },
            onSettleToAccount = {},
            onOpenContainerLoanDialog = {},
            onCloseContainerLoanDialog = {},
            onRecordContainerLoan = { _, _ -> }
        )
    }
}
