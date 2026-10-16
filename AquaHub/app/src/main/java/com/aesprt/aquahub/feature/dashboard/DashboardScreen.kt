package com.aesprt.aquahub.feature.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.DashboardSummary
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderItem
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.ui.theme.*
import java.text.NumberFormat
import java.util.Locale
import androidx.compose.ui.platform.LocalLocale

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToOrders: (tab: String) -> Unit,
    onNavigateToOrderDetail: (orderId: Long) -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToProducts: () -> Unit,
    onNavigateToExpenses: () -> Unit,
    onNavigateToManageStations: () -> Unit = {},
    onNavigateToSubscription: () -> Unit = {},
    isStaff: Boolean = false,
    onLogout: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshRemoteOrders()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DashboardContent(
        uiState = uiState,
        onNavigateToOrders = onNavigateToOrders,
        onNavigateToOrderDetail = onNavigateToOrderDetail,
        onNavigateToInventory = onNavigateToInventory,
        onNavigateToCustomers = onNavigateToCustomers,
        onNavigateToReports = onNavigateToReports,
        onNavigateToProducts = onNavigateToProducts,
        onNavigateToExpenses = onNavigateToExpenses,
        onNavigateToManageStations = onNavigateToManageStations,
        onSwitchStation = viewModel::switchStation,
        onNavigateToSubscription = onNavigateToSubscription,
        isStaff = isStaff,
        onLogout = onLogout
    )
}

