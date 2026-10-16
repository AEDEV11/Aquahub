package com.aesprt.aquahub.domain.usecase.report

import com.aesprt.aquahub.domain.model.DashboardSummary
import com.aesprt.aquahub.domain.model.SalesSummary
import com.aesprt.aquahub.domain.model.TopProduct
import com.aesprt.aquahub.domain.repository.ReportRepository
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

class ObserveDashboardSummaryUseCase(private val reportRepository: ReportRepository) {
    operator fun invoke(stationId: Long, riderId: Long? = null): Flow<DashboardSummary> =
        reportRepository.observeDashboardSummary(stationId, riderId)
}

class GetTodaySalesUseCase(private val reportRepository: ReportRepository) {
    suspend operator fun invoke(stationId: Long): SalesSummary {
        val (start, end) = todayRange()
        return reportRepository.getTodaySales(stationId, start, end)
    }
}

class GetWeeklySalesUseCase(private val reportRepository: ReportRepository) {
    suspend operator fun invoke(stationId: Long): SalesSummary {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val weekStart = cal.timeInMillis
        val weekEnd = weekStart + 7 * 24 * 60 * 60 * 1000L
        return reportRepository.getWeeklySales(stationId, weekStart, weekEnd)
    }
}

class GetMonthlySalesUseCase(private val reportRepository: ReportRepository) {
    suspend operator fun invoke(stationId: Long): SalesSummary {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val monthStart = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val monthEnd = cal.timeInMillis
        return reportRepository.getMonthlySales(stationId, monthStart, monthEnd)
    }
}

class GetTopProductsUseCase(private val reportRepository: ReportRepository) {
    suspend operator fun invoke(stationId: Long, days: Int = 30, limit: Int = 5): List<TopProduct> {
        val end = System.currentTimeMillis()
        val start = end - days * 24 * 60 * 60 * 1000L
        return reportRepository.getTopProducts(stationId, start, end, limit)
    }
}

class GetDailySalesSeriesUseCase(private val reportRepository: ReportRepository) {
    suspend operator fun invoke(stationId: Long, days: Int = 7): List<Pair<String, Double>> =
        reportRepository.getDailySalesSeries(stationId, days)
}

private fun todayRange(): Pair<Long, Long> {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val start = cal.timeInMillis
    val end = start + 24 * 60 * 60 * 1000L
    return start to end
}
