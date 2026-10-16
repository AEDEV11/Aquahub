package com.aesprt.aquahub.domain.usecase.export

import android.content.Context
import com.aesprt.aquahub.domain.export.*
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase

class ExportReportUseCase(
    private val checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase,
    private val exporters: Map<ReportFormat, ReportExporter>
) {
    suspend operator fun invoke(
        context: Context,
        request: ReportExportRequest
    ): Result<ReportExportResult> {
        val contentFeature = when (request.reportType) {
            ReportType.CUSTOMER_INSIGHTS -> SubscriptionFeature.CUSTOMER_INSIGHTS
            ReportType.DELIVERY_AND_RIDERS -> SubscriptionFeature.DELIVERY_ANALYTICS
            ReportType.FULL_BUSINESS_SUMMARY, ReportType.TOP_PRODUCTS -> SubscriptionFeature.ADVANCED_REPORTS
            else -> SubscriptionFeature.BASIC_REPORTS
        }
        if (checkSubscriptionAccessUseCase(request.stationId, SubscriptionFeature.DATA_EXPORT) !is SubscriptionAccessResult.Allowed ||
            checkSubscriptionAccessUseCase(request.stationId, contentFeature) !is SubscriptionAccessResult.Allowed ||
            (request.analytics.isProConsolidated && checkSubscriptionAccessUseCase(request.stationId, SubscriptionFeature.CONSOLIDATED_REPORTS) !is SubscriptionAccessResult.Allowed)) {
            return Result.failure(IllegalStateException("This report is not included in your subscription"))
        }
        // 1. Check Format Entitlement
        val requiredFeature = when (request.format) {
            ReportFormat.CSV -> SubscriptionFeature.EXPORT_CSV
            ReportFormat.EXCEL -> SubscriptionFeature.EXPORT_EXCEL
            ReportFormat.PDF -> SubscriptionFeature.EXPORT_PDF
        }

        val access = checkSubscriptionAccessUseCase(request.stationId, requiredFeature)
        if (access !is SubscriptionAccessResult.Allowed) {
            val planRequired = when (request.format) {
                ReportFormat.EXCEL -> "Business or Pro"
                ReportFormat.PDF -> "Business or Pro"
                ReportFormat.CSV -> "Starter, Business, or Pro"
            }
            return Result.failure(
                IllegalStateException("${request.format.displayName} export requires $planRequired plan.")
            )
        }

        // 2. Lookup Exporter Strategy
        val exporter = exporters[request.format]
            ?: return Result.failure(IllegalArgumentException("Exporter for format ${request.format} not found."))

        return exporter.export(context, request)
    }
}
