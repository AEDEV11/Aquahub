package com.aesprt.aquahub.domain.export

import android.content.Context
import android.net.Uri
import com.aesprt.aquahub.domain.model.DateRangeFilter
import com.aesprt.aquahub.domain.model.FullBusinessAnalytics
import java.io.File

enum class ReportFormat(val displayName: String, val extension: String, val mimeType: String) {
    CSV("CSV Spreadsheet", "csv", "text/csv"),
    EXCEL("Excel Workbook", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("PDF Document", "pdf", "application/pdf")
}

enum class ReportType(val displayName: String) {
    FULL_BUSINESS_SUMMARY("Full Business Summary"),
    SALES_AND_REVENUE("Sales & Revenue Trend"),
    ORDERS_LIST("Orders & Fulfillment"),
    CUSTOMER_INSIGHTS("Customer Directory & Spenders"),
    DELIVERY_AND_RIDERS("Delivery & Rider Performance"),
    TOP_PRODUCTS("Product Performance")
}

data class ReportExportRequest(
    val stationId: Long,
    val stationName: String,
    val reportType: ReportType,
    val format: ReportFormat,
    val dateRange: DateRangeFilter,
    val analytics: FullBusinessAnalytics
)

data class ReportExportResult(
    val file: File,
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long
)

interface ReportExporter {
    val format: ReportFormat
    suspend fun export(context: Context, request: ReportExportRequest): Result<ReportExportResult>
}
