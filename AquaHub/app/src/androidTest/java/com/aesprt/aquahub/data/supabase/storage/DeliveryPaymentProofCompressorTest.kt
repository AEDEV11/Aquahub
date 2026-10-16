package com.aesprt.aquahub.data.supabase.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeliveryPaymentProofCompressorTest {
    @Test
    fun oversizedCameraPhotoIsDownsampledAndEncodedBelowStorageTarget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File(context.cacheDir, "large-payment-proof-${System.nanoTime()}.jpg")
        val width = 3000
        val height = 2200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val random = Random(1234L)
        val pixels = IntArray(width * height) {
            0xff000000.toInt() or random.nextInt(0x01000000)
        }

        try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            FileOutputStream(source).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            }
            assertTrue("Fixture must exceed the original 5 MiB limit", source.length() > SupabaseStorageDataSource.MAX_IMAGE_BYTES)

            val compressed = DeliveryPaymentProofCompressor(context.contentResolver)
                .compress(Uri.fromFile(source).toString())

            assertTrue(compressed.size <= DeliveryPaymentProofCompressor.TARGET_MAX_BYTES)
            assertTrue(compressed.size <= SupabaseStorageDataSource.MAX_IMAGE_BYTES)
            assertEquals(0xff, compressed[0].toInt() and 0xff)
            assertEquals(0xd8, compressed[1].toInt() and 0xff)

            val decoded = BitmapFactory.decodeByteArray(compressed, 0, compressed.size)
            assertTrue(decoded != null)
            assertTrue(maxOf(decoded!!.width, decoded.height) <= DeliveryPaymentProofCompressor.MAX_DIMENSION)
            decoded.recycle()
        } finally {
            bitmap.recycle()
            source.delete()
        }
    }
}
