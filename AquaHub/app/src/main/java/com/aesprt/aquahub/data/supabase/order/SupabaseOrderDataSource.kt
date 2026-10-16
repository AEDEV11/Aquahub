package com.aesprt.aquahub.data.supabase.order

import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.supabase.catalog.SupabaseCatalogDataSource
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderItem
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode

data class SupabaseCreatedOrder(
    @SerialName("order_id") val orderId: String,
    @SerialName("order_number") val orderNumber: String,
    @SerialName("subtotal_centavos") val subtotalCentavos: Long,
    @SerialName("delivery_fee_centavos") val deliveryFeeCentavos: Long,
    @SerialName("discount_centavos") val discountCentavos: Long,
    @SerialName("total_centavos") val totalCentavos: Long,
    val created: Boolean,
    val status: String,
    @SerialName("payment_status") val paymentStatus: String,
    @SerialName("requested_at") val requestedAt: Long? = null,
    @SerialName("accepted_at") val acceptedAt: Long? = null,
    @SerialName("ready_at") val readyAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    val items: List<OrderItem>,
)

@Serializable
private data class SupabaseCreateOrderResult(
    @SerialName("order_id") val orderId: String,
    @SerialName("order_number") val orderNumber: String,
    @SerialName("total_centavos") val totalCentavos: Long,
    val created: Boolean,
    val status: String,
)

@Serializable
private data class SupabaseCreatedOrderSnapshot(
    @SerialName("order_number") val orderNumber: String,
    @SerialName("subtotal_centavos") val subtotalCentavos: Long,
    @SerialName("delivery_fee_centavos") val deliveryFeeCentavos: Long,
    @SerialName("discount_centavos") val discountCentavos: Long,
    @SerialName("total_centavos") val totalCentavos: Long,
    @SerialName("payment_status") val paymentStatus: String,
    val status: String,
    @SerialName("requested_at") val requestedAt: Long? = null,
    @SerialName("accepted_at") val acceptedAt: Long? = null,
    @SerialName("ready_at") val readyAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
)

@Serializable
private data class SupabaseCreatedOrderItem(
    @SerialName("product_id") val productId: String? = null,
    @SerialName("product_name_snapshot") val productName: String,
    @SerialName("product_type_snapshot") val productType: String,
    @SerialName("size_label_snapshot") val sizeLabel: String,
    @SerialName("unit_price_centavos") val unitPriceCentavos: Long,
    val quantity: Int,
    @SerialName("subtotal_centavos") val subtotalCentavos: Long,
    @SerialName("container_source") val containerSource: String? = null,
)

@Serializable
data class SupabaseImportedOrder(
    @SerialName("order_id") val orderId: String,
    val created: Boolean
)

@Serializable
data class SupabaseCancelledOrder(
    @SerialName("order_id") val orderId: String,
    val status: String,
    @SerialName("already_cancelled") val alreadyCancelled: Boolean,
    @SerialName("refund_requested_centavos") val refundRequestedCentavos: Long,
    @SerialName("inventory_return_required") val inventoryReturnRequired: Boolean
)

