package com.aesprt.aquahub.domain.export

import com.aesprt.aquahub.domain.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ReportCell(
    val value: String,
    val numeric: Boolean = false,
    val currency: Boolean = false
)

data class ReportTable(
    val headers: List<String>,
    val rows: List<List<ReportCell>>,
    val totalRow: List<ReportCell>? = null
)

data class ReportMetric(
    val label: String,
    val value: String,
    val numeric: Boolean = false,
    val currency: Boolean = false
)

data class ReportExportContent(
    val title: String,
    val worksheetName: String,
    val summary: List<ReportMetric>,
    val table: ReportTable
)

private fun money(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun dateTime(value: Long?): String = value?.let {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it))
} ?: ""
private fun titleFor(type: ReportType, consolidated: Boolean): Pair<String, String> = when {
    consolidated && type == ReportType.FULL_BUSINESS_SUMMARY -> "Consolidated Report" to "Consolidated"
    type == ReportType.FULL_BUSINESS_SUMMARY -> "Business Summary Report" to "Business Summary"
    type == ReportType.SALES_AND_REVENUE -> "Sales & Revenue Report" to "Sales & Revenue"
    type == ReportType.ORDERS_LIST -> "Orders Report" to "Orders"
    type == ReportType.CUSTOMER_INSIGHTS -> "Customer Insights Report" to "Customers"
    type == ReportType.DELIVERY_AND_RIDERS -> "Delivery & Rider Performance Report" to "Delivery"
    else -> "Product Performance Report" to "Products"
}

