package com.aesprt.aquahub.feature.stations

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.ui.theme.*
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ManageStationsScreen(
    viewModel: ManageStationsViewModel,
    onBackClick: () -> Unit,
    onNavigateToSubscription: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Manage Stations",
                subtitle = "${uiState.stations.size} Active Branches",
                onBackClick = onBackClick,
                actions = {
                    AquaHubPrimaryButton(
                        text = "Add Station",
                        icon = Icons.Rounded.AddLocationAlt,
                        onClick = viewModel::openAddDialog,
                        enabled = !uiState.isLimitReached,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                AquaHubLoadingIndicator(size = 56.dp)
            }
        } else {
            AdaptiveContentFrame(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                maxWidth = 1440.dp
            ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 440.dp),
                contentPadding = PaddingValues(
                    start = AquaHubSpacing.medium,
                    end = AquaHubSpacing.medium,
                    top = AquaHubSpacing.medium,
                    bottom = AquaHubSpacing.extraLarge
                ),
                verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                modifier = Modifier.fillMaxSize()
            ) {
                // Usage Progress Card
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AquaHubGlassCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PlanUsageIndicator(
                            label = "Station Limit Allocation",
                            currentUsage = uiState.stationCount,
                            maxLimit = uiState.maxStationLimit
                        )
                        Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                        Text(
                            text = "${uiState.stationCount} used · ${uiState.availableStationSlots} available · ${uiState.maxStationLimit} total (${uiState.subscriptionPlanName})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (uiState.isLimitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                        Text(
                            text = "Switch active branch context to filter orders, inventory, riders, and sales.",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }

                // Station List
                items(uiState.stations, key = { it.station.id }) { item ->
                    StationItemCard(
                        stationWithStats = item,
                        onSwitchClick = { viewModel.switchActiveStation(item.station.id) },
                        onUpgradeClick = onNavigateToSubscription
                    )
                }
            }
            }
        }
    }

    if (uiState.showAddDialog) {
        AddStationDialog(
            errorMessage = uiState.errorMessage,
            isSaving = uiState.isSaving,
            onDismiss = viewModel::closeAddDialog,
            onSave = viewModel::createStation
        )
    }
}

