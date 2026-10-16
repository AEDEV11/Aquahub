package com.aesprt.aquahub.data.supabase.expense

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.domain.model.Expense
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@Serializable
data class SupabaseExpenseRow(
    val id: String,
    @SerialName("firebase_id") val firebaseId: String? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("station_id") val stationId: String,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("amount_centavos") val amountCentavos: Long,
    val category: String,
    val description: String,
    val vendor: String? = null,
    @SerialName("payment_method") val paymentMethod: String,
    @SerialName("reference_number") val referenceNumber: String? = null,
    val notes: String? = null,
    @SerialName("incurred_at") val incurredAt: Long,
    @SerialName("receipt_path") val receiptPath: String? = null,
    @SerialName("operation_id") val operationId: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

class SupabaseExpenseDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun expenses(stationId: String): List<SupabaseExpenseRow> {
        val result = mutableListOf<SupabaseExpenseRow>()
        var from = 0L
        val pageSize = 500L
        while (true) {
            val page = supabase.from("expenses").select {
                filter { eq("station_id", stationId) }
                order("updated_at", Order.ASCENDING)
                range(from, from + pageSize - 1)
            }.decodeList<SupabaseExpenseRow>()
            result += page
            if (page.size < pageSize) return result
            from += pageSize
        }
    }

    suspend fun save(
        stationId: String,
        expense: Expense,
        remoteId: String?,
        operationId: String
    ): SupabaseExpenseRow {
        val payload = buildJsonObject {
            put("station_id", stationId)
            put("amount_centavos", expense.amountCentavos)
            put("category", expense.category.name)
            put("description", expense.description)
            put("vendor", expense.vendor)
            put("payment_method", expense.paymentMethod.name)
            put("reference_number", expense.referenceNumber)
            put("notes", expense.notes)
            put("incurred_at", expense.incurredAt)
            put("is_active", true)
            put("operation_id", operationId)
        }
        if (remoteId == null) {
            supabase.from("expenses").upsert(payload) { onConflict = "operation_id" }
            return findByOperation(operationId)
                ?: error("Supabase expense write did not return a row")
        }
        supabase.from("expenses").update(payload) { filter { eq("id", remoteId) } }
        return findById(remoteId) ?: error("Supabase expense update did not return a row")
    }

    suspend fun archive(remoteId: String) {
        supabase.from("expenses").update(buildJsonObject {
            put("is_active", false)
            put("deleted_at", Instant.now().toString())
        }) { filter { eq("id", remoteId) } }
    }

    private suspend fun findById(id: String): SupabaseExpenseRow? =
        supabase.from("expenses").select { filter { eq("id", id) } }
            .decodeList<SupabaseExpenseRow>().firstOrNull()

    private suspend fun findByOperation(operationId: String): SupabaseExpenseRow? =
        supabase.from("expenses").select { filter { eq("operation_id", operationId) } }
            .decodeList<SupabaseExpenseRow>().firstOrNull()
}