@Composable
fun DashboardContent(
    uiState: DashboardUiState,
    onNavigateToOrders: (tab: String) -> Unit,
    onNavigateToOrderDetail: (orderId: Long) -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToCustomers: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToProducts: () -> Unit,
    onNavigateToExpenses: () -> Unit,
    onNavigateToManageStations: () -> Unit = {},
    onSwitchStation: (Long) -> Unit = {},
    onNavigateToSubscription: () -> Unit = {},
    isStaff: Boolean = false,
    onLogout: () -> Unit = {}
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))
    var showStationMenu by remember { mutableStateOf(false) }
    var showLogoutConfirmation by remember { mutableStateOf(false) }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text("Sign out of AquaHub?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Signing out removes saved business data from this device when no sync work is pending. If offline changes remain, AquaHub keeps them tied to this account so they can be recovered. Your cloud account data is not deleted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmation = false
                        onLogout()
                    }
                ) {
                    Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    AdaptiveContentFrame(
        modifier = Modifier.fillMaxSize(),
        maxWidth = 1440.dp
    ) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 88.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 1. Header & Station Status Hero
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                border = BorderStroke(0.5.dp, GlassBorderAqua),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = AquaHubSpacing.mediumLarge, vertical = AquaHubSpacing.mediumLarge)
                ) {
                    Box {
                        Column(
                            modifier = Modifier.then(if (!isStaff) Modifier.clickable { showStationMenu = true } else Modifier)
                        ) {
                            Text(
                                text = "Active Station Branch",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = uiState.station?.name ?: "AquaPure Station",
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        letterSpacing = (-0.5).sp
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                if (!isStaff) Icon(
                                    imageVector = Icons.Rounded.ArrowDropDown,
                                    contentDescription = "Switch Station",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        if (!isStaff) DropdownMenu(
                            expanded = showStationMenu,
                            onDismissRequest = { showStationMenu = false }
                        ) {
                            uiState.allStations.forEach { st ->
                                val isCurrent = st.id == uiState.station?.id
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = st.name,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = st.address,
                                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isCurrent) Icons.Rounded.Check else Icons.Rounded.Storefront,
                                            contentDescription = null,
                                            tint = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    onClick = {
                                        showStationMenu = false
                                        onSwitchStation(st.id)
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Manage All Branches...", fontWeight = FontWeight.Bold) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.AddLocationAlt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                onClick = {
                                    showStationMenu = false
                                    onNavigateToManageStations()
                                }
                            )
                        }
                    }

                    // Open/Closed Status Pill
                    val isOpen = uiState.station?.isOpen ?: true
                    Surface(
                        color = if (isOpen) SuccessGreenLight else ErrorRedLight,
                        shape = AquaHubShapes.chip,
                        border = BorderStroke(1.dp, if (isOpen) SuccessGreen.copy(alpha = 0.3f) else ErrorRed.copy(alpha = 0.3f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(if (isOpen) SuccessGreen else ErrorRed, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isOpen) "OPEN" else "CLOSED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isOpen) SuccessGreen else ErrorRed,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }
                    if (isStaff) {
                        IconButton(onClick = { showLogoutConfirmation = true }) {
                            Icon(Icons.AutoMirrored.Rounded.ExitToApp, contentDescription = "Sign out")
                        }
                    }
                }
            }
        }

        // 1b. Subscription State Banner (Trial Countdown / Grace Period / Expired)
        if (!isStaff) item {
            SubscriptionBanner(
                subscriptionState = uiState.subscriptionState,
                remainingDays = uiState.remainingDays,
                onRenewClick = onNavigateToSubscription,
                onViewPlansClick = onNavigateToSubscription,
                onResumeSubscriptionClick = onNavigateToSubscription
            )
        }

        // 2. KPI cards expand to a four-column business dashboard when the content window allows.
        item {
            DashboardMetricGrid(
                modifier = Modifier.padding(AquaHubSpacing.medium),
                metrics = listOf(
                    DashboardMetric(
                        title = "Today's Orders",
                        value = uiState.summary.todayOrdersCount.toString(),
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        iconBgColor = MaterialTheme.colorScheme.primaryContainer,
                        iconColor = MaterialTheme.colorScheme.primary,
                        onClick = { onNavigateToOrders("ALL") }
                    ),
                    DashboardMetric(
                        title = "Pending",
                        value = uiState.summary.pendingOrdersCount.toString(),
                        icon = Icons.Rounded.PendingActions,
                        iconBgColor = StatusPendingBg,
                        iconColor = StatusPending,
                        onClick = { onNavigateToOrders("NEW") }
                    ),
                    DashboardMetric(
                        title = "Out for Delivery",
                        value = uiState.summary.outForDeliveryCount.toString(),
                        icon = Icons.Rounded.LocalShipping,
                        iconBgColor = StatusOutForDeliveryBg,
                        iconColor = StatusOutForDelivery,
                        onClick = { onNavigateToOrders("ON_THE_WAY") }
                    ),
                    DashboardMetric(
                        title = "Completed",
                        value = uiState.summary.completedTodayCount.toString(),
                        icon = Icons.Rounded.CheckCircle,
                        iconBgColor = StatusCompletedBg,
                        iconColor = StatusCompleted,
                        onClick = { onNavigateToOrders("COMPLETED") }
                    )
                )
            )
        }

        // 3. Today's Sales Glassmorphic Card
        if (!isStaff) item {
            AquaHubGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AquaHubSpacing.medium),
                onClick = onNavigateToReports
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text(
                            text = "Today's Collected Revenue",
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = currencyFormat.format(uiState.summary.todaySales),
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            val yesterdaySales = uiState.summary.yesterdaySales
                            val todaySales = uiState.summary.todaySales
                            val changePercent = when {
                                yesterdaySales > 0.0 -> ((todaySales - yesterdaySales) / yesterdaySales) * 100.0
                                todaySales > 0.0 -> 100.0
                                else -> 0.0
                            }
                            val isPositive = changePercent >= 0.0
                            Icon(
                                imageVector = if (isPositive) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                                contentDescription = null,
                                tint = if (isPositive) SuccessGreen else ErrorRed,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val sign = if (isPositive) "+" else ""
                            Text(
                                text = "${sign}${String.format(LocalLocale.current.platformLocale, "%.1f", changePercent)}% vs yesterday",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = if (isPositive) SuccessGreen else ErrorRed,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        if (uiState.summary.pendingOfflineCash > 0.0) {
                            Text(
                                text = "Pending offline cash: ${currencyFormat.format(uiState.summary.pendingOfflineCash)} · not confirmed revenue",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }

                    // Mini Sparkline - use real trend data
                    val sparklineData = uiState.salesTrend.map { it.second }
                    SmoothSparkline(
                        data = if (sparklineData.size >= 2) sparklineData
                               else listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                        lineColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .width(150.dp)
                            .height(48.dp)
                    )
                }
            }
        }

        // 4. Quick Actions
        if (!isStaff) item {
            Column(modifier = Modifier.padding(horizontal = AquaHubSpacing.medium, vertical = AquaHubSpacing.mediumLarge)) {
                AquaHubSectionHeader(title = "Quick Actions")
                Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    QuickActionItem(
                        icon = Icons.Rounded.Inventory2,
                        label = "Products",
                        color = MaterialTheme.colorScheme.primary,
                        onClick = onNavigateToProducts
                    )
                    QuickActionItem(
                        icon = Icons.Rounded.Storage,
                        label = "Containers",
                        color = MaterialTheme.colorScheme.secondary,
                        onClick = onNavigateToInventory
                    )
                    QuickActionItem(
                        icon = Icons.Rounded.Groups,
                        label = "Customers",
                        color = Color(0xFF8E24AA),
                        onClick = onNavigateToCustomers
                    )
                    QuickActionItem(
                        icon = Icons.Rounded.Analytics,
                        label = "Analytics",
                        color = Color(0xFF16A34A),
                        onClick = onNavigateToReports
                    )
                    QuickActionItem(
                        icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                        label = "Expenses",
                        color = Color(0xFFE66B2E),
                        onClick = onNavigateToExpenses
                    )
                }
            }
        }

        // 5. Recent Orders Section
        item {
            AquaHubSectionHeader(
                title = "Recent Orders",
                actionText = "View All",
                onActionClick = { onNavigateToOrders("ALL") },
                modifier = Modifier.padding(horizontal = AquaHubSpacing.medium)
            )
        }

        if (uiState.summary.recentOrders.isEmpty()) {
            item {
                AquaHubEmptyState(
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    message = "No Recent Orders",
                    description = "Customer orders will appear here in real time"
                )
            }
        } else {
            items(uiState.summary.recentOrders, key = { it.id }) { order ->
                DashboardOrderCard(
                    order = order,
                    onClick = { onNavigateToOrderDetail(order.id) },
                    modifier = Modifier.padding(horizontal = AquaHubSpacing.medium, vertical = 5.dp)
                )
            }
        }
    }
    }
}