fun ReportExportRequest.toContent(): ReportExportContent {
    val analytics = analytics
    val (title, worksheet) = titleFor(reportType, analytics.isProConsolidated && analytics.consolidated != null)
    val summary = when {
        analytics.isProConsolidated && analytics.consolidated != null -> listOf(
            ReportMetric("Total Revenue", money(analytics.consolidated.totalRevenue), currency = true),
            ReportMetric("Total Orders", analytics.consolidated.totalOrders.toString(), numeric = true),
            ReportMetric("Stations", analytics.consolidated.stationSummaries.size.toString(), numeric = true)
        )
        reportType == ReportType.SALES_AND_REVENUE -> listOf(
            ReportMetric("Gross Sales", money(analytics.revenue.totalRevenue), currency = true),
            ReportMetric("Orders", analytics.orders.totalOrders.toString(), numeric = true),
            ReportMetric("Average Order Value", money(analytics.orders.averageOrderValue), currency = true),
            ReportMetric("Completed Orders", analytics.orders.completedOrders.toString(), numeric = true),
            ReportMetric("Cancelled Orders", analytics.orders.cancelledOrders.toString(), numeric = true)
        )
        reportType == ReportType.ORDERS_LIST -> listOf(
            ReportMetric("Orders", analytics.orders.totalOrders.toString(), numeric = true),
            ReportMetric("Completed Orders", analytics.orders.completedOrders.toString(), numeric = true),
            ReportMetric("Cancelled Orders", analytics.orders.cancelledOrders.toString(), numeric = true),
            ReportMetric("Total Sales", money(analytics.revenue.totalRevenue), currency = true)
        )
        reportType == ReportType.CUSTOMER_INSIGHTS -> listOf(
            ReportMetric("Customers in Directory", analytics.customers.totalCustomers.toString(), numeric = true),
            ReportMetric("Active Customers", analytics.customers.activeCustomersCount.toString(), numeric = true),
            ReportMetric("Repeat Customers", analytics.customers.repeatCustomersCount.toString(), numeric = true),
            ReportMetric("Repeat Rate", String.format(Locale.US, "%.1f%%", analytics.customers.repeatRatePercent))
        )
        reportType == ReportType.DELIVERY_AND_RIDERS -> listOf(
            ReportMetric("Deliveries", analytics.delivery.totalDeliveries.toString(), numeric = true),
            ReportMetric("Completed Deliveries", analytics.delivery.completedDeliveries.toString(), numeric = true),
            ReportMetric("Cancelled Deliveries", analytics.delivery.cancelledDeliveries.toString(), numeric = true),
            ReportMetric("Average Delivery Minutes", analytics.delivery.averageDeliveryMinutes.toString(), numeric = true)
        )
        reportType == ReportType.TOP_PRODUCTS -> listOf(
            ReportMetric("Products Tracked", analytics.products.topProducts.size.toString(), numeric = true),
            ReportMetric("Units Sold", analytics.products.topProducts.sumOf { it.quantitySold }.toString(), numeric = true),
            ReportMetric("Product Revenue", money(analytics.products.topProducts.sumOf { it.totalRevenue }), currency = true)
        )
        else -> listOf(
            ReportMetric("Total Revenue", money(analytics.revenue.totalRevenue), currency = true),
            ReportMetric("Total Orders", analytics.orders.totalOrders.toString(), numeric = true),
            ReportMetric("Average Order Value", money(analytics.orders.averageOrderValue), currency = true),
            ReportMetric("Active Customers", analytics.customers.activeCustomersCount.toString(), numeric = true)
        )
    }

    val table = when {
        analytics.isProConsolidated && analytics.consolidated != null -> ReportTable(
            headers = listOf("Station", "Orders", "Completed Orders", "Revenue (PHP)"),
            rows = analytics.consolidated.stationSummaries.map { station -> listOf(
                ReportCell(station.stationName),
                ReportCell(station.totalOrders.toString(), numeric = true),
                ReportCell(station.completedOrders.toString(), numeric = true),
                ReportCell(money(station.totalRevenue), numeric = true, currency = true)
            ) },
            totalRow = listOf(
                ReportCell("Total"),
                ReportCell(analytics.consolidated.totalOrders.toString(), numeric = true),
                ReportCell(analytics.consolidated.stationSummaries.sumOf { it.completedOrders }.toString(), numeric = true),
                ReportCell(money(analytics.consolidated.totalRevenue), numeric = true, currency = true)
            )
        )
        reportType == ReportType.SALES_AND_REVENUE -> ReportTable(
            headers = listOf("Date", "Revenue (PHP)", "Orders"),
            rows = analytics.revenue.dailyRevenueTrend.mapIndexed { index, point -> listOf(
                ReportCell(point.label),
                ReportCell(money(point.value), numeric = true, currency = true),
                ReportCell(analytics.orders.dailyOrderTrend.getOrNull(index)?.value?.toInt()?.toString() ?: "0", numeric = true)
            ) },
            totalRow = listOf(ReportCell("Total"), ReportCell(money(analytics.revenue.totalRevenue), numeric = true, currency = true), ReportCell(analytics.orders.totalOrders.toString(), numeric = true))
        )
        reportType == ReportType.ORDERS_LIST -> ReportTable(
            headers = listOf("Order Number", "Customer", "Order Type", "Items", "Qty", "Total (PHP)", "Status", "Created", "Completed", "Payment"),
            rows = analytics.orders.reportRows.map { row -> listOf(
                ReportCell(row.orderNumber), ReportCell(row.customerName), ReportCell(row.orderType),
                ReportCell(row.items), ReportCell(row.quantity.toString(), numeric = true),
                ReportCell(money(row.total), numeric = true, currency = true), ReportCell(row.status),
                ReportCell(dateTime(row.createdAt)), ReportCell(dateTime(row.completedAt)), ReportCell(row.paymentMethod)
            ) }
        )
        reportType == ReportType.CUSTOMER_INSIGHTS -> ReportTable(
            headers = listOf("Customer", "Phone", "Orders", "Total Spent (PHP)", "Last Order", "Average Order (PHP)"),
            rows = analytics.customers.reportRows.map { row -> listOf(
                ReportCell(row.customerName), ReportCell(row.phone), ReportCell(row.totalOrders.toString(), numeric = true),
                ReportCell(money(row.totalSpent), numeric = true, currency = true), ReportCell(dateTime(row.lastOrderAt)),
                ReportCell(money(row.averageOrderValue), numeric = true, currency = true)
            ) }
        )
        reportType == ReportType.DELIVERY_AND_RIDERS -> ReportTable(
            headers = listOf("Order", "Rider", "Dispatch Time", "Delivery Time", "Status", "Duration (min)"),
            rows = analytics.delivery.reportRows.map { row -> listOf(
                ReportCell(row.orderNumber), ReportCell(row.riderName), ReportCell(dateTime(row.dispatchAt)),
                ReportCell(dateTime(row.deliveredAt)), ReportCell(row.status),
                ReportCell(row.durationMinutes?.toString() ?: "", numeric = row.durationMinutes != null)
            ) }
        )
        reportType == ReportType.TOP_PRODUCTS -> ReportTable(
            headers = listOf("Product", "Type", "Units Sold", "Revenue (PHP)"),
            rows = analytics.products.topProducts.map { row -> listOf(
                ReportCell(row.name), ReportCell(row.type.displayName()), ReportCell(row.quantitySold.toString(), numeric = true),
                ReportCell(money(row.totalRevenue), numeric = true, currency = true)
            ) },
            totalRow = listOf(
                ReportCell("Total"), ReportCell(""),
                ReportCell(analytics.products.topProducts.sumOf { it.quantitySold }.toString(), numeric = true),
                ReportCell(money(analytics.products.topProducts.sumOf { it.totalRevenue }), numeric = true, currency = true)
            )
        )
        else -> ReportTable(
            headers = listOf("Metric", "Value"),
            rows = listOf(
                listOf(ReportCell("Daily Revenue"), ReportCell(money(analytics.revenue.totalRevenue), numeric = true, currency = true)),
                listOf(ReportCell("Daily Orders"), ReportCell(analytics.orders.totalOrders.toString(), numeric = true)),
                listOf(ReportCell("Completed Orders"), ReportCell(analytics.orders.completedOrders.toString(), numeric = true)),
                listOf(ReportCell("Repeat Customer Rate"), ReportCell(String.format(Locale.US, "%.1f%%", analytics.customers.repeatRatePercent)))
            )
        )
    }
    return ReportExportContent(title, worksheet, summary, table)
}
