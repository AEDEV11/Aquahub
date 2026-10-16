package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.R
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import com.aesprt.aquahub.ui.theme.AquaHubSpacing

/**
 * Shared AquaHub brand mark.
 *
 * The size and any surrounding treatment (card, shadow, border, etc.) belong
 * to the caller so the same logo can be used consistently across surfaces.
 */
@Composable
fun AquaHubLogo(
    modifier: Modifier = Modifier,
    contentDescription: String? = "AquaHub logo",
    contentScale: ContentScale = ContentScale.Fit
) {
    Image(
        painter = painterResource(id = R.drawable.aquahub_new_logo),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier
    )
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun AquaHubLogoPreview() {
    AquaHubTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                modifier = Modifier.fillMaxSize().padding(AquaHubSpacing.large),
                contentAlignment = Alignment.Center
            ) {
                AquaHubLogo(modifier = Modifier.size(220.dp))
            }
        }
    }
}
