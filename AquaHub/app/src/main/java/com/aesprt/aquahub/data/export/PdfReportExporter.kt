package com.aesprt.aquahub.data.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.aesprt.aquahub.domain.export.ReportExportContent
import com.aesprt.aquahub.domain.export.ReportExportRequest
import com.aesprt.aquahub.domain.export.ReportExporter
import com.aesprt.aquahub.domain.export.ReportFormat
import com.aesprt.aquahub.domain.export.toContent
import com.aesprt.aquahub.domain.export.ReportTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class PdfReportExporter : ReportExporter {
    override val format: ReportFormat = ReportFormat.PDF

    override suspend fun export(context: Context, request: ReportExportRequest) = withContext(Dispatchers.IO) {
        runCatching {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = request.createReportFile(reportsDir)
            val content = request.toContent()
            val pageCount = pageCount(content.table)
            val document = PdfDocument()
            val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")).apply { maximumFractionDigits = 2 }
            val rows = content.table.rows + listOfNotNull(content.table.totalRow)
            var rowOffset = 0
            for (pageNumber in 1..pageCount) {
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                drawHeader(canvas, request, content, pageNumber, pageCount)
                val startY = if (pageNumber == 1) drawSummary(canvas, content, currency) else 130f
                val capacity = if (pageNumber == 1) 18 else 30
                val pageRows = rows.drop(rowOffset).take(capacity)
                drawTable(canvas, content.table, pageRows, startY)
                rowOffset += pageRows.size
                drawFooter(canvas, pageNumber, pageCount)
                document.finishPage(page)
            }
            FileOutputStream(file).use { document.writeTo(it) }
            document.close()
            request.resultFor(file, format, context)
        }
    }

    private fun pageCount(table: ReportTable): Int {
        val rowCount = table.rows.size + if (table.totalRow != null) 1 else 0
        return if (rowCount <= 18) 1 else 1 + ceil((rowCount - 18) / 30.0).toInt()
    }

    private fun drawHeader(canvas: Canvas, request: ReportExportRequest, content: ReportExportContent, page: Int, pages: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#0A192F")
        canvas.drawRect(0f, 0f, 595f, 104f, paint)
        paint.color = Color.parseColor("#00E5FF")
        paint.textSize = 21f
        paint.isFakeBoldText = true
        canvas.drawText("AquaHub", 36f, 37f, paint)
        paint.color = Color.WHITE
        paint.textSize = 14f
        canvas.drawText(content.title, 36f, 61f, paint)
        paint.isFakeBoldText = false
        paint.textSize = 10f
        canvas.drawText("Station: ${request.stationName}", 36f, 81f, paint)
        canvas.drawText("Period: ${request.dateRange.displayName}", 36f, 95f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}", 559f, 81f, paint)
        canvas.drawText("Page $page of $pages", 559f, 95f, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawSummary(canvas: Canvas, content: ReportExportContent, currency: NumberFormat): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#1E293B")
        paint.textSize = 12f
        paint.isFakeBoldText = true
        canvas.drawText("SUMMARY", 36f, 132f, paint)
        var y = 145f
        content.summary.forEachIndexed { index, metric ->
            val x = if (index % 2 == 0) 36f else 309f
            if (index % 2 == 0) {
                paint.color = Color.parseColor("#F1F5F9")
                canvas.drawRoundRect(RectF(x, y, x + 250f, y + 44f), 5f, 5f, paint)
            } else {
                paint.color = Color.parseColor("#F1F5F9")
                canvas.drawRoundRect(RectF(x, y, x + 250f, y + 44f), 5f, 5f, paint)
            }
            paint.color = Color.parseColor("#64748B")
            paint.textSize = 8f
            paint.isFakeBoldText = false
            canvas.drawText(metric.label.uppercase(Locale.US), x + 10f, y + 16f, paint)
            paint.color = Color.parseColor("#0F172A")
            paint.textSize = 13f
            paint.isFakeBoldText = true
            val value = if (metric.currency) currency.format(metric.value.toDoubleOrNull() ?: 0.0) else metric.value
            canvas.drawText(value.take(28), x + 10f, y + 34f, paint)
            if (index % 2 == 1 || index == content.summary.lastIndex) y += 52f
        }
        paint.color = Color.parseColor("#1E293B")
        paint.textSize = 12f
        paint.isFakeBoldText = true
        canvas.drawText(content.table.headers.joinToString(" • ").take(100), 36f, y + 10f, paint)
        return y + 22f
    }

    private fun drawTable(canvas: Canvas, table: ReportTable, rows: List<List<com.aesprt.aquahub.domain.export.ReportCell>>, startY: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = 36f
        val right = 559f
        val width = (right - left) / table.headers.size.coerceAtLeast(1)
        var y = startY
        paint.color = Color.parseColor("#DCEFF4")
        canvas.drawRect(left, y, right, y + 22f, paint)
        paint.color = Color.parseColor("#1E293B")
        paint.textSize = if (table.headers.size > 7) 6f else 8f
        paint.isFakeBoldText = true
        table.headers.forEachIndexed { index, header -> canvas.drawText(header.take(18), left + index * width + 4f, y + 15f, paint) }
        y += 23f
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex % 2 == 0) {
                paint.color = Color.parseColor("#F8FAFC")
                canvas.drawRect(left, y, right, y + 20f, paint)
            }
            paint.color = Color.parseColor("#334155")
            paint.textSize = if (table.headers.size > 7) 6f else 8f
            paint.isFakeBoldText = false
            row.forEachIndexed { index, cell ->
                val value = if (cell.currency) NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")).format(cell.value.toDoubleOrNull() ?: 0.0) else cell.value
                canvas.drawText(value.take(if (table.headers.size > 7) 12 else 24), left + index * width + 4f, y + 14f, paint)
            }
            y += 20f
        }
    }

    private fun drawFooter(canvas: Canvas, page: Int, pages: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor("#94A3B8")
        paint.textSize = 8f
        canvas.drawText("AquaHub Water Refilling Station Management Platform • Confidential", 36f, 814f, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Page $page of $pages", 559f, 814f, paint)
        paint.textAlign = Paint.Align.LEFT
    }
}
