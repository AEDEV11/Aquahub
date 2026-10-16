package com.aesprt.aquahub.feature.riders

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.Rider
import com.aesprt.aquahub.domain.repository.StaffMember
import com.aesprt.aquahub.ui.theme.*
import java.util.Locale

@Composable
fun RidersScreen(
    viewModel: RidersViewModel,
    onBackClick: () -> Unit,
    onNavigateToSubscription: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    RidersContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onOpenAddDialog = viewModel::openAddDialog,
        onCloseAddDialog = viewModel::closeAddDialog,
        onDismissLimitDialog = viewModel::dismissLimitDialog,
        onNavigateToSubscription = onNavigateToSubscription,
        onToggleAvailability = viewModel::toggleAvailability,
        onOpenEditDialog = viewModel::openEditDialog,
        onCloseEditDialog = viewModel::closeEditDialog,
        onUpdateRider = viewModel::updateRider,
        onAddRider = viewModel::addRider,
        availableRiderAccounts = uiState.availableRiderAccounts
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RidersContent(
    uiState: RidersUiState,
    onBackClick: () -> Unit,
    onOpenAddDialog: () -> Unit,
    onCloseAddDialog: () -> Unit,
    onDismissLimitDialog: () -> Unit = {},
    onNavigateToSubscription: () -> Unit = {},
    onToggleAvailability: (Rider) -> Unit,
    onOpenEditDialog: (Rider) -> Unit,
    onCloseEditDialog: () -> Unit,
    onUpdateRider: (Rider, String, String, String, String, String) -> Unit,
    onAddRider: (String, String, String, String, String) -> Unit,
    availableRiderAccounts: List<StaffMember> = emptyList()
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var vehicle by remember { mutableStateOf("Honda Wave") }
    var plate by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf("") }
    var selectedAccount by remember { mutableStateOf<StaffMember?>(null) }
    var accountMenuExpanded by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    var editPhone by remember { mutableStateOf("") }
    var editVehicle by remember { mutableStateOf("") }
    var editPlate by remember { mutableStateOf("") }
    var editRating by remember { mutableStateOf("") }

    LaunchedEffect(uiState.showAddDialog) {
        if (!uiState.showAddDialog) {
            name = ""
            phone = ""
            vehicle = "Honda Wave"
            plate = ""
            rating = ""
            selectedAccount = null
            accountMenuExpanded = false
        }
    }
    LaunchedEffect(uiState.showAddDialog, availableRiderAccounts) {
        if (uiState.showAddDialog && selectedAccount == null) {
            selectedAccount = availableRiderAccounts.firstOrNull()
            selectedAccount?.let {
                name = it.name
                phone = it.phone
            }
        }
    }
    LaunchedEffect(uiState.editingRider?.id) {
        uiState.editingRider?.let { rider ->
            editName = rider.name
            editPhone = rider.phone
            editVehicle = rider.vehicleName.orEmpty()
            editPlate = rider.plateNumber.orEmpty()
            editRating = rider.rating.takeIf { it > 0.0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
        }
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Delivery Fleet",
                subtitle = "Riders & active assignments",
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenAddDialog,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = "Add Rider")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        AnimatedContent(
            targetState = uiState.isLoading,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "ridersContentAnim"
        ) { loading ->
            if (loading) {
                Column(
                    modifier = Modifier
                        .padding(paddingValues)
                        .padding(AquaHubSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)
                ) {
                    repeat(3) {
                        AquaHubSkeleton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp)
                        )
                    }
                }
            } else if (uiState.riders.isEmpty()) {
                AquaHubEmptyState(
                    icon = Icons.AutoMirrored.Rounded.DirectionsBike,
                    message = "No Riders Available",
                    description = "Add riders to your team so deliveries can be dispatched",
                    actionText = "Add Rider",
                    onActionClick = onOpenAddDialog,
                    modifier = Modifier.padding(paddingValues)
                )
            } else {
                AdaptiveContentFrame(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    maxWidth = 1440.dp
                ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 420.dp),
                    contentPadding = PaddingValues(AquaHubSpacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.riders, key = { it.id }) { rider ->
                        AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(46.dp)
                                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Rounded.DirectionsBike,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(AquaHubSpacing.mediumSmall))
                                    Column {
                                        Text(
                                            text = rider.name,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Rounded.Star,
                                                contentDescription = "Rating",
                                                tint = WarningAmber,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "${rider.ratingLabel()} • ${rider.vehicleName ?: "Motorcycle"}",
                                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            )
                                        }
                                        Text(
                                            text = "${rider.activeOrderCount} active orders • ${rider.phone}",
                                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        )
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onOpenEditDialog(rider) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Edit,
                                            contentDescription = "Edit ${rider.name}",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Switch(
                                        checked = rider.isAvailable,
                                        onCheckedChange = { onToggleAvailability(rider) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                            checkedTrackColor = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        
        // Add Rider Dialog
        if (uiState.showAddDialog) {
            AquaHubFormDialog(
                onDismissRequest = onCloseAddDialog,
                title = "Add New Delivery Rider",
                confirmButtonText = "Save Rider",
                isConfirmLoading = uiState.isSavingRider,
                onConfirm = { onAddRider(name, phone, vehicle, plate, rating) },
                dismissButtonText = "Cancel",
                isConfirmEnabled = !uiState.isLoadingRiderAccounts &&
                    selectedAccount != null && isValidRatingInput(rating)
            ) {
                Box {
                    AquaHubTextField(
                        value = selectedAccount?.let { "${it.name} • ${it.phone}" }.orEmpty(),
                        onValueChange = {},
                        label = "Rider team account *",
                        placeholder = "Select a Rider account",
                        leadingIcon = Icons.Rounded.Person,
                        readOnly = true,
                        enabled = !uiState.isLoadingRiderAccounts,
                        trailingIcon = {
                            if (uiState.isLoadingRiderAccounts) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                        },
                        supportingText = if (uiState.isLoadingRiderAccounts) {
                            "Checking eligible Rider team accounts…"
                        } else {
                            "Create the Rider login first in Staff Management."
                        }
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable(
                                enabled = !uiState.isLoadingRiderAccounts && availableRiderAccounts.isNotEmpty()
                            ) { accountMenuExpanded = true }
                    ) {
                        DropdownMenu(
                            expanded = accountMenuExpanded,
                            onDismissRequest = { accountMenuExpanded = false }
                        ) {
                            availableRiderAccounts.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text("${account.name} • ${account.phone}") },
                                    onClick = {
                                        selectedAccount = account
                                        name = account.name
                                        phone = account.phone
                                        accountMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                AquaHubTextField(
                    value = name,
                    onValueChange = {},
                    label = "Rider name",
                    leadingIcon = Icons.Rounded.Person,
                    readOnly = true
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                AquaHubTextField(
                    value = phone,
                    onValueChange = {},
                    label = "Login phone",
                    leadingIcon = Icons.Rounded.Phone,
                    readOnly = true
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                AquaHubTextField(
                    value = vehicle,
                    onValueChange = { vehicle = it },
                    label = "Vehicle Type / Model",
                    placeholder = "e.g. Honda Click 125i",
                    leadingIcon = Icons.Rounded.TwoWheeler
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                AquaHubTextField(
                    value = plate,
                    onValueChange = { plate = it },
                    label = "Plate / Registration Number",
                    placeholder = "e.g. 123-ABC",
                    leadingIcon = Icons.AutoMirrored.Rounded.DirectionsBike
                )

                Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                AquaHubTextField(
                    value = rating,
                    onValueChange = { value ->
                        if (value.length <= 3 && value.count { it == '.' } <= 1 && value.all { it.isDigit() || it == '.' }) {
                            rating = value
                        }
                    },
                    label = "Rating (0–5, optional)",
                    placeholder = "Leave blank until rated",
                    leadingIcon = Icons.Rounded.Star,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = "Leave blank to show No rating yet"
                )

                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    Text(
                        text = uiState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        val editingRider = uiState.editingRider
        if (uiState.showEditDialog && editingRider != null) {
            AquaHubFormDialog(
                onDismissRequest = onCloseEditDialog,
                title = "Edit Delivery Rider",
                confirmButtonText = "Save Changes",
                isConfirmLoading = uiState.isSavingRider,
                onConfirm = {
                    onUpdateRider(editingRider, editName, editPhone, editVehicle, editPlate, editRating)
                },
                dismissButtonText = "Cancel",
                isConfirmEnabled = editName.isNotBlank() && editPhone.isNotBlank() && isValidRatingInput(editRating)
            ) {
                AquaHubTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = "Rider Full Name *",
                    leadingIcon = Icons.Rounded.Person,
                    enabled = !uiState.isSavingRider
                )
                Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                AquaHubPhoneField(
                    value = editPhone,
                    onValueChange = { editPhone = it },
                    label = "Phone Number *",
                    placeholder = "09XX XXX XXXX"
                )
                Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                AquaHubTextField(
                    value = editVehicle,
                    onValueChange = { editVehicle = it },
                    label = "Vehicle Type / Model",
                    placeholder = "e.g. Honda Click 125i",
                    leadingIcon = Icons.Rounded.TwoWheeler
                )
                Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                AquaHubTextField(
                    value = editPlate,
                    onValueChange = { editPlate = it },
                    label = "Plate / Registration Number",
                    placeholder = "e.g. 123-ABC",
                    leadingIcon = Icons.AutoMirrored.Rounded.DirectionsBike
                )
                Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                AquaHubTextField(
                    value = editRating,
                    onValueChange = { value ->
                        if (value.length <= 3 && value.count { it == '.' } <= 1 && value.all { it.isDigit() || it == '.' }) {
                            editRating = value
                        }
                    },
                    label = "Rating (0–5, optional)",
                    placeholder = "Leave blank until rated",
                    leadingIcon = Icons.Rounded.Star,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = "Leave blank to show No rating yet"
                )
                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))
                    Text(
                        text = uiState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

    }

    if (uiState.limitReachedResult != null) {
        LimitReachedDialog(
            limitResult = uiState.limitReachedResult,
            onDismiss = onDismissLimitDialog,
            onUpgradeClick = {
                onDismissLimitDialog()
                onNavigateToSubscription()
            }
        )
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun RidersScreenPreview() {
    AquaHubTheme {
        RidersContent(
            uiState = RidersUiState(
                isLoading = false,
                riders = listOf(
                    Rider(id = 1, name = "Rider Alex", phone = "09181234567", vehicleName = "Honda Wave", rating = 4.8, isAvailable = true, activeOrderCount = 1),
                    Rider(id = 2, name = "Rider Mark", phone = "09192345678", vehicleName = "Yamaha Mio", rating = 4.6, isAvailable = false, activeOrderCount = 0)
                )
            ),
            onBackClick = {},
            onOpenAddDialog = {},
            onCloseAddDialog = {},
            onToggleAvailability = {},
            onOpenEditDialog = {},
            onCloseEditDialog = {},
            onUpdateRider = { _, _, _, _, _, _ -> },
            onAddRider = { _, _, _, _, _ -> },
            availableRiderAccounts = listOf(
                StaffMember("preview-rider", "Rider Alex", "09181234567", "RIDER", true, emptyList())
            )
        )
    }
}

private fun Rider.ratingLabel(): String =
    if (rating > 0.0) String.format(Locale.US, "%.1f", rating) else "No rating yet"

private fun isValidRatingInput(value: String): Boolean =
    value.isBlank() || value.toDoubleOrNull()?.let { it in 0.0..5.0 } == true
