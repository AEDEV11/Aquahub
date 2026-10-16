package com.aesprt.aquahub.data.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.aesprt.aquahub.domain.export.CustomerStatementExporter
import com.aesprt.aquahub.domain.export.ReportExportResult
import com.aesprt.aquahub.domain.model.CustomerAccountSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class PdfCustomerStatementExporter : CustomerStatementExporter {
    override suspend fun export(
        context: Context,
        stationName: String,
        snapshot: CustomerAccountSnapshot
    ): Result<ReportExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, "reports").apply { mkdirs() }
            val safeName = snapshot.customer.name.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "customer" }
            val file = File(directory, "statement-$safeName-${System.currentTimeMillis()}.pdf")
            val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))
            val date = SimpleDateFormat("MMM dd, yyyy", Locale.US)
            val rowsPerPage = 24
            val pages = maxOf(1, ceil(snapshot.entries.size / rowsPerPage.toDouble()).toInt())
            val document = PdfDocument()
            try {
                for (pageNumber in 1..pages) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                    val canvas = page.canvas
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    paint.color = Color.rgb(10, 25, 47)
                    canvas.drawRect(0f, 0f, 595f, 104f, paint)
                    paint.color = Color.rgb(0, 229, 255)
                    paint.textSize = 21f
                    paint.isFakeBoldText = true
                    canvas.drawText("AquaHub", 36f, 36f, paint)
                    paint.color = Color.WHITE
                    paint.textSize = 15f
                    canvas.drawText("STATEMENT OF ACCOUNT", 36f, 61f, paint)
                    paint.textSize = 9f
                    paint.isFakeBoldText = false
                    canvas.drawText(stationName.take(60), 36f, 81f, paint)
                    canvas.drawText("Generated ${date.format(Date())} • Page $pageNumber of $pages", 36f, 96f, paint)

                    var y = 130f
                    paint.color = Color.rgb(15, 23, 42)
                    paint.textSize = 14f
                    paint.isFakeBoldText = true
                    canvas.drawText(snapshot.customer.name.take(52), 36f, y, paint)
                    y += 18f
                    paint.textSize = 9f
                    paint.isFakeBoldText = false
                    canvas.drawText(listOf(snapshot.customer.phone, snapshot.customer.email).filterNotNull().joinToString(" • ").take(90), 36f, y, paint)
                    y += 28f

                    if (pageNumber == 1) {
                        paint.color = Color.rgb(241, 245, 249)
                        canvas.drawRoundRect(36f, y, 559f, y + 54f, 6f, 6f, paint)
                        paint.color = Color.rgb(71, 85, 105)
                        paint.textSize = 8f
                        canvas.drawText("TOTAL BILLED", 50f, y + 17f, paint)
                        canvas.drawText("PAYMENTS", 220f, y + 17f, paint)
                        canvas.drawText("BALANCE DUE", 390f, y + 17f, paint)
                        paint.color = Color.rgb(15, 23, 42)
                        paint.textSize = 13f
                        paint.isFakeBoldText = true
                        canvas.drawText(currency.format(snapshot.totalBilled), 50f, y + 40f, paint)
                        canvas.drawText(currency.format(snapshot.totalPaid), 220f, y + 40f, paint)
                        paint.color = if (snapshot.outstandingBalance > 0.005) Color.rgb(185, 28, 28) else Color.rgb(21, 128, 61)
                        canvas.drawText(currency.format(snapshot.outstandingBalance), 390f, y + 40f, paint)
                        y += 76f
                    }

                    paint.color = Color.rgb(220, 239, 244)
                    canvas.drawRect(36f, y, 559f, y + 23f, paint)
                    paint.color = Color.rgb(30, 41, 59)
                    paint.textSize = 8f
                    paint.isFakeBoldText = true
                    listOf("DATE", "TRANSACTION", "BILLED", "PAID", "BALANCE").forEachIndexed { index, header ->
                        canvas.drawText(header, 40f + index * 104f, y + 15f, paint)
                    }
                    y += 24f
                    snapshot.entries.drop((pageNumber - 1) * rowsPerPage).take(rowsPerPage).forEachIndexed { index, entry ->
                        if (index % 2 == 0) {
                            paint.color = Color.rgb(248, 250, 252)
                            canvas.drawRect(36f, y, 559f, y + 22f, paint)
                        }
                        paint.color = Color.rgb(51, 65, 85)
                        paint.textSize = 7.5f
                        paint.isFakeBoldText = false
                        val values = listOf(
                            date.format(Date(entry.order.createdAt)),
                            entry.order.orderNumber,
                            currency.format(entry.order.total),
                            currency.format(entry.paidAmount),
                            currency.format(entry.balance)
                        )
                        values.forEachIndexed { column, value -> canvas.drawText(value.take(18), 40f + column * 104f, y + 15f, paint) }
                        y += 22f
                    }
                    paint.color = Color.rgb(148, 163, 184)
                    paint.textSize = 8f
                    canvas.drawText("AquaHub • Customer account statement • Please keep for your records", 36f, 814f, paint)
                    document.finishPage(page)
                }
                FileOutputStream(file).use(document::writeTo)
            } finally {
                document.close()
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            ReportExportResult(file, uri, file.name, "application/pdf", file.length())
        }
    }
}
