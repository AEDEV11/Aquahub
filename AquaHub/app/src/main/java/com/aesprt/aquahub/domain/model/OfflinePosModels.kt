package com.aesprt.aquahub.domain.model

data class OfflineLeaseProductSnapshot(
    val productLocalId: Long,
    val productRemoteId: String,
    val productName: String,
    val productType: ProductType,
    val sizeLabel: String,
    val quantityRemaining: Int,
    val unitPriceCentavos: Long
)

data class OfflinePosStatus(
    val grantExpiresAtMs: Long? = null,
    val leaseExpiresAtMs: Long? = null,
    val productSnapshots: Map<Long, OfflineLeaseProductSnapshot> = emptyMap(),
    val pendingCashSalesCentavos: Long = 0L,
    val pendingOperationCount: Int = 0,
    val state: String = "NOT_PREPARED",
    val message: String? = null,
    val trustedServerNowMs: Long? = null
) {
    val canCreateOfflineSale: Boolean get() = state == "READY"
}

data class OfflinePosReservationItem(val productLocalId: Long, val quantity: Int)

data class OfflinePosRecoveryLine(
    val productName: String,
    val sizeLabel: String,
    val quantity: Int,
    val unitPriceCentavos: Long,
    val lineTotalCentavos: Long,
)

data class OfflinePosRecoveryRecord(
    val operationId: String,
    val transactionAtMs: Long,
    val totalCentavos: Long,
    val cashReceivedCentavos: Long,
    val changeCentavos: Long,
    val state: String,
    val lastErrorCode: String?,
    val lastError: String?,
    val lines: List<OfflinePosRecoveryLine>,
)

data class OfflineCashSaleRequest(
    val stationId: Long,
    val customerId: Long,
    val customerName: String,
    val customerPhone: String,
    val notes: String?,
    val items: List<OfflinePosReservationItem>,
    val cashReceivedCentavos: Long?
)
