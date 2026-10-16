package com.aesprt.aquahub.feature.auth.onboarding

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.AquaHubButton
import com.aesprt.aquahub.core.designsystem.components.AquaHubLogo
import com.aesprt.aquahub.core.designsystem.components.AquaHubOutlinedButton
import com.aesprt.aquahub.core.designsystem.components.AdaptiveContentFrame
import com.aesprt.aquahub.ui.theme.*
import kotlinx.coroutines.launch
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview

private data class OnboardingSlideData(
    val badge: String,
    val headline: String,
    val body: String,
    val bulletPoints: List<String>,
    val supportingText: String,
    val icon: ImageVector,
    val gradientColors: List<Color>
)

private val ONBOARDING_SLIDES = listOf(
    // Screen 1 — Welcome
    OnboardingSlideData(
        badge = "WELCOME TO AQUAHUB",
        headline = "Run Your Water Station Smarter",
        body = "Manage your water-refilling business from one simple app.",
        bulletPoints = listOf(
            "Track customer orders & walk-in sales",
            "Monitor 5G & 3G container inventory",
            "Dispatch fleet delivery riders",
            "Real-time sales and revenue metrics"
        ),
        supportingText = "Track orders, customers, inventory, deliveries, riders, and sales — all in one place.",
        icon = Icons.Rounded.WaterDrop,
        gradientColors = listOf(Color(0xFF0077B6), Color(0xFF00B4D8))
    ),
    // Screen 2 — Manage Orders Easily
    OnboardingSlideData(
        badge = "ORDER MANAGEMENT",
        headline = "From Order to Delivery",
        body = "Receive and manage customer orders without relying on notebooks, chat messages, or manual lists.",
        bulletPoints = listOf(
            "Accept and manage orders",
            "Track order preparation",
            "Assign delivery riders",
            "Monitor delivery status",
            "Keep complete order history"
        ),
        supportingText = "Spend less time organizing orders and more time growing your business.",
        icon = Icons.Rounded.Receipt,
        gradientColors = listOf(Color(0xFF023E8A), Color(0xFF0077B6))
    ),
    // Screen 3 — Know Your Business
    OnboardingSlideData(
        badge = "STATION ANALYTICS",
        headline = "Your Water Station, At a Glance",
        body = "See how your business is performing every day.",
        bulletPoints = listOf(
            "Daily and monthly sales",
            "Number of orders",
            "Best-selling products",
            "Customer activity",
            "Available containers",
            "Containers currently with customers"
        ),
        supportingText = "Make better decisions using information you can understand instantly.",
        icon = Icons.AutoMirrored.Rounded.TrendingUp,
        gradientColors = listOf(Color(0xFF0077B6), Color(0xFF52B788))
    ),
    // Screen 4 — Control Containers & Inventory
    OnboardingSlideData(
        badge = "CONTAINER CONTROL",
        headline = "Never Lose Track of Your Containers",
        body = "Know exactly where your containers are.",
        bulletPoints = listOf(
            "Available containers in stock",
            "Containers delivered to customers",
            "Returned containers count",
            "Damaged or missing containers log",
            "Product and refill stock levels"
        ),
        supportingText = "Reduce lost containers and avoid unnecessary inventory costs.",
        icon = Icons.Rounded.Inventory2,
        gradientColors = listOf(Color(0xFF0096C7), Color(0xFF48CAE4))
    ),
    // Screen 5 — Simplify Your Deliveries
    OnboardingSlideData(
        badge = "FLEET DISPATCH",
        headline = "Deliver Faster. Stay Organized.",
        body = "Manage your riders and deliveries directly from AquaHub.",
        bulletPoints = listOf(
            "Assign riders to pending orders",
            "Calculate shortest road distances",
            "Monitor active on-the-way deliveries",
            "Organize seamless doorstep handoffs"
        ),
        supportingText = "Give your team a clearer workflow from your station to your customer's doorstep.",
        icon = Icons.Rounded.DeliveryDining,
        gradientColors = listOf(Color(0xFF0077B6), Color(0xFF90E0EF))
    ),
    // Screen 6 — Build Better Customer Relationships
    OnboardingSlideData(
        badge = "CUSTOMER RETENTION",
        headline = "Know Your Customers",
        body = "Keep customer information, addresses, order history, and container balances organized in one place.",
        bulletPoints = listOf(
            "Quick reordering for regulars",
            "Saved delivery address profiles",
            "Container deposit & balance ledger",
            "Outstanding bottle accountability"
        ),
        supportingText = "Your customers come back for water. AquaHub helps make sure they come back to you.",
        icon = Icons.Rounded.People,
        gradientColors = listOf(Color(0xFF03045E), Color(0xFF0077B6))
    )
)

