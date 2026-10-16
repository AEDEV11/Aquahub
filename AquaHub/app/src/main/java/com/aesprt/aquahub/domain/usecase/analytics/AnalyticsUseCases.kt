package com.aesprt.aquahub.domain.usecase.analytics

import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.*
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import kotlinx.coroutines.flow.firstOrNull
import java.text.SimpleDateFormat
import java.util.*

private const val WALK_IN_ADDRESS = "Station Walk-in / Pick-up"

class GetFullBusinessAnalyticsUseCase(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val customerRepository: CustomerRepository,
    private val riderRepository: RiderRepository,
    private val productRepository: ProductRepository,
    private val stationRepository: StationRepository,
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val offlinePosRepository: OfflinePosRepository? = null
) {
    suspend operator fun invoke(
        stationId: Long,
        dateRange: DateRangeFilter,
        isConsolidatedRequested: Boolean = false,
        customStartTimestamp: Long? = null,
        customEndTimestamp: Long? = null
    ): FullBusinessAnalytics {
        check(checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.BASIC_REPORTS) is SubscriptionAccessResult.Allowed) {
            "Reports are unavailable for your subscription"
        }
        val canAdvanced = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ADVANCED_REPORTS) is SubscriptionAccessResult.Allowed
        val canCustomers = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.CUSTOMER_INSIGHTS) is SubscriptionAccessResult.Allowed
        val canDelivery = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.DELIVERY_ANALYTICS) is SubscriptionAccessResult.Allowed
        val (startTs, endTs) = dateRange.calculateTimestamps(customStartTimestamp, customEndTimestamp)

        val consolidatedAllowed = isConsolidatedRequested &&
            checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.CONSOLIDATED_REPORTS) is SubscriptionAccessResult.Allowed
        val aggregationStations = if (consolidatedAllowed) {
            stationRepository.getAllStations().ifEmpty {
                stationRepository.getStation(stationId)?.let(::listOf).orEmpty()
            }
        } else {
            emptyList()
        }
        val aggregationStationIds = aggregationStations.map { it.id }.ifEmpty { listOf(stationId) }
        val ordersByStation = mutableMapOf<Long, List<Order>>()
        aggregationStationIds.forEach { id ->
            // Reports depend on the full order set, including orders never
            // opened on this installation. Do not publish a partial total if
            // the authoritative station refresh failed.
            orderRepository.refreshDeliveryReads(id).getOrThrow()
            ordersByStation[id] = orderRepository.observeOrders(id).firstOrNull() ?: emptyList()
        }

        // 1. Fetch Orders for the selected station or all branches in consolidated mode.
        val allOrders = ordersByStation.values.flatten()
        val ordersById = allOrders.associateBy { it.id }
        val salesEligibleOrderIds = allOrders.filter(::isSalesEligibleOrder).map { it.id }.toSet()
        // Payment receipts are authoritative in Supabase and may not have been
        // cached on this device yet. Hydrate them before computing revenue.
        val allReceipts = paymentRepository.refreshPaymentsForOrders(allOrders.map { it.id })
            .filter { it.orderId in salesEligibleOrderIds }
        val completedRefunds = paymentRepository.getRefundsForOrders(allOrders.map { it.id })
            .filter { it.orderId in salesEligibleOrderIds && it.status == RefundStatus.COMPLETED && it.completedAt != null }
        fun receiptsBetween(start: Long, end: Long) = paidReceiptsBetween(allReceipts, start, end)
        fun refundsBetween(start: Long, end: Long) = completedRefunds.filter { refund ->
            refund.completedAt?.let { it >= start && it < end } == true
        }
        val paidReceipts = allReceipts.filter { it.status == PaymentStatus.PAID }
        val currentPeriodReceipts = receiptsBetween(startTs, endTs)
        val currentPeriodRefunds = refundsBetween(startTs, endTs)
        val currentPaidByOrder = netPaidAmountsByOrder(allReceipts, completedRefunds, startTs, endTs)
        val periodOrders = allOrders.filter { it.createdAt >= startTs && it.createdAt < endTs }

        // Revenue Calculations
        val completedOrders = periodOrders.filter { it.orderStatus == OrderStatus.COMPLETED || it.orderStatus == OrderStatus.DELIVERED }
        val totalRevenue = currentPeriodReceipts.sumOf { it.amount } -
            currentPeriodRefunds.sumOf { it.amountCentavos } / 100.0
        val pendingOfflineCash = if (offlinePosRepository == null) 0.0 else aggregationStationIds.sumOf { id ->
            offlinePosRepository.observePendingCashSalesCentavos(id, startTs, endTs).firstOrNull() ?: 0L
        } / 100.0

        // Previous Period for growth
        val duration = endTs - startTs
        val prevStart = startTs - duration
        val prevEnd = startTs
        val prevRevenue = receiptsBetween(prevStart, prevEnd).sumOf { it.amount } -
            refundsBetween(prevStart, prevEnd).sumOf { it.amountCentavos } / 100.0

        val revenueGrowthPercent = if (prevRevenue > 0) {
            ((totalRevenue - prevRevenue) / prevRevenue) * 100.0
        } else if (totalRevenue > 0) 100.0 else 0.0

        // Daily Revenue & Order Series
        val dayWindows = buildDayWindows(startTs, endTs).takeLast(30)
        val sdf = SimpleDateFormat(if (dayWindows.size <= 7) "EEE" else "MMM d", Locale.getDefault())
        val dailyTrend = mutableListOf<ChartDataPoint>()
        val dailyOrderTrend = mutableListOf<ChartDataPoint>()

        dayWindows.forEach { (dStart, dEnd) ->
            val dayLabel = sdf.format(Date(dStart))

            val dayOrders = allOrders.filter { it.createdAt >= dStart && it.createdAt < dEnd }
            val grossDaySales = paidReceipts.sumOf { receipt ->
                val paidAt = receipt.paidAt ?: receipt.createdAt
                if (paidAt >= dStart && paidAt < dEnd) receipt.amount else 0.0
            }
            val refundDaySales = refundsBetween(dStart, dEnd).sumOf { it.amountCentavos } / 100.0

            dailyTrend.add(ChartDataPoint(label = dayLabel, value = grossDaySales - refundDaySales, timestamp = dStart))
            dailyOrderTrend.add(ChartDataPoint(label = dayLabel, value = dayOrders.size.toDouble(), timestamp = dStart))
        }

        val grossPaymentBreakdown = currentPeriodReceipts
            .groupingBy { it.paymentMethod.displayName() }
            .fold(0.0) { total, receipt -> total + receipt.amount }
            .toMutableMap()
        currentPeriodRefunds.groupBy { it.paymentMethod.displayName() }.forEach { (method, refunds) ->
            grossPaymentBreakdown[method] = grossPaymentBreakdown.getOrDefault(method, 0.0) -
                refunds.sumOf { it.amountCentavos } / 100.0
        }
        val paymentBreakdown = grossPaymentBreakdown
            .map { (label, value) -> ChartDataPoint(label, value) }
            .sortedByDescending { it.value }

        val revenueAnalytics = RevenueAnalytics(
            totalRevenue = totalRevenue,
            previousPeriodRevenue = prevRevenue,
            revenueGrowthPercent = revenueGrowthPercent,
            averageDailyRevenue = if (dailyTrend.isNotEmpty()) totalRevenue / dailyTrend.size else 0.0,
            dailyRevenueTrend = dailyTrend,
            paymentBreakdown = paymentBreakdown,
            pendingOfflineCash = pendingOfflineCash
        )

        // 2. Order Analytics
        val cancelledCount = periodOrders.count { it.orderStatus == OrderStatus.CANCELLED || it.orderStatus == OrderStatus.REJECTED }
        val pendingCount = periodOrders.count { it.orderStatus in listOf(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.READY_FOR_PICKUP) }
        val deliveryOrders = periodOrders.count { it.deliveryAddress.isNotBlank() && it.deliveryAddress != WALK_IN_ADDRESS }
        val walkInOrders = periodOrders.size - deliveryOrders
        val paidOrderCount = currentPaidByOrder.values.count { it > 0.0 }
        val avgOrderValue = if (paidOrderCount > 0) totalRevenue / paidOrderCount else 0.0
        val completionRate = if (periodOrders.isNotEmpty()) (completedOrders.size.toDouble() / periodOrders.size.toDouble()) * 100.0 else 0.0

        val statusDist = listOf(
            ChartDataPoint("Completed", completedOrders.size.toDouble()),
            ChartDataPoint("Cancelled", cancelledCount.toDouble()),
            ChartDataPoint("Pending", pendingCount.toDouble())
        )

        val orderReportRows = periodOrders.sortedByDescending { it.createdAt }.map { order ->
            OrderReportRow(
                orderNumber = order.orderNumber.ifBlank { "#${order.id}" },
                customerName = order.customerName.ifBlank { "Walk-in customer" },
                orderType = order.deliveryMode,
                items = order.items.joinToString("; ") { item -> "${item.productName} x${item.quantity}" },
                quantity = order.items.sumOf { it.quantity },
                total = order.total,
                status = order.orderStatus.displayName(),
                createdAt = order.createdAt,
                completedAt = order.completedAt ?: order.deliveredAt,
                paymentMethod = order.paymentMethod.displayName()
            )
        }

        val orderAnalytics = OrderAnalytics(
            totalOrders = periodOrders.size,
            completedOrders = completedOrders.size,
            cancelledOrders = cancelledCount,
            pendingOrders = pendingCount,
            deliveryOrders = deliveryOrders,
            walkInOrders = walkInOrders,
            averageOrderValue = avgOrderValue,
            completionRatePercent = completionRate,
            orderStatusDistribution = statusDist,
            dailyOrderTrend = dailyOrderTrend,
            reportRows = orderReportRows
        )

        // 3. Customer Analytics
        val allCustomers = aggregationStationIds.flatMap { id ->
            customerRepository.observeCustomers(id).firstOrNull() ?: emptyList()
        }
        val customerOrderCounts = periodOrders.groupBy { it.customerId }
        val customerPaidTotals = netSalesByDimension(currentPaidByOrder, ordersById) { order ->
            order.customerId.takeIf { it > 0L }
        }
        val customerPaidOrderCounts = currentPaidByOrder.asSequence()
            .filter { (_, netAmount) -> netAmount > 0.0 }
            .mapNotNull { (orderId, _) ->
                ordersById[orderId]?.customerId?.takeIf { it > 0L }?.let { it to orderId }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ids) -> ids.toSet().size }
        val customerIds = customerOrderCounts.keys + customerPaidTotals.keys
        val activeCount = customerOrderCounts.size
        val repeatCustomers = customerOrderCounts.filter { it.value.size > 1 }.size
        val repeatRate = if (activeCount > 0) (repeatCustomers.toDouble() / activeCount.toDouble()) * 100.0 else 0.0

        val topSpenders = customerIds.mapNotNull { cid ->
            if (cid <= 0L) return@mapNotNull null
            val cust = allCustomers.find { it.id == cid }
            val cSales = customerPaidTotals[cid] ?: 0.0
            TopCustomer(
                customerId = cid,
                name = cust?.name ?: "Customer #$cid",
                phone = cust?.phone ?: "",
                orderCount = customerOrderCounts[cid].orEmpty().size,
                totalSpent = cSales
            )
        }.sortedByDescending { it.totalSpent }.take(5)

        val customerReportRows = customerIds.map { cid ->
            val cOrders = customerOrderCounts[cid].orEmpty()
            val cust = allCustomers.find { it.id == cid }
            val customerSpent = customerPaidTotals[cid] ?: 0.0
            CustomerReportRow(
                customerId = cid,
                customerName = cust?.name ?: "Customer #$cid",
                phone = cust?.phone.orEmpty(),
                totalOrders = cOrders.size,
                totalSpent = customerSpent,
                lastOrderAt = cOrders.maxOfOrNull { it.createdAt },
                averageOrderValue = if ((customerPaidOrderCounts[cid] ?: 0) == 0) 0.0 else customerSpent / customerPaidOrderCounts.getValue(cid)
            )
        }.sortedByDescending { it.totalSpent }

        val customerAnalytics = CustomerAnalytics(
            totalCustomers = allCustomers.size,
            activeCustomersCount = activeCount,
            repeatCustomersCount = repeatCustomers,
            repeatRatePercent = repeatRate,
            topSpendingCustomers = topSpenders,
            reportRows = customerReportRows
        )

        // 4. Delivery Analytics
        val allRiders = aggregationStationIds.flatMap { id ->
            riderRepository.observeRiders(id).firstOrNull() ?: emptyList()
        }
        val deliveryList = periodOrders.filter { it.deliveryAddress.isNotBlank() && it.deliveryAddress != WALK_IN_ADDRESS }
        val completedDeliveries = deliveryList.filter { it.orderStatus == OrderStatus.COMPLETED || it.orderStatus == OrderStatus.DELIVERED }
        val cancelledDeliveries = deliveryList.filter { it.orderStatus == OrderStatus.CANCELLED || it.orderStatus == OrderStatus.REJECTED }

        fun deliveryDurationMinutes(order: Order): Int? {
            val start = order.outForDeliveryAt ?: order.assignedAt
            val end = order.deliveredAt ?: order.completedAt
            return if (start != null && end != null && end >= start) ((end - start) / 60_000L).toInt() else null
        }

        val deliveryReportRows = deliveryList.map { order ->
            DeliveryReportRow(
                orderNumber = order.orderNumber.ifBlank { "#${order.id}" },
                riderName = order.riderName ?: "Unassigned",
                dispatchAt = order.outForDeliveryAt ?: order.assignedAt,
                deliveredAt = order.deliveredAt ?: order.completedAt,
                status = order.orderStatus.displayName(),
                durationMinutes = deliveryDurationMinutes(order)
            )
        }.sortedByDescending { it.dispatchAt ?: 0L }

        val riderSales = netSalesByDimension(currentPaidByOrder, ordersById) { it.riderId }
        val riderStats = allRiders.map { rider ->
            val riderOrders = deliveryList.filter { it.riderId == rider.id }
            val completedRiderOrders = riderOrders.filter { it.orderStatus == OrderStatus.COMPLETED || it.orderStatus == OrderStatus.DELIVERED }
            val durations = completedRiderOrders.mapNotNull(::deliveryDurationMinutes)
            RiderPerformance(
                riderId = rider.id,
                name = rider.name,
                phone = rider.phone,
                deliveriesCompleted = completedRiderOrders.size,
                totalSalesCollected = riderSales[rider.id] ?: 0.0,
                assignedDeliveries = riderOrders.size,
                failedDeliveries = riderOrders.count { it.orderStatus == OrderStatus.CANCELLED || it.orderStatus == OrderStatus.REJECTED },
                averageDeliveryMinutes = if (durations.isEmpty()) 0 else durations.average().toInt()
            )
        }.sortedByDescending { it.deliveriesCompleted }

        val completedDurations = completedDeliveries.mapNotNull(::deliveryDurationMinutes)
        val deliveryAnalytics = DeliveryAnalytics(
            totalDeliveries = deliveryList.size,
            completedDeliveries = completedDeliveries.size,
            cancelledDeliveries = cancelledDeliveries.size,
            activeRidersCount = allRiders.count { it.isActive },
            averageDeliveryMinutes = if (completedDurations.isEmpty()) 0 else completedDurations.average().toInt(),
            riderPerformances = riderStats,
            reportRows = deliveryReportRows
        )

        // 5. Product Analytics
        val allProducts = aggregationStationIds.flatMap { id ->
            productRepository.observeProducts(id).firstOrNull() ?: emptyList()
        }
        val productSalesMap = mutableMapOf<Long, Pair<Int, Double>>() // productId -> (qty, revenue)

        allOrders.filter { it.orderStatus == OrderStatus.COMPLETED || it.orderStatus == OrderStatus.DELIVERED }.forEach { ord ->
            val paidAmount = currentPaidByOrder[ord.id] ?: 0.0
            if (paidAmount <= 0.0 || ord.total <= 0.0) return@forEach
            val paidRatio = (paidAmount / ord.total).coerceIn(0.0, 1.0)
            ord.items.forEach { item ->
                val pid = item.productId ?: 0L
                val current = productSalesMap.getOrDefault(pid, Pair(0, 0.0))
                productSalesMap[pid] = Pair(current.first + item.quantity, current.second + item.subtotal * paidRatio)
            }
        }

        val topProducts = productSalesMap.mapNotNull { (pid, pair) ->
            val prod = allProducts.find { it.id == pid }
            TopProductItem(
                productId = pid,
                name = prod?.name ?: "Product #$pid",
                type = prod?.type ?: ProductType.REFILL,
                quantitySold = pair.first,
                totalRevenue = pair.second
            )
        }.sortedByDescending { it.totalRevenue }.take(5)

        val productAnalytics = ProductAnalytics(topProducts = topProducts)

        // 6. Consolidated Multi-Station Analytics (Pro Entitlement)
        var consolidated: ConsolidatedStationAnalytics? = null
        var isProConsolidated = false

        if (consolidatedAllowed) {
            isProConsolidated = true
                val stationSummaries = mutableListOf<StationSummary>()
                val salesByStation = netSalesByDimension(currentPaidByOrder, ordersById) { it.stationId }
                var grandTotalSales = 0.0
                var grandTotalOrders = 0

                aggregationStations.forEach { st ->
                    val stOrders = ordersByStation[st.id].orEmpty()
                    val stPeriodOrders = stOrders.filter { it.createdAt >= startTs && it.createdAt < endTs }
                    val stCompleted = stPeriodOrders.filter { it.orderStatus == OrderStatus.COMPLETED || it.orderStatus == OrderStatus.DELIVERED }
                    val stSales = salesByStation[st.id] ?: 0.0

                    grandTotalSales += stSales
                    grandTotalOrders += stPeriodOrders.size

                    stationSummaries.add(
                        StationSummary(
                            stationId = st.id,
                            stationName = st.name,
                            totalRevenue = stSales,
                            totalOrders = stPeriodOrders.size,
                            completedOrders = stCompleted.size
                        )
                    )
                }

                consolidated = ConsolidatedStationAnalytics(
                    totalRevenue = grandTotalSales,
                    totalOrders = grandTotalOrders,
                    stationSummaries = stationSummaries
                )
        }

        return FullBusinessAnalytics(
            stationId = stationId,
            dateRange = dateRange,
            startTimestamp = startTs,
            endTimestamp = endTs,
            revenue = revenueAnalytics,
            orders = orderAnalytics,
            customers = if (canCustomers) customerAnalytics else CustomerAnalytics(),
            delivery = if (canDelivery) deliveryAnalytics else DeliveryAnalytics(averageDeliveryMinutes = 0),
            products = if (canAdvanced) productAnalytics else ProductAnalytics(),
            consolidated = consolidated,
            isProConsolidated = isProConsolidated
        )
    }
}

