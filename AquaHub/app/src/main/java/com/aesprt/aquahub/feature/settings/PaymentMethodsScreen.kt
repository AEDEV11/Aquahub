package com.aesprt.aquahub.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.feature.settings.profile.StationProfileViewModel
import com.aesprt.aquahub.ui.theme.*
import coil.compose.AsyncImage

@Composable
fun PaymentMethodsScreen(
    viewModel: StationProfileViewModel,
    onBackClick: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val station = state.station
    val snackbar = remember { SnackbarHostState() }
    var cashEnabled by remember { mutableStateOf(true) }
    var codEnabled by remember { mutableStateOf(true) }
    var gcashEnabled by remember { mutableStateOf(false) }
    var gcashName by remember { mutableStateOf("") }
    var gcashNumber by remember { mutableStateOf("") }
    var gcashQrSourceUri by remember { mutableStateOf<String?>(null) }
    var mayaEnabled by remember { mutableStateOf(false) }
    var mayaName by remember { mutableStateOf("") }
    var mayaNumber by remember { mutableStateOf("") }
    var mayaQrSourceUri by remember { mutableStateOf<String?>(null) }
    val gcashQrPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        gcashQrSourceUri = it?.toString()
    }
    val mayaQrPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        mayaQrSourceUri = it?.toString()
    }
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    LaunchedEffect(station?.id) {
        station?.let {
            cashEnabled = it.cashPaymentEnabled
            codEnabled = it.codPaymentEnabled
            gcashEnabled = it.gcashPaymentEnabled
            gcashName = it.gcashAccountName.orEmpty()
            gcashNumber = it.gcashAccountNumber.orEmpty()
            mayaEnabled = it.mayaPaymentEnabled
            mayaName = it.mayaAccountName.orEmpty()
            mayaNumber = it.mayaAccountNumber.orEmpty()
        }
    }
    LaunchedEffect(state.isSaved, state.errorMessage) {
        when {
            state.isSaved -> {
                snackbar.showSnackbar("Payment methods updated")
                viewModel.clearStatus()
            }
            state.errorMessage != null -> {
                snackbar.showSnackbar(state.errorMessage.orEmpty())
                viewModel.clearStatus()
            }
        }
    }

    Scaffold(
        topBar = { AquaHubTopBar(title = "Payment Methods", subtitle = station?.name, onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!isKeyboardVisible) {
                Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface) {
                    AquaHubButton(
                        text = "Save payment settings",
                        icon = Icons.Rounded.CheckCircle,
                        onClick = {
                            viewModel.updatePaymentMethods(
                                cashEnabled, codEnabled,
                                gcashEnabled, gcashName, gcashNumber, gcashQrSourceUri,
                                mayaEnabled, mayaName, mayaNumber, mayaQrSourceUri
                            )
                        },
                        enabled = station != null,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        AdaptiveFormFrame(
            modifier = Modifier.fillMaxSize().padding(padding),
            maxWidth = 1180.dp
        ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f),
                shape = AquaHubShapes.cardPrimary,
                border = BorderStroke(1.dp, GlassBorderAqua)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(18.dp)
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Rounded.AccountBalanceWallet, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(12.dp))
                    }
                    Column {
                        Text("Choose how this station gets paid", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Only active methods appear during checkout. Digital wallet payments are manually verified by the cashier.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Text("PAY AT THE STATION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            PaymentActivationCard(
                title = "Cash",
                description = "Collected at pickup and recorded as paid immediately.",
                icon = Icons.Rounded.Payments,
                enabled = cashEnabled,
                onEnabledChange = { cashEnabled = it }
            )
            PaymentActivationCard(
                title = "Cash on Delivery",
                description = "Shown only for delivery orders and kept pending until collection.",
                icon = Icons.Rounded.LocalShipping,
                enabled = codEnabled,
                onEnabledChange = { codEnabled = it },
                badge = "DELIVERY ONLY"
            )

            Text("MANUAL DIGITAL WALLETS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            DigitalWalletCard(
                title = "GCash",
                icon = Icons.Rounded.PhoneAndroid,
                enabled = gcashEnabled,
                onEnabledChange = { gcashEnabled = it },
                accountName = gcashName,
                onAccountNameChange = { gcashName = it },
                accountNumber = gcashNumber,
                onAccountNumberChange = { gcashNumber = it },
                qrImage = gcashQrSourceUri ?: station?.gcashQrImagePath,
                onChooseQr = { gcashQrPicker.launch("image/*") },
            )
            DigitalWalletCard(
                title = "Maya",
                icon = Icons.Rounded.Wallet,
                enabled = mayaEnabled,
                onEnabledChange = { mayaEnabled = it },
                accountName = mayaName,
                onAccountNameChange = { mayaName = it },
                accountNumber = mayaNumber,
                onAccountNumberChange = { mayaNumber = it },
                qrImage = mayaQrSourceUri ?: station?.mayaQrImagePath,
                onChooseQr = { mayaQrPicker.launch("image/*") },
            )

            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Payment process", fontWeight = FontWeight.Bold)
                        Text("1. Select an active method during checkout.\n2. Confirm the money was received.\n3. For GCash or Maya, keep the reference number in the payment record.\n4. AquaHub updates the order and customer payment history.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

@Composable
private fun PaymentActivationCard(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    badge: String? = null
) {
    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    badge?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

@Composable
private fun DigitalWalletCard(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    accountName: String,
    onAccountNameChange: (String) -> Unit,
    accountNumber: String,
    onAccountNumberChange: (String) -> Unit,
    qrImage: String?,
    onChooseQr: () -> Unit,
) {
    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
        PaymentActivationCardContent(title, "Show this wallet during checkout and receipt recording.", icon, enabled, onEnabledChange)
        AnimatedVisibility(enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                OutlinedTextField(
                    value = accountName,
                    onValueChange = onAccountNameChange,
                    label = { Text("Account name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = accountNumber,
                    onValueChange = { onAccountNumberChange(it.filter(Char::isDigit).take(13)) },
                    label = { Text("Mobile number") },
                    supportingText = { Text("Used for cashier reference; never shown as a card payment.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (!qrImage.isNullOrBlank()) {
                            StorageAssetImage(
                                reference = qrImage,
                                privateByDefault = true,
                                contentDescription = "$title payment QR",
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                            )
                        }
                        OutlinedButton(onClick = onChooseQr, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.QrCode2, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (qrImage.isNullOrBlank()) "Upload payment QR" else "Replace payment QR")
                        }
                        Text(
                            "Customers can view and save this QR during checkout.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentActivationCardContent(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
            Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun PaymentMethodsScreenPreview() {
    AquaHubTheme {
        Scaffold(
            topBar = {
                AquaHubTopBar(
                    title = "Payment Methods",
                    subtitle = "AquaPure Main Station",
                    onBackClick = {}
                )
            },
            bottomBar = {
                Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface) {
                    AquaHubButton(
                        text = "Save payment settings",
                        icon = Icons.Rounded.CheckCircle,
                        onClick = {},
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            AdaptiveFormFrame(
                modifier = Modifier.fillMaxSize().padding(padding),
                maxWidth = 1180.dp
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                ) {
                    Text("Choose how this station gets paid", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Only active methods appear during checkout. Digital wallet payments are manually verified by the cashier.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("PAY AT THE STATION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    PaymentActivationCard("Cash", "Collected at pickup and recorded as paid immediately.", Icons.Rounded.Payments, true, {})
                    PaymentActivationCard("Cash on Delivery", "Shown only for delivery orders and kept pending until collection.", Icons.Rounded.LocalShipping, true, {}, badge = "DELIVERY ONLY")
                    Text("MANUAL DIGITAL WALLETS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    DigitalWalletCard(
                        title = "GCash",
                        icon = Icons.Rounded.PhoneAndroid,
                        enabled = true,
                        onEnabledChange = {},
                        accountName = "AquaPure Main",
                        onAccountNameChange = {},
                        accountNumber = "09171234567",
                        onAccountNumberChange = {},
                        qrImage = null,
                        onChooseQr = {}
                    )
                    DigitalWalletCard(
                        title = "Maya",
                        icon = Icons.Rounded.Wallet,
                        enabled = false,
                        onEnabledChange = {},
                        accountName = "",
                        onAccountNameChange = {},
                        accountNumber = "",
                        onAccountNumberChange = {},
                        qrImage = null,
                        onChooseQr = {}
                    )
                }
            }
        }
    }
}
