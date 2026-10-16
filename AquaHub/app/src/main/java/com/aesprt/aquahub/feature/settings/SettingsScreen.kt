package com.aesprt.aquahub.feature.settings

import android.content.Intent
import android.net.Uri
import com.aesprt.aquahub.R
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.ui.theme.*
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToStationProfile: () -> Unit,
    onNavigateToCustomerOrdering: () -> Unit = {},
    onNavigateToPaymentMethods: () -> Unit = {},
    onNavigateToDeliverySettings: () -> Unit = {},
    onNavigateToManageStations: () -> Unit = {},
    onNavigateToStaffManagement: () -> Unit = {},
    onNavigateToRiders: () -> Unit,
    onNavigateToProducts: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToSubscription: () -> Unit = {},
    onLogoutSuccess: () -> Unit,
    onAccountDeleted: () -> Unit = {},
    onReauthenticate: (String) -> Unit = {},
    onReauthenticateWithGoogle: (String) -> Unit = {},
    onDismissReauthentication: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.LogoutSuccess -> onLogoutSuccess()
                SettingsEvent.AccountDeleted -> onAccountDeleted()
            }
        }
    }

    SettingsContent(
        uiState = uiState,
        onNavigateToStationProfile = onNavigateToStationProfile,
        onNavigateToCustomerOrdering = onNavigateToCustomerOrdering,
        onNavigateToPaymentMethods = onNavigateToPaymentMethods,
        onNavigateToDeliverySettings = onNavigateToDeliverySettings,
        onNavigateToManageStations = onNavigateToManageStations,
        onNavigateToStaffManagement = onNavigateToStaffManagement,
        onNavigateToRiders = onNavigateToRiders,
        onNavigateToProducts = onNavigateToProducts,
        onNavigateToReports = onNavigateToReports,
        onNavigateToSubscription = onNavigateToSubscription,
        onLogout = viewModel::logout,
        onDeleteAccount = viewModel::deleteAccount,
        onResendEmailVerification = viewModel::resendEmailVerification,
        onMarketingPreferenceChanged = viewModel::setMarketingPreference,
        onRetryMarketingPreference = viewModel::retryLoadingMarketingPreference,
        onDismissDeletionError = viewModel::dismissDeletionError,
        onReauthenticate = viewModel::reauthenticate,
        onReauthenticateWithGoogle = viewModel::reauthenticateWithGoogle,
        onDismissReauthentication = viewModel::dismissReauthentication
    )
}

