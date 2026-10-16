package com.aesprt.aquahub.data.export

import android.content.Context
import com.aesprt.aquahub.domain.export.ReportExporter
import com.aesprt.aquahub.domain.export.ReportFormat
import com.aesprt.aquahub.domain.export.toContent
import com.aesprt.aquahub.domain.export.ReportExportRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvReportExporter : ReportExporter {
    override val format: ReportFormat = ReportFormat.CSV

    override suspend fun export(context: Context, request: ReportExportRequest) = withContext(Dispatchers.IO) {
        runCatching {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = request.createReportFile(reportsDir)
            val content = request.toContent()
            val generated = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val csv = StringBuilder("\uFEFF")
            fun row(vararg values: String) {
                csv.append(values.joinToString(",", transform = ::csvEscape)).append('\n')
            }
            row("AquaHub Water Station Report")
            row("Report", content.title)
            row("Station", request.stationName)
            row("Date Range", request.dateRange.displayName)
            row("Generated At", generated)
            csv.append('\n')
            row("Executive Summary")
            row("Metric", "Value")
            content.summary.forEach { metric -> row(metric.label, metric.value) }
            csv.append('\n')
            content.table.headers.let { headers -> row(*headers.toTypedArray()) }
            content.table.rows.forEach { cells -> row(*cells.map { it.value }.toTypedArray()) }
            content.table.totalRow?.let { cells -> row(*cells.map { it.value }.toTypedArray()) }
            FileOutputStream(file).use { output -> OutputStreamWriter(output, Charsets.UTF_8).use { it.write(csv.toString()) } }
            request.resultFor(file, format, context)
        }
    }
}
