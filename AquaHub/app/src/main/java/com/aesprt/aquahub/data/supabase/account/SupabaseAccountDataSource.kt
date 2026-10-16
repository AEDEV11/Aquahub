package com.aesprt.aquahub.data.supabase.account

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.supabase.auth.SupabaseProfileRecord
import com.aesprt.aquahub.data.supabase.workspace.SupabaseWorkspaceDataSource
import com.aesprt.aquahub.domain.model.OwnerRole
import com.aesprt.aquahub.domain.model.Station
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Reads the authenticated Supabase profile and its tenant boundary. */
class SupabaseAccountDataSource(
    private val workspaceDataSource: SupabaseWorkspaceDataSource
) {
    private val supabase get() = SupabaseClientProvider.client

    suspend fun loadCurrent(): SupabaseAccountProfile {
        val userId = requireNotNull(supabase.auth.currentUserOrNull()?.id) { "Supabase session is missing." }
        val profile = supabase.from("profiles").select { filter { eq("id", userId) } }
            .decodeList<SupabaseProfileRecord>().singleOrNull()
            ?: error("AquaHub cloud profile is missing")
        val members = supabase.from("organization_members").select {
            filter { eq("profile_id", userId); eq("is_active", true) }
            order("created_at", Order.ASCENDING)
        }.decodeList<MemberRow>()
        val member = members.firstOrNull { it.organizationId == profile.preferredBusinessId }
            ?: members.firstOrNull()
            ?: error("AquaHub membership is inactive")
        val assignedIds = supabase.from("member_stations").select {
            filter { eq("member_id", member.id) }
        }.decodeList<MemberStationRow>().map { it.stationId }
        val stationRows = supabase.from("stations").select {
            filter {
                eq("organization_id", member.organizationId)
                eq("is_active", true)
            }
            order("created_at", Order.ASCENDING)
            order("id", Order.ASCENDING)
        }.decodeList<SupabaseStationRow>()
        val stations = if (member.role == "OWNER" || member.role == "MANAGER") {
            stationRows
        } else {
            stationRows.filter { it.id in assignedIds }
        }
        return SupabaseAccountProfile(
            profile = profile,
            organizationId = member.organizationId,
            memberId = member.id,
            role = OwnerRole.requireFromString(member.role),
            stationIds = stations.map { it.id },
            stations = stations
        )
    }

    suspend fun provisionOwner(owner: com.aesprt.aquahub.domain.model.Owner, station: Station): SupabaseAccountProfile {
        workspaceDataSource.provisionOwnerWorkspace(owner, station)
        return loadCurrent()
    }
}

data class SupabaseAccountProfile(
    val profile: SupabaseProfileRecord,
    val organizationId: String,
    val memberId: String,
    val role: OwnerRole,
    val stationIds: List<String>,
    val stations: List<SupabaseStationRow>
) {
    val displayName: String get() = profile.displayName
    val phone: String get() = profile.phone
    val email: String? get() = profile.email
}

@Serializable
private data class MemberRow(
    val id: String,
    @SerialName("organization_id") val organizationId: String,
    val role: String,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
private data class MemberStationRow(
    @SerialName("member_id") val memberId: String,
    @SerialName("station_id") val stationId: String
)

@Serializable
data class SupabaseStationRow(
    val id: String,
    val name: String,
    val description: String? = null,
    val phone: String = "",
    val email: String? = null,
    val address: String = "",
    val barangay: String? = null,
    val city: String? = null,
    val province: String? = null,
    @SerialName("place_id") val placeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("opening_time") val openingTime: String? = null,
    @SerialName("closing_time") val closingTime: String? = null,
    @SerialName("is_open") val isOpen: Boolean = true,
    @SerialName("manual_open_override") val manualOpenOverride: Boolean = false,
    @SerialName("is_accepting_online_orders") val isAcceptingOnlineOrders: Boolean = true,
    @SerialName("delivery_radius_km") val deliveryRadiusKm: Double? = null,
    @SerialName("delivery_fee_centavos") val deliveryFeeCentavos: Long = 0,
    @SerialName("estimated_preparation_minutes") val estimatedPreparationMinutes: Int = 30,
    @SerialName("business_rules_text") val businessRulesText: String? = null,
    @SerialName("business_rules_version") val businessRulesVersion: Long = 0,
    @SerialName("public_station_code") val publicStationCode: String? = null,
    @SerialName("cash_payment_enabled") val cashPaymentEnabled: Boolean = true,
    @SerialName("cod_payment_enabled") val codPaymentEnabled: Boolean = true,
    @SerialName("gcash_payment_enabled") val gcashPaymentEnabled: Boolean = false,
    @SerialName("gcash_account_name") val gcashAccountName: String? = null,
    @SerialName("gcash_account_number") val gcashAccountNumber: String? = null,
    @SerialName("gcash_qr_image_path") val gcashQrImagePath: String? = null,
    @SerialName("maya_payment_enabled") val mayaPaymentEnabled: Boolean = false,
    @SerialName("maya_account_name") val mayaAccountName: String? = null,
    @SerialName("maya_account_number") val mayaAccountNumber: String? = null,
    @SerialName("maya_qr_image_path") val mayaQrImagePath: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
