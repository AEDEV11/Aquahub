package com.aesprt.aquahub.data.supabase.payment

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.domain.model.PaymentStatus
import com.aesprt.aquahub.domain.model.RefundObligationSummary
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SupabasePaymentRow(
    val id: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("amount_centavos") val amountCentavos: Long,
    val status: String,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("reference_number") val referenceNumber: String? = null,
    val provider: String? = null,
    @SerialName("provider_transaction_id") val providerTransactionId: String? = null,
    @SerialName("metadata") val metadata: JsonElement? = null,
    @SerialName("operation_id") val operationId: String? = null,
    @SerialName("created_at") val createdAtText: String,
    @SerialName("paid_at") val paidAtText: String? = null
) {
    val paymentStatus: PaymentStatus get() = PaymentStatus.valueOf(status)
    val createdAt: Long get() = java.time.Instant.parse(createdAtText).toEpochMilli()
    val paidAt: Long? get() = paidAtText?.let { java.time.Instant.parse(it).toEpochMilli() }
    val metadataJson: String? get() = metadata?.toString()
}

@Serializable
data class SupabaseRecordedPayment(
    @SerialName("payment_id") val paymentId: String,
    @SerialName("order_id") val orderId: String,
    val status: String,
    @SerialName("amount_centavos") val amountCentavos: Long,
    val created: Boolean
)

@Serializable
data class SupabasePaymentRefundRow(
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("refund_id") val refundId: String,
    @SerialName("original_payment_id") val originalPaymentId: String? = null,
    @SerialName("operation_id") val operationId: String? = null,
    @SerialName("amount_centavos") val amountCentavos: Long,
    val status: String,
    @SerialName("payment_method") val paymentMethod: String,
    val reason: String,
    @SerialName("external_reference") val externalReference: String? = null,
    @SerialName("failure_reason") val failureReason: String? = null,
    @SerialName("created_at") val createdAtText: String,
    @SerialName("completed_at") val completedAtText: String? = null
) {
    val createdAt: Long get() = java.time.Instant.parse(createdAtText).toEpochMilli()
    val completedAt: Long? get() = completedAtText?.let { java.time.Instant.parse(it).toEpochMilli() }
}

@Serializable
data class SupabaseRefundMutationResult(
    @SerialName("refund_id") val refundId: String,
    val status: String,
    @SerialName("amount_centavos") val amountCentavos: Long? = null,
    @SerialName("created") val created: Boolean,
    @SerialName("completed_at") val completedAtText: String? = null,
    @SerialName("failed_at") val failedAtText: String? = null
)

@Serializable
data class SupabaseRefundObligationRow(
    @SerialName("organization_id") val organizationId: String,
    @SerialName("organization_name") val organizationName: String,
    @SerialName("station_id") val stationId: String,
    @SerialName("station_name") val stationName: String,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("refund_status") val refundStatus: String,
    @SerialName("period_start") val periodStartText: String,
    @SerialName("period_end") val periodEndText: String,
    @SerialName("requested_count") val requestedCount: Long,
    @SerialName("requested_centavos") val requestedCentavos: Long,
    @SerialName("pending_count") val pendingCount: Long,
    @SerialName("pending_centavos") val pendingCentavos: Long,
    @SerialName("reserved_refundable_centavos") val reservedRefundableCentavos: Long,
    @SerialName("completed_count") val completedCount: Long,
    @SerialName("completed_centavos") val completedCentavos: Long,
    @SerialName("failed_attempt_count") val failedAttemptCount: Long,
    @SerialName("failed_attempt_centavos") val failedAttemptCentavos: Long,
    @SerialName("partial_payment_count") val partialPaymentCount: Long,
    @SerialName("partial_refund_centavos") val partialRefundCentavos: Long,
    @SerialName("oldest_pending_at") val oldestPendingAtText: String? = null,
    @SerialName("pending_under_24h_count") val pendingUnder24HoursCount: Long,
    @SerialName("pending_under_24h_centavos") val pendingUnder24HoursCentavos: Long,
    @SerialName("pending_1_to_7d_count") val pending1To7DaysCount: Long,
    @SerialName("pending_1_to_7d_centavos") val pending1To7DaysCentavos: Long,
    @SerialName("pending_8_to_30d_count") val pending8To30DaysCount: Long,
    @SerialName("pending_8_to_30d_centavos") val pending8To30DaysCentavos: Long,
    @SerialName("pending_over_30d_count") val pendingOver30DaysCount: Long,
    @SerialName("pending_over_30d_centavos") val pendingOver30DaysCentavos: Long
) {
    fun toDomain(): RefundObligationSummary = RefundObligationSummary(
        organizationId = organizationId,
        organizationName = organizationName,
        stationId = stationId,
        stationName = stationName,
        paymentMethod = paymentMethod,
        refundStatus = refundStatus,
        periodStart = java.time.Instant.parse(periodStartText).toEpochMilli(),
        periodEnd = java.time.Instant.parse(periodEndText).toEpochMilli(),
        requestedCount = requestedCount,
        requestedCentavos = requestedCentavos,
        pendingCount = pendingCount,
        pendingCentavos = pendingCentavos,
        reservedRefundableCentavos = reservedRefundableCentavos,
        completedCount = completedCount,
        completedCentavos = completedCentavos,
        failedAttemptCount = failedAttemptCount,
        failedAttemptCentavos = failedAttemptCentavos,
        partialPaymentCount = partialPaymentCount,
        partialRefundCentavos = partialRefundCentavos,
        oldestPendingAt = oldestPendingAtText?.let { java.time.Instant.parse(it).toEpochMilli() },
        pendingUnder24HoursCount = pendingUnder24HoursCount,
        pendingUnder24HoursCentavos = pendingUnder24HoursCentavos,
        pending1To7DaysCount = pending1To7DaysCount,
        pending1To7DaysCentavos = pending1To7DaysCentavos,
        pending8To30DaysCount = pending8To30DaysCount,
        pending8To30DaysCentavos = pending8To30DaysCentavos,
        pendingOver30DaysCount = pendingOver30DaysCount,
        pendingOver30DaysCentavos = pendingOver30DaysCentavos
    )
}

class SupabasePaymentDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun getPaymentForOrder(orderId: String): SupabasePaymentRow? =
        supabase.from("payments").select {
            filter { eq("order_id", orderId) }
            order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
        }.decodeList<SupabasePaymentRow>().firstOrNull()

    suspend fun listPaymentsForOrder(orderId: String): List<SupabasePaymentRow> =
        supabase.from("payments").select {
            filter { eq("order_id", orderId) }
            order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
        }.decodeList()

    /** Loads receipt rows in bounded batches so reports do not depend on opened order details. */
    suspend fun listPaymentsForOrders(orderIds: List<String>): List<SupabasePaymentRow> {
        require(orderIds.isNotEmpty() && orderIds.size <= MAX_ORDER_IDS_PER_QUERY)
        val rows = mutableListOf<SupabasePaymentRow>()
        var first = 0L
        while (true) {
            val page = supabase.from("payments").select {
                filter { isIn("order_id", orderIds) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                order("id", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                range(first..(first + PAYMENT_PAGE_SIZE - 1))
            }.decodeList<SupabasePaymentRow>()
            rows += page
            if (page.size < PAYMENT_PAGE_SIZE) break
            first += PAYMENT_PAGE_SIZE
        }
        return rows
    }

    suspend fun recordPayment(
        orderId: String,
        operationId: String,
        amountCentavos: Long,
        paymentMethod: String,
        referenceNumber: String?,
        deliveryProofObjectPath: String? = null
    ): SupabaseRecordedPayment = supabase.postgrest.rpc("record_payment", buildJsonObject {
        put("p_order_id", orderId)
        put("p_operation_id", operationId)
        put("p_amount_centavos", amountCentavos)
        put("p_payment_method", paymentMethod)
        put("p_reference_number", referenceNumber)
        if (deliveryProofObjectPath != null) {
            put("p_metadata", buildJsonObject {
                put("delivery_payment_proof_object_path", deliveryProofObjectPath)
            })
        }
    }).decodeList<SupabaseRecordedPayment>().single()

    suspend fun listOrderRefunds(remoteOrderId: String): List<SupabasePaymentRefundRow> =
        supabase.postgrest.rpc("list_order_refunds", buildJsonObject {
            put("p_order_id", remoteOrderId)
        }).decodeList()

    suspend fun listOrderRefundsForOrders(remoteOrderIds: List<String>): List<SupabasePaymentRefundRow> {
        require(remoteOrderIds.isNotEmpty() && remoteOrderIds.size <= 200)
        return supabase.postgrest.rpc("list_order_refunds_for_orders", buildJsonObject {
            put("p_order_ids", buildJsonArray { remoteOrderIds.forEach { add(JsonPrimitive(it)) } })
        }).decodeList()
    }

    suspend fun refundObligationReport(
        remoteStationId: String,
        startAt: Long,
        endAt: Long
    ): List<SupabaseRefundObligationRow> = supabase.postgrest.rpc("refund_obligation_report", buildJsonObject {
        put("p_station_id", remoteStationId)
        put("p_start_at", java.time.Instant.ofEpochMilli(startAt).toString())
        put("p_end_at", java.time.Instant.ofEpochMilli(endAt).toString())
    }).decodeList()

    suspend fun requestRefund(
        paymentId: String,
        operationId: String,
        amountCentavos: Long,
        reason: String
    ): SupabaseRefundMutationResult = supabase.postgrest.rpc("request_payment_refund", buildJsonObject {
        put("p_payment_id", paymentId)
        put("p_operation_id", operationId)
        put("p_amount_centavos", amountCentavos)
        put("p_reason", reason)
    }).decodeList<SupabaseRefundMutationResult>().single()

    suspend fun completeRefund(
        refundId: String,
        operationId: String,
        actualRefundConfirmed: Boolean,
        externalReference: String?
    ): SupabaseRefundMutationResult = supabase.postgrest.rpc("complete_payment_refund", buildJsonObject {
        put("p_refund_id", refundId)
        put("p_operation_id", operationId)
        put("p_actual_refund_confirmed", actualRefundConfirmed)
        put("p_external_reference", externalReference)
    }).decodeList<SupabaseRefundMutationResult>().single()

    suspend fun failRefund(
        refundId: String,
        operationId: String,
        failureReason: String
    ): SupabaseRefundMutationResult = supabase.postgrest.rpc("fail_payment_refund", buildJsonObject {
        put("p_refund_id", refundId)
        put("p_operation_id", operationId)
        put("p_failure_reason", failureReason)
    }).decodeList<SupabaseRefundMutationResult>().single()

    private companion object {
        const val MAX_ORDER_IDS_PER_QUERY = 200
        const val PAYMENT_PAGE_SIZE = 500
    }
}
