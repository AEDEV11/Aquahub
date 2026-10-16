package com.aesprt.aquahub.data.supabase.storage

import android.content.Context
import android.net.Uri
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import kotlin.time.Duration.Companion.seconds
import java.util.UUID

class SupabaseStorageDataSource(private val context: Context) {
    suspend fun uploadImage(bucket: String, path: String, sourceUri: String, mimeType: String): String {
        val bytes = context.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { it.readBytes() }
            ?: error("The selected file could not be read")
        return uploadImageBytes(bucket, path, bytes, mimeType)
    }

    suspend fun uploadImageBytes(bucket: String, path: String, bytes: ByteArray, mimeType: String): String {
        require(bytes.size <= MAX_IMAGE_BYTES) { "Image must be 5 MB or smaller" }
        SupabaseClientProvider.client.storage.from(bucket).upload(path, bytes) {
            upsert = false
            contentType = ContentType.parse(mimeType)
        }
        return path
    }

    fun publicUrl(bucket: String, path: String): String =
        SupabaseClientProvider.client.storage.from(bucket).publicUrl(path)

    suspend fun signedUrl(bucket: String, path: String, expiresInSeconds: Long = 3600): String =
        SupabaseClientProvider.client.storage.from(bucket).createSignedUrl(path, expiresInSeconds.seconds)

    suspend fun delete(bucket: String, path: String) {
        SupabaseClientProvider.client.storage.from(bucket).delete(path)
    }

    companion object {
        const val MAX_IMAGE_BYTES = 5L * 1024L * 1024L
        fun imagePath(organizationId: String, stationId: String, area: String, entityId: String, extension: String): String =
            "organizations/$organizationId/stations/$stationId/$area/$entityId-${UUID.randomUUID()}.$extension"
    }
}
