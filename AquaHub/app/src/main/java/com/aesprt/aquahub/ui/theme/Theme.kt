package com.aesprt.aquahub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = AquaPrimary,
    onPrimary = White,
    primaryContainer = LightAqua,
    onPrimaryContainer = NavyDeep,
    secondary = AquaCyan,
    onSecondary = White,
    secondaryContainer = Color(0xFFE0F7FA),
    onSecondaryContainer = NavyDeep,
    tertiary = NavyDeep,
    onTertiary = White,
    tertiaryContainer = LightAqua,
    onTertiaryContainer = NavyDeep,
    background = AquaBackground,
    onBackground = NeutralGray900,
    surface = AquaSurface,
    onSurface = NeutralGray900,
    surfaceVariant = NeutralGray50,
    onSurfaceVariant = NeutralGray600,
    surfaceContainer = White,
    surfaceContainerHigh = Color(0xFFF0F6FE),
    surfaceContainerLowest = White,
    outline = NeutralGray300,
    outlineVariant = NeutralGray200,
    error = ErrorRed,
    onError = White,
    errorContainer = ErrorRedLight,
    onErrorContainer = ErrorRed,
    inverseSurface = NeutralGray800,
    inverseOnSurface = White,
    inversePrimary = AquaPrimaryDark
)

private val DarkColorScheme = darkColorScheme(
    primary = AquaPrimaryDark,
    onPrimary = NavyDark,
    primaryContainer = NavyDeep,
    onPrimaryContainer = AquaCyan,
    secondary = AquaCyan,
    onSecondary = NavyDark,
    secondaryContainer = Color(0xFF003F47),
    onSecondaryContainer = AquaCyan,
    tertiary = AquaCyan,
    onTertiary = NavyDark,
    background = BackgroundDark,
    onBackground = White,
    surface = SurfaceDark,
    onSurface = White,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = NeutralGray300,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceContainerDark,
    surfaceContainerLowest = NavyDark,
    outline = NeutralGray600,
    outlineVariant = NeutralGray700,
    error = ErrorRed,
    onError = White,
    errorContainer = Color(0xFF4A0000),
    onErrorContainer = ErrorRed
)

@Composable
fun AquaHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AquaHubTypography,
        content = content
    )
}