@Composable
private fun StationItemCard(
    stationWithStats: StationWithStats,
    onSwitchClick: () -> Unit,
    onUpgradeClick: () -> Unit = {}
) {
    val currencyFormat = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH")) }
    val station = stationWithStats.station
    val isActive = stationWithStats.isActiveContext
    val isLocked = stationWithStats.isLockedBySubscriptionLimit

    AquaHubGlassCard(
        borderColor = when {
            isActive -> MaterialTheme.colorScheme.primary
            isLocked -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            else -> GlassBorderAqua
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = AquaHubShapes.chip,
                            color = SuccessGreenLight
                        ) {
                            Text(
                                text = "ACTIVE CONTEXT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = SuccessGreen,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp
                                ),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    } else if (isLocked) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = AquaHubShapes.chip,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = "LOCKED",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 10.sp
                                ),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = station.address,
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
            }

            if (!isActive) {
                if (isLocked) {
                    AquaHubOutlinedButton(
                        text = "Upgrade",
                        icon = Icons.Rounded.Lock,
                        onClick = onUpgradeClick
                    )
                } else {
                    AquaHubOutlinedButton(
                        text = "Switch",
                        icon = Icons.Rounded.SwapHoriz,
                        onClick = onSwitchClick
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.mediumSmall))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        // Quick Stats Row
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    text = "Today's Orders",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Text(
                    text = "${stationWithStats.todayOrdersCount}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                )
            }

            Column {
                Text(
                    text = "Today's Sales",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Text(
                    text = currencyFormat.format(stationWithStats.todaySales),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                )
            }

            Column {
                Text(
                    text = "Active Riders",
                    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Text(
                    text = "${stationWithStats.activeRidersCount}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                )
            }
        }
    }
}

@Composable
private fun AddStationDialog(
    errorMessage: String?,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, String, Double?, Double?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("Cagayan de Oro") }
    var province by remember { mutableStateOf("Misamis Oriental") }
    var openingTime by remember { mutableStateOf("07:00 AM") }
    var closingTime by remember { mutableStateOf("06:00 PM") }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var showLocationDialog by remember { mutableStateOf(false) }

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
                loc.city?.let { city = it }
                loc.province?.let { province = it }
                latitude = loc.latitude
                longitude = loc.longitude
                showLocationDialog = false
            }
        )
    }

    AquaHubFormDialog(
        onDismissRequest = onDismiss,
        title = "Add Station Branch",
        confirmButtonText = "Create Station",
        onConfirm = {
            onSave(name, phone, address, city, province, openingTime, closingTime, latitude, longitude)
        },
        dismissButtonText = "Cancel",
        isConfirmEnabled = name.isNotBlank() && address.isNotBlank(),
        isConfirmLoading = isSaving
    ) {
        AquaHubTextField(
            value = name,
            onValueChange = { name = it },
            label = "Station / Branch Name *",
            placeholder = "e.g. AquaPure North Branch",
            leadingIcon = Icons.Rounded.Storefront
        )

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        AquaHubPhoneField(
            value = phone,
            onValueChange = { phone = it },
            label = "Station Contact Phone",
            placeholder = "09XXXXXXXXX"
        )

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        // Location Map Picker Trigger
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
                    .padding(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Map,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
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
                        text = address.ifBlank { "Tap to search or pin branch on map" },
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (address.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        AquaHubTextField(
            value = address,
            onValueChange = { address = it },
            label = "Street Address *",
            placeholder = "Complete physical address",
            leadingIcon = Icons.Rounded.LocationOn
        )

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        Row(horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
            AquaHubTextField(
                value = city,
                onValueChange = { city = it },
                label = "City",
                modifier = Modifier.weight(1f)
            )
            AquaHubTextField(
                value = province,
                onValueChange = { province = it },
                label = "Province",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(AquaHubSpacing.small))

        Row(horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
            AquaHubTimePickerField(
                timeString = openingTime,
                onTimeSelected = { openingTime = it },
                label = "Opens",
                placeholder = "07:00 AM",
                modifier = Modifier.weight(1f)
            )
            AquaHubTimePickerField(
                timeString = closingTime,
                onTimeSelected = { closingTime = it },
                label = "Closes",
                placeholder = "06:00 PM",
                modifier = Modifier.weight(1f)
            )
        }

        if (!errorMessage.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(AquaHubSpacing.small))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun ManageStationsScreenPreview() {
    val stations = listOf(
        StationWithStats(
            station = Station(
                id = 1,
                name = "AquaPure Main",
                address = "Quezon City, Metro Manila",
                isOpen = true
            ),
            isActiveContext = true,
            todayOrdersCount = 24,
            todaySales = 5_420.0,
            activeRidersCount = 2
        ),
        StationWithStats(
            station = Station(
                id = 2,
                name = "AquaPure North",
                address = "Novaliches, Quezon City",
                isOpen = true
            ),
            isActiveContext = false,
            todayOrdersCount = 15,
            todaySales = 3_180.0,
            activeRidersCount = 1
        )
    )
    AquaHubTheme {
        Scaffold(
            topBar = {
                AquaHubTopBar(
                    title = "Manage Stations",
                    subtitle = "2 Active Branches",
                    onBackClick = {},
                    actions = {
                        AquaHubPrimaryButton(
                            text = "Add Station",
                            icon = Icons.Rounded.AddLocationAlt,
                            onClick = {}
                        )
                    }
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { paddingValues ->
            AdaptiveContentFrame(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                maxWidth = 1440.dp
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 440.dp),
                    contentPadding = PaddingValues(AquaHubSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            PlanUsageIndicator(
                                label = "Station Limit Allocation",
                                currentUsage = 2,
                                maxLimit = 4
                            )
                            Spacer(Modifier.height(AquaHubSpacing.small))
                            Text("2 used · 2 available · 4 total (Business)")
                        }
                    }
                    items(stations, key = { it.station.id }) { stationWithStats ->
                        StationItemCard(
                            stationWithStats = stationWithStats,
                            onSwitchClick = {},
                            onUpgradeClick = {}
                        )
                    }
                }
            }
        }
    }
}
