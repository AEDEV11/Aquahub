package com.aesprt.aquahub.data.supabase.inventory

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SupabaseInventoryBalance(
    val id: String,
    @SerialName("station_id") val stationId: String,
    @SerialName("product_id") val productId: String,
    val quantity: Int,
    val version: Long
)

@Serializable
data class SupabaseInventoryMovement(
    val id: String,
    @SerialName("station_id") val stationId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("operation_id") val operationId: String,
    val delta: Int,
    @SerialName("quantity_after") val quantityAfter: Int,
    val reason: String,
    @SerialName("movement_type") val movementType: String,
    @SerialName("source_id") val sourceId: String,
    @SerialName("created_at") val createdAt: String
)

/** Supabase is the authority for product stock; Room only caches the result. */
class SupabaseInventoryDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun balances(stationId: String): List<SupabaseInventoryBalance> =
        supabase.from("inventory_balances").select {
            filter { eq("station_id", stationId) }
        }.decodeList()

    suspend fun history(stationId: String, productId: String? = null): List<SupabaseInventoryMovement> =
        supabase.from("inventory_movements").select {
            filter {
                eq("station_id", stationId)
                productId?.let { eq("product_id", it) }
            }
            order("created_at", order = io.github.jan.supabase.postgrest.query.Order.DESCENDING)
        }.decodeList()

    suspend fun adjust(
        stationId: String,
        productId: String,
        delta: Int,
        operationId: String,
        reason: String,
        movementType: String,
        sourceId: String,
        orderId: String? = null
    ): SupabaseInventoryBalance {
        require(delta != 0) { "Inventory delta must not be zero" }
        require(operationId.isNotBlank() && reason.isNotBlank()) { "Inventory operation metadata is required" }
        val result = supabase.postgrest.rpc(
            function = "adjust_inventory",
            parameters = buildJsonObject {
                put("p_station_id", stationId)
                put("p_product_id", productId)
                put("p_delta", delta)
                put("p_operation_id", operationId)
                put("p_reason", reason)
                put("p_order_id", orderId)
                put("p_movement_type", movementType)
                put("p_source_id", sourceId)
            }
        ).decodeList<InventoryAdjustmentResult>().single()
        return SupabaseInventoryBalance(
            id = result.balanceId,
            stationId = result.stationId,
            productId = result.productId,
            quantity = result.quantity,
            version = result.version
        )
    }

    /** Absolute stock target guarded by the balance's server revision. */
    suspend fun setQuantity(
        stationId: String,
        productId: String,
        quantity: Int,
        expectedVersion: Long,
        operationId: String,
        reason: String
    ): SupabaseInventoryBalance {
        require(quantity >= 0 && expectedVersion >= 0) { "Stock target and revision must be non-negative" }
        require(operationId.isNotBlank() && reason.isNotBlank()) { "Inventory operation metadata is required" }
        val result = supabase.postgrest.rpc(
            function = "set_inventory_quantity",
            parameters = buildJsonObject {
                put("p_station_id", stationId)
                put("p_product_id", productId)
                put("p_quantity", quantity)
                put("p_expected_version", expectedVersion)
                put("p_operation_id", operationId)
                put("p_reason", reason)
            }
        ).decodeList<SetInventoryQuantityResult>().single()
        return SupabaseInventoryBalance(
            id = result.balanceId,
            stationId = result.stationId,
            productId = result.productId,
            quantity = result.quantity,
            version = result.version
        )
    }

    @Serializable
    private data class InventoryAdjustmentResult(
        @SerialName("balance_id") val balanceId: String,
        @SerialName("station_id") val stationId: String,
        @SerialName("product_id") val productId: String,
        val quantity: Int,
        val version: Long,
        @SerialName("movement_id") val movementId: String,
        @SerialName("operation_id") val operationId: String
    )

    @Serializable
    private data class SetInventoryQuantityResult(
        @SerialName("balance_id") val balanceId: String = "",
        @SerialName("station_id") val stationId: String,
        @SerialName("product_id") val productId: String,
        val quantity: Int,
        val version: Long,
        @SerialName("operation_id") val operationId: String
    )
}
