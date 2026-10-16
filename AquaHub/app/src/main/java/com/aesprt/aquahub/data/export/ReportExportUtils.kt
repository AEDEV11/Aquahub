package com.aesprt.aquahub.data.export

import com.aesprt.aquahub.domain.export.ReportExportRequest
import com.aesprt.aquahub.domain.export.ReportExportResult
import com.aesprt.aquahub.domain.export.ReportFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

internal fun ReportExportRequest.reportSlug(): String = when {
    analytics.isProConsolidated -> "consolidated"
    else -> when (reportType) {
        com.aesprt.aquahub.domain.export.ReportType.FULL_BUSINESS_SUMMARY -> "business_summary"
        com.aesprt.aquahub.domain.export.ReportType.SALES_AND_REVENUE -> "sales"
        com.aesprt.aquahub.domain.export.ReportType.ORDERS_LIST -> "orders"
        com.aesprt.aquahub.domain.export.ReportType.CUSTOMER_INSIGHTS -> "customer"
        com.aesprt.aquahub.domain.export.ReportType.DELIVERY_AND_RIDERS -> "delivery"
        com.aesprt.aquahub.domain.export.ReportType.TOP_PRODUCTS -> "product_performance"
    }
}

internal fun ReportExportRequest.createReportFile(reportsDir: File): File {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val start = day.format(Date(analytics.startTimestamp))
    val end = day.format(Date(analytics.endTimestamp - 1L))
    val generated = SimpleDateFormat("HHmmssSSS", Locale.US).format(Date())
    val fileName = "aquahub_${reportSlug()}_report_${start}_to_${end}_$generated.${format.extension}"
    return File(reportsDir, fileName)
}

internal fun csvEscape(value: String): String {
    val escaped = value.replace("\"", "\"\"")
    return if (escaped.any { it == ',' || it == '\n' || it == '\r' || it == '\"' }) "\"$escaped\"" else escaped
}

internal fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

internal fun ReportExportRequest.resultFor(file: File, format: ReportFormat, context: android.content.Context): ReportExportResult {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return ReportExportResult(file, uri, file.name, format.mimeType, file.length())
}
