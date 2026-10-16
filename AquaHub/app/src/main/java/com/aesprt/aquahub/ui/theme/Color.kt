package com.aesprt.aquahub.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// AquaHub Primary Brand Palette
val AquaPrimary = Color(0xFF1264E6)
val AquaCyan = Color(0xFF16C7E8)
val NavyDeep = Color(0xFF073B8C)
val LightAqua = Color(0xFFDFF8FC)
val AquaBackground = Color(0xFFF5FAFF)
val AquaSurface = Color(0xFFFFFFFF)

// Semantic Colors
val SuccessGreen = Color(0xFF20B26B)
val SuccessGreenLight = Color(0xFFE8F8F0)
val WarningAmber = Color(0xFFF4A623)
val WarningAmberLight = Color(0xFFFEF6E9)
val ErrorRed = Color(0xFFE5484D)
val ErrorRedLight = Color(0xFFFDE8E9)

// Neutral Palette
val NeutralGray900 = Color(0xFF0F172A)
val NeutralGray800 = Color(0xFF1E293B)
val NeutralGray700 = Color(0xFF334155)
val NeutralGray600 = Color(0xFF475569)
val NeutralGray500 = Color(0xFF64748B)
val NeutralGray400 = Color(0xFF94A3B8)
val NeutralGray300 = Color(0xFFCBD5E1)
val NeutralGray200 = Color(0xFFE2E8F0)
val NeutralGray100 = Color(0xFFF1F5F9)
val NeutralGray50 = Color(0xFFF8FAFC)
val White = Color(0xFFFFFFFF)

// Dark Theme Palette
val BackgroundDark = Color(0xFF0B132B)
val SurfaceDark = Color(0xFF1C2541)
val SurfaceContainerDark = Color(0xFF243054)
val NavyDark = Color(0xFF071426)
val AquaPrimaryDark = Color(0xFF3B82F6)

// Glassmorphism & Translucent Accents
val GlassWhite = Color(0xCCFFFFFF)
val GlassWhiteSubtle = Color(0x99FFFFFF)
val GlassBorder = Color(0x4DFFFFFF)
val GlassBorderAqua = Color(0x3316C7E8)
val GlassDark = Color(0xCC1C2541)
val GlassBorderDark = Color(0x333B82F6)

// Gradients
val AquaHeroGradient = Brush.linearGradient(
    colors = listOf(AquaPrimary, AquaCyan)
)
val AquaGlassGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFFFFFFFF), Color(0xEEF0F9FF))
)
val AquaDarkGlassGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF243054), Color(0xFF1C2541))
)
val WaterDropletGradient = Brush.verticalGradient(
    colors = listOf(AquaCyan, AquaPrimary)
)

// Order Status Chip Colors
val StatusPending = Color(0xFFD97706)
val StatusPendingBg = Color(0xFFFEF3C7)

val StatusAccepted = Color(0xFF2563EB)
val StatusAcceptedBg = Color(0xFFDBEAFE)

val StatusPreparing = Color(0xFF0284C7)
val StatusPreparingBg = Color(0xFFE0F2FE)

val StatusReady = Color(0xFF059669)
val StatusReadyBg = Color(0xFFD1FAE5)

val StatusOutForDelivery = Color(0xFF7C3AED)
val StatusOutForDeliveryBg = Color(0xFFEDE9FE)

val StatusDelivered = Color(0xFF0D9488)
val StatusDeliveredBg = Color(0xFFCCFBF1)

val StatusCompleted = Color(0xFF16A34A)
val StatusCompletedBg = Color(0xFFDCFCE7)

val StatusRejected = Color(0xFFDC2626)
val StatusRejectedBg = Color(0xFFFEE2E2)

val StatusCancelled = Color(0xFF6B7280)
val StatusCancelledBg = Color(0xFFF3F4F6)