@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onNavigateToStationProfile: () -> Unit,
    onNavigateToCustomerOrdering: () -> Unit = {},
    onNavigateToPaymentMethods: () -> Unit = {},
    onNavigateToDeliverySettings: () -> Unit = {},
    onNavigateToManageStations: () -> Unit = {},
    onNavigateToStaffManagement: () -> Unit = {},
    onNavigateToRiders: () -> Unit,
    onNavigateToProducts: () -> Unit,
    onNavigateToReports: () -> Unit,
    onNavigateToSubscription: () -> Unit = {},
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit = {},
    onResendEmailVerification: () -> Unit = {},
    onMarketingPreferenceChanged: (Boolean) -> Unit = {},
    onRetryMarketingPreference: () -> Unit = {},
    onDismissDeletionError: () -> Unit = {},
    onReauthenticate: (String) -> Unit = {},
    onReauthenticateWithGoogle: (String) -> Unit = {},
    onDismissReauthentication: () -> Unit = {}
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }
    var showDeleteFinal by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val defaultWebClientId = stringResource(R.string.default_web_client_id)
    val credentialManager = remember { CredentialManager.create(context) }
    val coroutineScope = rememberCoroutineScope()
    var reauthPassword by rememberSaveable { mutableStateOf("") }
    var localReauthError by remember { mutableStateOf<String?>(null) }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = {
                Text(
                    "Sign Out",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    "Signing out removes saved business data from this device when no sync work is pending. If offline changes remain, AquaHub keeps them tied to this account so they can be recovered. Your cloud account data is not deleted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                AquaHubButton(
                    text = "Sign Out",
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    }
                )
            },
            dismissButton = {
                AquaHubOutlinedButton(
                    text = "Cancel",
                    onClick = { showLogoutDialog = false }
                )
            }
        )
    }

    if (infoDialogTitle != null) {
        AlertDialog(
            onDismissRequest = { infoDialogTitle = null },
            title = {
                Text(
                    infoDialogTitle ?: "",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    infoDialogMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                AquaHubButton(
                    text = "OK",
                    onClick = { infoDialogTitle = null }
                )
            }
        )
    }

    if (uiState.requiresReauthentication) {
        AlertDialog(
            onDismissRequest = {
                if (!uiState.reauthenticating) {
                    localReauthError = null
                    onDismissReauthentication()
                }
            },
            title = { Text("Verify your identity", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("For your security, sign in again before deleting your AquaHub account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = reauthPassword,
                        onValueChange = { reauthPassword = it; localReauthError = null },
                        label = { Text("AquaHub password") },
                        singleLine = true,
                        enabled = !uiState.reauthenticating
                    )
                    (uiState.reauthenticationError ?: localReauthError)?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        enabled = !uiState.reauthenticating,
                        onClick = {
                            val webClientId = defaultWebClientId
                            if (webClientId.startsWith("YOUR_")) {
                                localReauthError = "Google reauthentication is not configured in this build."
                            } else {
                                coroutineScope.launch {
                                    try {
                                        val googleIdOption = GetGoogleIdOption.Builder()
                                            .setServerClientId(webClientId)
                                            .setFilterByAuthorizedAccounts(true)
                                            .setAutoSelectEnabled(false)
                                            .build()
                                        val request = GetCredentialRequest.Builder()
                                            .addCredentialOption(googleIdOption)
                                            .build()
                                        val result = credentialManager.getCredential(context, request)
                                        val credential = result.credential
                                        if (credential is CustomCredential &&
                                            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                        ) {
                                            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                                            if (token.isBlank()) localReauthError = "Google did not return a verification token."
                                            else onReauthenticateWithGoogle(token)
                                        } else {
                                            localReauthError = "Google returned an unsupported credential."
                                        }
                                    } catch (error: GetCredentialException) {
                                        localReauthError = error.message ?: "Google reauthentication was cancelled."
                                    }
                                }
                            }
                        }
                    ) { Text("Use Google instead") }
                }
            },
            confirmButton = {
                AquaHubButton(
                    text = "Verify",
                    enabled = reauthPassword.isNotBlank() && !uiState.reauthenticating,
                    isLoading = uiState.reauthenticating,
                    onClick = { onReauthenticate(reauthPassword) }
                )
            },
            dismissButton = {
                AquaHubOutlinedButton(
                    text = "Cancel",
                    enabled = !uiState.reauthenticating,
                    onClick = { localReauthError = null; onDismissReauthentication() }
                )
            }
        )
    }

    if (showDeleteWarning) {
        AccountDeletionWarningDialog(
            onDismiss = { showDeleteWarning = false },
            onContinue = { showDeleteWarning = false; showDeleteFinal = true },
            onManageSubscription = {
                showDeleteWarning = false
                val playIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/account/subscriptions?package=" + context.packageName)
                ).setPackage("com.android.vending")
                runCatching { context.startActivity(playIntent) }
                    .onFailure { onNavigateToSubscription() }
            }
        )
    }

    if (showDeleteFinal) {
        AlertDialog(
            onDismissRequest = { if (!uiState.deletingAccount) showDeleteFinal = false },
            title = { Text("Delete permanently", fontWeight = FontWeight.Bold) },
            text = { Text("This action cannot be undone. Review Subscription & Billing first if you need to manage Google Play billing.", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                AquaHubButton(
                    text = "Delete Account",
                    enabled = !uiState.deletingAccount,
                    isLoading = uiState.deletingAccount,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = { onDeleteAccount() }
                )
            },
            dismissButton = { AquaHubOutlinedButton(text = "Cancel", enabled = !uiState.deletingAccount, onClick = { showDeleteFinal = false }) }
        )
    }

    if (uiState.deletionError != null) {
        AlertDialog(
            onDismissRequest = onDismissDeletionError,
            title = { Text("Account deletion failed", fontWeight = FontWeight.Bold) },
            text = { Text(uiState.deletionError, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = { AquaHubButton(text = "Try Again", onClick = { onDismissDeletionError(); onDeleteAccount() }) },
            dismissButton = { AquaHubOutlinedButton(text = "Close", onClick = onDismissDeletionError) }
        )
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Settings",
                subtitle = "Station preferences & management"
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AdaptiveContentFrame(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            maxWidth = 1280.dp
        ) {
        LazyColumn(
            contentPadding = PaddingValues(
                start = AquaHubSpacing.medium,
                end = AquaHubSpacing.medium,
                top = AquaHubSpacing.medium,
                bottom = 40.dp
            ),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
            modifier = Modifier.fillMaxSize()
        ) {
            // Station summary header card
            item {
                AquaHubSectionHeader(title = "Station Overview")
            }
            item {
                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = AquaHubSpacing.small)
                        ) {
                            Text(
                                text = uiState.station?.name ?: "No station selected",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = uiState.station?.address ?: "Add the station address in Station Profile",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                shape = AquaHubShapes.chip,
                                color = if (uiState.station?.isOpen == true) SuccessGreenLight else ErrorRedLight
                            ) {
                                Text(
                                    text = if (uiState.station?.isOpen == true) "Station Open" else "Station Closed",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (uiState.station?.isOpen == true) SuccessGreen else ErrorRed,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        TextButton(onClick = onNavigateToStationProfile) {
                            Text("Edit", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Setting items group
            item {
                AquaHubSectionHeader(title = "Preferences & Management")
            }
            item {
                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                    if (uiState.emailVerificationVisible) {
                        SettingsRow(
                            icon = if (uiState.emailVerified) Icons.Rounded.Verified else Icons.Rounded.MarkEmailUnread,
                            title = if (uiState.emailVerified) "Email verified" else "Verify email address",
                            onClick = { if (!uiState.emailVerified) onResendEmailVerification() }
                        )
                        if (!uiState.emailVerified) {
                            Text(
                                text = uiState.emailVerificationMessage
                                    ?: "Optional: verify ${uiState.emailAddress} to secure account recovery.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = AquaHubSpacing.small)
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    }
                    Text(
                        text = "AquaHub marketing emails",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = AquaHubSpacing.extraSmall),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                uiState.marketingPreferenceLoading -> "Loading your marketing email preference…"
                                uiState.marketingPreferenceMessage?.startsWith("We couldn't load") == true ->
                                    "Your marketing preference is temporarily unavailable."
                                uiState.marketingEmailAvailable ->
                                    "Product updates, practical tips, and offers. Account and service messages are separate."
                                else -> "Add an email address to your owner profile to manage marketing emails."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = uiState.marketingSubscribed,
                            onCheckedChange = onMarketingPreferenceChanged,
                            enabled = uiState.marketingEmailAvailable &&
                                !uiState.marketingPreferenceLoading && !uiState.marketingPreferenceSaving,
                            modifier = Modifier.semantics { contentDescription = "AquaHub marketing emails" }
                        )
                    }
                    if (uiState.marketingPreferenceMessage?.startsWith("We couldn't load") == true) {
                        TextButton(
                            onClick = onRetryMarketingPreference,
                            enabled = !uiState.marketingPreferenceLoading
                        ) { Text("Retry") }
                    }
                    uiState.marketingPreferenceMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.marketingSubscribed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = AquaHubSpacing.small)
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.Diamond,
                        title = "Subscription & Billing",
                        onClick = onNavigateToSubscription
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.AddLocationAlt,
                        title = "Multi-Branch & Stations",
                        onClick = onNavigateToManageStations
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.Badge,
                        title = "Staff Management",
                        onClick = onNavigateToStaffManagement
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.Storefront,
                        title = "Station Profile",
                        onClick = onNavigateToStationProfile
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.QrCode2,
                        title = "Customer Ordering QR",
                        onClick = onNavigateToCustomerOrdering
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.Inventory2,
                        title = "Products & Pricing",
                        onClick = onNavigateToProducts
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.AutoMirrored.Rounded.DirectionsBike,
                        title = "Delivery Rider Management",
                        onClick = onNavigateToRiders
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.LocalShipping,
                        title = "Delivery Settings",
                        onClick = onNavigateToDeliverySettings
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.Payments,
                        title = "Payment Methods",
                        onClick = onNavigateToPaymentMethods
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.Analytics,
                        title = "Sales Reports & Analytics",
                        onClick = onNavigateToReports
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    SettingsRow(
                        icon = Icons.Rounded.Backup,
                        title = "Local Database Persistence",
                        onClick = {
                            infoDialogTitle = "Offline Data Storage"
                            infoDialogMessage = "AquaHub is operating in Local-First offline mode using Room Database with automatic persistence."
                        }
                    )
                }
            }

            // Destructive account actions
            item {
                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                    SettingsRow(
                        icon = Icons.Rounded.DeleteForever,
                        title = "Delete AquaHub Account",
                        onClick = { showDeleteWarning = true }
                    )
                }
            }

            // Logout Button Card
            item {
                AquaHubGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showLogoutDialog = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ExitToApp,
                            contentDescription = "Sign Out",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(AquaHubSpacing.mediumSmall))
                        Text(
                            text = "Sign Out",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        )
                    }
                }
            }

            // App Version Info
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AquaHubSpacing.small)
                ) {
                    Text(
                        text = "AquaHub Water Station Manager v1.0 • Clean Architecture",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun AccountDeletionWarningDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onManageSubscription: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = AquaHubShapes.dialog,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Delete owner account?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Review what happens before continuing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }

                Surface(
                    shape = AquaHubShapes.cardSecondary,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                        Text("This will:", fontWeight = FontWeight.Bold)
                        Text("• Remove your owner sign-in and profile")
                        Text("• Stop online ordering for owned stations")
                        Text("• Keep historical orders and reports for business continuity")
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Google Play subscriptions are not cancelled automatically. Manage billing first if a subscription is active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AquaHubOutlinedButton(
                    text = "Manage Google Play subscription",
                    icon = Icons.Rounded.Subscriptions,
                    onClick = onManageSubscription,
                    modifier = Modifier.fillMaxWidth()
                )
                AquaHubPrimaryButton(
                    text = "Continue to account deletion",
                    icon = Icons.Rounded.ArrowForward,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep my account")
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(AquaHubSpacing.mediumSmall))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun SettingsScreenPreview() {
    AquaHubTheme {
        SettingsContent(
            uiState = SettingsUiState(
                station = Station(id = 1, name = "AquaPure Station", address = "123 Tiano Brothers St., CDO", isOpen = true)
            ),
            onNavigateToStationProfile = {},
            onNavigateToRiders = {},
            onNavigateToProducts = {},
            onNavigateToReports = {},
            onLogout = {}
        )
    }
}
