package com.aesprt.aquahub.domain.model

import java.util.Calendar

enum class DateRangeFilter(val displayName: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    LAST_7_DAYS("Last 7 Days"),
    LAST_30_DAYS("Last 30 Days"),
    THIS_MONTH("This Month"),
    LAST_MONTH("Last Month"),
    CUSTOM("Custom Range");

    fun calculateTimestamps(
        customStart: Long? = null,
        customEnd: Long? = null,
        now: Long = System.currentTimeMillis()
    ): Pair<Long, Long> {
        fun startOfDay(timestamp: Long): Calendar = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = startOfDay(now)
        fun tomorrow() = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }

        return when (this) {
            TODAY -> today.timeInMillis to tomorrow().timeInMillis
            YESTERDAY -> {
                val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                yesterday.timeInMillis to today.timeInMillis
            }
            LAST_7_DAYS -> {
                val start = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -6) }
                start.timeInMillis to tomorrow().timeInMillis
            }
            LAST_30_DAYS -> {
                val start = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -29) }
                start.timeInMillis to tomorrow().timeInMillis
            }
            THIS_MONTH -> {
                val start = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
                start.timeInMillis to tomorrow().timeInMillis
            }
            LAST_MONTH -> {
                val end = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
                val start = (end.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
                start.timeInMillis to end.timeInMillis
            }
            CUSTOM -> {
                val selectedStart = customStart ?: (now - 29L * 24 * 3600 * 1000)
                val selectedEnd = customEnd ?: now
                require(selectedStart <= selectedEnd) { "Custom report start must not be after its end" }
                val start = startOfDay(selectedStart)
                val endExclusive = startOfDay(selectedEnd).apply { add(Calendar.DAY_OF_YEAR, 1) }
                start.timeInMillis to endExclusive.timeInMillis
            }
        }
    }
}

data class ChartDataPoint(
    val label: String,
    val value: Double,
    val secondaryValue: Double = 0.0,
    val timestamp: Long = 0L
)

data class RevenueAnalytics(
    val totalRevenue: Double = 0.0,
    val previousPeriodRevenue: Double = 0.0,
    val revenueGrowthPercent: Double = 0.0,
    val averageDailyRevenue: Double = 0.0,
    val dailyRevenueTrend: List<ChartDataPoint> = emptyList(),
    val paymentBreakdown: List<ChartDataPoint> = emptyList(),
    /** Cash received locally; excluded from confirmed revenue until server commit. */
    val pendingOfflineCash: Double = 0.0
)

data class OrderAnalytics(
    val totalOrders: Int = 0,
    val completedOrders: Int = 0,
    val cancelledOrders: Int = 0,
    val pendingOrders: Int = 0,
    val deliveryOrders: Int = 0,
    val walkInOrders: Int = 0,
    val averageOrderValue: Double = 0.0,
    val completionRatePercent: Double = 0.0,
    val orderStatusDistribution: List<ChartDataPoint> = emptyList(),
    val dailyOrderTrend: List<ChartDataPoint> = emptyList(),
    val reportRows: List<OrderReportRow> = emptyList()
)

data class CustomerAnalytics(
    val totalCustomers: Int = 0,
    val activeCustomersCount: Int = 0,
    val repeatCustomersCount: Int = 0,
    val repeatRatePercent: Double = 0.0,
    val topSpendingCustomers: List<TopCustomer> = emptyList(),
    val reportRows: List<CustomerReportRow> = emptyList()
)

data class TopCustomer(
    val customerId: Long,
    val name: String,
    val phone: String,
    val orderCount: Int,
    val totalSpent: Double
)

data class DeliveryAnalytics(
    val totalDeliveries: Int = 0,
    val completedDeliveries: Int = 0,
    val cancelledDeliveries: Int = 0,
    val activeRidersCount: Int = 0,
    val averageDeliveryMinutes: Int = 25,
    val riderPerformances: List<RiderPerformance> = emptyList(),
    val reportRows: List<DeliveryReportRow> = emptyList()
)

data class RiderPerformance(
    val riderId: Long,
    val name: String,
    val phone: String,
    val deliveriesCompleted: Int,
    val totalSalesCollected: Double,
    val assignedDeliveries: Int = deliveriesCompleted,
    val failedDeliveries: Int = 0,
    val averageDeliveryMinutes: Int = 0
)

data class OrderReportRow(
    val orderNumber: String,
    val customerName: String,
    val orderType: String,
    val items: String,
    val quantity: Int,
    val total: Double,
    val status: String,
    val createdAt: Long,
    val completedAt: Long?,
    val paymentMethod: String
)

data class CustomerReportRow(
    val customerId: Long,
    val customerName: String,
    val phone: String,
    val totalOrders: Int,
    val totalSpent: Double,
    val lastOrderAt: Long?,
    val averageOrderValue: Double
)

data class DeliveryReportRow(
    val orderNumber: String,
    val riderName: String,
    val dispatchAt: Long?,
    val deliveredAt: Long?,
    val status: String,
    val durationMinutes: Int?,
    val distanceKm: Double? = null
)

data class ProductAnalytics(
    val topProducts: List<TopProductItem> = emptyList()
)

data class TopProductItem(
    val productId: Long,
    val name: String,
    val type: ProductType,
    val quantitySold: Int,
    val totalRevenue: Double
)

data class StationSummary(
    val stationId: Long,
    val stationName: String,
    val totalRevenue: Double,
    val totalOrders: Int,
    val completedOrders: Int
)

data class ConsolidatedStationAnalytics(
    val totalRevenue: Double = 0.0,
    val totalOrders: Int = 0,
    val stationSummaries: List<StationSummary> = emptyList()
)

data class FullBusinessAnalytics(
    val stationId: Long,
    val dateRange: DateRangeFilter,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val revenue: RevenueAnalytics = RevenueAnalytics(),
    val orders: OrderAnalytics = OrderAnalytics(),
    val customers: CustomerAnalytics = CustomerAnalytics(),
    val delivery: DeliveryAnalytics = DeliveryAnalytics(),
    val products: ProductAnalytics = ProductAnalytics(),
    val consolidated: ConsolidatedStationAnalytics? = null,
    val isProConsolidated: Boolean = false
)

/** Server-authoritative refund aggregates. Monetary amounts stay in centavos. */
data class RefundObligationSummary(
    val organizationId: String,
    val organizationName: String,
    val stationId: String,
    val stationName: String,
    val paymentMethod: String,
    val refundStatus: String,
    val periodStart: Long,
    val periodEnd: Long,
    val requestedCount: Long,
    val requestedCentavos: Long,
    val pendingCount: Long,
    val pendingCentavos: Long,
    val reservedRefundableCentavos: Long,
    val completedCount: Long,
    val completedCentavos: Long,
    val failedAttemptCount: Long,
    val failedAttemptCentavos: Long,
    val partialPaymentCount: Long,
    val partialRefundCentavos: Long,
    val oldestPendingAt: Long?,
    val pendingUnder24HoursCount: Long,
    val pendingUnder24HoursCentavos: Long,
    val pending1To7DaysCount: Long,
    val pending1To7DaysCentavos: Long,
    val pending8To30DaysCount: Long,
    val pending8To30DaysCentavos: Long,
    val pendingOver30DaysCount: Long,
    val pendingOver30DaysCentavos: Long
)
