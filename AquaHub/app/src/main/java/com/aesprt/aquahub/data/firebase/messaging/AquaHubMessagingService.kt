package com.aesprt.aquahub.data.firebase.messaging

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aesprt.aquahub.BuildConfig
import com.aesprt.aquahub.MainActivity
import com.aesprt.aquahub.R
import com.aesprt.aquahub.data.supabase.SupabaseDeviceTokenRepository
import com.aesprt.aquahub.sync.SyncScheduler
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.launch

class AquaHubMessagingService : FirebaseMessagingService() {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (!BuildConfig.QA_BUILD) saveDeviceToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // 1. Wake a durable sync and refresh this station's order queue before
        // relying on the notification tap to open stale Room-backed UI.
        val stationRemoteId = message.data["station_id"] ?: message.data["stationId"]
        runCatching { SyncScheduler(applicationContext).enqueueImmediate(stationRemoteId) }
            .onFailure { Log.w("AquaHubMessaging", "Could not schedule the notification sync", it) }

        // 2. Build and post a heads-up system notification
        createNotificationChannel(applicationContext)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "New Customer Order"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "A new customer order is waiting for confirmation."

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            (message.data["orderId"] ?: message.data["order_id"])?.let { putExtra("orderId", it) }
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            (System.currentTimeMillis() % 100000).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID_ORDERS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = NotificationManagerCompat.from(applicationContext)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), notification)
        }
    }

    companion object {
        const val CHANNEL_ID_ORDERS = "aquahub_orders"
        private const val CHANNEL_NAME_ORDERS = "Customer Orders"

        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID_ORDERS,
                CHANNEL_NAME_ORDERS,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming customer orders and updates"
                enableLights(true)
                enableVibration(true)
            }
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }

        fun registerCurrentDeviceToken(context: Context, explicitUid: String? = null) {
            if (BuildConfig.QA_BUILD) return
            // The Firebase UID parameter is retained for compatibility with old
            // callers, but Supabase Auth is the only token owner now.
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    saveDeviceToken(token)
                }
            }
        }

        suspend fun removeCurrentDeviceToken(uid: String) {
            if (BuildConfig.QA_BUILD) return
            val token = FirebaseMessaging.getInstance().token.await()
            if (token.isBlank()) return
            runCatching { SupabaseDeviceTokenRepository.remove(token) }
        }

        fun saveDeviceToken(token: String) {
            if (BuildConfig.QA_BUILD) return
            try {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { SupabaseDeviceTokenRepository.upsert(token) }
                }
            } catch (_: Exception) {
            }
        }
    }
}