@Composable
fun IntroOnboardingScreen(
    onFinishOnboarding: () -> Unit,
    onNavigateToSubscription: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_SLIDES.size })
    val coroutineScope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == ONBOARDING_SLIDES.size - 1

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AdaptiveContentFrame(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            maxWidth = 1440.dp
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AquaHubSpacing.medium, vertical = AquaHubSpacing.small)
        ) {
            // Top Bar with Logo and Skip Button
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Mini Logo with Shadow
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = CircleShape,
                                ambientColor = MaterialTheme.colorScheme.primary,
                                spotColor = MaterialTheme.colorScheme.primary
                            )
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(4.dp)
                    ) {
                        AquaHubLogo(modifier = Modifier.fillMaxSize())
                    }
                    Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                    Text(
                        text = "AquaHub",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                // Skip Button
                TextButton(
                    onClick = onFinishOnboarding,
                    shape = AquaHubShapes.chip
                ) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(AquaHubSpacing.small))

            // Carousel Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { pageIndex ->
                val slide = ONBOARDING_SLIDES[pageIndex]

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = AquaHubSpacing.small)
                ) {
                    // Feature Icon Orb with Glowing Depth
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(110.dp)
                            .shadow(
                                elevation = 20.dp,
                                shape = CircleShape,
                                ambientColor = slide.gradientColors.first().copy(alpha = 0.5f),
                                spotColor = slide.gradientColors.first().copy(alpha = 0.5f)
                            )
                            .background(
                                brush = Brush.radialGradient(slide.gradientColors),
                                shape = CircleShape
                            )
                            .padding(2.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.15f),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = slide.icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                    // Badge Pill
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        shape = AquaHubShapes.chip,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = slide.badge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 1.sp
                            ),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    // Headline
                    Text(
                        text = slide.headline,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Body
                    Text(
                        text = slide.body,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = AquaHubSpacing.small)
                    )

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    // Bullet Points Card
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        shape = AquaHubShapes.cardSecondary,
                        border = BorderStroke(0.5.dp, GlassBorderAqua),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            slide.bulletPoints.forEach { point ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = point,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.Medium
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    // Supporting Text
                    Text(
                        text = slide.supportingText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontWeight = FontWeight.Normal
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = AquaHubSpacing.small)
                    )
                }
            }

            Spacer(modifier = Modifier.height(AquaHubSpacing.small))

            // Indicator Dots (6 Dots)
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                repeat(ONBOARDING_SLIDES.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    val dotWidth by animateDpAsState(
                        targetValue = if (isSelected) 24.dp else 7.dp,
                        label = "dotWidth"
                    )

                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .height(6.dp)
                            .width(dotWidth)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

            // Action Buttons
            if (pagerState.currentPage == 0) {
                // Screen 1 Action Buttons
                Column(
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AquaHubButton(
                        text = "Get Started",
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(1)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onFinishOnboarding) {
                            Text(
                                text = "I already have an account",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            } else if (!isLastPage) {
                // Screens 2 to 5 Continue Buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AquaHubOutlinedButton(
                        text = "Back",
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )

                    AquaHubButton(
                        text = "Continue",
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                        modifier = Modifier.weight(1.6f)
                    )
                }
            } else {
                // Screen 6 Final Actions
                Column(
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Start Free Trial CTA
                    AquaHubButton(
                        text = "Start Free Trial",
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        onClick = onNavigateToSubscription,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Secondary Actions Row
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onFinishOnboarding) {
                            Text(
                                text = "I already have an account",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }

                    }
                }
            }
        }
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun IntroOnboardingScreenPreview() {
    AquaHubTheme {
        IntroOnboardingScreen(
            onFinishOnboarding = {},
            onNavigateToSubscription = {},
        )
    }
}
