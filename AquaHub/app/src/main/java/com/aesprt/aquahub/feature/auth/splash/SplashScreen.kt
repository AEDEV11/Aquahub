package com.aesprt.aquahub.feature.auth.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.AquaHubLogo
import com.aesprt.aquahub.ui.theme.*

@Composable
fun SplashScreen(
    viewModel: SplashViewModel,
    onNavigateToOnboarding: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToDashboard: () -> Unit
) {
    val target by viewModel.target.collectAsState()

    LaunchedEffect(target) {
        when (target) {
            SplashNavigationTarget.ToOnboarding -> onNavigateToOnboarding()
            SplashNavigationTarget.ToLogin -> onNavigateToLogin()
            SplashNavigationTarget.ToDashboard -> onNavigateToDashboard()
            SplashNavigationTarget.Loading -> {}
        }
    }

    SplashContent()
}

@Composable
fun SplashContent() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val rippleRadius by infiniteTransition.animateFloat(
        initialValue = 60f,
        targetValue = 120f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rippleRadius"
    )

    val rippleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rippleAlpha"
    )

    val rippleColor = MaterialTheme.colorScheme.primary

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        // Water ripple canvas
        Canvas(modifier = Modifier.size(260.dp)) {
            drawCircle(
                color = rippleColor.copy(alpha = rippleAlpha),
                radius = rippleRadius.dp.toPx(),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = rippleColor.copy(alpha = (rippleAlpha * 0.6f)),
                radius = (rippleRadius * 0.7f).dp.toPx(),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Enhanced Logo with Dark-Mode Contrast Backing & Glowing Shadow
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(200.dp)
                    .scale(scale)
                    .shadow(
                        elevation = 20.dp,
                        shape = CircleShape,
                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shape = CircleShape
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                GlassBorderAqua
                            )
                        ),
                        shape = CircleShape
                    )
                    .padding(14.dp)
            ) {
                AquaHubLogo(modifier = Modifier.fillMaxSize())
            }

            Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))
            Text(
                text = "WATER STATION MANAGER",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun SplashScreenPreview() {
    AquaHubTheme {
        SplashContent()
    }
}
