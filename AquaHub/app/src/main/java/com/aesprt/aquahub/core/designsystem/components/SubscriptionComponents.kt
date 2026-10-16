package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.domain.model.ResolvedSubscriptionState
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.ui.theme.*

@Composable
fun SubscriptionBanner(
    subscriptionState: ResolvedSubscriptionState,
    remainingDays: Int,
    onRenewClick: () -> Unit,
    onViewPlansClick: () -> Unit,
    onResumeSubscriptionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = subscriptionState != ResolvedSubscriptionState.ACTIVE,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        when (subscriptionState) {
            ResolvedSubscriptionState.TRIAL_ACTIVE -> {
                Surface(
                    shape = AquaHubShapes.cardPrimary,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = AquaHubSpacing.medium, vertical = AquaHubSpacing.small)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (remainingDays <= 1) "Trial ends ${if (remainingDays == 0) "today" else "tomorrow"}" else "$remainingDays days remaining in free trial",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "Explore all features without limitations",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                )
                            }
                        }

                        Button(
                            onClick = onViewPlansClick,
                            shape = AquaHubShapes.chip,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("View Plans", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            ResolvedSubscriptionState.GRACE_PERIOD -> {
                Surface(
                    shape = AquaHubShapes.cardPrimary,
                    color = WarningAmberLight,
                    border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.5f)),
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = AquaHubSpacing.medium, vertical = AquaHubSpacing.small)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(WarningAmber, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Grace Period: $remainingDays days left",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Renew now to prevent service interruption",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }

                        Button(
                            onClick = onRenewClick,
                            shape = AquaHubShapes.chip,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WarningAmber,
                                contentColor = Color.Black
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Renew", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END -> {
                Surface(
                    shape = AquaHubShapes.cardPrimary,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    border = BorderStroke(1.dp, GlassBorderAqua),
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = AquaHubSpacing.medium, vertical = AquaHubSpacing.small)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.EventBusy,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Subscription set to cancel",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Active access remains for $remainingDays days",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }

                        TextButton(onClick = onResumeSubscriptionClick) {
                            Text("Resume", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            ResolvedSubscriptionState.EXPIRED -> {
                Surface(
                    shape = AquaHubShapes.cardPrimary,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = AquaHubSpacing.medium, vertical = AquaHubSpacing.small)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Subscription Expired",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Data is safe. Renew to manage active orders",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f))
                                )
                            }
                        }

                        Button(
                            onClick = onRenewClick,
                            shape = AquaHubShapes.chip,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Renew", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            ResolvedSubscriptionState.SUSPENDED -> {
                Surface(
                    shape = AquaHubShapes.cardPrimary,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = AquaHubSpacing.medium, vertical = AquaHubSpacing.small)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Account Suspended. Please contact support.",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
fun SubscriptionGate(
    accessResult: SubscriptionAccessResult,
    onUpgradeClick: () -> Unit,
    onRenewClick: () -> Unit,
    content: @Composable () -> Unit
) {
    when (accessResult) {
        is SubscriptionAccessResult.Allowed -> {
            content()
        }
        is SubscriptionAccessResult.FeatureNotIncluded -> {
            LockedFeatureCard(
                title = accessResult.feature.displayName,
                description = "Available on ${accessResult.requiredPlans.joinToString(" and ") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }}. Upgrade your plan to unlock full capabilities.",
                buttonText = "Upgrade Plan",
                icon = Icons.Rounded.Diamond,
                onButtonClick = onUpgradeClick
            )
        }
        is SubscriptionAccessResult.SubscriptionExpired -> {
            LockedFeatureCard(
                title = "Subscription Required",
                description = "Your subscription has expired. Renew your plan to continue using this feature.",
                buttonText = "Renew Subscription",
                icon = Icons.Rounded.Lock,
                onButtonClick = onRenewClick
            )
        }
        is SubscriptionAccessResult.SubscriptionSuspended -> {
            LockedFeatureCard(
                title = "Account Suspended",
                description = accessResult.message ?: "Account restricted due to administrative hold.",
                buttonText = "Contact Support",
                icon = Icons.Rounded.Block,
                onButtonClick = {}
            )
        }
        is SubscriptionAccessResult.LimitReached -> {
            LockedFeatureCard(
                title = "Limit Reached (${accessResult.current}/${accessResult.maximum})",
                description = "You have reached the maximum allowed ${accessResult.limitType.displayName.lowercase()} for your current plan. Upgrade to increase your limit.",
                buttonText = "Upgrade Plan",
                icon = Icons.Rounded.TrendingUp,
                onButtonClick = onUpgradeClick
            )
        }
    }
}

@Composable
fun LockedFeatureCard(
    title: String,
    description: String,
    buttonText: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AquaHubGlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(AquaHubSpacing.medium)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AquaHubSpacing.large, horizontal = AquaHubSpacing.medium)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(AquaHubSpacing.small))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(AquaHubSpacing.large))

            AquaHubButton(
                text = buttonText,
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                onClick = onButtonClick,
                modifier = Modifier.fillMaxWidth(0.85f)
            )
        }
    }
}

