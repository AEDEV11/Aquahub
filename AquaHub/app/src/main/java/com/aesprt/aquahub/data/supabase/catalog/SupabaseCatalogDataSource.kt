package com.aesprt.aquahub.data.supabase.catalog

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.CustomerAddress
import com.aesprt.aquahub.domain.model.Product
import com.aesprt.aquahub.domain.model.ProductPromotion
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

@Serializable
data class SupabaseCustomerRow(
    val id: String,
    @SerialName("firebase_id") val legacyId: String? = null,
    @SerialName("station_id") val stationId: String,
    val name: String,
    val phone: String,
    val email: String? = null,
    val notes: String? = null,
    @SerialName("total_orders") val totalOrders: Int = 0,
    @SerialName("total_spent_centavos") val totalSpentCentavos: Long = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SupabaseCustomerAddressRow(
    val id: String,
    @SerialName("firebase_id") val legacyId: String? = null,
    @SerialName("station_id") val stationId: String,
    @SerialName("customer_id") val customerId: String,
    val label: String,
    @SerialName("address_line") val addressLine: String,
    val barangay: String? = null,
    val city: String? = null,
    val province: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("is_default") val isDefault: Boolean = false
)

@Serializable
data class SupabaseProductRow(
    val id: String,
    @SerialName("firebase_id") val legacyId: String? = null,
    @SerialName("station_id") val stationId: String,
    val name: String,
    @SerialName("product_type") val productType: String,
    @SerialName("size_label") val sizeLabel: String,
    val description: String? = null,
    @SerialName("price_centavos") val priceCentavos: Long,
    @SerialName("stock_quantity") val stockQuantity: Int? = null,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("is_available") val isAvailable: Boolean = true,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("promotion_label") val promotionLabel: String? = null,
    @SerialName("promotion_type") val promotionType: String? = null,
    @SerialName("promotion_percent_bps") val promotionPercentBps: Int? = null,
    @SerialName("promotional_price_centavos") val promotionalPriceCentavos: Long? = null,
    @SerialName("promotion_minimum_quantity") val promotionMinimumQuantity: Int? = null,
    @SerialName("promotion_starts_at") val promotionStartsAt: Long? = null,
    @SerialName("promotion_ends_at") val promotionEndsAt: Long? = null,
    @SerialName("promotion_is_active") val promotionIsActive: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

class SupabaseCatalogDataSource {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun findStationId(name: String, address: String): String? =
        supabase.from("stations").select {
            filter {
                eq("name", name.trim())
                eq("address", address.trim())
                eq("is_active", true)
            }
        }.decodeList<StationLookup>().firstOrNull()?.id

    suspend fun customers(stationId: String): List<SupabaseCustomerRow> =
        supabase.from("customers").select {
            filter {
                eq("station_id", stationId)
                eq("is_active", true)
            }
        }.decodeList()

    private suspend fun findCustomerByStationPhone(
        stationId: String,
        phone: String
    ): SupabaseCustomerRow? {
        val normalizedPhone = phone.normalizeStationPhone()
        if (normalizedPhone.isEmpty()) return null
        return customers(stationId).firstOrNull {
            it.phone.normalizeStationPhone() == normalizedPhone
        }
    }

    suspend fun customerAddresses(stationId: String): List<SupabaseCustomerAddressRow> =
        supabase.from("customer_addresses").select {
            filter { eq("station_id", stationId) }
        }.decodeList()

    suspend fun findCustomerIdByLegacyId(legacyId: String): String? =
        supabase.from("customers").select {
            filter { eq("firebase_id", legacyId) }
        }.decodeList<SupabaseCustomerRow>().firstOrNull()?.id

    suspend fun resolveCustomerId(reference: String): String? {
        supabase.from("customers").select {
            filter { eq("id", reference) }
        }.decodeList<SupabaseCustomerRow>().firstOrNull()?.let { return it.id }
        return findCustomerIdByLegacyId(reference)
    }

    suspend fun upsertCustomer(stationId: String, customer: Customer, legacyId: String): SupabaseCustomerRow {
        val existing = supabase.from("customers").select {
            filter { eq("id", legacyId) }
        }.decodeList<SupabaseCustomerRow>().firstOrNull()
            ?: supabase.from("customers").select {
                filter { eq("firebase_id", legacyId) }
            }.decodeList<SupabaseCustomerRow>().firstOrNull()
            ?: findCustomerByStationPhone(stationId, customer.phone)
        val stableLegacyId = existing?.legacyId ?: legacyId
        val payload = buildJsonObject {
            put("firebase_id", stableLegacyId)
            put("station_id", stationId)
            put("name", customer.name.trim())
            put("phone", customer.phone.trim())
            put("email", customer.email)
            put("notes", customer.notes)
            put("total_orders", customer.totalOrders)
            put("total_spent_centavos", moneyToCentavos(customer.totalSpent))
            put("is_active", true)
        }
        if (existing == null) {
            supabase.from("customers").upsert(payload) { onConflict = "firebase_id" }
        } else {
            supabase.from("customers").update(payload) { filter { eq("id", existing.id) } }
        }
        return supabase.from("customers").select {
            filter { eq("id", existing?.id ?: legacyId) }
        }.decodeList<SupabaseCustomerRow>().singleOrNull()
            ?: supabase.from("customers").select {
                filter { eq("firebase_id", stableLegacyId) }
            }.decodeList<SupabaseCustomerRow>().single()
    }

    suspend fun upsertAddress(
        stationId: String,
        customerId: String,
        address: CustomerAddress,
        legacyId: String
    ): SupabaseCustomerAddressRow {
        val existing = supabase.from("customer_addresses").select {
            filter { eq("id", legacyId) }
        }.decodeList<SupabaseCustomerAddressRow>().firstOrNull()
            ?: supabase.from("customer_addresses").select {
                filter { eq("firebase_id", legacyId) }
            }.decodeList<SupabaseCustomerAddressRow>().firstOrNull()
        val stableLegacyId = existing?.legacyId ?: legacyId
        val payload = buildJsonObject {
            put("firebase_id", stableLegacyId)
            put("station_id", stationId)
            put("customer_id", customerId)
            put("label", address.label)
            put("address_line", address.addressLine)
            put("barangay", address.barangay)
            put("city", address.city)
            put("province", address.province)
            put("latitude", address.latitude)
            put("longitude", address.longitude)
            put("is_default", address.isDefault)
        }
        if (existing == null) {
            supabase.from("customer_addresses").upsert(payload) { onConflict = "firebase_id" }
        } else {
            supabase.from("customer_addresses").update(payload) { filter { eq("id", existing.id) } }
        }
        return supabase.from("customer_addresses").select {
            filter { eq("id", existing?.id ?: legacyId) }
        }.decodeList<SupabaseCustomerAddressRow>().singleOrNull()
            ?: supabase.from("customer_addresses").select {
                filter { eq("firebase_id", stableLegacyId) }
            }.decodeList<SupabaseCustomerAddressRow>().single()
    }

    suspend fun resolveCustomerAddressId(reference: String): String? {
        supabase.from("customer_addresses").select {
            filter { eq("id", reference) }
        }.decodeList<SupabaseCustomerAddressRow>().firstOrNull()?.let { return it.id }
        return supabase.from("customer_addresses").select {
            filter { eq("firebase_id", reference) }
        }.decodeList<SupabaseCustomerAddressRow>().firstOrNull()?.id
    }

    suspend fun archiveAddress(reference: String) {
        val addressId = resolveCustomerAddressId(reference) ?: return
        supabase.from("customer_addresses").delete { filter { eq("id", addressId) } }
    }

    suspend fun archiveCustomer(reference: String) {
        val customerId = resolveCustomerId(reference) ?: return
        supabase.from("customers").update(buildJsonObject {
            put("is_active", false)
            put("deleted_at", Instant.now().toString())
        }) { filter { eq("id", customerId) } }
    }

    suspend fun products(stationId: String): List<SupabaseProductRow> =
        supabase.from("products").select {
            filter {
                eq("station_id", stationId)
                eq("is_active", true)
            }
        }.decodeList()

    suspend fun findProductIdByLegacyId(legacyId: String): String? =
        supabase.from("products").select {
            filter { eq("firebase_id", legacyId) }
        }.decodeList<SupabaseProductRow>().firstOrNull()?.id

    suspend fun upsertProduct(
        stationId: String,
        product: Product,
        legacyId: String,
        includeStock: Boolean
    ): SupabaseProductRow {
        val existing = supabase.from("products").select {
            filter { eq("id", legacyId) }
        }.decodeList<SupabaseProductRow>().firstOrNull()
            ?: supabase.from("products").select {
                filter { eq("firebase_id", legacyId) }
            }.decodeList<SupabaseProductRow>().firstOrNull()
        val stableLegacyId = existing?.legacyId ?: legacyId
        val promotion = product.promotion
        val payload = buildJsonObject {
            put("firebase_id", stableLegacyId)
            put("station_id", stationId)
            put("name", product.name.trim())
            put("product_type", product.type.name)
            put("size_label", product.sizeLabel.trim())
            put("price_centavos", moneyToCentavos(product.price))
            if (includeStock) put("stock_quantity", product.stockQuantity)
            put("image_path", product.imagePath)
            put("is_available", product.isAvailable)
            put("is_active", product.isActive)
            put("promotion_label", promotion?.label)
            put("promotion_type", promotion?.type?.name)
            put("promotion_percent_bps", promotion?.percentBps)
            put("promotional_price_centavos", promotion?.promotionalPrice?.let(::moneyToCentavos))
            put("promotion_minimum_quantity", promotion?.minimumQuantity)
            put("promotion_starts_at", promotion?.startsAt)
            put("promotion_ends_at", promotion?.endsAt)
            put("promotion_is_active", promotion?.isActive ?: false)
        }
        if (existing == null) {
            supabase.from("products").upsert(payload) { onConflict = "firebase_id" }
        } else {
            supabase.from("products").update(payload) { filter { eq("id", existing.id) } }
        }
        return supabase.from("products").select {
            filter { eq("id", existing?.id ?: legacyId) }
        }.decodeList<SupabaseProductRow>().singleOrNull()
            ?: supabase.from("products").select {
                filter { eq("firebase_id", stableLegacyId) }
            }.decodeList<SupabaseProductRow>().single()
    }

    /**
     * Resolves both the canonical Supabase UUID and the legacy reference used
     * by older Room mappings. Older builds stored firebase_id in sync metadata
     * even after the row had a different Supabase UUID.
     */
    suspend fun resolveProductId(reference: String): String? {
        supabase.from("products").select {
            filter { eq("id", reference) }
        }.decodeList<SupabaseProductRow>().firstOrNull()?.let { return it.id }
        return findProductIdByLegacyId(reference)
    }

    suspend fun archiveProduct(reference: String) {
        val productId = resolveProductId(reference) ?: return
        supabase.from("products").update(buildJsonObject {
            put("is_active", false)
            put("is_available", false)
            put("deleted_at", Instant.now().toString())
        }) { filter { eq("id", productId) } }
    }

    private fun moneyToCentavos(value: Double): Long =
        Math.round(value * 100.0).also { require(value.isFinite() && value >= 0) }

    @Serializable
    private data class StationLookup(val id: String)
}

private fun String.normalizeStationPhone(): String = filter(Char::isDigit).let { digits ->
    when {
        digits.startsWith("63") -> digits.drop(2)
        digits.startsWith("0") -> digits.drop(1)
        else -> digits
    }
}
