package com.aesprt.aquahub.data.export

import android.content.Context
import com.aesprt.aquahub.domain.export.ReportExportRequest
import com.aesprt.aquahub.domain.export.ReportExporter
import com.aesprt.aquahub.domain.export.ReportFormat
import com.aesprt.aquahub.domain.export.toContent
import com.aesprt.aquahub.domain.export.ReportCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes an OOXML package, so the result is a real .xlsx workbook. */
class ExcelReportExporter : ReportExporter {
    override val format: ReportFormat = ReportFormat.EXCEL

    override suspend fun export(context: Context, request: ReportExportRequest) = withContext(Dispatchers.IO) {
        runCatching {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val file = request.createReportFile(reportsDir)
            val content = request.toContent()
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                zipEntry(zip, "[Content_Types].xml", contentTypes())
                zipEntry(zip, "_rels/.rels", rootRelationships())
                zipEntry(zip, "xl/workbook.xml", workbook(content.worksheetName))
                zipEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelationships())
                zipEntry(zip, "xl/styles.xml", styles())
                zipEntry(zip, "xl/worksheets/sheet1.xml", worksheet(request, content))
            }
            request.resultFor(file, format, context)
        }
    }

    private fun worksheet(request: ReportExportRequest, content: com.aesprt.aquahub.domain.export.ReportExportContent): String {
        val rows = mutableListOf<List<ReportCell>>()
        rows += listOf(ReportCell(content.title))
        rows += listOf(ReportCell("Station"), ReportCell(request.stationName))
        rows += listOf(ReportCell("Date Range"), ReportCell(request.dateRange.displayName))
        rows += listOf(ReportCell("Generated At"), ReportCell(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())))
        rows.add(emptyList())
        rows += listOf(ReportCell("Executive Summary"))
        rows += listOf(ReportCell("Metric"), ReportCell("Value"))
        rows.addAll(content.summary.map { listOf(ReportCell(it.label), ReportCell(it.value, it.numeric, it.currency)) })
        rows.add(emptyList())
        rows += listOf(ReportCell(content.title))
        rows += listOf(cellsFromText(content.table.headers))
        rows.addAll(content.table.rows.map { cells -> cellsFrom(cells) })
        content.table.totalRow?.let { rows += cellsFrom(it) }

        val maxColumns = rows.maxOfOrNull { it.size } ?: 1
        val widths = (0 until maxColumns).map { column ->
            (rows.maxOfOrNull { row -> row.getOrNull(column)?.value?.length ?: 0 } ?: 12)
                .coerceIn(12, 34) * 7
        }
        val headerRows = rows.withIndex()
            .filter { (_, row) -> row.map { it.value } == content.table.headers || row.map { it.value } == listOf("Metric", "Value") }
            .map { it.index }
            .toSet()
        val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        xml.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><cols>")
        widths.forEachIndexed { index, width -> xml.append("<col min=\"${index + 1}\" max=\"${index + 1}\" width=\"${width / 7}\" customWidth=\"1\"/>") }
        xml.append("</cols><sheetData>")
        rows.forEachIndexed { rowIndex, row ->
            xml.append("<row r=\"${rowIndex + 1}\">")
            row.forEachIndexed { colIndex, cell ->
                xml.append(xlsxCell(colIndex, rowIndex, cell, rowIndex == 0, rowIndex in headerRows))
            }
            xml.append("</row>")
        }
        xml.append("</sheetData></worksheet>")
        return xml.toString()
    }

    private fun xlsxCell(column: Int, row: Int, cell: ReportCell, title: Boolean, header: Boolean): String {
        val ref = "${columnName(column)}${row + 1}"
        val style = when {
            title -> 1
            header -> 2
            cell.currency -> 3
            cell.numeric -> 4
            else -> 0
        }
        return if (cell.numeric && cell.value.isNotBlank()) {
            "<c r=\"$ref\" s=\"$style\"><v>${xmlEscape(cell.value)}</v></c>"
        } else {
            "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t>${xmlEscape(cell.value)}</t></is></c>"
        }
    }

    private fun columnName(index: Int): String {
        var value = index
        val result = StringBuilder()
        do {
            result.insert(0, ('A'.code + value % 26).toChar())
            value = value / 26 - 1
        } while (value >= 0)
        return result.toString()
    }

    private fun zipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""
    private fun rootRelationships() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    private fun workbook(sheet: String) = """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xmlEscape(sheet)}" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    private fun workbookRelationships() = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
    private fun styles() = """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;₱&quot;#,##0.00"/></numFmts><fonts count="2"><font><sz val="11"/><color rgb="FF0F172A"/><name val="Aptos"/></font><font><b/><sz val="14"/><color rgb="FF006699"/><name val="Aptos Display"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE2F5FA"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/><xf numFmtId="0" fontId="1" fillId="1" borderId="0"/><xf numFmtId="0" fontId="0" fillId="1" borderId="0" applyFill="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0"/><xf numFmtId="1" fontId="0" fillId="0" borderId="0"/></cellXfs></styleSheet>"""
}

private fun cellsFromText(values: List<String>) = values.map { ReportCell(it) }
private fun cellsFrom(values: List<ReportCell>) = values