@Composable
fun PlanBadge(
    planCode: com.aesprt.aquahub.domain.model.SubscriptionPlanCode,
    modifier: Modifier = Modifier
) {
    val (bg, fg, label) = when (planCode) {
        com.aesprt.aquahub.domain.model.SubscriptionPlanCode.FREE -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "FREE"
        )
        com.aesprt.aquahub.domain.model.SubscriptionPlanCode.STARTER -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "STARTER"
        )
        com.aesprt.aquahub.domain.model.SubscriptionPlanCode.BUSINESS -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.primary,
            "BUSINESS"
        )
        com.aesprt.aquahub.domain.model.SubscriptionPlanCode.PRO -> Triple(
            NavyDeep,
            AquaCyan,
            "PRO"
        )
    }

    Surface(
        color = bg,
        shape = AquaHubShapes.chip,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.3f)),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                color = fg,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun SubscriptionStatusChip(
    state: ResolvedSubscriptionState,
    modifier: Modifier = Modifier
) {
    val (label, bg, fg) = when (state) {
        ResolvedSubscriptionState.ACTIVE -> Triple("ACTIVE", SuccessGreenLight, SuccessGreen)
        ResolvedSubscriptionState.TRIAL_ACTIVE -> Triple("TRIAL ACTIVE", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
        ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END -> Triple("CANCELLING", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        ResolvedSubscriptionState.GRACE_PERIOD -> Triple("GRACE PERIOD", WarningAmberLight, WarningAmber)
        ResolvedSubscriptionState.EXPIRED -> Triple("EXPIRED", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
        ResolvedSubscriptionState.PAST_DUE -> Triple("PAST DUE", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
        ResolvedSubscriptionState.SUSPENDED -> Triple("SUSPENDED", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
    }

    Surface(
        color = bg,
        shape = AquaHubShapes.chip,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.35f)),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                color = fg,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun PlanUsageIndicator(
    label: String,
    currentUsage: Long,
    maxLimit: Long,
    modifier: Modifier = Modifier
) {
    val progress = if (maxLimit > 0) (currentUsage.toFloat() / maxLimit.toFloat()).coerceIn(0f, 1f) else 0f
    val isNearLimit = progress >= 0.85f

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Text(
                text = "$currentUsage / $maxLimit",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            )
        }
        Spacer(modifier = Modifier.height(AquaHubSpacing.extraSmall))
        LinearProgressIndicator(
            progress = { progress },
            color = if (isNearLimit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        )
    }
}

@Composable
fun CurrentPlanCard(
    subscription: com.aesprt.aquahub.domain.model.Subscription?,
    plan: com.aesprt.aquahub.domain.model.SubscriptionPlan?,
    subscriptionState: ResolvedSubscriptionState,
    remainingDays: Int,
    onManageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AquaHubGlassCard(
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plan?.name ?: "Business Plan",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PlanBadge(planCode = plan?.code ?: com.aesprt.aquahub.domain.model.SubscriptionPlanCode.BUSINESS)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (subscription?.billingCycle == com.aesprt.aquahub.domain.model.BillingCycle.ANNUAL) "Annual Billing (Billed yearly)" else "Monthly Billing",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
            }

            SubscriptionStatusChip(state = subscriptionState)
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.medium))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = if (subscriptionState == ResolvedSubscriptionState.TRIAL_ACTIVE) "Trial Remaining" else "Next Renewal",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Text(
                    text = if (subscriptionState == ResolvedSubscriptionState.TRIAL_ACTIVE) "$remainingDays days remaining" else "Active & Protected",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                )
            }

            AquaHubOutlinedButton(
                text = "Manage Plan",
                onClick = onManageClick
            )
        }
    }
}

