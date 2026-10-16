package com.aesprt.aquahub.feature.settings.delivery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.AquaHubGlassCard
import com.aesprt.aquahub.core.designsystem.components.AdaptiveFormFrame
import com.aesprt.aquahub.core.designsystem.components.AquaHubLoadingOverlay
import com.aesprt.aquahub.core.designsystem.components.AquaHubPrimaryButton
import com.aesprt.aquahub.core.designsystem.components.AquaHubTextField
import com.aesprt.aquahub.core.designsystem.components.AquaHubTopBar
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.ui.theme.AquaHubSpacing
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import com.aesprt.aquahub.ui.theme.ErrorRedLight
import com.aesprt.aquahub.ui.theme.SuccessGreen
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun DeliverySettingsScreen(
    viewModel: DeliverySettingsViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) onBackClick()
    }

    DeliverySettingsContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onClearError = viewModel::clearError,
        onSave = viewModel::saveDeliverySettings
    )
}

@Composable
fun DeliverySettingsContent(
    uiState: DeliverySettingsUiState,
    onBackClick: () -> Unit,
    onClearError: () -> Unit,
    onSave: (Boolean, String, String, String) -> Unit
) {
    var acceptOrders by remember { mutableStateOf(true) }
    var radius by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("0.00") }
    var preparationMinutes by remember { mutableStateOf("30") }
    var loadedStationId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(uiState.station?.id) {
        val station = uiState.station ?: return@LaunchedEffect
        if (loadedStationId != station.id) {
            loadedStationId = station.id
            acceptOrders = station.isAcceptingOnlineOrders
            radius = station.deliveryRadiusKm?.toString().orEmpty()
            fee = "%.2f".format(java.util.Locale.US, station.deliveryFeeCentavos / 100.0)
            preparationMinutes = station.estimatedPreparationMinutes.toString()
        }
    }

    androidx.compose.material3.Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Delivery Settings",
                subtitle = "Customer delivery preferences",
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (uiState.isLoading) {
            AquaHubLoadingOverlay(modifier = Modifier.padding(paddingValues))
        } else {
            AdaptiveFormFrame(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                maxWidth = 1100.dp
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(AquaHubSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
            ) {
                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Rounded.LocalShipping,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                        Column {
                            Text(
                                text = "Control how customers receive orders",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = uiState.station?.name ?: "Active station",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Accept online & delivery orders",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = if (acceptOrders) "Customers can place delivery orders" else "Delivery ordering is paused",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = acceptOrders,
                            onCheckedChange = {
                                acceptOrders = it
                                onClearError()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = SuccessGreen
                            )
                        )
                    }
                }

                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Delivery coverage & pricing",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "These values are shown to customers and applied to new delivery orders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))
                    AquaHubTextField(
                        value = radius,
                        onValueChange = { radius = it; onClearError() },
                        label = "Delivery radius (km)",
                        placeholder = "Leave blank for no radius limit",
                        leadingIcon = Icons.Rounded.LocationOn,
                        supportingText = "Use 0 or leave blank when your station serves any distance.",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    AquaHubTextField(
                        value = fee,
                        onValueChange = { fee = it; onClearError() },
                        label = "Delivery fee (₱)",
                        placeholder = "0.00",
                        leadingIcon = Icons.Rounded.Payments,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    AquaHubTextField(
                        value = preparationMinutes,
                        onValueChange = { preparationMinutes = it; onClearError() },
                        label = "Preparation time (minutes)",
                        placeholder = "30",
                        leadingIcon = Icons.Rounded.AccessTime,
                        supportingText = "Customers use this to set delivery expectations.",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                AquaHubGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                        Text(
                            text = "Delivery orders still need a saved customer address. Add riders and manage assignments from Delivery Rider Management.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                uiState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ErrorRedLight, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }

                AquaHubPrimaryButton(
                    text = "Save Delivery Settings",
                    icon = Icons.Rounded.Check,
                    isLoading = uiState.isSaving,
                    onClick = { onSave(acceptOrders, radius, fee, preparationMinutes) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            }
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun DeliverySettingsScreenPreview() {
    AquaHubTheme {
        DeliverySettingsContent(
            uiState = DeliverySettingsUiState(
                isLoading = false,
                station = Station(
                    id = 1,
                    name = "AquaPure Station",
                    isAcceptingOnlineOrders = true,
                    deliveryRadiusKm = 5.0,
                    deliveryFeeCentavos = 2500,
                    estimatedPreparationMinutes = 30
                )
            ),
            onBackClick = {},
            onClearError = {},
            onSave = { _, _, _, _ -> }
        )
    }
}
