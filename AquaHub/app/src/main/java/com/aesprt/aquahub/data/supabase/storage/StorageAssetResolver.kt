package com.aesprt.aquahub.data.supabase.storage

import java.util.concurrent.ConcurrentHashMap

enum class StorageAssetReferenceType {
    SUPABASE_OBJECT_PATH,
    LEGACY_DOWNLOAD_URL,
    LEGACY_STORAGE_PATH,
    UNKNOWN
}

data class ParsedStorageAssetReference(
    val type: StorageAssetReferenceType,
    val bucket: String? = null,
    val objectPath: String? = null,
)

/** Resolves stored asset references without changing the database source of truth. */
class StorageAssetResolver(
    private val supabaseStorage: SupabaseStorageDataSource,
) {
    private data class CachedUrl(val value: String, val refreshAtMillis: Long)

    private val privateUrlCache = ConcurrentHashMap<String, CachedUrl>()

    suspend fun resolve(reference: String?, privateByDefault: Boolean = false): String? {
        if (reference.isNullOrBlank()) return null
        val parsed = parse(reference)
        return when (parsed.type) {
            StorageAssetReferenceType.SUPABASE_OBJECT_PATH -> {
                val bucket = requireNotNull(parsed.bucket)
                val path = requireNotNull(parsed.objectPath)
                if (!privateByDefault && bucket != PRIVATE_PAYMENT_BUCKET) {
                    supabaseStorage.publicUrl(bucket, path)
                } else {
                    resolvePrivate(bucket, path)
                }
            }
            StorageAssetReferenceType.LEGACY_DOWNLOAD_URL -> reference
            // gs:// references require the removed Storage SDK. They must be
            // imported into Supabase before they can be displayed.
            StorageAssetReferenceType.LEGACY_STORAGE_PATH -> null
            StorageAssetReferenceType.UNKNOWN -> reference
        }
    }

    fun parse(reference: String): ParsedStorageAssetReference {
        if (reference.startsWith(SUPABASE_PREFIX)) {
            val value = reference.removePrefix(SUPABASE_PREFIX)
            val separator = value.indexOf('/')
            if (separator > 0 && separator < value.lastIndex) {
                return ParsedStorageAssetReference(
                    type = StorageAssetReferenceType.SUPABASE_OBJECT_PATH,
                    bucket = value.substring(0, separator),
                    objectPath = value.substring(separator + 1),
                )
            }
        }
        if (reference.startsWith("gs://")) {
            return ParsedStorageAssetReference(StorageAssetReferenceType.LEGACY_STORAGE_PATH)
        }
        if (reference.startsWith("https://") || reference.startsWith("http://")) {
            return ParsedStorageAssetReference(StorageAssetReferenceType.LEGACY_DOWNLOAD_URL)
        }
        return ParsedStorageAssetReference(StorageAssetReferenceType.UNKNOWN)
    }

    private suspend fun resolvePrivate(bucket: String, path: String): String {
        val key = "$bucket/$path"
        val now = System.currentTimeMillis()
        privateUrlCache[key]?.takeIf { it.refreshAtMillis > now }?.let { return it.value }
        val value = supabaseStorage.signedUrl(bucket, path, PRIVATE_URL_LIFETIME_SECONDS)
        privateUrlCache[key] = CachedUrl(
            value = value,
            refreshAtMillis = now + (PRIVATE_URL_LIFETIME_SECONDS - REFRESH_MARGIN_SECONDS) * 1_000L,
        )
        return value
    }

    companion object {
        const val SUPABASE_PREFIX = "supabase://"
        const val PRIVATE_PAYMENT_BUCKET = "payment-assets"
        private const val PRIVATE_URL_LIFETIME_SECONDS = 900L
        private const val REFRESH_MARGIN_SECONDS = 60L
    }
}
