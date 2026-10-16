package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import coil.compose.AsyncImage
import com.aesprt.aquahub.data.supabase.storage.StorageAssetResolver
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import org.koin.compose.koinInject

@Composable
fun StorageAssetImage(
    reference: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    privateByDefault: Boolean = false,
) {
    if (LocalInspectionMode.current) {
        Box(
            modifier = modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                contentDescription?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        return
    }

    val resolver: StorageAssetResolver = koinInject()
    var resolvedUrl by remember(reference, privateByDefault) { mutableStateOf<String?>(null) }
    LaunchedEffect(reference, privateByDefault) {
        resolvedUrl = runCatching { resolver.resolve(reference, privateByDefault) }.getOrNull()
    }
    AsyncImage(
        model = resolvedUrl,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun StorageAssetImagePreview() {
    AquaHubTheme {
        StorageAssetImage(
            reference = "supabase://station-assets/aquahub-preview.png",
            contentDescription = "Station logo preview",
            modifier = Modifier.fillMaxSize()
        )
    }
}
