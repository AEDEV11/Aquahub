package com.aesprt.aquahub.feature.reports

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.DateRangeFilter
import com.aesprt.aquahub.domain.model.ChartDataPoint
import com.aesprt.aquahub.domain.model.FullBusinessAnalytics
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.feature.reports.export.ExportReportDialog
import com.aesprt.aquahub.ui.theme.*
import java.text.NumberFormat
import java.math.BigDecimal
import java.util.Locale

@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    onBackClick: () -> Unit,
    onNavigateToSubscription: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        onDispose { viewModel.resetConsolidatedView() }
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Analytics & Reports",
                subtitle = if (uiState.isConsolidatedView) "Consolidated (All Branches)" else uiState.stationName,
                onBackClick = onBackClick,
                actions = {
                    AquaHubPrimaryButton(
                        text = "Export",
                        icon = Icons.Rounded.Download,
                        onClick = viewModel::openExportDialog,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AnimatedContent(
            targetState = uiState.isLoading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "analyticsLoadingAnim"
        ) { loading ->
            if (loading) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(AquaHubSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
                ) {
                    repeat(4) {
                        AquaHubSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                        )
                    }
                }
            } else if (uiState.analytics == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(AquaHubSpacing.large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = uiState.errorMessage ?: "Analytics could not be loaded.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(AquaHubSpacing.medium))
                    Button(onClick = viewModel::retryLoad) { Text("Retry") }
                }
            } else {
                val analytics = checkNotNull(uiState.analytics)
                val currencyFormat = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) }

                LazyColumn(
                    contentPadding = PaddingValues(
                        start = AquaHubSpacing.medium,
                        end = AquaHubSpacing.medium,
                        top = AquaHubSpacing.medium,
                        bottom = AquaHubSpacing.extraLarge
                    ),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // 1. Date Range Chips Filter
                    item {
                        var showCustomDateRangeDialog by remember { mutableStateOf(false) }

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(DateRangeFilter.entries) { filter ->
                                val isSelected = uiState.selectedDateRange == filter
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        if (filter == DateRangeFilter.CUSTOM) {
                                            showCustomDateRangeDialog = true
                                        } else {
                                            viewModel.selectDateRange(filter)
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = filter.displayName,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                )
                            }
                        }

                        if (showCustomDateRangeDialog) {
                            AquaHubDateRangePickerDialog(
                                onDismiss = { showCustomDateRangeDialog = false },
                                initialStartDateMillis = uiState.customStartTimestamp,
                                initialEndDateMillis = uiState.customEndTimestamp,
                                onDateRangeSelected = { startMs, endMs ->
                                    viewModel.selectCustomDateRange(startMs, endMs)
                                    showCustomDateRangeDialog = false
                                }
                            )
                        }
                    }

                    // 2. Pro Consolidated Switch (if applicable)
                    if (uiState.canAccessConsolidated) {
                        item {
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Consolidated All-Station View",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            PlanBadge(planCode = SubscriptionPlanCode.PRO)
                                        }
                                        Text(
                                            text = "Aggregate metrics across all station branches",
                                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }

                                    Switch(
                                        checked = uiState.isConsolidatedView,
                                        onCheckedChange = viewModel::toggleConsolidatedView
                                    )
                                }
                            }
                        }
                    }

                    // 3. Executive KPIs use the same compact-to-wide card rhythm as the dashboard.
                    item {
                        AdaptiveCardGrid(
                            cards = buildList<AdaptiveCardItem> {
                                add { cardModifier -> AnimatedKpiCard(
                                    title = "Total Revenue",
                                    value = currencyFormat.format(analytics.revenue.totalRevenue),
                                    trendLabel = "${if (analytics.revenue.revenueGrowthPercent >= 0) "+" else ""}${String.format(Locale.US, "%.1f", analytics.revenue.revenueGrowthPercent)}%",
                                    isPositiveTrend = analytics.revenue.revenueGrowthPercent >= 0,
                                    icon = Icons.Rounded.Payments,
                                    accentColor = AquaCyan,
                                    modifier = cardModifier
                                ) }
                                add { cardModifier -> AnimatedKpiCard(
                                    title = "Pending Offline Cash",
                                    value = currencyFormat.format(analytics.revenue.pendingOfflineCash),
                                    trendLabel = "Not confirmed revenue",
                                    isPositiveTrend = false,
                                    icon = Icons.Rounded.CloudSync,
                                    accentColor = WarningAmber,
                                    modifier = cardModifier
                                ) }
                                add { cardModifier -> AnimatedKpiCard(
                                    title = "Total Orders",
                                    value = "${analytics.orders.totalOrders}",
                                    trendLabel = "${String.format(Locale.US, "%.0f", analytics.orders.completionRatePercent)}% done",
                                    isPositiveTrend = analytics.orders.completionRatePercent >= 70,
                                    icon = Icons.Rounded.ShoppingBag,
                                    accentColor = SuccessGreen,
                                    modifier = cardModifier
                                ) }
                                add { cardModifier -> AnimatedKpiCard(
                                    title = "Average Order",
                                    value = currencyFormat.format(analytics.orders.averageOrderValue),
                                    trendLabel = "Per Order",
                                    isPositiveTrend = true,
                                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                                    accentColor = MaterialTheme.colorScheme.tertiary,
                                    modifier = cardModifier
                                ) }
                                if (uiState.canAccessCustomerInsights) {
                                    add { cardModifier -> AnimatedKpiCard(
                                        title = "Active Customers",
                                        value = "${analytics.customers.activeCustomersCount}",
                                        trendLabel = "${String.format(Locale.US, "%.0f", analytics.customers.repeatRatePercent)}% repeat",
                                        isPositiveTrend = analytics.customers.repeatRatePercent >= 30,
                                        icon = Icons.Rounded.Groups,
                                        accentColor = WarningAmber,
                                        modifier = cardModifier
                                    ) }
                                }
                            },
                            wideBreakpoint = 1100.dp,
                            itemSpacing = AquaHubSpacing.small
                        )
                    }

                    // Refund outcome activity is period-filtered; unresolved obligations and their aging are current as-of-now.
                    item {
                        val refundRows = uiState.refundObligations
                        val pendingCount = refundRows.sumOf { it.pendingCount }
                        val reservedCentavos = refundRows.sumOf { it.reservedRefundableCentavos }
                        val completedCount = refundRows.sumOf { it.completedCount }
                        val completedCentavos = refundRows.sumOf { it.completedCentavos }
                        val failedCount = refundRows.sumOf { it.failedAttemptCount }
                        val failedCentavos = refundRows.sumOf { it.failedAttemptCentavos }
                        val partialCount = refundRows.sumOf { it.partialPaymentCount }
                        val partialCentavos = refundRows.sumOf { it.partialRefundCentavos }
                        val oldestPendingAt = refundRows.mapNotNull { it.oldestPendingAt }.minOrNull()
                        val oldestPendingDays = oldestPendingAt?.let {
                            ((System.currentTimeMillis() - it).coerceAtLeast(0L) / 86_400_000L)
                        }

                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Refund obligations",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = if (uiState.isConsolidatedView) {
                                            "${uiState.stationName} · this station only"
                                        } else {
                                            uiState.stationName
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.AutoMirrored.Rounded.ReceiptLong, contentDescription = null, tint = AquaCyan)
                            }
                            Spacer(Modifier.height(AquaHubSpacing.small))
                            Text(
                                text = "Requested, completed and failed totals use the selected period. Open refunds and aging include every unresolved request; partial-refund totals are current receipt status.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            when {
                                uiState.isLoadingRefundObligations -> {
                                    Spacer(Modifier.height(AquaHubSpacing.medium))
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                uiState.refundObligationsError != null -> {
                                    Spacer(Modifier.height(AquaHubSpacing.small))
                                    Text(
                                        text = uiState.refundObligationsError.orEmpty(),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(onClick = viewModel::refreshRefundObligations) {
                                        Text("Retry")
                                    }
                                }
                                refundRows.isEmpty() -> {
                                    Spacer(Modifier.height(AquaHubSpacing.small))
                                    Text(
                                        text = "No refund activity or unresolved refund obligations.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                else -> {
                                    Spacer(Modifier.height(AquaHubSpacing.medium))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)
                                    ) {
                                        RefundMetric(
                                            "Pending",
                                            "$pendingCount · ${currencyFormat.format(BigDecimal.valueOf(reservedCentavos, 2))}",
                                            Modifier.weight(1f)
                                        )
                                        RefundMetric(
                                            "Completed in period",
                                            "$completedCount · ${currencyFormat.format(BigDecimal.valueOf(completedCentavos, 2))}",
                                            Modifier.weight(1f)
                                        )
                                    }
                                    Spacer(Modifier.height(AquaHubSpacing.small))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)
                                    ) {
                                        RefundMetric(
                                            "Failed attempts",
                                            "$failedCount · ${currencyFormat.format(BigDecimal.valueOf(failedCentavos, 2))}",
                                            Modifier.weight(1f)
                                        )
                                        RefundMetric(
                                            "Partially refunded receipts",
                                            "$partialCount · ${currencyFormat.format(BigDecimal.valueOf(partialCentavos, 2))}",
                                            Modifier.weight(1f)
                                        )
                                    }
                                    if (oldestPendingDays != null) {
                                        Spacer(Modifier.height(AquaHubSpacing.small))
                                        Text(
                                            text = "Oldest unresolved refund: $oldestPendingDays day(s). Reserved refundable amount: ${currencyFormat.format(BigDecimal.valueOf(reservedCentavos, 2))}.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    refundRows.forEach { row ->
                                        HorizontalDivider(Modifier.padding(vertical = AquaHubSpacing.extraSmall))
                                        Text(
                                            text = "${row.paymentMethod.replace('_', ' ')} · ${row.refundStatus.lowercase(Locale.ROOT)} · ${row.requestedCount} requested in period",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. Revenue Trend Chart Card
                    item {
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    Text(
                                        text = "Revenue Trend",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Daily revenue trajectory for selected period",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                                    contentDescription = null,
                                    tint = AquaCyan
                                )
                            }

                            Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

                            AnimatedRevenueLineChart(
                                dataPoints = analytics.revenue.dailyRevenueTrend,
                                currencyFormat = currencyFormat
                            )
                        }
                    }

                    // 5. Order Volume & Status Distribution
                    item {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Orders Bar Chart
                            AquaHubGlassCard(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Order Volume",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                                AnimatedOrdersBarChart(dataPoints = analytics.orders.dailyOrderTrend)
                            }
                        }
                    }

                    // 6. Order Status Breakdown Donut
                    item {
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Order Fulfillment Breakdown",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                            AnimatedDonutChart(dataPoints = analytics.orders.orderStatusDistribution)
                        }
                    }

                    // 7. Top Performing Products
                    if (analytics.products.topProducts.isNotEmpty()) {
                        item {
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Top Products by Revenue",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                                val maxProductRev = analytics.products.topProducts.maxOfOrNull { it.totalRevenue } ?: 1.0

                                Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)) {
                                    analytics.products.topProducts.forEachIndexed { index, item ->
                                        AnimatedHorizontalRankBar(
                                            rank = index + 1,
                                            title = item.name,
                                            subtitle = "${item.quantitySold} units • ${item.type.name}",
                                            value = item.totalRevenue,
                                            maxValue = maxProductRev,
                                            formattedValue = currencyFormat.format(item.totalRevenue)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 8. Top Valued Customers
                    if (analytics.customers.topSpendingCustomers.isNotEmpty()) {
                        item {
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Top Valued Customers",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                                val maxCustomerSpend = analytics.customers.topSpendingCustomers.maxOfOrNull { it.totalSpent } ?: 1.0

                                Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)) {
                                    analytics.customers.topSpendingCustomers.forEachIndexed { index, cust ->
                                        AnimatedHorizontalRankBar(
                                            rank = index + 1,
                                            title = cust.name,
                                            subtitle = "${cust.orderCount} orders • ${cust.phone}",
                                            value = cust.totalSpent,
                                            maxValue = maxCustomerSpend,
                                            formattedValue = currencyFormat.format(cust.totalSpent),
                                            barColor = SuccessGreen
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 9. Rider Fleet Metrics
                    if (analytics.delivery.riderPerformances.isNotEmpty()) {
                        item {
                            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Delivery Fleet Activity",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                                val maxDeliveries = (analytics.delivery.riderPerformances.maxOfOrNull { it.deliveriesCompleted } ?: 1).toDouble()

                                Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)) {
                                    analytics.delivery.riderPerformances.forEachIndexed { index, rider ->
                                        AnimatedHorizontalRankBar(
                                            rank = index + 1,
                                            title = rider.name,
                                            subtitle = "${rider.deliveriesCompleted} deliveries completed",
                                            value = rider.deliveriesCompleted.toDouble(),
                                            maxValue = maxDeliveries,
                                            formattedValue = currencyFormat.format(rider.totalSalesCollected),
                                            barColor = MaterialTheme.colorScheme.tertiary
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

    if (uiState.showExportDialog) {
        ExportReportDialog(
            currentPlanCode = uiState.currentPlanCode,
            currentDateRange = uiState.selectedDateRange,
            allowedFormats = uiState.allowedFormats,
            allowedReportTypes = uiState.allowedReportTypes,
            isExporting = uiState.isExporting,
            exportResult = uiState.exportResult,
            successMessage = uiState.exportSuccessMessage,
            errorMessage = uiState.exportErrorMessage,
            onDismiss = viewModel::closeExportDialog,
            onExport = { rType, fmt, range ->
                viewModel.exportReport(context, rType, fmt, range)
            },
            onNavigateToSubscription = onNavigateToSubscription
        )
    }
}

@Composable
private fun RefundMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(AquaHubSpacing.small),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
private fun AnimatedKpiCard(
    title: String,
    value: String,
    trendLabel: String,
    isPositiveTrend: Boolean,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    AquaHubGlassCard(
        borderColor = GlassBorderAqua,
        modifier = modifier
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            )
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.15f),
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isPositiveTrend) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                contentDescription = null,
                tint = if (isPositiveTrend) SuccessGreen else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = trendLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isPositiveTrend) SuccessGreen else MaterialTheme.colorScheme.error
                )
            )
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun ReportsScreenPreview() {
    val currency = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) }
    val revenuePoints = listOf(
        ChartDataPoint("Mon", 4_200.0),
        ChartDataPoint("Tue", 5_100.0),
        ChartDataPoint("Wed", 3_800.0),
        ChartDataPoint("Thu", 6_200.0),
        ChartDataPoint("Fri", 5_700.0),
        ChartDataPoint("Sat", 7_400.0),
        ChartDataPoint("Sun", 6_900.0)
    )
    AquaHubTheme {
        Scaffold(
            topBar = {
                AquaHubTopBar(
                    title = "Analytics & Reports",
                    subtitle = "AquaPure Main Station",
                    onBackClick = {},
                    actions = {
                        AquaHubPrimaryButton(
                            text = "Export",
                            icon = Icons.Rounded.Download,
                            onClick = {}
                        )
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            LazyColumn(
                contentPadding = PaddingValues(
                    start = AquaHubSpacing.medium,
                    end = AquaHubSpacing.medium,
                    top = AquaHubSpacing.medium,
                    bottom = AquaHubSpacing.extraLarge
                ),
                verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            ) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                        items(DateRangeFilter.entries) { range ->
                            FilterChip(
                                selected = range == DateRangeFilter.LAST_7_DAYS,
                                onClick = {},
                                label = { Text(range.displayName) }
                            )
                        }
                    }
                }
                item {
                    AdaptiveCardGrid(
                        cards = listOf<AdaptiveCardItem>(
                            { modifier -> AnimatedKpiCard("Total Revenue", currency.format(42_300), "+8.2%", true, Icons.Rounded.Payments, AquaCyan, modifier) },
                            { modifier -> AnimatedKpiCard("Total Orders", "186", "92% completed", true, Icons.Rounded.ShoppingBag, SuccessGreen, modifier) },
                            { modifier -> AnimatedKpiCard("Average Order", currency.format(227), "Per order", true, Icons.AutoMirrored.Rounded.ReceiptLong, MaterialTheme.colorScheme.tertiary, modifier) }
                        )
                    )
                }
                item {
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                            Text("Revenue trend", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            AnimatedRevenueLineChart(dataPoints = revenuePoints)
                        }
                    }
                }
                item {
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                            Text("Order status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            AnimatedDonutChart(
                                dataPoints = listOf(
                                    ChartDataPoint("Completed", 142.0),
                                    ChartDataPoint("Pending", 32.0),
                                    ChartDataPoint("Cancelled", 12.0)
                                )
                            )
                        }
                    }
                }
                item {
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                            Text("Refund obligations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                                RefundMetric("Pending", currency.format(1_250), Modifier.weight(1f))
                                RefundMetric("Settled", currency.format(4_800), Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
