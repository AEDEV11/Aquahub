package com.aesprt.aquahub.data.supabase.settings

import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.domain.model.Station
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class SupabaseSettingsDataSource {
    suspend fun createStation(remoteStationId: String, station: Station) {
        SupabaseClientProvider.client.postgrest.rpc(
            function = "create_station",
            parameters = buildJsonObject {
                put("p_station_id", remoteStationId)
                put("p_settings", station.settingsPayload())
            }
        )
    }

    suspend fun archiveStation(remoteStationId: String) {
        SupabaseClientProvider.client.from("stations").update(buildJsonObject {
            put("is_active", false)
            put("deleted_at", Instant.now().toString())
        }) { filter { eq("id", remoteStationId) } }
    }

    suspend fun updateStationSettings(remoteStationId: String, station: Station) {
        SupabaseClientProvider.client.postgrest.rpc(
            function = "update_station_settings",
            parameters = buildJsonObject {
                put("p_station_id", remoteStationId)
                put("p_settings", station.settingsPayload())
            }
        )
    }

    private fun Station.settingsPayload() = buildJsonObject {
        put("name", name)
        put("description", description)
        put("phone", phone)
        put("email", email)
        put("address", address)
        put("barangay", barangay)
        put("city", city)
        put("province", province)
        put("latitude", latitude)
        put("longitude", longitude)
        put("logo_path", logoPath)
        put("opening_time", openingTime)
        put("closing_time", closingTime)
        put("is_open", isOpen)
        put("manual_open_override", manualOpenOverride)
        put("is_accepting_online_orders", isAcceptingOnlineOrders)
        put("delivery_radius_km", deliveryRadiusKm)
        put("delivery_fee_centavos", deliveryFeeCentavos)
        put("estimated_preparation_minutes", estimatedPreparationMinutes)
        put("business_rules_text", businessRulesText)
        put("public_station_code", publicStationCode)
        put("cash_payment_enabled", cashPaymentEnabled)
        put("cod_payment_enabled", codPaymentEnabled)
        put("gcash_payment_enabled", gcashPaymentEnabled)
        put("gcash_account_name", gcashAccountName)
        put("gcash_account_number", gcashAccountNumber)
        put("gcash_qr_image_path", gcashQrImagePath)
        put("maya_payment_enabled", mayaPaymentEnabled)
        put("maya_account_name", mayaAccountName)
        put("maya_account_number", mayaAccountNumber)
        put("maya_qr_image_path", mayaQrImagePath)
    }
}
