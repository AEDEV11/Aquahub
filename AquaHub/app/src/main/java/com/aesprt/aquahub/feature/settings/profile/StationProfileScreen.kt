package com.aesprt.aquahub.feature.settings.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.R
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.ui.theme.*
import coil.compose.AsyncImage

@Composable
fun StationProfileScreen(
    viewModel: StationProfileViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onBackClick()
        }
    }

    StationProfileContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onUpdateStation = viewModel::updateStation
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationProfileContent(
    uiState: StationProfileUiState,
    onBackClick: () -> Unit,
    onUpdateStation: (
        name: String,
        phone: String,
        address: String,
        openingTime: String,
        closingTime: String,
        isOpen: Boolean,
        latitude: Double?,
        longitude: Double?,
        businessRulesText: String,
        logoSourceUri: String?
    ) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var openingTime by remember { mutableStateOf("06:00 AM") }
    var closingTime by remember { mutableStateOf("08:00 PM") }
    var isOpen by remember { mutableStateOf(true) }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var businessRulesText by remember { mutableStateOf("") }
    var selectedLogoUri by remember { mutableStateOf<String?>(null) }
    var showLocationDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val rulesFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                businessRulesText = reader.readText().take(12000)
            }
        }
    }
    val logoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> selectedLogoUri = uri?.toString() }

    LaunchedEffect(uiState.station) {
        val st = uiState.station
        if (st != null) {
            name = st.name
            phone = st.phone
            address = st.address
            openingTime = st.openingTime ?: "06:00 AM"
            closingTime = st.closingTime ?: "08:00 PM"
            isOpen = st.isOpen
            latitude = st.latitude
            longitude = st.longitude
            businessRulesText = st.businessRulesText.orEmpty()
            selectedLogoUri = null
        }
    }

    if (showLocationDialog) {
        val locationPickerViewModel: com.aesprt.aquahub.feature.location.LocationPickerViewModel = org.koin.androidx.compose.koinViewModel()
        com.aesprt.aquahub.feature.location.LocationPickerDialog(
            viewModel = locationPickerViewModel,
            initialAddress = address,
            initialLat = latitude,
            initialLng = longitude,
            onDismiss = { showLocationDialog = false },
            onLocationConfirmed = { loc ->
                address = loc.formattedAddress
                latitude = loc.latitude
                longitude = loc.longitude
                showLocationDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Station Profile",
                subtitle = "Station information & hours",
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
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(AquaHubSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
            ) {
                // Header Branding Card
                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AquaHubSpacing.small)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(92.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            val logoModel = selectedLogoUri ?: uiState.station?.logoPath
                            if (!logoModel.isNullOrBlank()) {
                                StorageAssetImage(
                                    reference = logoModel,
                                    contentDescription = "Station image preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                AquaHubLogo(modifier = Modifier.size(64.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                        Text(
                            text = name.ifBlank { "AquaPure Station" },
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        Text(
                            text = "Water Refilling & Delivery Station",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        FilledTonalButton(
                            onClick = {
                                logoPicker.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        ) {
                            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if ((selectedLogoUri ?: uiState.station?.logoPath).isNullOrBlank()) "Add station image" else "Change image")
                        }
                        Text(
                            text = "JPG, PNG, or WebP · up to 5 MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Station Form Card
                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Station Information",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                    AquaHubTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "Station Name *",
                        leadingIcon = Icons.Rounded.Storefront
                    )

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    AquaHubPhoneField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = "Contact Phone *"
                    )

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    // Location on Map Card
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
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Map Location & Coordinates",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = address.ifBlank { "Tap to set location on Google Map" },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2
                                )
                                if (latitude != null && longitude != null) {
                                    Text(
                                        text = "${String.format(java.util.Locale.US, "%.5f", latitude)}, ${String.format(java.util.Locale.US, "%.5f", longitude)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Edit Location",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    AquaHubTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = "Street Address *",
                        leadingIcon = Icons.Rounded.LocationOn
                    )

                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    Row(horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                        AquaHubTimePickerField(
                            timeString = openingTime,
                            onTimeSelected = { openingTime = it },
                            label = "Opening Time",
                            placeholder = "06:00 AM",
                            modifier = Modifier.weight(1f)
                        )
                        AquaHubTimePickerField(
                            timeString = closingTime,
                            onTimeSelected = { closingTime = it },
                            label = "Closing Time",
                            placeholder = "08:00 PM",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = "Station Currently Open",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Text(
                                text = if (isOpen) "Accepting online & delivery orders" else "Station temporarily closed",
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                        Switch(
                            checked = isOpen,
                            onCheckedChange = { isOpen = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = SuccessGreen
                            )
                        )
                    }
                }

                // Delivery configuration is managed from Delivery Settings.

                // Customer-facing Rules
                AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Customer Rules / Terms & Conditions",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Text(
                        text = "Customers must read and accept these rules before placing an order. Import a .txt or .md file, or type/paste the rules below.",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    AquaHubTextField(
                        value = businessRulesText,
                        onValueChange = { businessRulesText = it.take(12000) },
                        label = "Rules and terms",
                        placeholder = "Example: delivery, payment, bottle return, cancellation, and service rules",
                        leadingIcon = Icons.Rounded.Description,
                        singleLine = false,
                        maxLines = 12
                    )
                    TextButton(
                        onClick = { rulesFileLauncher.launch("text/*") },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Import rules file")
                    }
                    Text(
                        text = if (businessRulesText.isBlank()) "No station rules are published yet." else "Published rules: " + businessRulesText.length + "/12000 characters",
                        style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }

                AquaHubPrimaryButton(
                    text = if (uiState.isSaving) "Saving profile…" else "Save Profile Changes",
                    icon = Icons.Rounded.Check,
                    onClick = {
                        onUpdateStation(
                            name, phone, address, openingTime, closingTime, isOpen,
                            latitude, longitude, businessRulesText, selectedLogoUri
                        )
                    },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
                uiState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            }
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun StationProfileScreenPreview() {
    AquaHubTheme {
        StationProfileContent(
            uiState = StationProfileUiState(
                isLoading = false,
                station = Station(
                    id = 1,
                    name = "AquaPure Station",
                    phone = "09123456789",
                    address = "123 Tiano Brothers St., Divisoria, CDO",
                    openingTime = "06:00 AM",
                    closingTime = "08:00 PM",
                    isOpen = true
                )
            ),
            onBackClick = {},
            onUpdateStation = { _, _, _, _, _, _, _, _, _, _ -> }
        )
    }
}
