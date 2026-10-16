package com.aesprt.aquahub.domain.export

import com.aesprt.aquahub.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportExportContentTest {
    private val analytics = FullBusinessAnalytics(
        stationId = 1L,
        dateRange = DateRangeFilter.THIS_MONTH,
        startTimestamp = 1L,
        endTimestamp = 2L,
        revenue = RevenueAnalytics(totalRevenue = 1250.0, dailyRevenueTrend = listOf(ChartDataPoint("Sep 1", 1250.0))),
        orders = OrderAnalytics(
            totalOrders = 2,
            completedOrders = 1,
            reportRows = listOf(
                OrderReportRow("ORD-1", "Ana", "DELIVERY", "Refill x1", 1, 1250.0, "Completed", 1L, 2L, "Cash")
            )
        ),
        customers = CustomerAnalytics(
            totalCustomers = 1,
            activeCustomersCount = 1,
            reportRows = listOf(CustomerReportRow(1L, "Ana", "0917", 2, 1250.0, 1L, 625.0))
        ),
        delivery = DeliveryAnalytics(
            totalDeliveries = 1,
            completedDeliveries = 1,
            reportRows = listOf(DeliveryReportRow("ORD-1", "Rider", 1L, 2L, "Completed", 1))
        ),
        products = ProductAnalytics(
            topProducts = listOf(TopProductItem(1L, "Refill", ProductType.REFILL, 1, 1250.0))
        )
    )

    private fun request(type: ReportType, format: ReportFormat = ReportFormat.CSV) = ReportExportRequest(
        stationId = 1L, stationName = "Main Station", reportType = type, format = format,
        dateRange = DateRangeFilter.THIS_MONTH, analytics = analytics
    )

    @Test
    fun `report types select different headers and datasets`() {
        val sales = request(ReportType.SALES_AND_REVENUE).toContent()
        val orders = request(ReportType.ORDERS_LIST).toContent()
        val customers = request(ReportType.CUSTOMER_INSIGHTS).toContent()
        assertNotEquals(sales.table.headers, orders.table.headers)
        assertNotEquals(orders.table.headers, customers.table.headers)
        assertEquals("ORD-1", orders.table.rows.single().first().value)
        assertEquals("Ana", customers.table.rows.single().first().value)
        assertEquals("Sep 1", sales.table.rows.single().first().value)
    }

    @Test
    fun `changing format preserves report content`() {
        val csv = request(ReportType.SALES_AND_REVENUE, ReportFormat.CSV).toContent()
        val xlsx = request(ReportType.SALES_AND_REVENUE, ReportFormat.EXCEL).toContent()
        val pdf = request(ReportType.SALES_AND_REVENUE, ReportFormat.PDF).toContent()
        assertEquals(csv.title, xlsx.title)
        assertEquals(csv.table.headers, pdf.table.headers)
        assertEquals(csv.table.rows, xlsx.table.rows)
    }

    @Test
    fun `different report types retain different summaries`() {
        val delivery = request(ReportType.DELIVERY_AND_RIDERS).toContent()
        val products = request(ReportType.TOP_PRODUCTS).toContent()
        assertTrue(delivery.summary.any { it.label == "Deliveries" })
        assertTrue(products.summary.any { it.label == "Units Sold" })
        assertNotEquals(delivery.summary, products.summary)
    }
}
