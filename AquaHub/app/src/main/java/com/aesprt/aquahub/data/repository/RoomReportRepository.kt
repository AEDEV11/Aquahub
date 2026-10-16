package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.local.dao.OrderDao
import com.aesprt.aquahub.data.local.dao.OrderItemDao
import com.aesprt.aquahub.data.local.dao.PaymentDao
import com.aesprt.aquahub.data.local.dao.OfflinePosDao
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.ReportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.*

class RoomReportRepository(
    private val orderDao: OrderDao,
    private val orderItemDao: OrderItemDao,
    private val paymentDao: PaymentDao,
    private val offlinePosDao: OfflinePosDao,
    private val pendingOperationsProfileUid: suspend () -> String? = { null },
) : ReportRepository {

    override fun observeDashboardSummary(stationId: Long, riderId: Long?): Flow<DashboardSummary> {
        val (startOfDay, endOfDay) = todayRange()
        val startOfYesterday = startOfDay - 86400000L
        val recentOrders = riderId?.let { orderDao.observeRecentOrdersForRider(stationId, it, 10) }
            ?: orderDao.observeRecentOrders(stationId, 10)
        val todayOrders = riderId?.let { orderDao.observeTodayOrdersForRider(stationId, it, startOfDay, endOfDay) }
            ?: orderDao.observeTodayOrders(stationId, startOfDay, endOfDay)
        val completedTodayOrders = riderId?.let {
            orderDao.observeCompletedTodayOrdersForRider(stationId, it, startOfDay, endOfDay)
        } ?: orderDao.observeCompletedTodayOrders(stationId, startOfDay, endOfDay)
        val todayCollected = riderId?.let {
            paymentDao.observeCollectedRevenueForRider(stationId, it, startOfDay, endOfDay)
        } ?: paymentDao.observeCollectedRevenue(stationId, startOfDay, endOfDay)
        val yesterdayCollected = riderId?.let {
            paymentDao.observeCollectedRevenueForRider(stationId, it, startOfYesterday, startOfDay)
        } ?: paymentDao.observeCollectedRevenue(stationId, startOfYesterday, startOfDay)
        val collectedRevenue = combine(todayCollected, yesterdayCollected, ::Pair)
        val pendingOffline = if (riderId == null) {
            pendingCashForCurrentProfile(stationId, startOfDay, endOfDay)
        } else kotlinx.coroutines.flow.flowOf(0L)
        return combine(
            recentOrders,
            todayOrders,
            completedTodayOrders,
            collectedRevenue,
            pendingOffline
        ) { recent, todayOrders, completedToday, collected, pendingOfflineCentavos ->
            val pendingCount = todayOrders.count {
                it.order.orderStatus in listOf("PENDING", "ACCEPTED")
            }
            val outForDeliveryCount = todayOrders.count {
                it.order.orderStatus in listOf("RIDER_ASSIGNED", "OUT_FOR_DELIVERY", "DELIVERED")
            }
            val completedCount = completedToday.size
            // Dashboard revenue is cash collected in this date range. Completing
            // an account charge changes fulfilment only; it does not create revenue
            // until one or more PAID receipt rows are recorded.
            val todaySales = collected.first
            val yesterdaySales = collected.second

            DashboardSummary(
                todayOrdersCount = todayOrders.size,
                pendingOrdersCount = pendingCount,
                outForDeliveryCount = outForDeliveryCount,
                completedTodayCount = completedCount,
                todaySales = todaySales,
                yesterdaySales = yesterdaySales,
                pendingOfflineCash = pendingOfflineCentavos / 100.0,
                recentOrders = recent.take(5).map { it.toDomain() }
            )
        }
    }

    override suspend fun getTodaySales(stationId: Long, startOfDay: Long, endOfDay: Long): SalesSummary {
        return buildSummary(stationId, startOfDay, endOfDay, "Today")
    }

    override suspend fun getWeeklySales(stationId: Long, weekStart: Long, weekEnd: Long): SalesSummary {
        return buildSummary(stationId, weekStart, weekEnd, "This Week")
    }

    override suspend fun getMonthlySales(stationId: Long, monthStart: Long, monthEnd: Long): SalesSummary {
        return buildSummary(stationId, monthStart, monthEnd, "This Month")
    }

    override suspend fun getTopProducts(stationId: Long, startMs: Long, endMs: Long, limit: Int): List<TopProduct> {
        return orderItemDao.getTopProducts(stationId, startMs, endMs, limit).map {
            TopProduct(it.productName, it.sizeLabel, it.totalQty, it.totalRev)
        }
    }

    override suspend fun getDailySalesSeries(stationId: Long, days: Int): List<Pair<String, Double>> {
        val sdf = SimpleDateFormat("EEE", Locale.getDefault())
        val result = mutableListOf<Pair<String, Double>>()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)

        for (i in days - 1 downTo 0) {
            val dayStart = cal.timeInMillis - i * 86400000L
            val dayEnd = dayStart + 86400000L
            val label = sdf.format(Date(dayStart))
            val sales = paymentDao.getCollectedRevenue(stationId, dayStart, dayEnd)
            result.add(label to sales)
        }
        return result
    }

    private suspend fun buildSummary(stationId: Long, start: Long, end: Long, period: String): SalesSummary {
        val totalSales = paymentDao.getCollectedRevenue(stationId, start, end)
        val totalOrders = orderDao.getOrderCount(stationId, start, end)
        val completedOrders = orderDao.getCompletedCount(stationId, start, end)
        val cancelledOrders = orderDao.getCancelledCount(stationId, start, end)
        val paidOrderCount = paymentDao.getPaidOrderCount(stationId, start, end)
        val profileUid = pendingOperationsProfileUid()
        val pendingOfflineCash = profileUid?.let {
            offlinePosDao.getPendingCashSalesCentavos(stationId, it, start, end)
        }?.div(100.0) ?: 0.0
        val avgOrderValue = if (paidOrderCount > 0) totalSales / paidOrderCount else 0.0
        return SalesSummary(period, totalSales, totalOrders, completedOrders, cancelledOrders, avgOrderValue, pendingOfflineCash)
    }

    private fun todayRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        return start to (start + 86400000L)
    }

    private fun pendingCashForCurrentProfile(stationId: Long, start: Long, end: Long) = flow {
        val profileUid = pendingOperationsProfileUid()
        if (profileUid == null) emit(0L)
        else emitAll(offlinePosDao.observePendingCashSalesCentavos(stationId, profileUid, start, end))
    }
}