internal fun buildDayWindows(startTimestamp: Long, endTimestamp: Long): List<Pair<Long, Long>> {
    if (startTimestamp >= endTimestamp) return emptyList()
    val cursor = Calendar.getInstance().apply { timeInMillis = startTimestamp }
    val windows = mutableListOf<Pair<Long, Long>>()
    while (cursor.timeInMillis < endTimestamp) {
        val dayStart = cursor.timeInMillis
        cursor.add(Calendar.DAY_OF_YEAR, 1)
        windows += dayStart to minOf(cursor.timeInMillis, endTimestamp)
    }
    return windows
}

internal fun paidReceiptsBetween(payments: List<Payment>, start: Long, end: Long): List<Payment> =
    payments.filter { payment ->
        if (payment.status != PaymentStatus.PAID) return@filter false
        val paidAt = payment.paidAt ?: payment.createdAt
        paidAt >= start && paidAt < end
    }

internal fun netPaidAmountsByOrder(
    receipts: List<Payment>,
    refunds: List<PaymentRefund>,
    start: Long,
    end: Long
): Map<Long, Double> {
    val collectedByOrder = paidReceiptsBetween(receipts, start, end)
        .groupBy { it.orderId }
        .mapValues { (_, rows) -> rows.sumOf { it.amount } }
    val refundedByOrder = refunds.asSequence()
        .filter { refund ->
            refund.status == RefundStatus.COMPLETED && refund.completedAt?.let { it >= start && it < end } == true
        }
        .groupBy { it.orderId }
        .mapValues { (_, rows) -> rows.sumOf { it.amountCentavos } / 100.0 }
    return (collectedByOrder.keys + refundedByOrder.keys).associateWith { orderId ->
        collectedByOrder.getOrDefault(orderId, 0.0) - refundedByOrder.getOrDefault(orderId, 0.0)
    }
}

internal fun netSalesByDimension(
    netByOrder: Map<Long, Double>,
    ordersById: Map<Long, Order>,
    dimensionId: (Order) -> Long?
): Map<Long, Double> = netByOrder.entries.mapNotNull { (orderId, amount) ->
    val order = ordersById[orderId] ?: return@mapNotNull null
    dimensionId(order)?.let { it to amount }
}.groupingBy { it.first }.fold(0.0) { total, row -> total + row.second }

internal fun isSalesEligibleOrder(order: Order): Boolean =
    order.orderStatus != OrderStatus.CANCELLED && order.orderStatus != OrderStatus.REJECTED
