package com.aesprt.aquahub.domain.export

import android.content.Context
import com.aesprt.aquahub.domain.model.CustomerAccountSnapshot

interface CustomerStatementExporter {
    suspend fun export(
        context: Context,
        stationName: String,
        snapshot: CustomerAccountSnapshot
    ): Result<ReportExportResult>
}
