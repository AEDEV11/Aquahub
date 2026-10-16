package com.aesprt.aquahub.feature.auth.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.feature.auth.onboarding.OnboardingUiState
import com.aesprt.aquahub.feature.auth.onboarding.OnboardingViewModel
import com.aesprt.aquahub.ui.theme.*

@Composable
fun StationSetupScreen(
    viewModel: OnboardingViewModel,
    onBackClick: () -> Unit,
    onContinueToPlan: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    StationSetupContent(
        uiState = uiState,
        onStationNameChange = viewModel::onStationNameChange,
        onStationDescriptionChange = viewModel::onStationDescriptionChange,
        onStationPhoneChange = viewModel::onStationPhoneChange,
        onStationAddressChange = viewModel::onStationAddressChange,
        onBarangayChange = viewModel::onBarangayChange,
        onCityChange = viewModel::onCityChange,
        onOpeningTimeChange = viewModel::onOpeningTimeChange,
        onClosingTimeChange = viewModel::onClosingTimeChange,
        onLocationSelected = viewModel::onLocationSelected,
        onBackClick = onBackClick,
        onContinueToPlan = { viewModel.continueToPlan(onContinueToPlan) }
    )
}

@Composable
fun StationSetupContent(
    uiState: OnboardingUiState,
    onStationNameChange: (String) -> Unit,
    onStationDescriptionChange: (String) -> Unit,
    onStationPhoneChange: (String) -> Unit,
    onStationAddressChange: (String) -> Unit,
    onBarangayChange: (String) -> Unit,
    onCityChange: (String) -> Unit,
    onOpeningTimeChange: (String) -> Unit,
    onClosingTimeChange: (String) -> Unit,
    onLocationSelected: (com.aesprt.aquahub.domain.model.StationLocation) -> Unit = {},
    onBackClick: () -> Unit,
    onContinueToPlan: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            OnboardingStepperHeader(
                currentStep = 2,
                onBackClick = onBackClick
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AdaptiveFormFrame(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            maxWidth = 900.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AquaHubSpacing.medium)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
            Spacer(modifier = Modifier.height(AquaHubSpacing.small))

            // Station Information Form Card
            AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Station Details",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "Enter the primary physical station details and operating hours",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                // Station Name
                AquaHubTextField(
                    value = uiState.stationName,
                    onValueChange = onStationNameChange,
                    label = "Station Name *",
                    placeholder = "e.g. AquaPure Refilling Station",
                    leadingIcon = Icons.Rounded.Storefront,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                // Tagline / Description
                AquaHubTextField(
                    value = uiState.stationDescription,
                    onValueChange = onStationDescriptionChange,
                    label = "Tagline / Description (Optional)",
                    placeholder = "e.g. Purified, Mineral & Alkaline Water",
                    leadingIcon = Icons.Rounded.Description,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                // Station Contact Phone
                AquaHubPhoneField(
                    value = uiState.stationPhone,
                    onValueChange = onStationPhoneChange,
                    label = "Station Contact Phone (Optional)",
                    placeholder = "Leave blank to use Owner phone",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                // Location Picker Trigger & Address
                var showLocationDialog by remember { mutableStateOf(false) }

                Surface(
                    onClick = { showLocationDialog = true },
                    shape = AquaHubShapes.cardSecondary,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, GlassBorderAqua),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, AquaHubShapes.chip)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Map,
                                contentDescription = "Map Location",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Station Location & Map Coordinates",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            )
                            Text(
                                text = if (uiState.stationAddress.isNotBlank()) uiState.stationAddress else "Tap to search address or pin on map",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (uiState.stationAddress.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search Location",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (showLocationDialog) {
                    val locationPickerViewModel: com.aesprt.aquahub.feature.location.LocationPickerViewModel = org.koin.androidx.compose.koinViewModel()
                    com.aesprt.aquahub.feature.location.LocationPickerDialog(
                        viewModel = locationPickerViewModel,
                        initialAddress = uiState.stationAddress,
                        initialLat = uiState.latitude,
                        initialLng = uiState.longitude,
                        onDismiss = { showLocationDialog = false },
                        onLocationConfirmed = { location ->
                            onLocationSelected(location)
                            showLocationDialog = false
                        }
                    )
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                // Street Address fallback/manual edit
                AquaHubTextField(
                    value = uiState.stationAddress,
                    onValueChange = onStationAddressChange,
                    label = "Street Address *",
                    placeholder = "e.g. 120 Tiano Brothers St.",
                    leadingIcon = Icons.Rounded.LocationOn,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                // Barangay and City Row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AquaHubTextField(
                        value = uiState.barangay,
                        onValueChange = onBarangayChange,
                        label = "Barangay",
                        placeholder = "e.g. Divisoria",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Right) }),
                        modifier = Modifier.weight(1f)
                    )

                    AquaHubTextField(
                        value = uiState.city,
                        onValueChange = onCityChange,
                        label = "City *",
                        placeholder = "e.g. CDO",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                // Operating Hours Row (Native Material 3 Time Pickers)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AquaHubTimePickerField(
                        timeString = uiState.openingTime,
                        onTimeSelected = onOpeningTimeChange,
                        label = "Opening Time",
                        placeholder = "07:00 AM",
                        modifier = Modifier.weight(1f)
                    )

                    AquaHubTimePickerField(
                        timeString = uiState.closingTime,
                        onTimeSelected = onClosingTimeChange,
                        label = "Closing Time",
                        placeholder = "06:00 PM",
                        modifier = Modifier.weight(1f)
                    )
                }

                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = AquaHubShapes.chip,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.padding(AquaHubSpacing.small),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.medium))

                // Continue to plan selection; account/station persistence is finalized
                // after the last step chooses the subscription.
                AquaHubPrimaryButton(
                    text = "Continue to Plan Selection",
                    icon = Icons.AutoMirrored.Rounded.ArrowForward,
                    isLoading = uiState.isLoading,
                    onClick = {
                        focusManager.clearFocus()
                        onContinueToPlan()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

                Spacer(modifier = Modifier.height(AquaHubSpacing.extraLarge))
            }
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun StationSetupScreenPreview() {
    AquaHubTheme {
        StationSetupContent(
            uiState = OnboardingUiState(),
            onStationNameChange = {},
            onStationDescriptionChange = {},
            onStationPhoneChange = {},
            onStationAddressChange = {},
            onBarangayChange = {},
            onCityChange = {},
            onOpeningTimeChange = {},
            onClosingTimeChange = {},
            onBackClick = {},
            onContinueToPlan = {}
        )
    }
}
