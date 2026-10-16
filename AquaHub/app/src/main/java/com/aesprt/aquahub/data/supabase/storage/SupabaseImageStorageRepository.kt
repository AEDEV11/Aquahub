package com.aesprt.aquahub.data.supabase.storage

import android.content.Context
import android.net.Uri
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.domain.repository.ImageStorageRepository
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

class SupabaseImageStorageRepository(
    context: Context,
    private val syncDao: SyncDao,
    private val resolver: StorageAssetResolver,
) : ImageStorageRepository {
    private val appContext = context.applicationContext
    private val storage = SupabaseStorageDataSource(context)
    private val paymentProofCompressor = DeliveryPaymentProofCompressor(appContext.contentResolver)

    override suspend fun uploadProductImage(stationId: Long, sourceUri: String): Result<String> =
        upload(stationId, sourceUri, "products", "product-images")

    override suspend fun uploadStationLogo(stationId: Long, sourceUri: String): Result<String> =
        upload(stationId, sourceUri, "station", "station-assets")

    override suspend fun uploadPaymentQr(stationId: Long, wallet: String, sourceUri: String): Result<String> =
        upload(stationId, sourceUri, "payment-assets/${wallet.lowercase()}", "payment-assets")

    override suspend fun uploadDeliveryPaymentProof(
        stationId: Long,
        orderId: Long,
        sourceUri: String
    ): Result<String> = runCatching {
        val stationRemoteId = requireNotNull(syncDao.getMetadata("STATION", stationId)?.remoteId) {
            "Station is not provisioned in Supabase"
        }
        val orderRemoteId = requireNotNull(syncDao.getMetadata("ORDER", orderId)?.remoteId) {
            "Order is not synced yet; payment proof cannot be attached"
        }
        val station = SupabaseClientProvider.client.from("stations").select {
            filter { eq("id", stationRemoteId) }
        }.decodeList<StorageStationRow>().singleOrNull() ?: error("Station is not provisioned in Supabase")
        val mimeType = requireNotNull(appContext.contentResolver.getType(Uri.parse(sourceUri))) {
            "The captured payment proof is not a supported image"
        }
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp")) {
            "Payment proof must be a JPG, PNG, or WebP image"
        }
        val proofBytes = paymentProofCompressor.compress(sourceUri)
        val path = "organizations/${station.organizationId}/stations/$stationRemoteId/" +
            "delivery-payment-proofs/$orderRemoteId/${UUID.randomUUID()}.jpg"
        storage.uploadImageBytes("payment-assets", path, proofBytes, JPEG_MIME_TYPE)
        path
    }

    override suspend fun deleteOwnedImage(path: String): Result<Unit> = runCatching {
        val parsed = resolver.parse(path)
        if (parsed.type != StorageAssetReferenceType.SUPABASE_OBJECT_PATH && !path.substringBefore(':').let { it in setOf("product-images", "station-assets", "payment-assets") }) {
            // Legacy asset URLs/paths are read-only during migration.
            return Result.success(Unit)
        }
        val bucket = parsed.bucket ?: path.substringBefore(':')
        val objectPath = parsed.objectPath ?: path.substringAfter(':', missingDelimiterValue = path)
        require(bucket in setOf("product-images", "station-assets", "payment-assets")) { "Unsupported storage asset" }
        storage.delete(bucket, objectPath)
    }

    private suspend fun upload(stationId: Long, sourceUri: String, area: String, bucket: String): Result<String> = runCatching {
        val station = requireNotNull(syncDao.getMetadata("STATION", stationId)) { "Station is not provisioned in Supabase" }
        val remoteStation = SupabaseClientProvider.client.from("stations").select {
            filter { eq("id", station.remoteId) }
        }.decodeList<StorageStationRow>().singleOrNull() ?: error("Station is not provisioned in Supabase")
        val organizationId = remoteStation.organizationId
        val mimeType = requireNotNull(appContext.contentResolver.getType(Uri.parse(sourceUri)))
        require(mimeType in setOf("image/jpeg", "image/png", "image/webp")) { "Choose a JPG, PNG, or WebP image" }
        val extension = mimeType.substringAfter('/').replace("jpeg", "jpg")
        val path = SupabaseStorageDataSource.imagePath(organizationId, station.remoteId, area, stationId.toString(), extension)
        val stored = storage.uploadImage(bucket, path, sourceUri, mimeType)
        "${StorageAssetResolver.SUPABASE_PREFIX}$bucket/$stored"
    }

    private companion object {
        const val JPEG_MIME_TYPE = "image/jpeg"
    }

}

@Serializable
private data class StorageStationRow(@SerialName("organization_id") val organizationId: String)
