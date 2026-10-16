package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.DashboardSummary
import com.aesprt.aquahub.domain.model.SalesSummary
import com.aesprt.aquahub.domain.model.TopProduct
import kotlinx.coroutines.flow.Flow

interface ReportRepository {
    fun observeDashboardSummary(stationId: Long, riderId: Long? = null): Flow<DashboardSummary>
    suspend fun getTodaySales(stationId: Long, startOfDay: Long, endOfDay: Long): SalesSummary
    suspend fun getWeeklySales(stationId: Long, weekStart: Long, weekEnd: Long): SalesSummary
    suspend fun getMonthlySales(stationId: Long, monthStart: Long, monthEnd: Long): SalesSummary
    suspend fun getTopProducts(stationId: Long, startMs: Long, endMs: Long, limit: Int): List<TopProduct>
    suspend fun getDailySalesSeries(stationId: Long, days: Int): List<Pair<String, Double>>
}
