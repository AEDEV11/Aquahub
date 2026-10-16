package com.aesprt.aquahub

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.aesprt.aquahub.core.navigation.AppNavGraph
import com.aesprt.aquahub.data.firebase.messaging.AquaHubMessagingService
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import com.aesprt.aquahub.domain.repository.GooglePlayBillingRepository
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val googlePlayBillingRepository: GooglePlayBillingRepository by inject()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            AquaHubMessagingService.registerCurrentDeviceToken(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        AquaHubMessagingService.createNotificationChannel(this)
        AquaHubMessagingService.registerCurrentDeviceToken(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            AquaHubTheme {
                val navController = rememberNavController()
                AppNavGraph(navController = navController)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the Play Store can leave an existing BillingClient
        // disconnected. connect() reuses a ready client or starts a new
        // connection, and its success callback restores owned subscriptions.
        googlePlayBillingRepository.connect()
    }
}
