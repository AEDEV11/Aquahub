package com.aesprt.aquahub.feature.location

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aesprt.aquahub.BuildConfig
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.StationLocation
import com.aesprt.aquahub.ui.theme.AquaHubShapes
import com.aesprt.aquahub.ui.theme.AquaHubSpacing
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import com.aesprt.aquahub.ui.theme.GlassBorderAqua
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerDialog(
    viewModel: LocationPickerViewModel,
    initialAddress: String?,
    initialLat: Double?,
    initialLng: Double?,
    searchPlaceholder: String = "Search station address or place...",
    selectedAddressLabel: String = "Selected Station Address",
    markerTitle: String = "Station Location",
    onDismiss: () -> Unit,
    onLocationConfirmed: (StationLocation) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initializeLocation(initialAddress, initialLat, initialLng)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            viewModel.useCurrentDeviceLocation()
        }
    }

    val targetLatLng = remember(uiState.currentCoordinates) {
        LatLng(uiState.currentCoordinates.latitude, uiState.currentCoordinates.longitude)
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(targetLatLng, 16f)
    }

    LaunchedEffect(targetLatLng) {
        cameraPositionState.animate(
            CameraUpdateFactory.newLatLngZoom(targetLatLng, 16.5f),
            1000
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, GlassBorderAqua),
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .padding(vertical = AquaHubSpacing.small)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 1. Google Map or Fallback Map UI
                if (BuildConfig.MAPS_API_KEY.isNotBlank()) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        uiSettings = MapUiSettings(
                            zoomControlsEnabled = false,
                            myLocationButtonEnabled = false,
                            compassEnabled = true
                        ),
                        properties = MapProperties(
                            isMyLocationEnabled = false
                        ),
                        onMapClick = { latLng ->
                            viewModel.updateMapCoordinates(latLng.latitude, latLng.longitude)
                        }
                    ) {
                        Marker(
                            state = rememberUpdatedMarkerState(position = targetLatLng),
                            title = markerTitle,
                            snippet = uiState.selectedLocation?.formattedAddress ?: "Selected Point",
                            draggable = true
                        )
                    }
                } else {
                    // API Key pending placeholder
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(AquaHubSpacing.large)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Map,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                            Text(
                                text = "Google Maps & Places Ready",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Add your MAPS_API_KEY in local.properties to enable live Google Maps rendering.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // 2. Center Pin indicator when moving map
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-20).dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Place,
                        contentDescription = "Target Point",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                }

                // 3. Floating Search Bar & Suggestions at Top
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(AquaHubSpacing.medium)
                ) {
                    // Top Search Card
                    AquaHubGlassCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                            OutlinedTextField(
                                value = uiState.query,
                                onValueChange = viewModel::onQueryChanged,
                                placeholder = { Text(searchPlaceholder) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            if (uiState.isSearching || uiState.isReverseGeocoding) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                            }

                            IconButton(
                                onClick = {
                                    permissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MyLocation,
                                    contentDescription = "Current Location",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Autocomplete Suggestions Dropdown
                    AnimatedVisibility(visible = uiState.suggestions.isNotEmpty()) {
                        Surface(
                            shape = AquaHubShapes.cardSecondary,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, GlassBorderAqua),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .shadow(8.dp, AquaHubShapes.cardSecondary)
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                            ) {
                                items(uiState.suggestions) { suggestion ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectSuggestion(suggestion) }
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Place,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = suggestion.primaryText,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (!suggestion.secondaryText.isNullOrBlank()) {
                                                Text(
                                                    text = suggestion.secondaryText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }

                // 4. Bottom Confirmation Panel
                AquaHubGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(AquaHubSpacing.medium)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedAddressLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = uiState.selectedLocation?.formattedAddress
                                        ?: "Coordinates: ${String.format(java.util.Locale.US, "%.5f, %.5f", uiState.currentCoordinates.latitude, uiState.currentCoordinates.longitude)}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AquaHubOutlinedButton(
                                text = "Cancel",
                                onClick = onDismiss,
                                modifier = Modifier.weight(1f)
                            )
                            AquaHubPrimaryButton(
                                text = "Confirm Location",
                                icon = Icons.Rounded.Check,
                                onClick = {
                                    val loc = uiState.selectedLocation ?: StationLocation(
                                        formattedAddress = uiState.query.ifBlank { "Coordinates (${uiState.currentCoordinates.latitude}, ${uiState.currentCoordinates.longitude})" },
                                        latitude = uiState.currentCoordinates.latitude,
                                        longitude = uiState.currentCoordinates.longitude
                                    )
                                    onLocationConfirmed(loc)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1.4f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun LocationPickerDialogPreview() {
    AquaHubTheme {
        Dialog(onDismissRequest = {}) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, GlassBorderAqua),
                modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f)
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val expanded = maxWidth >= 840.dp
                    if (expanded) {
                        Row(Modifier.fillMaxSize()) {
                            LocationPickerPreviewMap(Modifier.weight(0.68f).fillMaxSize())
                            Column(
                                modifier = Modifier.weight(0.32f).fillMaxSize().padding(AquaHubSpacing.medium),
                                verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
                            ) {
                                Text("Choose station location", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                OutlinedTextField(
                                    value = "Quezon City, Metro Manila",
                                    onValueChange = {},
                                    label = { Text("Search address") },
                                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = AquaHubShapes.cardSecondary) {
                                    Column(Modifier.padding(AquaHubSpacing.medium)) {
                                        Text("Selected location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Quezon City, Metro Manila", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text("14.6760, 121.0437", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Use this location") }
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            LocationPickerPreviewMap(Modifier.fillMaxSize())
                            OutlinedTextField(
                                value = "Quezon City, Metro Manila",
                                onValueChange = {},
                                label = { Text("Search address") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(AquaHubSpacing.medium)
                            )
                            Surface(
                                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(AquaHubSpacing.medium),
                                color = MaterialTheme.colorScheme.surface,
                                shape = AquaHubShapes.cardPrimary,
                                shadowElevation = 6.dp
                            ) {
                                Column(Modifier.padding(AquaHubSpacing.medium), verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                                    Text("Selected station address", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Quezon City, Metro Manila", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Use this location") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationPickerPreviewMap(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(AquaHubSpacing.small))
            Text("Map preview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Move the map to select a station location", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.Place, contentDescription = "Selected location", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.Center).offset(y = (-16).dp).size(34.dp))
    }
}
