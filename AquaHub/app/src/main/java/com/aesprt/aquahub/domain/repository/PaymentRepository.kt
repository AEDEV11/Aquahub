package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.Payment
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.PaymentRefund
import com.aesprt.aquahub.domain.model.RefundObligationSummary
import kotlinx.coroutines.flow.Flow

interface PaymentRepository {
    fun observePaymentsForCustomer(customerId: Long): Flow<List<Payment>>
    suspend fun getPaymentsForOrder(orderId: Long): List<Payment>
    /** Refreshes server-authoritative receipts into the local cache when available. */
    suspend fun refreshPaymentsForOrder(orderId: Long): List<Payment> = getPaymentsForOrder(orderId)
    /** Refreshes receipts for a report batch before it derives financial totals. */
    suspend fun refreshPaymentsForOrders(orderIds: List<Long>): List<Payment> {
        if (orderIds.isEmpty()) return emptyList()
        orderIds.distinct().forEach { refreshPaymentsForOrder(it) }
        return getPaymentsForOrders(orderIds.distinct())
    }
    suspend fun getPaymentsForOrders(orderIds: List<Long>): List<Payment>
    suspend fun getRefundsForOrder(orderId: Long): List<PaymentRefund>
    suspend fun getRefundsForOrders(orderIds: List<Long>): List<PaymentRefund>
    suspend fun getRefundObligationReport(stationId: Long, startAt: Long, endAt: Long): List<RefundObligationSummary>
    suspend fun requestRefund(paymentId: Long, amountCentavos: Long, reason: String): PaymentRefund
    suspend fun completeRefund(refundId: String, actualRefundConfirmed: Boolean, externalReference: String?): PaymentRefund
    suspend fun failRefund(refundId: String, failureReason: String): PaymentRefund
    suspend fun recordPayment(
        orderId: Long,
        amount: Double,
        method: PaymentMethod,
        referenceNumber: String? = null,
        deliveryProofObjectPath: String? = null,
        requireRemoteConfirmation: Boolean = false
    ): Payment
}