/** Canonical owner walk-in order write. Inventory is reserved by create_order. */
class SupabaseOrderDataSource(
    private val syncDao: SyncDao,
    private val catalog: SupabaseCatalogDataSource,
) {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun createWalkInOrder(
        stationLocalId: Long,
        customerLocalId: Long,
        customerName: String,
        customerPhone: String,
        items: List<OrderItem>,
        paymentMethod: String,
        notes: String?,
        deliveryMode: String,
        deliveryAddress: String,
        deliveryLatitude: Double?,
        deliveryLongitude: Double?,
        deliveryFeeCentavos: Long,
        clientOperationId: String,
    ): SupabaseCreatedOrder {
        check(supabase.auth.currentUserOrNull() != null) { "Sign in before creating an order." }
        val station = syncDao.getMetadata("STATION", stationLocalId)?.remoteId
            ?: error("Station is not provisioned in Supabase.")
        val customer = if (customerLocalId > 0) {
            syncDao.getMetadata("CUSTOMER", customerLocalId)?.remoteId
                ?.let { reference -> catalog.resolveCustomerId(reference) }
        } else null
        val remoteItems = items.map { item ->
            val localProduct = requireNotNull(item.productId) { "Every item must reference a product." }
            val product = syncDao.getMetadata("PRODUCT", localProduct)?.remoteId
                ?.let { reference -> catalog.resolveProductId(reference) }
                ?: error("Product $localProduct is not provisioned in Supabase.")
            product to item.quantity
        }
        val created = supabase.postgrest.rpc("create_order", buildJsonObject {
            put("p_station_id", station)
            put("p_client_operation_id", clientOperationId)
            put("p_source", "OWNER_WALK_IN")
            put("p_customer_id", customer)
            put("p_customer_name", customerName)
            put("p_customer_phone", customerPhone)
            put("p_items", buildJsonArray { remoteItems.forEach { (id, quantity) -> add(buildJsonObject { put("product_id", id); put("quantity", quantity) }) } })
            put("p_delivery_mode", deliveryMode)
            put("p_delivery_address", deliveryAddress)
            put("p_delivery_place_id", null as String?)
            put("p_delivery_latitude", deliveryLatitude)
            put("p_delivery_longitude", deliveryLongitude)
            put("p_delivery_fee_centavos", deliveryFeeCentavos)
            put("p_payment_method", paymentMethod)
            put("p_customer_note", notes)
        }).decodeList<SupabaseCreateOrderResult>().single()
        // The RPC applies current server-side pricing/promotions. Read the
        // committed snapshot so Room and receipts never keep stale local prices
        // or an optimistic payment/order state.
        val snapshot = supabase.from("orders").select {
            filter { eq("id", created.orderId) }
        }.decodeList<SupabaseCreatedOrderSnapshot>().single()
        val persistedItems = supabase.from("order_items").select {
            filter { eq("order_id", created.orderId) }
        }.decodeList<SupabaseCreatedOrderItem>().map { item ->
            val productType = com.aesprt.aquahub.domain.model.ProductType.entries
                .firstOrNull { it.name.equals(item.productType, ignoreCase = true) }
                ?: error("Supabase returned an unsupported product type: ${item.productType}")
            OrderItem(
                productId = item.productId?.let { remoteId ->
                    syncDao.getMetadataByRemoteId("PRODUCT", remoteId)?.localId
                },
                productName = item.productName,
                productType = productType,
                sizeLabel = item.sizeLabel,
                unitPrice = item.unitPriceCentavos / 100.0,
                quantity = item.quantity,
                subtotal = item.subtotalCentavos / 100.0,
                containerSource = item.containerSource
            )
        }
        return SupabaseCreatedOrder(
            orderId = created.orderId,
            orderNumber = snapshot.orderNumber,
            subtotalCentavos = snapshot.subtotalCentavos,
            deliveryFeeCentavos = snapshot.deliveryFeeCentavos,
            discountCentavos = snapshot.discountCentavos,
            totalCentavos = snapshot.totalCentavos,
            created = created.created,
            status = snapshot.status,
            paymentStatus = snapshot.paymentStatus,
            requestedAt = snapshot.requestedAt,
            acceptedAt = snapshot.acceptedAt,
            readyAt = snapshot.readyAt,
            completedAt = snapshot.completedAt,
            items = persistedItems
        )
    }

    suspend fun cancelOrderWithRefunds(remoteOrderId: String, operationId: String, reason: String?): SupabaseCancelledOrder {
        check(supabase.auth.currentUserOrNull() != null) { "Sign in before cancelling an order." }
        return supabase.postgrest.rpc("cancel_order_with_refunds", buildJsonObject {
            put("p_order_id", remoteOrderId)
            put("p_operation_id", operationId)
            put("p_reason", reason?.take(300))
        }).decodeList<SupabaseCancelledOrder>().single()
    }

    /** Imports a legacy Room snapshot without re-reserving stock or recalculating prices. */
    suspend fun importLocalOrder(
        order: Order,
        orderId: String,
        items: List<OrderItem>
    ): SupabaseImportedOrder {
        check(supabase.auth.currentUserOrNull() != null) { "Sign in before syncing an order." }
        val stationId = syncDao.getMetadata("STATION", order.stationId)?.remoteId
            ?: error("Station is not provisioned in Supabase.")
        val customerId = order.customerId.takeIf { it > 0L }?.let { localId ->
            syncDao.getMetadata("CUSTOMER", localId)?.remoteId?.let { catalog.resolveCustomerId(it) }
        }
        val riderId = order.riderId?.let { localId ->
            syncDao.getMetadata("RIDER", localId)?.remoteId
                ?: error("Rider must sync before its assigned order.")
        }
        val addressId = order.deliveryAddressId?.let { localId ->
            syncDao.getMetadata("CUSTOMER_ADDRESS", localId)?.remoteId?.let { catalog.resolveCustomerAddressId(it) }
        }
        val remoteItems = items.map { item ->
            val productId = item.productId?.let { localId ->
                syncDao.getMetadata("PRODUCT", localId)?.remoteId?.let { catalog.resolveProductId(it) }
            }
            buildJsonObject {
                put("product_id", productId)
                put("product_name", item.productName)
                put("product_type", item.productType.name)
                put("size_label", item.sizeLabel)
                put("unit_price_centavos", item.unitPrice.toCentavos())
                put("quantity", item.quantity)
                put("subtotal_centavos", item.subtotal.toCentavos())
            }
        }
        return supabase.postgrest.rpc("import_legacy_order", buildJsonObject {
            put("p_order_id", orderId)
            put("p_station_id", stationId)
            put("p_order_number", order.orderNumber)
            put("p_customer_id", customerId)
            put("p_customer_name", order.customerName)
            put("p_customer_phone", order.customerPhone)
            put("p_rider_id", riderId)
            put("p_delivery_address_id", addressId)
            put("p_delivery_address", order.deliveryAddress)
            put("p_delivery_latitude", order.deliveryLatitude)
            put("p_delivery_longitude", order.deliveryLongitude)
            put("p_delivery_mode", order.deliveryMode)
            put("p_subtotal_centavos", order.subtotal.toCentavos())
            put("p_delivery_fee_centavos", order.deliveryFee.toCentavos())
            put("p_discount_centavos", order.discount.toCentavos())
            put("p_total_centavos", order.total.toCentavos())
            put("p_payment_method", order.paymentMethod.name)
            put("p_payment_status", order.paymentStatus.name)
            put("p_status", order.orderStatus.name)
            put("p_scheduled_delivery_start", order.scheduledDeliveryStart)
            put("p_scheduled_delivery_end", order.scheduledDeliveryEnd)
            put("p_customer_note", order.customerNote)
            put("p_station_note", order.stationNote)
            put("p_created_at", order.createdAt)
            put("p_accepted_at", order.acceptedAt)
            put("p_ready_at", order.readyAt)
            put("p_assigned_at", order.assignedAt)
            put("p_out_for_delivery_at", order.outForDeliveryAt)
            put("p_delivered_at", order.deliveredAt)
            put("p_completed_at", order.completedAt)
            put("p_cancelled_at", order.cancelledAt)
            put("p_cancellation_reason", order.cancellationReason)
            put("p_client_updated_at", order.updatedAt ?: order.createdAt)
            put("p_items", buildJsonArray { remoteItems.forEach { add(it) } })
        }).decodeList<SupabaseImportedOrder>().single()
    }

    /** Only owner-editable fields are mirrored through this fallback path. */
    suspend fun updateOwnerFields(remoteOrderId: String, order: Order) {
        if (order.paymentMethod.name == "CLIENT_ACCOUNT") {
            supabase.postgrest.rpc("charge_order_to_client_account", buildJsonObject {
                put("p_order_id", remoteOrderId)
            })
        }
        supabase.from("orders").update(buildJsonObject {
            put("station_note", order.stationNote)
        }) {
            filter { eq("id", remoteOrderId) }
        }
    }

    suspend fun rejectOrder(remoteOrderId: String, operationId: String) {
        check(supabase.auth.currentUserOrNull() != null) { "Sign in before rejecting an order." }
        supabase.postgrest.rpc("reject_order", buildJsonObject {
            put("p_order_id", remoteOrderId)
            put("p_operation_id", operationId)
        })
    }
}

private fun Double.toCentavos(): Long = BigDecimal.valueOf(this)
    .movePointRight(2)
    .setScale(0, RoundingMode.HALF_UP)
    .longValueExact()
