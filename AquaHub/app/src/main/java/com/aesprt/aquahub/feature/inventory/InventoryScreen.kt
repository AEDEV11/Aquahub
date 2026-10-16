package com.aesprt.aquahub.feature.inventory

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalLocale
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.ContainerTransaction
import com.aesprt.aquahub.domain.model.ContainerTransactionType
import com.aesprt.aquahub.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf

@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel,
    onBackClick: () -> Unit,
    onOpenSubscription: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val transactions = viewModel.transactions.collectAsLazyPagingItems()

    InventoryContent(
        uiState = uiState,
        transactions = transactions,
        onBackClick = onBackClick,
        onOpenSubscription = onOpenSubscription,
        onOpenAdjustDialog = viewModel::openAdjustDialog,
        onCloseAdjustDialog = viewModel::closeAdjustDialog,
        onAdjustInventory = viewModel::adjustInventory
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryContent(
    uiState: InventoryUiState,
    transactions: LazyPagingItems<ContainerTransaction>,
    onBackClick: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenAdjustDialog: () -> Unit,
    onCloseAdjustDialog: () -> Unit,
    onAdjustInventory: (String, ContainerTransactionType, Int, String?) -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd • h:mm a", LocalLocale.current.platformLocale)

    var adjustSize by remember { mutableStateOf("") }
    var adjustType by remember { mutableStateOf(ContainerTransactionType.ADJUSTMENT) }
    var adjustQtyText by remember { mutableStateOf("") }
    var adjustNotes by remember { mutableStateOf("") }

    val knownSizes = uiState.availableSizes
    LaunchedEffect(uiState.showAdjustDialog, knownSizes) {
        if (uiState.showAdjustDialog) {
            if (knownSizes.none { it.equals(adjustSize, ignoreCase = true) }) {
                adjustSize = knownSizes.firstOrNull().orEmpty()
            }
        }
    }

    fun openMovement(type: ContainerTransactionType) {
        adjustType = type
        adjustQtyText = ""
        adjustNotes = ""
        onOpenAdjustDialog()
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Inventory",
                subtitle = "Stock & container tracking",
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AnimatedContent(
            targetState = uiState.isLoading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "inventoryContentAnim"
        ) { loading ->
            if (loading) {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(AquaHubSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)
                ) {
                    repeat(4) {
                        AquaHubSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = AquaHubSpacing.medium,
                        end = AquaHubSpacing.medium,
                        top = AquaHubSpacing.medium,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    item {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                AquaHubSectionHeader(title = "Products & Saleable Stock")
                                Text(
                                    "Live sale quantities shared with customer ordering.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "${uiState.products.sumOf { it.stockQuantity }} units",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    items(uiState.products, key = { "product-${it.id}" }) { product ->
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Rounded.WaterDrop,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(product.name, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                    Text(
                                        "${product.sizeLabel} • ${product.type.displayName()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val stockColor = when {
                                    product.stockQuantity == 0 -> ErrorRed
                                    product.stockQuantity <= 5 -> WarningAmber
                                    else -> SuccessGreen
                                }
                                Surface(
                                    color = when {
                                        product.stockQuantity == 0 -> ErrorRedLight
                                        product.stockQuantity <= 5 -> WarningAmberLight
                                        else -> SuccessGreenLight
                                    },
                                    shape = AquaHubShapes.chip
                                ) {
                                    Text(
                                        text = when {
                                            product.stockQuantity == 0 -> "Out of stock"
                                            product.stockQuantity <= 5 -> "${product.stockQuantity} left"
                                            else -> "${product.stockQuantity} ready"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = stockColor,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (uiState.canTrackContainers) {
                    item {
                        AquaHubSectionHeader(title = "Station-Owned Containers")
                        Text("Add only physical containers owned by the station. On-site damaged or lost bottles leave available stock. Returned bottles must be cleaned before reuse.", style = MaterialTheme.typography.bodySmall)
                    }
                    // 1. Water Level Stock Visualization Card
                    item {
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.Inventory2, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Container Stock Level",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    Text(
                                        text = "Live station-owned bottle balance",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                            val availabilityFraction = if (uiState.totalOwned > 0)
                                uiState.available.toFloat() / uiState.totalOwned.toFloat()
                            else 0f

                            AquaHubWaterLevelProgress(
                                progress = availabilityFraction,
                                label = "Available at Station",
                                countText = "${uiState.available} / ${uiState.totalOwned} bottles",
                                barColor = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                            val inCirculationFraction = if (uiState.totalOwned > 0)
                                uiState.inCirculation.toFloat() / uiState.totalOwned.toFloat()
                            else 0f

                            AquaHubWaterLevelProgress(
                                progress = inCirculationFraction,
                                label = "In Circulation (At Customers)",
                                countText = "${uiState.inCirculation} bottles",
                                barColor = SuccessGreen
                            )
                        }
                    }

                    // 2. Summary cards gain a four-column arrangement on wide workspaces.
                    item {
                        AdaptiveCardGrid(
                            cards = listOf<AdaptiveCardItem>(
                                { cardModifier -> AquaHubMetricCard(
                                    title = "Total Bottles",
                                    value = uiState.totalOwned.toString(),
                                    icon = Icons.Rounded.Storage,
                                    iconBgColor = MaterialTheme.colorScheme.primaryContainer,
                                    iconColor = MaterialTheme.colorScheme.primary,
                                    modifier = cardModifier
                                ) },
                                { cardModifier -> AquaHubMetricCard(
                                    title = "In Circulation",
                                    value = uiState.inCirculation.toString(),
                                    subtitle = "At customer homes",
                                    icon = Icons.Rounded.LocalShipping,
                                    iconBgColor = SuccessGreenLight,
                                    iconColor = SuccessGreen,
                                    modifier = cardModifier
                                ) },
                                { cardModifier -> AquaHubMetricCard(
                                    title = "Available",
                                    value = uiState.available.toString(),
                                    subtitle = "Ready at station",
                                    icon = Icons.Rounded.Inventory2,
                                    iconBgColor = MaterialTheme.colorScheme.primaryContainer,
                                    iconColor = MaterialTheme.colorScheme.secondary,
                                    modifier = cardModifier
                                ) },
                                { cardModifier -> AquaHubMetricCard(
                                    title = "Damaged / Lost",
                                    value = uiState.damagedOrLost.toString(),
                                    subtitle = "Needs replacement",
                                    icon = Icons.Rounded.ReportProblem,
                                    iconBgColor = ErrorRedLight,
                                    iconColor = ErrorRed,
                                    modifier = cardModifier
                                ) }
                            ),
                            wideBreakpoint = 1100.dp,
                            itemSpacing = AquaHubSpacing.mediumSmall
                        )
                    }

                    item {
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Safe container cycle",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(Modifier.height(AquaHubSpacing.extraSmall))
                            Text(
                                text = "Receive returned containers → wash → sanitize → inspect → refill. Keep damaged or questionable containers out of the available count until they pass inspection.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 3. Clear stock movement shortcuts
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                            Text(
                                "Update container counts",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                "Choose what happened. Available and customer counts update automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                InventoryMovementShortcut(
                                    label = "Add bottles",
                                    icon = Icons.Rounded.Add,
                                    color = MaterialTheme.colorScheme.primary,
                                    onClick = { openMovement(ContainerTransactionType.ADJUSTMENT) },
                                    modifier = Modifier.weight(1f)
                                )
                                InventoryMovementShortcut(
                                    label = "Damaged",
                                    icon = Icons.Rounded.WarningAmber,
                                    color = WarningAmber,
                                    onClick = { openMovement(ContainerTransactionType.DAMAGED) },
                                    modifier = Modifier.weight(1f)
                                )
                                InventoryMovementShortcut(
                                    label = "Lost",
                                    icon = Icons.Rounded.ErrorOutline,
                                    color = ErrorRed,
                                    onClick = { openMovement(ContainerTransactionType.LOST) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // 4. Movement Transactions Section
                    item {
                        AquaHubSectionHeader(title = "Container Movements")
                    }

                    if (transactions.loadState.refresh is LoadState.Loading) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            }
                        }
                    } else if (transactions.loadState.refresh is LoadState.Error) {
                        item {
                            AquaHubOutlinedButton(
                                text = "Retry loading movements",
                                onClick = transactions::retry,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else if (transactions.itemCount == 0) {
                        item {
                            AquaHubEmptyState(
                                icon = Icons.Rounded.SwapHoriz,
                                message = if (uiState.totalOwned == 0) "No Inventory Configured" else "No Movements Logged",
                                description = if (uiState.totalOwned == 0) "Use Add bottles above to start tracking your station-owned bottles." else "Sales, returns, loans, and stock changes appear here"
                            )
                        }
                    } else {
                        items(
                            count = transactions.itemCount,
                            key = { index -> transactions.peek(index)?.id ?: "movement-placeholder-$index" }
                        ) { index ->
                            val txn = transactions[index] ?: return@items
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = when (txn.type) {
                                            ContainerTransactionType.RETURNED,
                                            ContainerTransactionType.ADJUSTMENT -> SuccessGreenLight
                                            ContainerTransactionType.DELIVERED -> MaterialTheme.colorScheme.primaryContainer
                                            ContainerTransactionType.DAMAGED,
                                            ContainerTransactionType.LOST -> ErrorRedLight
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = when (txn.type) {
                                                    ContainerTransactionType.RETURNED -> Icons.Rounded.KeyboardReturn
                                                    ContainerTransactionType.DELIVERED -> Icons.Rounded.LocalShipping
                                                    ContainerTransactionType.ADJUSTMENT -> Icons.Rounded.AddBox
                                                    ContainerTransactionType.DAMAGED -> Icons.Rounded.WarningAmber
                                                    ContainerTransactionType.LOST -> Icons.Rounded.ErrorOutline
                                                    else -> Icons.Rounded.SwapHoriz
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = txn.type.displayName(),
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        Text(
                                            text = "${txn.quantity}x ${txn.containerSize} • ${dateFormat.format(Date(txn.createdAt))}",
                                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                        if (!txn.customerName.isNullOrBlank()) {
                                            Text(
                                                text = "Customer: ${txn.customerName}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            )
                                        }
                                        if (!txn.notes.isNullOrBlank()) {
                                            Text(
                                                text = "Note: ${txn.notes}",
                                                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                    }

                                    Surface(
                                        color = when (txn.type) {
                                            ContainerTransactionType.RETURNED,
                                            ContainerTransactionType.ADJUSTMENT -> SuccessGreenLight
                                            ContainerTransactionType.DELIVERED -> MaterialTheme.colorScheme.primaryContainer
                                            ContainerTransactionType.DAMAGED,
                                            ContainerTransactionType.LOST -> ErrorRedLight
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        shape = AquaHubShapes.chip
                                    ) {
                                        Text(
                                            text = "${if (txn.type == ContainerTransactionType.RETURNED || txn.type == ContainerTransactionType.ADJUSTMENT) "+" else "-"}${txn.quantity}",
                                            color = when (txn.type) {
                                                ContainerTransactionType.RETURNED,
                                                ContainerTransactionType.ADJUSTMENT -> SuccessGreen
                                                ContainerTransactionType.DELIVERED -> MaterialTheme.colorScheme.primary
                                                ContainerTransactionType.DAMAGED,
                                                ContainerTransactionType.LOST -> ErrorRed
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            fontWeight = FontWeight.ExtraBold,
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                        when (transactions.loadState.append) {
                            is LoadState.Loading -> item {
                                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                }
                            }
                            is LoadState.Error -> item {
                                AquaHubOutlinedButton(
                                    text = "Retry loading movements",
                                    onClick = transactions::retry,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            else -> Unit
                        }
                    }
                    } else {
                        item {
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.Lock,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(AquaHubSpacing.small))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "Station-owned bottle tracking",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                        Text(
                                            "Your saleable product stock is available above. Track bottles loaned to customers on an eligible plan.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(AquaHubSpacing.small))
                                Button(onClick = onOpenSubscription, modifier = Modifier.fillMaxWidth()) {
                                    Text("View plans")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Adjust dialog
    if (uiState.showAdjustDialog && uiState.canTrackContainers) {
        Dialog(
            onDismissRequest = onCloseAdjustDialog,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.9f),
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
                                text = when (adjustType) {
                                    ContainerTransactionType.ADJUSTMENT -> "Add bottles to stock"
                                    ContainerTransactionType.DAMAGED -> "Report damaged bottles"
                                    ContainerTransactionType.LOST -> "Report lost bottles"
                                    else -> "Update container stock"
                                },
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = when (adjustType) {
                                    ContainerTransactionType.ADJUSTMENT -> "Record bottles received or restocked"
                                    ContainerTransactionType.DAMAGED -> "Remove bottles that cannot be used"
                                    ContainerTransactionType.LOST -> "Remove bottles that cannot be found"
                                    else -> "Record a stock movement for this station"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onCloseAdjustDialog) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close"
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(AquaHubSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                            Text(
                                text = "Movement type",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                listOf(
                                    Triple(ContainerTransactionType.ADJUSTMENT, "Add stock", Icons.Rounded.Add),
                                    Triple(ContainerTransactionType.DAMAGED, "Damaged", Icons.Rounded.WarningAmber),
                                    Triple(ContainerTransactionType.LOST, "Lost", Icons.Rounded.ErrorOutline)
                                ).forEach { (type, label, icon) ->
                                    val isSelected = adjustType == type
                                    Surface(
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                        },
                                        contentColor = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                            }
                                        ),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 76.dp)
                                            .clickable { adjustType = type }
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 4.dp, vertical = 10.dp)
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(22.dp)
                                            )
                                            Text(
                                                text = label,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                ),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                            Text(
                                text = "Container size",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            var sizeMenuExpanded by remember { mutableStateOf(false) }
                            if (knownSizes.isEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "No saved sizes yet. Add a size from Products & Pricing first.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(AquaHubSpacing.medium)
                                    )
                                }
                            } else {
                                ExposedDropdownMenuBox(
                                    expanded = sizeMenuExpanded,
                                    onExpandedChange = { sizeMenuExpanded = !sizeMenuExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = adjustSize,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("Container size") },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Straighten, contentDescription = null)
                                        },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(sizeMenuExpanded) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(16.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                        ),
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = sizeMenuExpanded,
                                        onDismissRequest = { sizeMenuExpanded = false }
                                    ) {
                                        knownSizes.forEach { size ->
                                            DropdownMenuItem(
                                                text = { Text(size) },
                                                onClick = {
                                                    adjustSize = size
                                                    sizeMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = adjustQtyText,
                            onValueChange = { adjustQtyText = it.filter(Char::isDigit).take(5) },
                            label = { Text("Quantity") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Numbers, contentDescription = null)
                            },
                            supportingText = {
                                Text("Enter the number of bottles affected")
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

                        OutlinedTextField(
                            value = adjustNotes,
                            onValueChange = { adjustNotes = it },
                            label = { Text("Reason or notes") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = null)
                            },
                            minLines = 2,
                            maxLines = 3,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
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
                            onClick = onCloseAdjustDialog,
                            modifier = Modifier.weight(0.8f)
                        )
                            AquaHubPrimaryButton(
                                text = "Save adjustment",
                                icon = Icons.Rounded.Check,
                                enabled = knownSizes.any { it.equals(adjustSize, ignoreCase = true) } &&
                                    (adjustQtyText.toIntOrNull() ?: 1) > 0,
                            onClick = {
                                val qty = adjustQtyText.toIntOrNull() ?: 1
                                onAdjustInventory(
                                    adjustSize.trim(),
                                    adjustType,
                                    qty,
                                    adjustNotes.takeIf { it.isNotBlank() }
                                )
                            },
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InventoryMovementShortcut(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = color.copy(alpha = 0.10f),
        contentColor = color,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f)),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun InventoryScreenPreview() {
    AquaHubTheme {
        val previewTransactions = flowOf(
            PagingData.from(
                listOf(
                    ContainerTransaction(
                        id = 1,
                        customerName = "Juan Dela Cruz",
                        type = ContainerTransactionType.DELIVERED,
                        quantity = 5,
                        containerSize = "5 Gallon"
                    ),
                    ContainerTransaction(
                        id = 2,
                        customerName = "Maria Reyes",
                        type = ContainerTransactionType.RETURNED,
                        quantity = 3,
                        containerSize = "5 Gallon"
                    )
                )
            )
        ).collectAsLazyPagingItems()
        InventoryContent(
            uiState = InventoryUiState(
                totalOwned = 120,
                inCirculation = 85,
                available = 35,
                damagedOrLost = 0,
                canTrackContainers = true,
                isLoading = false
            ),
            transactions = previewTransactions,
            onBackClick = {},
            onOpenSubscription = {},
            onOpenAdjustDialog = {},
            onCloseAdjustDialog = {},
            onAdjustInventory = { _, _, _, _ -> }
        )
    }
}