@Composable
fun PlanComparisonTable(
    plans: List<com.aesprt.aquahub.domain.model.SubscriptionPlan>,
    onSelectPlan: (com.aesprt.aquahub.domain.model.SubscriptionPlan) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
        modifier = modifier.fillMaxWidth()
    ) {
        plans.forEach { plan ->
            AquaHubGlassCard(
                borderColor = if (plan.isPopular) MaterialTheme.colorScheme.primary else GlassBorderAqua,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = plan.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            if (plan.isPopular) {
                                Spacer(modifier = Modifier.width(6.dp))
                                PlanBadge(planCode = plan.code)
                            }
                        }
                        Text(
                            text = "₱${plan.monthlyPrice.toInt()}/mo",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    AquaHubPrimaryButton(
                        text = "Choose",
                        onClick = { onSelectPlan(plan) },
                        containerColor = if (plan.isPopular) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
fun LimitReachedDialog(
    limitResult: SubscriptionAccessResult.LimitReached,
    onDismiss: () -> Unit,
    onUpgradeClick: () -> Unit
) {
    AquaHubConfirmDialog(
        onDismissRequest = onDismiss,
        title = "Plan Limit Reached",
        message = "You've reached your plan's maximum ${limitResult.limitType.displayName.lowercase()} limit (${limitResult.current} of ${limitResult.maximum}). Upgrade your subscription to add more.",
        confirmButtonText = "Upgrade Plan",
        onConfirm = {
            onDismiss()
            onUpgradeClick()
        },
        dismissButtonText = "Cancel",
        icon = Icons.Rounded.Lock,
        isDestructive = false
    )
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun SubscriptionComponentsPreview() {
    AquaHubTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(AquaHubSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
        ) {
            SubscriptionBanner(
                subscriptionState = ResolvedSubscriptionState.TRIAL_ACTIVE,
                remainingDays = 12,
                onRenewClick = {},
                onViewPlansClick = {},
                onResumeSubscriptionClick = {}
            )
            CurrentPlanCard(
                subscription = null,
                plan = null,
                subscriptionState = ResolvedSubscriptionState.TRIAL_ACTIVE,
                remainingDays = 12,
                onManageClick = {}
            )
            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Usage limits", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                PlanUsageIndicator(label = "Team accounts", currentUsage = 3, maxLimit = 5)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                PlanBadge(planCode = com.aesprt.aquahub.domain.model.SubscriptionPlanCode.FREE)
                PlanBadge(planCode = com.aesprt.aquahub.domain.model.SubscriptionPlanCode.BUSINESS)
                SubscriptionStatusChip(state = ResolvedSubscriptionState.GRACE_PERIOD)
            }
            LockedFeatureCard(
                title = "Rider management",
                description = "Assign delivery work to riders and track their active orders.",
                buttonText = "View plans",
                icon = Icons.Rounded.LocalShipping,
                onButtonClick = {}
            )
        }
    }
}
