package com.aesprt.aquahub.data.supabase.delivery

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SupabaseDeliveryMutation(
    @SerialName("order_id") val orderId: String,
    @SerialName("rider_id") val riderId: String? = null,
    val status: String,
    val created: Boolean
)

@Serializable
data class SupabaseDeliveryOrderRow(
    val id: String,
    @SerialName("client_operation_id") val clientOperationId: String? = null,
    @SerialName("station_id") val stationId: String,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("order_number") val orderNumber: String,
    @SerialName("customer_name") val customerName: String,
    @SerialName("customer_phone") val customerPhone: String = "",
    @SerialName("rider_id") val riderId: String? = null,
    @SerialName("delivery_address_id") val deliveryAddressId: String? = null,
    @SerialName("delivery_address") val deliveryAddress: String = "",
    @SerialName("delivery_latitude") val deliveryLatitude: Double? = null,
    @SerialName("delivery_longitude") val deliveryLongitude: Double? = null,
    @SerialName("delivery_mode") val deliveryMode: String = "DELIVERY",
    @SerialName("subtotal_centavos") val subtotalCentavos: Long,
    @SerialName("delivery_fee_centavos") val deliveryFeeCentavos: Long,
    @SerialName("discount_centavos") val discountCentavos: Long,
    @SerialName("total_centavos") val totalCentavos: Long,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("payment_status") val paymentStatus: String = "PENDING",
    val status: String,
    @SerialName("scheduled_delivery_start") val scheduledDeliveryStart: Long? = null,
    @SerialName("scheduled_delivery_end") val scheduledDeliveryEnd: Long? = null,
    @SerialName("customer_note") val customerNote: String? = null,
    @SerialName("station_note") val stationNote: String? = null,
    @SerialName("requested_at") val requestedAt: Long? = null,
    @SerialName("accepted_at") val acceptedAt: Long? = null,
    @SerialName("ready_at") val readyAt: Long? = null,
    @SerialName("assigned_at") val assignedAt: Long? = null,
    @SerialName("out_for_delivery_at") val outForDeliveryAt: Long? = null,
    @SerialName("delivered_at") val deliveredAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    @SerialName("cancelled_at") val cancelledAt: Long? = null,
    @SerialName("cancellation_reason") val cancellationReason: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

@Serializable
data class SupabaseDeliveryItemRow(
    val id: String,
    @SerialName("order_id") val orderId: String,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("product_name_snapshot") val productName: String,
    @SerialName("product_type_snapshot") val productType: String,
    @SerialName("size_label_snapshot") val sizeLabel: String,
    @SerialName("unit_price_centavos") val unitPriceCentavos: Long,
    val quantity: Int,
    @SerialName("subtotal_centavos") val subtotalCentavos: Long,
    @SerialName("container_source") val containerSource: String? = null
)

/** Foundation client for server-authoritative delivery assignment and transitions. */
class SupabaseDeliveryDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun loadStationOrders(stationId: String): List<SupabaseDeliveryOrderRow> {
        val rows = mutableListOf<SupabaseDeliveryOrderRow>()
        var first = 0L
        while (true) {
            val page = supabase.from("orders").select {
                filter { eq("station_id", stationId) }
                order("id", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                range(first..(first + READ_PAGE_SIZE - 1))
            }.decodeList<SupabaseDeliveryOrderRow>()
            rows += page
            if (page.size < READ_PAGE_SIZE) break
            first += READ_PAGE_SIZE
        }
        return rows.filter { it.deletedAt == null }
    }

    suspend fun loadOrderItems(orderIds: List<String>): List<SupabaseDeliveryItemRow> {
        if (orderIds.isEmpty()) return emptyList()
        val rows = mutableListOf<SupabaseDeliveryItemRow>()
        orderIds.distinct().chunked(MAX_ORDER_IDS_PER_QUERY).forEach { orderIdBatch ->
            var first = 0L
            while (true) {
                val page = supabase.from("order_items").select {
                    filter { isIn("order_id", orderIdBatch) }
                    order("id", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                    range(first..(first + READ_PAGE_SIZE - 1))
                }.decodeList<SupabaseDeliveryItemRow>()
                rows += page
                if (page.size < READ_PAGE_SIZE) break
                first += READ_PAGE_SIZE
            }
        }
        return rows
    }

    suspend fun assign(orderId: String, riderId: String, operationId: String): SupabaseDeliveryMutation =
        supabase.postgrest.rpc("assign_delivery", buildJsonObject {
            put("p_order_id", orderId)
            put("p_rider_id", riderId)
            put("p_operation_id", operationId)
        }).decodeList<SupabaseDeliveryMutation>().single()

    suspend fun transition(orderId: String, targetStatus: String, operationId: String): SupabaseDeliveryMutation =
        supabase.postgrest.rpc("transition_delivery_status", buildJsonObject {
            put("p_order_id", orderId)
            put("p_target_status", targetStatus)
            put("p_operation_id", operationId)
        }).decodeList<SupabaseDeliveryMutation>().single()

    private companion object {
        const val MAX_ORDER_IDS_PER_QUERY = 200
        const val READ_PAGE_SIZE = 500
    }
}