private data class DashboardMetric(
    val title: String,
    val value: String,
    val icon: ImageVector,
    val iconBgColor: Color,
    val iconColor: Color,
    val onClick: () -> Unit
)

@Composable
private fun DashboardMetricGrid(
    metrics: List<DashboardMetric>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 1100.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)) {
            metrics.chunked(columns).forEach { rowMetrics ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    rowMetrics.forEach { metric ->
                        AquaHubMetricCard(
                            title = metric.title,
                            value = metric.value,
                            icon = metric.icon,
                            iconBgColor = metric.iconBgColor,
                            iconColor = metric.iconColor,
                            modifier = Modifier.weight(1f),
                            onClick = metric.onClick
                        )
                    }
                    repeat(columns - rowMetrics.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionItem(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(56.dp)
                .background(color.copy(alpha = 0.12f), AquaHubShapes.cardSecondary)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(AquaHubIconSize.medium)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )
    }
}

@Composable
private fun DashboardOrderCard(
    order: Order,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))
    AquaHubGlassCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = order.orderNumber,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            AquaHubStatusChip(status = order.orderStatus)
        }
        Spacer(modifier = Modifier.height(AquaHubSpacing.extraSmall))
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = order.customerName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = if (order.items.isNotEmpty())
                        order.items.joinToString(", ") { "${it.quantity}x ${it.sizeLabel}" }
                    else "No items",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }
            Text(
                text = currencyFormat.format(order.total),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
private fun SmoothSparkline(
    data: List<Double>,
    modifier: Modifier = Modifier,
    lineColor: Color = AquaPrimary
) {
    if (data.size < 2) return
    val max = data.maxOrNull() ?: 1.0
    val min = data.minOrNull() ?: 0.0
    val range = if (max == min) 1.0 else max - min

    val animatedFraction by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(1200),
        label = "sparklineAnim"
    )

    Canvas(modifier = modifier) {
        val stepX = size.width / (data.size - 1)
        val path = Path()

        data.forEachIndexed { i, value ->
            val x = i * stepX
            val y = (size.height - (((value - min) / range) * size.height * 0.75f).toFloat() - (size.height * 0.15f)) * animatedFraction
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun DashboardScreenPreview() {
    AquaHubTheme {
        DashboardContent(
            uiState = DashboardUiState(
                station = Station(id = 1, name = "AquaPure Station", isOpen = true),
                summary = DashboardSummary(
                    todayOrdersCount = 24,
                    pendingOrdersCount = 5,
                    outForDeliveryCount = 3,
                    completedTodayCount = 16,
                    todaySales = 8640.0,
                    recentOrders = listOf(
                        Order(
                            id = 1,
                            orderNumber = "AQH-20240518-0012",
                            customerName = "Juan Dela Cruz",
                            total = 180.0,
                            orderStatus = OrderStatus.PENDING,
                            items = listOf(OrderItem(quantity = 4, sizeLabel = "5 Gallon"))
                        )
                    )
                )
            ),
            onNavigateToOrders = {},
            onNavigateToOrderDetail = {},
            onNavigateToInventory = {},
            onNavigateToCustomers = {},
            onNavigateToReports = {},
            onNavigateToProducts = {},
            onNavigateToExpenses = {}
        )
    }
}
