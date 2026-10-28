package com.aesprt.aquahub.data.supabase.inventory

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.domain.model.Container
import com.aesprt.aquahub.domain.model.ContainerTransaction
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@Serializable
data class SupabaseContainerRow(
    val id: String,
    @SerialName("station_id") val stationId: String,
    @SerialName("size_label") val sizeLabel: String,
    @SerialName("total_owned") val totalOwned: Int,
    val available: Int,
    @SerialName("with_customers") val withCustomers: Int,
    val damaged: Int,
    val lost: Int,
    @SerialName("deposit_price_centavos") val depositPriceCentavos: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class SupabaseContainerMovementRow(
    val id: String,
    @SerialName("firebase_id") val legacyId: String? = null,
    @SerialName("client_operation_id") val operationId: String? = null,
    @SerialName("station_id") val stationId: String,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("movement_type") val movementType: String,
    val quantity: Int,
    @SerialName("container_size") val containerSize: String,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null
)

/** Applies bottle movements atomically and reads the canonical station inventory. */
class SupabaseContainerDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun containers(stationId: String): List<SupabaseContainerRow> =
        supabase.from("containers").select {
            filter { eq("station_id", stationId) }
            order("size_label", order = io.github.jan.supabase.postgrest.query.Order.ASCENDING)
        }.decodeList()

    /** Reads the complete immutable movement ledger in stable, bounded pages. */
    suspend fun movements(stationId: String): List<SupabaseContainerMovementRow> = buildList {
        var offset = 0L
        while (true) {
            val page = supabase.from("container_movements").select {
                filter {
                    eq("station_id", stationId)
                    exact("deleted_at", null)
                }
                order("created_at", order = io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                order("id", order = io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                range(offset, offset + MOVEMENT_PAGE_SIZE - 1)
            }.decodeList<SupabaseContainerMovementRow>()
            addAll(page)
            if (page.size < MOVEMENT_PAGE_SIZE) break
            offset += MOVEMENT_PAGE_SIZE
        }
    }

    suspend fun ensureContainer(
        stationId: String,
        container: Container,
        seedSnapshot: Boolean
    ): String = supabase.postgrest.rpc("ensure_container_inventory", buildJsonObject {
        put("p_station_id", stationId)
        put("p_size_label", container.sizeLabel.trim())
        put("p_total_owned", container.totalOwned)
        put("p_available", container.available)
        put("p_with_customers", container.withCustomers)
        put("p_damaged", container.damaged)
        put("p_lost", container.lost)
        put("p_deposit_price_centavos", moneyToCentavos(container.depositPrice))
        put("p_seed_snapshot", seedSnapshot)
    }).decodeList<EnsureContainerResult>().single().containerId

    suspend fun applyMovement(
        stationId: String,
        transaction: ContainerTransaction,
        legacyId: String,
        operationId: String,
        customerId: String?,
        orderId: String?,
        applyInventory: Boolean,
        tracksCustomerBalance: Boolean,
        depositPrice: Double
    ): String = supabase.postgrest.rpc("apply_container_movement", buildJsonObject {
        put("p_firebase_id", legacyId)
        put("p_operation_id", operationId)
        put("p_station_id", stationId)
        put("p_container_size", transaction.containerSize.trim())
        put("p_movement_type", transaction.type.name)
        put("p_quantity", transaction.quantity)
        put("p_customer_id", customerId)
        put("p_order_id", orderId)
        put("p_customer_name", transaction.customerName)
        put("p_notes", transaction.notes)
        put("p_created_at", Instant.ofEpochMilli(transaction.createdAt).toString())
        put("p_apply_inventory", applyInventory)
        put("p_tracks_customer_balance", tracksCustomerBalance)
        put("p_deposit_price_centavos", moneyToCentavos(depositPrice))
    }).decodeList<ApplyContainerMovementResult>().single().movementId

    @Serializable
    private data class EnsureContainerResult(
        @SerialName("synced_container_id") val containerId: String
    )

    @Serializable
    private data class ApplyContainerMovementResult(
        @SerialName("synced_movement_id") val movementId: String
    )

    private fun moneyToCentavos(value: Double): Long =
        Math.round(value * 100.0).also { require(value.isFinite() && value >= 0) }

    private companion object {
        const val MOVEMENT_PAGE_SIZE = 500L
    }
}
