package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.OfflineCashSaleRequest
import com.aesprt.aquahub.domain.model.OfflinePosReservationItem
import com.aesprt.aquahub.domain.model.OfflinePosRecoveryRecord
import com.aesprt.aquahub.domain.model.OfflinePosStatus
import kotlinx.coroutines.flow.Flow

interface OfflinePosRepository {
    suspend fun prepareReservation(stationId: Long, items: List<OfflinePosReservationItem>): OfflinePosStatus
    suspend fun getStatus(stationId: Long): OfflinePosStatus
    suspend fun recordCashSale(request: OfflineCashSaleRequest): Long
    suspend fun getRecoveryRecords(stationId: Long): List<OfflinePosRecoveryRecord>
    suspend fun retryReconciliation(operationId: String)
    fun observePendingCashSalesCentavos(stationId: Long, startMs: Long, endMs: Long): Flow<Long>
}
