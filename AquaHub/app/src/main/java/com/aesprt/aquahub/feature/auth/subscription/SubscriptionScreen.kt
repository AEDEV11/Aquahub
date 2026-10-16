package com.aesprt.aquahub.feature.auth.subscription

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.AquaHubGlassCard
import com.aesprt.aquahub.core.designsystem.components.AdaptiveContentFrame
import com.aesprt.aquahub.core.designsystem.components.OnboardingStepperHeader
import com.aesprt.aquahub.domain.model.DEFAULT_SUBSCRIPTION_PLANS
import com.aesprt.aquahub.domain.model.SubscriptionPlan
import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.BillingOperationState
import com.aesprt.aquahub.feature.auth.onboarding.OnboardingUiState
import com.aesprt.aquahub.feature.auth.onboarding.OnboardingViewModel
import com.aesprt.aquahub.ui.theme.*

@Composable
fun SubscriptionScreen(
    viewModel: OnboardingViewModel,
    onBackClick: () -> Unit,
    onPlanSelected: (plan: SubscriptionPlan, activity: Activity) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val activity = LocalContext.current.findActivity()

    SubscriptionContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onToggleBilling = viewModel::toggleBillingCycle,
        onSelectPlan = { plan ->
            viewModel.setPlan(plan)
            if (activity != null) {
                onPlanSelected(plan, activity)
            }
        }
    )
}

private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return context as? Activity
}

