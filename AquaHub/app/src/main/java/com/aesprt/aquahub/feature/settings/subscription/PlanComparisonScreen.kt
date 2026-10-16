package com.aesprt.aquahub.feature.settings.subscription

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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.AquaHubButton
import com.aesprt.aquahub.core.designsystem.components.AquaHubGlassCard
import com.aesprt.aquahub.core.designsystem.components.AdaptiveContentFrame
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanComparisonScreen(
    viewModel: SubscriptionBillingViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    RefreshBillingOnResume(viewModel)
    var isAnnual by remember(uiState.currentSubscription) {
        mutableStateOf(uiState.currentSubscription?.billingCycle == BillingCycle.ANNUAL)
    }

    PlanComparisonContent(
        uiState = uiState,
        isAnnual = isAnnual,
        onToggleBilling = { isAnnual = it },
        onSelectPlan = { planId ->
            val cycle = if (isAnnual) BillingCycle.ANNUAL else BillingCycle.MONTHLY
            if (activity != null) {
                viewModel.purchasePlan(activity, planId, cycle)
            }
        },
        onSelectFree = { activity?.let(viewModel::requestFreePlan) },
        onRetryRestore = viewModel::restorePurchases,
        onBackClick = onBackClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanComparisonContent(
    uiState: SubscriptionBillingUiState,
    isAnnual: Boolean,
    onToggleBilling: (Boolean) -> Unit,
    onSelectPlan: (Long) -> Unit,
    onSelectFree: () -> Unit,
    onRetryRestore: () -> Unit,
    onBackClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Compare Subscription Plans",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AdaptiveContentFrame(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            maxWidth = 1480.dp
        ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            contentPadding = PaddingValues(
                start = AquaHubSpacing.medium,
                end = AquaHubSpacing.medium,
                top = AquaHubSpacing.small,
                bottom = AquaHubSpacing.extraLarge
            ),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
            modifier = Modifier.fillMaxSize()
        ) {
            if (uiState.billingAvailability != BillingAvailability.AVAILABLE ||
                uiState.billingOperation is BillingOperationState.Failed
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                        Text(
                            text = when (val operation = uiState.billingOperation) {
                                is BillingOperationState.Failed -> operation.message
                                else -> when (uiState.billingAvailability) {
                                    BillingAvailability.CONNECTING -> "Connecting to Google Play…"
                                    BillingAvailability.BILLING_UNAVAILABLE -> "Google Play billing is temporarily unavailable. Check Google Play and try again."
                                    BillingAvailability.NETWORK_ERROR -> "Google Play could not be reached. Check your connection and try again."
                                    BillingAvailability.NOT_CONFIGURED -> "Google Play returned no eligible plan prices for this app and account. Check the Play Store installation and product/base-plan setup. Free and trial access continue to work."
                                    BillingAvailability.AVAILABLE -> ""
                                }
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.billingOperation is BillingOperationState.Failed) {
                            TextButton(onClick = onRetryRestore) {
                                Text("Restore purchase / retry")
                            }
                        }
                    }
                }
            }
            // Billing Cycle Switcher
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    shape = AquaHubShapes.cardSecondary,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, GlassBorderAqua),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                    ) {
                        Surface(
                            shape = AquaHubShapes.chip,
                            color = if (!isAnnual) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onToggleBilling(false) }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = "Monthly Billing",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (!isAnnual) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (!isAnnual) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }

                        Surface(
                            shape = AquaHubShapes.chip,
                            color = if (isAnnual) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onToggleBilling(true) }
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 10.dp)
                            ) {
                                Text(
                                    text = "Annual Billing",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isAnnual) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (isAnnual) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = if (isAnnual) Color.White.copy(alpha = 0.25f) else SuccessGreen.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "Annual",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isAnnual) Color.White else SuccessGreen
                                        ),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Plan Cards
            items(uiState.availablePlans, key = { it.id }, span = { GridItemSpan(1) }) { plan ->
                val billingCycle = if (isAnnual) BillingCycle.ANNUAL else BillingCycle.MONTHLY
                val isCurrentPlan = SubscriptionBillingPolicy.isCurrentPlanSelection(
                    subscription = uiState.currentSubscription,
                    resolvedState = uiState.resolvedState,
                    planId = plan.id,
                    billingCycle = billingCycle
                )
                val playOffer = uiState.billingOffers.firstOrNull {
                    it.planCode == plan.code && it.billingCycle == billingCycle
                }
                val freePlan = plan.code == SubscriptionPlanCode.FREE
                val canCancelToFree = freePlan && SubscriptionBillingPolicy.canCancelToFree(
                    uiState.currentSubscription, uiState.resolvedState
                )
                val playOwnedWhileFree = freePlan && SubscriptionBillingPolicy.hasOwnedPlayWhileFree(
                    uiState.currentSubscription, uiState.ownedPlayProductId
                )
                val canManagePaidForFree = freePlan && (
                    playOwnedWhileFree || SubscriptionBillingPolicy.canManagePaidPlayForFree(
                        uiState.currentSubscription, uiState.resolvedState
                    )
                )
                val freeAfterPaidPeriod = freePlan &&
                    uiState.currentSubscription?.provider == "GOOGLE_PLAY" &&
                    uiState.currentSubscription.planCode != SubscriptionPlanCode.FREE &&
                    uiState.resolvedState != ResolvedSubscriptionState.EXPIRED &&
                    (uiState.currentSubscription.cancelAtPeriodEnd ||
                        uiState.currentSubscription.status == SubscriptionStatus.CANCELLED)
                val requiresPlayManagement = !freePlan && SubscriptionBillingPolicy.requiresPlayManagementBeforePlanChange(
                    uiState.currentSubscription, uiState.resolvedState, uiState.ownedPlayProductId
                )
                val billingBusy = uiState.billingOperation is BillingOperationState.Purchasing ||
                    uiState.billingOperation == BillingOperationState.Verifying
                val cycleLabel = if (freePlan) "" else if (isAnnual) "/ year" else "/ month"

                AquaHubGlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Header Row
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = plan.name,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (plan.isPopular) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = AquaHubShapes.chip,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "MOST POPULAR",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            if (isCurrentPlan) {
                                Surface(
                                    shape = AquaHubShapes.chip,
                                    color = SuccessGreen.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = "CURRENT",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = SuccessGreen
                                        ),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        if (!plan.description.isNullOrBlank()) {
                            Text(
                                text = plan.description,
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }

                        // Pricing
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = if (freePlan) "Free" else playOffer?.formattedPrice ?: "Price unavailable",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                text = cycleLabel,
                                style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                            )
                        }
                        Text(
                            text = when {
                                playOwnedWhileFree -> "Google Play reports an AquaHub subscription on this Play account. Manage it in Play or restore it after signing into its AquaHub account. Free access remains until verification."
                                canCancelToFree -> "Cancel your paid subscription in Google Play. Paid access continues until the current period ends."
                                freeAfterPaidPeriod -> "Your paid access continues until the current period ends. Free applies after that."
                                canManagePaidForFree -> "Manage or cancel your Google Play subscription there. Your access follows its billing status."
                                freePlan -> "No Google Play subscription is required."
                                requiresPlayManagement -> "Manage your existing Google Play subscription before changing plans."
                                playOffer?.introductoryTerms != null -> playOffer.introductoryTerms.orEmpty()
                                playOffer == null -> "Connect to Google Play to load this plan's eligible price."
                                else -> "Review billing, renewal, and cancellation terms in Google Play checkout."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)

                        // Feature Checklist
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            plan.features.filter { it.enabled }.distinctBy { it.feature.displayName }.forEach { entitlement ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val limitSuffix = if (entitlement.limit != null) " (up to ${entitlement.limit})" else ""
                                    Text(
                                        text = "${entitlement.feature.displayName}$limitSuffix",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                        // CTA Button
                        if (canManagePaidForFree) {
                            AquaHubButton(
                                text = if (canCancelToFree) "Cancel Paid Plan in Google Play" else "Manage Subscription in Google Play",
                                icon = Icons.Rounded.Diamond,
                                onClick = onSelectFree,
                                enabled = !billingBusy,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (requiresPlayManagement) {
                            AquaHubButton(
                                text = "Manage Subscription in Google Play",
                                icon = Icons.Rounded.Diamond,
                                onClick = { onSelectPlan(plan.id) },
                                enabled = !billingBusy,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (isCurrentPlan || freePlan || playOffer == null) {
                            OutlinedButton(
                                onClick = {},
                                enabled = false,
                                shape = AquaHubShapes.button,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isCurrentPlan) "Your Active Plan" else if (freeAfterPaidPeriod) "Free after paid period" else if (freePlan) "Free plan" else "Price unavailable")
                            }
                        } else {
                            AquaHubButton(
                                text = "Choose ${plan.name}",
                                icon = Icons.Rounded.Diamond,
                                onClick = { onSelectPlan(plan.id) },
                                enabled = !billingBusy,
                                modifier = Modifier.fillMaxWidth()
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
private fun PlanComparisonScreenPreview() {
    AquaHubTheme {
        PlanComparisonContent(
            uiState = SubscriptionBillingUiState(
                isLoading = false,
                currentSubscription = Subscription(
                    id = 1L,
                    stationId = 1L,
                    planId = 2L,
                    planCode = SubscriptionPlanCode.BUSINESS,
                    status = SubscriptionStatus.ACTIVE,
                    billingCycle = BillingCycle.MONTHLY,
                    startedAt = 0L,
                    currentPeriodStart = 0L,
                    currentPeriodEnd = 0L
                ),
                availablePlans = listOf(
                    SubscriptionPlan(
                        id = 1L,
                        code = SubscriptionPlanCode.STARTER,
                        name = "Starter",
                        description = "For small water stations getting started",
                        monthlyPrice = 199.0,
                        annualPrice = annualPriceFromMonthly(199.0),
                        ctaText = "Choose Starter",
                        features = listOf(
                            FeatureEntitlement(SubscriptionFeature.DASHBOARD, true),
                            FeatureEntitlement(SubscriptionFeature.ORDER_MANAGEMENT, true),
                            FeatureEntitlement(SubscriptionFeature.RIDER_MANAGEMENT, true, 2L)
                        )
                    ),
                    SubscriptionPlan(
                        id = 2L,
                        code = SubscriptionPlanCode.BUSINESS,
                        name = "Business",
                        description = "For growing water-refilling stations",
                        monthlyPrice = 499.0,
                        annualPrice = annualPriceFromMonthly(499.0),
                        isPopular = true,
                        ctaText = "Choose Business",
                        features = listOf(
                            FeatureEntitlement(SubscriptionFeature.DASHBOARD, true),
                            FeatureEntitlement(SubscriptionFeature.ORDER_MANAGEMENT, true),
                            FeatureEntitlement(SubscriptionFeature.RIDER_MANAGEMENT, true, 10L),
                            FeatureEntitlement(SubscriptionFeature.ADVANCED_REPORTS, true)
                        )
                    )
                )
            ),
            isAnnual = false,
            onToggleBilling = {},
            onSelectPlan = {},
            onSelectFree = {},
            onRetryRestore = {},
            onBackClick = {}
        )
    }
}
