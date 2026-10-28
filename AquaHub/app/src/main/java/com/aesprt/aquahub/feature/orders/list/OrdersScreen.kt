package com.aesprt.aquahub.feature.orders.list

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderFilter
import com.aesprt.aquahub.domain.model.OrderItem
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.feature.orders.walkin.QuickWalkInSaleSheet
import com.aesprt.aquahub.feature.orders.walkin.WalkInSaleViewModel
import com.aesprt.aquahub.ui.theme.*
import org.koin.androidx.compose.koinViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.LocalLocale
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.flow.flowOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun OrdersScreen(
    viewModel: OrdersViewModel,
    initialTab: String? = null,
    onNavigateToOrderDetail: (orderId: Long) -> Unit,
    canCreateWalkInSale: Boolean = true,
    onNavigateToSubscription: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val orders = viewModel.orders.collectAsLazyPagingItems()
    var showWalkInSheet by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh(isManual = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(initialTab) {
        if (!initialTab.isNullOrBlank()) {
            val filter = when (initialTab.uppercase()) {
                "NEW" -> OrderFilter.NEW
                "PREPARING" -> OrderFilter.PREPARING
                "ON_THE_WAY" -> OrderFilter.ON_THE_WAY
                "COMPLETED" -> OrderFilter.COMPLETED
                "CANCELLED" -> OrderFilter.CANCELLED
                else -> OrderFilter.ALL
            }
            viewModel.selectFilter(filter)
        }
    }

    OrdersContent(
        uiState = uiState,
        orders = orders,
        onFilterSelected = viewModel::selectFilter,
        onRefresh = viewModel::refresh,
        onNavigateToOrderDetail = onNavigateToOrderDetail,
        onOpenWalkInSale = { showWalkInSheet = true },
        canCreateWalkInSale = canCreateWalkInSale
    )

    if (showWalkInSheet) {
        val walkInViewModel: WalkInSaleViewModel = koinViewModel()
        QuickWalkInSaleSheet(
            viewModel = walkInViewModel,
            onDismiss = {
                walkInViewModel.resetState()
                showWalkInSheet = false
            },
            onSaleCompleted = { orderId ->
                onNavigateToOrderDetail(orderId)
            },
            onNavigateToSubscription = {
                walkInViewModel.resetState()
                showWalkInSheet = false
                onNavigateToSubscription()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersContent(
    uiState: OrdersUiState,
    orders: LazyPagingItems<Order>,
    onFilterSelected: (OrderFilter) -> Unit,
    onRefresh: () -> Unit = {},
    onNavigateToOrderDetail: (orderId: Long) -> Unit,
    onOpenWalkInSale: () -> Unit = {},
    canCreateWalkInSale: Boolean = true
) {
    var selectedOrderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var filterExpanded by rememberSaveable { mutableStateOf(false) }
    AdaptiveContentFrame(modifier = Modifier.fillMaxSize(), maxWidth = 1560.dp) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
            border = BorderStroke(0.5.dp, GlassBorderAqua),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = AquaHubSpacing.mediumLarge, end = AquaHubSpacing.mediumLarge, top = AquaHubSpacing.medium, bottom = 4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "Orders",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                letterSpacing = (-0.3).sp
                            )
                        )
                        Text(
                            text = "Track and manage station orders & deliveries",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = onRefresh,
                            enabled = !uiState.isRefreshing,
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (uiState.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = "Refresh orders",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (canCreateWalkInSale) FilledTonalButton(
                            onClick = onOpenWalkInSale,
                            shape = AquaHubShapes.chip,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PointOfSale,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Walk-in Sale",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    OrderFilterDropdown(
                        selectedFilter = uiState.selectedFilter,
                        expanded = filterExpanded,
                        onExpandedChange = { filterExpanded = it },
                        onFilterSelected = { filter ->
                            filterExpanded = false
                            onFilterSelected(filter)
                        },
                        modifier = Modifier.width(
                            if (maxWidth >= 840.dp) 360.dp else maxWidth
                        )
                    )
                }
            }
        }

        // The selected record remains in screen state as the window moves between one and two panes.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val showDetailPane = maxWidth >= 840.dp
        val visibleOrders = orders.itemSnapshotList.items
        val selectedOrder = visibleOrders.firstOrNull { it.id == selectedOrderId } ?: visibleOrders.firstOrNull()
        LaunchedEffect(showDetailPane, selectedOrder?.id, selectedOrderId) {
            if (showDetailPane && selectedOrder != null && selectedOrderId != selectedOrder.id) {
                selectedOrderId = selectedOrder.id
            }
        }

        AnimatedContent(
            targetState = uiState.isLoading || orders.loadState.refresh is LoadState.Loading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "ordersContentAnim"
        ) { loading ->
            if (loading) {
                Column(
                    modifier = Modifier.padding(AquaHubSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)
                ) {
                    repeat(4) {
                        AquaHubSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                    }
                }
            } else if (orders.loadState.refresh is LoadState.Error) {
                AquaHubEmptyState(
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    message = "Could not load orders",
                    description = "Check the local database and try again.",
                    actionText = "Retry",
                    onActionClick = orders::retry
                )
            } else if (orders.itemCount == 0) {
                AquaHubEmptyState(
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    message = if (uiState.isRider && !uiState.riderLinked) "Rider profile not linked" else "No ${uiState.selectedFilter.displayName} Orders",
                    description = if (uiState.isRider && !uiState.riderLinked) {
                        "Use the same phone number as the Delivery Fleet rider profile to see assigned deliveries."
                    } else {
                        "Orders matching this status filter will appear here"
                    }
                )
            } else {
                if (showDetailPane) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = AquaHubSpacing.medium),
                        horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
                    ) {
                        OrderPagingList(
                            orders = orders,
                            selectedOrderId = selectedOrder?.id,
                            onOrderClick = { order -> selectedOrderId = order.id },
                            modifier = Modifier.weight(0.55f).fillMaxHeight()
                        )
                        selectedOrder?.let { order ->
                            OrderWorkspaceDetail(
                                order = order,
                                onOpenDetails = { onNavigateToOrderDetail(order.id) },
                                modifier = Modifier.weight(0.45f).fillMaxHeight()
                            )
                        }
                    }
                } else {
                    OrderPagingList(
                        orders = orders,
                        selectedOrderId = null,
                        onOrderClick = { order -> onNavigateToOrderDetail(order.id) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrderFilterDropdown(
    selectedFilter: OrderFilter,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onFilterSelected: (OrderFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedFilter.displayName,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Filter orders") },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            OrderFilter.entries.forEach { filter ->
                val isSelected = selectedFilter == filter
                DropdownMenuItem(
                    text = { Text(filter.displayName) },
                    onClick = { onFilterSelected(filter) },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null
                            )
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun OrderPagingList(
    orders: LazyPagingItems<Order>,
    selectedOrderId: Long?,
    onOrderClick: (Order) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val horizontalPadding = if (maxWidth < 600.dp) {
            AquaHubSpacing.medium
        } else {
            AquaHubSpacing.mediumLarge
        }
        LazyColumn(
            contentPadding = PaddingValues(
                horizontal = horizontalPadding,
                vertical = AquaHubSpacing.medium
            ),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                count = orders.itemCount,
                key = { index -> orders.peek(index)?.id ?: "order-placeholder-$index" }
            ) { index ->
                orders[index]?.let { order ->
                    OrderListCard(
                        order = order,
                        selected = order.id == selectedOrderId,
                        onClick = { onOrderClick(order) }
                    )
                }
            }
            when (orders.loadState.append) {
                is LoadState.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }
                }
                is LoadState.Error -> item {
                    AquaHubOutlinedButton(
                        text = "Retry loading orders",
                        onClick = orders::retry,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun OrderWorkspaceDetail(
    order: Order,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))
    Surface(
        modifier = modifier,
        shape = AquaHubShapes.cardPrimary,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AquaHubSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(order.orderNumber, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(order.customerName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AquaHubStatusChip(status = order.orderStatus)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.large)) {
                Column(Modifier.weight(1f)) {
                    Text("Payment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${order.paymentStatus.name} · ${order.paymentMethod.displayName()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Fulfillment", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(order.deliveryMode, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            if (order.deliveryAddress.isNotBlank()) {
                Column {
                    Text("Delivery address", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(order.deliveryAddress, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Items", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            order.items.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${item.quantity} × ${item.productName}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(currencyFormat.format(item.subtotal), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Order total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(currencyFormat.format(order.total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.weight(1f, fill = false))
            Button(onClick = onOpenDetails, modifier = Modifier.fillMaxWidth()) {
                Text("Open order actions")
            }
        }
    }
}

@Composable
fun OrderListCard(
    order: Order,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))
    val timeFormat = SimpleDateFormat("h:mm a", LocalLocale.current.platformLocale)

    AquaHubGlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (selected) MaterialTheme.colorScheme.primary else GlassBorderAqua,
        backgroundColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        onClick = onClick
    ) {
        // Keep the long order number flexible so the fulfillment and time labels remain visible.
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = order.orderNumber,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.width(AquaHubSpacing.small))
            val isPickup = order.deliveryMode.equals("PICKUP", ignoreCase = true)
            Surface(
                color = if (isPickup) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = if (isPickup) "PICKUP" else "DELIVERY",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (isPickup) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                )
            }
            Spacer(modifier = Modifier.width(AquaHubSpacing.small))
            Text(
                text = timeFormat.format(Date(order.createdAt)),
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            )
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        // Row 2: Customer Name & Price
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = order.customerName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (order.items.isNotEmpty())
                        order.items.joinToString(", ") { "${it.quantity}x ${it.productName}" }
                    else "No items",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
            Spacer(modifier = Modifier.width(AquaHubSpacing.small))
            Text(
                text = currencyFormat.format(order.total),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        // Row 3: Payment Method Icon + Status Badge
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Payments,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = order.paymentMethod.displayName(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            AquaHubStatusChip(status = order.orderStatus)
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun OrdersScreenPreview() {
    AquaHubTheme {
        val previewOrders = flowOf(
            PagingData.from(
                listOf(
                    Order(
                        id = 1,
                        orderNumber = "AQH-20240518-0012",
                        customerName = "Juan Dela Cruz",
                        total = 180.0,
                        orderStatus = OrderStatus.PENDING,
                        items = listOf(OrderItem(quantity = 4, productName = "5 Gallon Refill"))
                    ),
                    Order(
                        id = 2,
                        orderNumber = "AQH-20240518-0011",
                        customerName = "Maria Reyes",
                        total = 95.0,
                        orderStatus = OrderStatus.PREPARING,
                        items = listOf(OrderItem(quantity = 3, productName = "3 Gallon Refill"))
                    )
                )
            )
        ).collectAsLazyPagingItems()
        OrdersContent(
            uiState = OrdersUiState(selectedFilter = OrderFilter.ALL),
            orders = previewOrders,
            onFilterSelected = {},
            onNavigateToOrderDetail = {}
        )
    }
}