@Composable
fun SubscriptionContent(
    uiState: OnboardingUiState,
    onBackClick: () -> Unit,
    onToggleBilling: (Boolean) -> Unit,
    onSelectPlan: (SubscriptionPlan) -> Unit,
    isLoading: Boolean = uiState.isLoading
) {
    Scaffold(
        topBar = {
            OnboardingStepperHeader(
                currentStep = 3,
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AdaptiveContentFrame(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            maxWidth = 1480.dp
        ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 380.dp),
            contentPadding = PaddingValues(
                start = AquaHubSpacing.medium,
                end = AquaHubSpacing.medium,
                top = AquaHubSpacing.medium,
                bottom = AquaHubSpacing.extraLarge
            ),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
            modifier = Modifier.fillMaxSize()
        ) {
            // Headline Section
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = AquaHubSpacing.small)
                ) {
                    Surface(
                        shape = AquaHubShapes.chip,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, GlassBorderAqua)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Diamond,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Simple, Honest Pricing",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                    Text(
                        text = if (uiState.trialAlreadyUsed) "Choose a Paid Plan for Your Water Station" else "Choose the Right Plan for Your Water Station",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onBackground
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    Text(
                        text = when {
                            uiState.trialAlreadyUsed -> "This owner identity has already used its free trial. Paid plans require verified billing activation."
                            uiState.selectedPlan.trialDays > 0 -> {
                            "Eligible new owners get a ${uiState.selectedPlan.trialDays}-day AquaHub trial. No Google Play checkout or charge at signup."
                            }
                            else -> {
                            "Start with the Free plan for core tools for one water station. Upgrade when your business is ready."
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Normal
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }

            uiState.errorMessage?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(AquaHubSpacing.medium)
                        )
                    }
                }
            }

            // Monthly / Annual Toggle
            item(span = { GridItemSpan(maxLineSpan) }) {
                BillingToggleCard(
                    isAnnual = uiState.isAnnualBilling,
                    onToggle = { annual ->
                        if (!isLoading) onToggleBilling(annual)
                    }
                )
            }

            // Plan entitlement summary
            item(span = { GridItemSpan(maxLineSpan) }) {
                AquaHubGlassCard(
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    borderColor = GlassBorderAqua,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .shadow(
                                    elevation = 6.dp,
                                    shape = CircleShape,
                                    spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                                .background(
                                    brush = Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.tertiary
                                        )
                                    ),
                                    shape = CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(AquaHubSpacing.mediumSmall))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    uiState.trialAlreadyUsed && uiState.selectedPlan.code != com.aesprt.aquahub.domain.model.SubscriptionPlanCode.FREE -> "Paid Plan · No Trial"
                                    uiState.selectedPlan.trialDays > 0 -> "${uiState.selectedPlan.trialDays}-Day Free Trial Included"
                                    else -> "Free Plan · No Trial"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = when {
                                    uiState.trialAlreadyUsed -> "Your trial entitlement has already been used. Select Free or continue through verified paid billing when available."
                                    uiState.selectedPlan.trialDays > 0 -> {
                                    "Explore the selected plan now. Activate payment through Google Play after the trial ends."
                                    }
                                    else -> {
                                    "Use AquaHub for free with the core tools needed to run one water station."
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 16.sp
                                )
                            )
                        }
                    }
                }
            }

            // Subscription Plan Cards
            items(DEFAULT_SUBSCRIPTION_PLANS, key = { it.id }) { plan ->
                val isSelected = uiState.selectedPlan.code == plan.code
                val billingCycle = if (uiState.isAnnualBilling) BillingCycle.ANNUAL else BillingCycle.MONTHLY
                val pricePeriod = if (uiState.isAnnualBilling) "/ year" else "/ month"
                val playOffer = uiState.billingOffers.firstOrNull {
                    it.planCode == plan.code && it.billingCycle == billingCycle
                }

                PlanCard(
                    plan = plan,
                    isSelected = isSelected,
                    formattedPrice = if (plan.code == com.aesprt.aquahub.domain.model.SubscriptionPlanCode.FREE) {
                        "Free"
                    } else {
                        playOffer?.formattedPrice ?: "Price unavailable"
                    },
                    pricePeriod = if (plan.code == com.aesprt.aquahub.domain.model.SubscriptionPlanCode.FREE) "" else pricePeriod,
                    pricingTerms = playOffer?.introductoryTerms
                        ?: if (plan.code == com.aesprt.aquahub.domain.model.SubscriptionPlanCode.FREE) {
                            "No Google Play subscription is required."
                        } else if (playOffer == null) {
                            "Connect to Google Play to load this plan's eligible price before continuing."
                        } else {
                            "Review billing, renewal, and cancellation terms in Google Play checkout."
                        },
                    canSelect = plan.code == com.aesprt.aquahub.domain.model.SubscriptionPlanCode.FREE || playOffer != null,
                    trialAlreadyUsed = uiState.trialAlreadyUsed,
                    isLoading = isLoading,
                    onSelect = { if (!isLoading) onSelectPlan(plan) }
                )
            }

            if (isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                        Text(
                            text = when (uiState.billingOperation) {
                                is BillingOperationState.Purchasing -> "Opening Google Play checkout…"
                                BillingOperationState.Verifying -> "Verifying your subscription…"
                                else -> "Setting up your AquaHub account…"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Supporting Message
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AquaHubSpacing.medium)
                ) {
                    Text(
                        text = "Less paperwork. Fewer mistakes. Better service. More time to grow your business.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun BillingToggleCard(
    isAnnual: Boolean,
    onToggle: (Boolean) -> Unit
) {
    AquaHubGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                shape = AquaHubShapes.cardSecondary,
                color = if (!isAnnual) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f) else Color.Transparent,
                border = if (!isAnnual) BorderStroke(1.dp, GlassBorderAqua) else null,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onToggle(false) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(vertical = 10.dp)
                ) {
                    Text(
                        text = "Monthly",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (!isAnnual) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (!isAnnual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = AquaHubShapes.cardSecondary,
                color = if (isAnnual) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f) else Color.Transparent,
                border = if (isAnnual) BorderStroke(1.dp, GlassBorderAqua) else null,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onToggle(true) }
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 10.dp)
                ) {
                    Text(
                        text = "Annual",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (isAnnual) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isAnnual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = CircleShape,
                        color = SuccessGreenLight,
                        border = BorderStroke(0.5.dp, SuccessGreen.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "Annual plan",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = SuccessGreen,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 10.sp
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: SubscriptionPlan,
    isSelected: Boolean,
    formattedPrice: String,
    pricePeriod: String,
    pricingTerms: String,
    canSelect: Boolean,
    trialAlreadyUsed: Boolean,
    isLoading: Boolean = false,
    onSelect: () -> Unit
) {
    AquaHubGlassCard(
        borderColor = if (isSelected) {
            MaterialTheme.colorScheme.primary
        } else if (plan.isPopular) {
            GlassBorderAqua
        } else {
            GlassBorderAqua.copy(alpha = 0.6f)
        },
        backgroundColor = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        if (plan.isPopular) {
            Surface(
                shape = AquaHubShapes.chip,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = AquaHubSpacing.small)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "MOST POPULAR",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plan.name,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                if (!plan.description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = plan.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

        // Price Row
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formattedPrice,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = pricePeriod,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                ),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Text(
            text = pricingTerms,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))
        HorizontalDivider(color = GlassBorderAqua.copy(alpha = 0.4f), thickness = 1.dp)
        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        // Features List
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            plan.features.filter { it.enabled }.distinctBy { it.feature.displayName }.forEach { entitlement ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val limitSuffix = if (entitlement.limit != null) " (up to ${entitlement.limit})" else ""
                    Text(
                        text = "${entitlement.feature.displayName}$limitSuffix",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

        // Select Plan Button
        if (isSelected) {
            com.aesprt.aquahub.core.designsystem.components.AquaHubPrimaryButton(
                text = if (trialAlreadyUsed && plan.code != com.aesprt.aquahub.domain.model.SubscriptionPlanCode.FREE) {
                    "Continue with ${plan.name} (Paid)"
                } else {
                    plan.ctaText
                },
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                isLoading = isLoading,
                enabled = !isLoading && canSelect,
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            com.aesprt.aquahub.core.designsystem.components.AquaHubOutlinedButton(
                text = if (trialAlreadyUsed && plan.code != com.aesprt.aquahub.domain.model.SubscriptionPlanCode.FREE) {
                    "Choose ${plan.name} (Paid)"
                } else {
                    plan.ctaText
                },
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                enabled = !isLoading && canSelect,
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun SubscriptionScreenPreview() {
    AquaHubTheme {
        SubscriptionContent(
            uiState = OnboardingUiState(),
            onBackClick = {},
            onToggleBilling = {},
            onSelectPlan = {}
        )
    }
}
