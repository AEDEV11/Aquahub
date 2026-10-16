package com.aesprt.aquahub.data.supabase.storage

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Normalizes camera proofs so they fit the private Storage bucket without uploading full-resolution photos. */
class DeliveryPaymentProofCompressor(private val contentResolver: ContentResolver) {
    suspend fun compress(sourceUri: String): ByteArray = withContext(Dispatchers.IO) {
        compressBlocking(Uri.parse(sourceUri))
    }

    private fun compressBlocking(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = contentResolver.openInputStream(uri)
            ?: error("The selected payment photo could not be read")
        boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected payment photo is invalid" }

        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("The selected payment photo could not be decoded")

        val orientation = readOrientation(uri)
        var bitmap = orient(decoded, orientation)
        if (bitmap !== decoded) decoded.recycle()

        var quality = DEFAULT_QUALITY
        try {
            while (true) {
                val bytes = ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        "The payment photo could not be compressed"
                    }
                    output.toByteArray()
                }
                if (bytes.size <= TARGET_MAX_BYTES) return bytes

                if (quality > MIN_QUALITY) {
                    quality = (quality - QUALITY_STEP).coerceAtLeast(MIN_QUALITY)
                    continue
                }

                val scaledWidth = (bitmap.width * RESIZE_FACTOR).toInt().coerceAtLeast(1)
                val scaledHeight = (bitmap.height * RESIZE_FACTOR).toInt().coerceAtLeast(1)
                if (scaledWidth == bitmap.width && scaledHeight == bitmap.height ||
                    maxOf(scaledWidth, scaledHeight) < MIN_DIMENSION
                ) {
                    error("The payment photo could not be reduced below the 4 MB upload target")
                }
                val smaller = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
                if (smaller !== bitmap) bitmap.recycle()
                bitmap = smaller
                quality = DEFAULT_QUALITY
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (maxOf(width / sampleSize, height / sampleSize) > MAX_DIMENSION) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun readOrientation(uri: Uri): Int = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        const val TARGET_MAX_BYTES = 4 * 1024 * 1024
        const val MAX_DIMENSION = 2560
        private const val MIN_DIMENSION = 1024
        private const val DEFAULT_QUALITY = 88
        private const val MIN_QUALITY = 64
        private const val QUALITY_STEP = 8
        private const val RESIZE_FACTOR = 0.8
    }
}
