package com.aesprt.aquahub.feature.settings.subscription

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.AquaHubButton
import com.aesprt.aquahub.core.designsystem.components.AquaHubGlassCard
import com.aesprt.aquahub.core.designsystem.components.AquaHubSectionHeader
import com.aesprt.aquahub.core.designsystem.components.AdaptiveContentFrame
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionBillingScreen(
    viewModel: SubscriptionBillingViewModel,
    onBackClick: () -> Unit,
    onNavigateToPlanComparison: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val activity = LocalActivity.current
    RefreshBillingOnResume(viewModel)

    SubscriptionBillingContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onNavigateToPlanComparison = onNavigateToPlanComparison,
        onRenew = { activity?.let(viewModel::renew) },
        onCancel = { activity?.let(viewModel::cancelSubscription) },
        onResume = { activity?.let(viewModel::resume) },
        onClearMessages = viewModel::clearMessages
    )
}

@Composable
internal fun RefreshBillingOnResume(viewModel: SubscriptionBillingViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAfterPlayReturn()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionBillingContent(
    uiState: SubscriptionBillingUiState,
    onBackClick: () -> Unit,
    onNavigateToPlanComparison: () -> Unit,
    onRenew: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onClearMessages: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val dateTimeFormat = remember { SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()) }

    var showCancelDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Subscription & Billing",
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
        if (uiState.isLoading) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                CircularProgressIndicator()
            }
        } else {
            AdaptiveContentFrame(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                maxWidth = 1320.dp
            ) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = AquaHubSpacing.medium,
                    end = AquaHubSpacing.medium,
                    top = AquaHubSpacing.small,
                    bottom = AquaHubSpacing.extraLarge
                ),
                verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                modifier = Modifier.fillMaxSize()
            ) {
                    if (uiState.billingAvailability != BillingAvailability.AVAILABLE) {
                        item {
                            Text(
                                text = when (uiState.billingAvailability) {
                                    BillingAvailability.CONNECTING -> "Connecting to Google Play…"
                                    BillingAvailability.NOT_CONFIGURED -> "Google Play returned no eligible plan prices for this app and account. Check the Play Store installation and product/base-plan setup. Free and trial access continue to work."
                                    BillingAvailability.BILLING_UNAVAILABLE -> "Google Play billing is temporarily unavailable. Check Google Play and try again."
                                    BillingAvailability.NETWORK_ERROR -> "Google Play could not be reached. Check your connection and try again."
                                    BillingAvailability.AVAILABLE -> ""
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    if (SubscriptionBillingPolicy.hasOwnedPlayWhileFree(
                            uiState.currentSubscription, uiState.ownedPlayProductId
                        )) {
                        item {
                            Text(
                                text = "Google Play reports an AquaHub subscription for this account. Your plan is Free until AquaHub verifies paid access. Manage the subscription in Google Play or restore it from Compare Plans.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                // Success / Error Banner Alerts
                if (uiState.actionSuccessMessage != null) {
                    item {
                        Surface(
                            shape = AquaHubShapes.cardSecondary,
                            color = SuccessGreen.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = SuccessGreen)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = uiState.actionSuccessMessage,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = SuccessGreen
                                    )
                                }
                                IconButton(onClick = onClearMessages, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = SuccessGreen)
                                }
                            }
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    item {
                        Surface(
                            shape = AquaHubShapes.cardSecondary,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = uiState.errorMessage,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = onClearMessages, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }

                // 1. Current Active Subscription Card
                item {
                    val sub = uiState.currentSubscription
                    val plan = uiState.currentPlan

                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Top Row: Plan Name + Status Badge
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    Text(
                                        text = "CURRENT PLAN",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            letterSpacing = 1.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                    Text(
                                        text = plan?.name ?: sub?.planCode?.name ?: "—",
                                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }

                                StatusChip(state = uiState.resolvedState)
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)

                            // Stack the billing copy and date when the card cannot fit both columns.
                            val isFree = plan?.code == SubscriptionPlanCode.FREE ||
                                sub?.planCode == SubscriptionPlanCode.FREE
                            val billingTitle = when {
                                isFree -> "Free"
                                plan == null -> "—"
                                else -> "Billing managed by Google Play"
                            }
                            val billingDescription = when {
                                isFree -> "Free access has no AquaHub plan charge."
                                sub?.provider == "AQUAHUB_TRIAL" -> "AquaHub trial; no Google Play charge has been made."
                                sub?.provider == "GOOGLE_PLAY" -> "Check Google Play for the amount, renewal date, or cancellation status."
                                else -> "Checkout pricing and renewal terms are confirmed by Google Play."
                            }
                            val billingCycle = if (isFree) "Billing Cycle: None" else
                                "Billing Cycle: ${sub?.billingCycle?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—"}"
                            val isExpiredTrial = sub?.status == SubscriptionStatus.TRIALING &&
                                uiState.resolvedState == ResolvedSubscriptionState.EXPIRED
                            val renewalLabel = when {
                                isFree -> "Renewal"
                                uiState.resolvedState == ResolvedSubscriptionState.TRIAL_ACTIVE -> "Trial Ends"
                                isExpiredTrial -> "Trial Ended"
                                else -> "Next Renewal"
                            }
                            val targetEnd = if (isFree) null else if (sub?.status == SubscriptionStatus.TRIALING) sub.trialEndsAt else sub?.currentPeriodEnd
                            val renewalDate = if (isFree) "No renewal" else if (targetEnd != null) dateFormat.format(Date(targetEnd)) else "—"
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                if (maxWidth < 600.dp) {
                                    Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)) {
                                        BillingCopy(billingTitle, billingDescription, billingCycle, Modifier.fillMaxWidth())
                                        RenewalDate(renewalLabel, renewalDate, Modifier.fillMaxWidth(), Alignment.Start)
                                    }
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                                        verticalAlignment = Alignment.Top,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        BillingCopy(billingTitle, billingDescription, billingCycle, Modifier.weight(1f))
                                        RenewalDate(renewalLabel, renewalDate, Modifier.widthIn(min = 144.dp), Alignment.End)
                                    }
                                }
                            }

                            // Notice for Scheduled Downgrade or Cancel
                            if (sub?.cancelAtPeriodEnd == true) {
                                Surface(
                                    shape = AquaHubShapes.cardSecondary,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(10.dp)
                                    ) {
                                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Auto-renew cancelled. Access remains active until ${dateFormat.format(Date(sub.currentPeriodEnd))}.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }

                            if (sub?.pendingPlanId != null && sub.pendingPlanEffectiveAt != null) {
                                val pendingPlan = uiState.availablePlans.find { it.id == sub.pendingPlanId }
                                Surface(
                                    shape = AquaHubShapes.cardSecondary,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(10.dp)
                                    ) {
                                        Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Downgrade to ${pendingPlan?.name ?: "Plan"} takes effect on ${dateFormat.format(Date(sub.pendingPlanEffectiveAt))}.",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                            // Full-width actions keep their labels readable on compact cards.
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                if (maxWidth < 600.dp) {
                                    Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                                        AquaHubButton(
                                            text = "Change Plan",
                                            icon = Icons.Rounded.Diamond,
                                            onClick = onNavigateToPlanComparison,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        SubscriptionSecondaryAction(
                                            sub = sub,
                                            resolvedState = uiState.resolvedState,
                                            ownedPlayProductId = uiState.ownedPlayProductId,
                                            isActionLoading = uiState.isActionLoading,
                                            onRenew = onRenew,
                                            onResume = onResume,
                                            onCancelClick = { showCancelDialog = true },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        AquaHubButton(
                                            text = "Change Plan",
                                            icon = Icons.Rounded.Diamond,
                                            onClick = onNavigateToPlanComparison,
                                            modifier = Modifier.weight(1f)
                                        )
                                        SubscriptionSecondaryAction(
                                            sub = sub,
                                            resolvedState = uiState.resolvedState,
                                            ownedPlayProductId = uiState.ownedPlayProductId,
                                            isActionLoading = uiState.isActionLoading,
                                            onRenew = onRenew,
                                            onResume = onResume,
                                            onCancelClick = { showCancelDialog = true },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Plan Entitlements & Features Overview
                item {
                    AquaHubSectionHeader(title = "Included Plan Features")
                }

                item {
                    val plan = uiState.currentPlan
                    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (plan != null && plan.features.isNotEmpty()) {
                                plan.features.filter { it.enabled }.distinctBy { it.feature.displayName }.forEach { entitlement ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = null,
                                            tint = SuccessGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        val limitSuffix = entitlement.limit?.let { " (up to $it)" } ?: ""
                                        Text(
                                            text = entitlement.feature.displayName + limitSuffix,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "All core water refilling station features enabled.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // 4. Subscription History Log
                item {
                    AquaHubSectionHeader(title = "Subscription History & Audit Log")
                }

                if (uiState.history.isEmpty()) {
                    item {
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "No past subscription events recorded.",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                modifier = Modifier.padding(AquaHubSpacing.medium)
                            )
                        }
                    }
                } else {
                    items(uiState.history, key = { it.id }) { entry ->
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    val icon = when (entry.eventType) {
                                        SubscriptionEventType.TRIAL_STARTED -> Icons.Rounded.PlayCircle
                                        SubscriptionEventType.SUBSCRIBED -> Icons.Rounded.CheckCircle
                                        SubscriptionEventType.UPGRADED -> Icons.Rounded.TrendingUp
                                        SubscriptionEventType.DOWNGRADED -> Icons.Rounded.TrendingDown
                                        SubscriptionEventType.RENEWED -> Icons.Rounded.Autorenew
                                        SubscriptionEventType.CANCELLED -> Icons.Rounded.Cancel
                                        SubscriptionEventType.EXPIRED -> Icons.Rounded.Lock
                                        SubscriptionEventType.REACTIVATED -> Icons.Rounded.Replay
                                        else -> Icons.Rounded.Event
                                    }
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                    ) {
                                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = entry.eventType.name.replace("_", " "),
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                        if (!entry.notes.isNullOrBlank()) {
                                            Text(
                                                text = entry.notes,
                                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = dateTimeFormat.format(Date(entry.effectiveAt)),
                                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }

    // Cancel Confirmation Dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            icon = { Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Manage Cancellation in Google Play?") },
            text = {
                Text("Google Play handles cancellation. Your paid access continues until the end of the current billing period after you cancel there.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelDialog = false
                        onCancel()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Open Google Play")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text("Keep Subscription")
                }
            }
        )
    }
}

@Composable
private fun BillingCopy(title: String, description: String, cycle: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Text(
            text = cycle,
            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
    }
}

@Composable
private fun RenewalDate(label: String, date: String, modifier: Modifier, horizontalAlignment: Alignment.Horizontal) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Text(
            text = date,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SubscriptionSecondaryAction(
    sub: Subscription?,
    resolvedState: ResolvedSubscriptionState,
    ownedPlayProductId: String?,
    isActionLoading: Boolean,
    onRenew: () -> Unit,
    onResume: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier
) {
    if (SubscriptionBillingPolicy.hasOwnedPlayWhileFree(sub, ownedPlayProductId)) {
        OutlinedButton(
            onClick = onRenew,
            enabled = !isActionLoading,
            shape = AquaHubShapes.button,
            modifier = modifier.heightIn(min = 48.dp)
        ) { Text("Manage Google Play Billing") }
        return
    }
    when (SubscriptionBillingPolicy.secondaryAction(sub, resolvedState)) {
        SubscriptionBillingPolicy.SecondaryAction.NONE -> Unit
        SubscriptionBillingPolicy.SecondaryAction.ACTIVATE -> {
            Button(
                onClick = onRenew,
                enabled = !isActionLoading,
                shape = AquaHubShapes.button,
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                modifier = modifier.heightIn(min = 48.dp)
            ) {
                Text(if (sub?.provider == "GOOGLE_PLAY") "Renew with Google Play" else "Activate Paid Plan", fontWeight = FontWeight.Bold)
            }
        }
        SubscriptionBillingPolicy.SecondaryAction.MANAGE_PAYMENT -> {
            OutlinedButton(
                onClick = onRenew,
                enabled = !isActionLoading,
                shape = AquaHubShapes.button,
                modifier = modifier.heightIn(min = 48.dp)
            ) { Text("Manage Payment in Google Play") }
        }
        SubscriptionBillingPolicy.SecondaryAction.RESUME -> {
            OutlinedButton(
                onClick = onResume,
                enabled = !isActionLoading,
                shape = AquaHubShapes.button,
                modifier = modifier.heightIn(min = 48.dp)
            ) { Text("Resume in Google Play") }
        }
        SubscriptionBillingPolicy.SecondaryAction.CANCEL -> {
            OutlinedButton(
                onClick = onCancelClick,
                enabled = !isActionLoading,
                shape = AquaHubShapes.button,
                modifier = modifier.heightIn(min = 48.dp)
            ) { Text("Cancel in Google Play") }
        }
    }
}

@Composable
private fun StatusChip(state: ResolvedSubscriptionState) {
    val (label, bg, fg) = when (state) {
        ResolvedSubscriptionState.ACTIVE -> Triple("ACTIVE", SuccessGreen.copy(alpha = 0.15f), SuccessGreen)
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
        border = BorderStroke(1.dp, fg.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                color = fg,
                letterSpacing = 0.5.sp
            ),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun SubscriptionBillingScreenPreview() {
    AquaHubTheme {
        SubscriptionBillingContent(
            uiState = SubscriptionBillingUiState(
                isLoading = false,
                currentSubscription = Subscription(
                    id = 1L,
                    stationId = 1L,
                    planId = 1L,
                    planCode = SubscriptionPlanCode.STARTER,
                    status = SubscriptionStatus.ACTIVE,
                    billingCycle = BillingCycle.MONTHLY,
                    startedAt = System.currentTimeMillis() - 10000000L,
                    currentPeriodStart = System.currentTimeMillis() - 10000000L,
                    currentPeriodEnd = System.currentTimeMillis() + 1500000000L,
                    provider = "GOOGLE_PLAY"
                ),
                currentPlan = SubscriptionPlan(
                    id = 1L,
                    code = SubscriptionPlanCode.STARTER,
                    name = "Starter",
                    description = "Inventory and rider tools for one water station",
                    monthlyPrice = 199.0,
                    annualPrice = annualPriceFromMonthly(199.0),
                    isPopular = false,
                    ctaText = "Choose Starter",
                    features = listOf(
                        FeatureEntitlement(SubscriptionFeature.DASHBOARD, true),
                        FeatureEntitlement(SubscriptionFeature.ORDER_MANAGEMENT, true),
                        FeatureEntitlement(SubscriptionFeature.RIDER_MANAGEMENT, true, 10L),
                        FeatureEntitlement(SubscriptionFeature.ADVANCED_REPORTS, true)
                    )
                ),
                resolvedState = ResolvedSubscriptionState.ACTIVE,
                remainingDays = 18,
                history = listOf(
                    SubscriptionHistoryEntry(
                        id = 1L,
                        stationId = 1L,
                        subscriptionId = 1L,
                        eventType = SubscriptionEventType.SUBSCRIBED,
                        effectiveAt = System.currentTimeMillis() - 10000000L,
                        notes = "Subscribed to Starter plan"
                    )
                )
            ),
            onBackClick = {},
            onNavigateToPlanComparison = {},
            onRenew = {},
            onCancel = {},
            onResume = {},
            onClearMessages = {}
        )
    }
}
