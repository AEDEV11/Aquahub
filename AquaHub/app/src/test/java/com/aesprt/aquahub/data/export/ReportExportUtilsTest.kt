package com.aesprt.aquahub.data.export

import com.aesprt.aquahub.domain.export.ReportExportRequest
import com.aesprt.aquahub.domain.export.ReportFormat
import com.aesprt.aquahub.domain.export.ReportType
import com.aesprt.aquahub.domain.model.DateRangeFilter
import com.aesprt.aquahub.domain.model.FullBusinessAnalytics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReportExportUtilsTest {
    @Test
    fun `csv and xml escaping protect delimiters and markup`() {
        assertEquals("\"Ana, Jr.\"", csvEscape("Ana, Jr."))
        assertEquals("\"line1\nline2\"", csvEscape("line1\nline2"))
        assertEquals("A &amp; B &lt;safe&gt; &quot;quoted&quot;", xmlEscape("A & B <safe> \"quoted\""))
    }

    @Test
    fun `report file names are safe and keep format extension`() {
        val analytics = FullBusinessAnalytics(
            stationId = 7L,
            dateRange = DateRangeFilter.THIS_MONTH,
            startTimestamp = 0L,
            endTimestamp = 86_400_000L
        )
        val dir = Files.createTempDirectory("aquahub-export-test").toFile()
        try {
            ReportFormat.entries.forEach { format ->
                val request = ReportExportRequest(
                    stationId = 7L,
                    stationName = "Main / Station",
                    reportType = ReportType.FULL_BUSINESS_SUMMARY,
                    format = format,
                    dateRange = DateRangeFilter.THIS_MONTH,
                    analytics = analytics
                )
                val file = request.createReportFile(dir)
                assertTrue(file.name.startsWith("aquahub_business_summary_report_"))
                assertTrue(file.name.endsWith(".${format.extension}"))
                assertTrue(file.name.none { it == '/' || it == '\\' })
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
