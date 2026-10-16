package com.aesprt.aquahub.feature.staff

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.repository.StaffMember
import com.aesprt.aquahub.ui.theme.*
import kotlin.math.min

@Composable
fun StaffManagementScreen(
    viewModel: StaffManagementViewModel,
    onBackClick: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var editingMember by remember { mutableStateOf<StaffMember?>(null) }
    var deleteTarget by remember { mutableStateOf<StaffMember?>(null) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("STAFF") }
    var selectedStationIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(uiState.successMessage) {
        if (uiState.successMessage != null) {
            showCreate = false
            editingMember = null
            name = ""
            phone = ""
            role = "STAFF"
            selectedStationIds = emptySet()
        }
    }

    LaunchedEffect(showCreate, editingMember, uiState.stations) {
        if (showCreate && editingMember == null && selectedStationIds.isEmpty() && uiState.stations.isNotEmpty()) {
            selectedStationIds = uiState.stations.map { it.remoteId }.toSet()
        }
    }

    Scaffold(
        topBar = {
            AquaHubTopBar(
                title = "Staff Management",
                subtitle = "Paid plan feature",
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.clearMessage()
                    editingMember = null
                    selectedStationIds = uiState.stations.map { it.remoteId }.toSet()
                    showCreate = true
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Rounded.PersonAdd, contentDescription = "Add staff")
            }
        }
    ) { padding ->
        AdaptiveContentFrame(modifier = Modifier.fillMaxSize().padding(padding), maxWidth = 1440.dp) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 420.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(AquaHubSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Team accounts", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Staff can use Home, Orders, and Walk-in Sales. Riders can update delivery status only.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                StaffLimitCard(
                    used = uiState.staff.size,
                    limit = uiState.staffLimit
                )
            }
            uiState.successMessage?.let { message ->
                item(span = { GridItemSpan(maxLineSpan) }) { MessageCard(message = message, isError = false) }
            }
            if (!showCreate) {
                uiState.errorMessage?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) { MessageCard(message = message, isError = true) }
                }
            }
            if (uiState.isLoading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(uiState.staff, key = { it.uid }) { member ->
                StaffRow(
                    member = member,
                    stationNames = uiState.stations.associate { it.remoteId to it.station.name },
                    onEdit = {
                        viewModel.clearMessage()
                        name = member.name
                        phone = member.phone
                        role = member.role
                        selectedStationIds = member.stationIds.toSet()
                        editingMember = member
                        showCreate = false
                    },
                    onDelete = { deleteTarget = member }
                )
            }
            if (!uiState.isLoading && uiState.staff.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    AquaHubEmptyState(
                        icon = Icons.Rounded.Group,
                        message = "No team accounts yet",
                        description = "Add a staff or rider account for your delivery team."
                    )
                }
            }
        }
        }
    }

    if (showCreate || editingMember != null) {
        CreateStaffDialog(
            isEditing = editingMember != null,
            name = name,
            onNameChange = { name = it },
            phone = phone,
            onPhoneChange = { phone = it },
            role = role,
            onRoleChange = { role = it },
            stations = uiState.stations,
            stationsPendingSync = uiState.stationsPendingSync,
            selectedStationIds = selectedStationIds,
            onStationToggle = { stationId ->
                selectedStationIds = selectedStationIds.toMutableSet().apply {
                    if (!add(stationId)) remove(stationId)
                }
            },
            isSaving = uiState.isSaving,
            errorMessage = uiState.errorMessage,
            onDismiss = {
                showCreate = false
                editingMember = null
            },
            onSubmit = {
                editingMember?.let { member ->
                    viewModel.update(member, name, phone, role, selectedStationIds.toList())
                } ?: viewModel.create(name, phone, role, selectedStationIds.toList())
            }
        )
    }

    deleteTarget?.let { member ->
        AlertDialog(
            onDismissRequest = { if (!uiState.isSaving) deleteTarget = null },
            icon = { Icon(Icons.Rounded.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Remove staff account?") },
            text = { Text("${member.name} will no longer be able to sign in to the owner app. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        viewModel.delete(member)
                    },
                    enabled = !uiState.isSaving,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }, enabled = !uiState.isSaving) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StaffLimitCard(used: Int, limit: Int?) {
    val progress = if (limit != null && limit > 0) min(used.toFloat() / limit, 1f) else 0f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AquaHubShapes.cardSecondary,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AquaHubSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = AquaHubShapes.chip,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text("Team account capacity", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (limit != null) "$used of $limit accounts used" else "$used accounts used",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (limit != null) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (progress >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, isError: Boolean) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AquaHubShapes.cardSecondary,
        color = container
    ) {
        Row(
            modifier = Modifier.padding(AquaHubSpacing.mediumSmall),
            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = content
            )
            Text(message, color = content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CreateStaffDialog(
    isEditing: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    phone: String,
    onPhoneChange: (String) -> Unit,
    role: String,
    onRoleChange: (String) -> Unit,
    stations: List<StaffStationOption>,
    stationsPendingSync: Boolean,
    selectedStationIds: Set<String>,
    onStationToggle: (String) -> Unit,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit
) {
    val phoneDigits = phone.filter(Char::isDigit)
    val nameValid = name.trim().length >= 2
    val phoneValid = phoneDigits.length >= 10
    val formValid = nameValid && phoneValid && selectedStationIds.isNotEmpty()

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = !isSaving
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
            shape = AquaHubShapes.dialog,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = AquaHubSpacing.large, vertical = AquaHubSpacing.medium)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)
                ) {
                    Surface(
                        modifier = Modifier.size(46.dp),
                        shape = AquaHubShapes.chip,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.PersonAdd,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (isEditing) "Edit team account" else "Add team account", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (isEditing) "Update this team member's access details" else "Create a phone-OTP account for your team",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = !isSaving) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(AquaHubSpacing.medium))

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)
                ) {
                    AquaHubTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = "Full name",
                        placeholder = "e.g. Juan Dela Cruz",
                        leadingIcon = Icons.Rounded.Person,
                        isError = name.isNotBlank() && !nameValid,
                        errorMessage = "Enter at least 2 characters.",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    AquaHubPhoneField(
                        value = phone,
                        onValueChange = onPhoneChange,
                        label = "Phone number",
                        isError = phone.isNotBlank() && !phoneValid,
                        errorMessage = "Enter a valid phone number.",
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Next)
                    )
                    Text(
                        if (isEditing) {
                            "The team member signs in with this phone number and a one-time SMS code."
                        } else {
                            "No temporary password is needed. The team member will sign in with this phone number and a one-time SMS code."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                        Text("Choose a role", style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(
                            "Permissions are applied immediately after sign-in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        StaffRoleOption(
                            title = "Staff",
                            description = "Home, orders, and walk-in sales",
                            icon = Icons.Rounded.Badge,
                            selected = role == "STAFF",
                            onClick = { onRoleChange("STAFF") }
                        )
                        StaffRoleOption(
                            title = "Manager",
                            description = "Operations plus team and station management",
                            icon = Icons.Rounded.ManageAccounts,
                            selected = role == "MANAGER",
                            onClick = { onRoleChange("MANAGER") }
                        )
                        StaffRoleOption(
                            title = "Rider",
                            description = "Assigned delivery orders and delivery status",
                            icon = Icons.Rounded.TwoWheeler,
                            selected = role == "RIDER",
                            onClick = { onRoleChange("RIDER") }
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                        Text("Assigned stations", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "Choose which station branches this account can operate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (stations.isEmpty()) {
                            MessageCard(
                                message = if (stationsPendingSync) {
                                    "Your station is still syncing. Refresh Staff Management after sync completes."
                                } else {
                                    "No station branches are available. Create a station first."
                                },
                                isError = true
                            )
                        } else {
                            stations.forEach { option ->
                                val station = option.station
                                val stationId = option.remoteId
                                FilterChip(
                                    selected = stationId in selectedStationIds,
                                    onClick = { onStationToggle(stationId) },
                                    label = {
                                        Column {
                                            Text(station.name)
                                            if (station.address.isNotBlank()) {
                                                Text(
                                                    station.address,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (stationId in selectedStationIds) Icons.Rounded.CheckCircle else Icons.Rounded.Storefront,
                                            contentDescription = null
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    if (!errorMessage.isNullOrBlank()) {
                        MessageCard(message = errorMessage, isError = true)
                    }
                }

                Spacer(Modifier.height(AquaHubSpacing.medium))
                AquaHubButton(
                    text = if (isSaving) "Saving…" else if (isEditing) "Save changes" else "Create account",
                    onClick = onSubmit,
                    enabled = formValid && !isSaving,
                    isLoading = isSaving,
                    icon = Icons.Rounded.PersonAdd,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(AquaHubSpacing.small))
                AquaHubOutlinedButton(
                    text = "Cancel",
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun StaffRoleOption(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = AquaHubShapes.cardSecondary,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(12.dp)
        ) {
            Surface(
                shape = AquaHubShapes.chip,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
private fun StaffRow(
    member: StaffMember,
    stationNames: Map<String, String> = emptyMap(),
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AquaHubGlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(member.name, style = MaterialTheme.typography.titleMedium)
                Text(member.phone, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val assignedStations = member.stationIds.map { stationNames[it] ?: "Station $it" }
                Text(
                    text = if (assignedStations.isEmpty()) "No stations assigned" else "Stations: ${assignedStations.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (assignedStations.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(member.role, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (member.isActive) "Active" else "Inactive",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = "Edit ${member.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Remove ${member.name}",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun StaffManagementScreenPreview() {
    val stationNames = mapOf("station-main" to "AquaPure Main")
    val members = listOf(
        StaffMember(
            uid = "preview-cashier",
            name = "Maria Santos",
            phone = "+639171234567",
            role = "STAFF",
            isActive = true,
            stationIds = listOf("station-main")
        ),
        StaffMember(
            uid = "preview-rider",
            name = "Jose Reyes",
            phone = "+639181234567",
            role = "RIDER",
            isActive = true,
            stationIds = listOf("station-main")
        )
    )
    AquaHubTheme {
        Scaffold(
            topBar = {
                AquaHubTopBar(
                    title = "Staff Management",
                    subtitle = "Paid plan feature",
                    onBackClick = {}
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = {}) {
                    Icon(Icons.Rounded.PersonAdd, contentDescription = "Add staff")
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            AdaptiveContentFrame(
                modifier = Modifier.fillMaxSize().padding(padding),
                maxWidth = 1440.dp
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 420.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(AquaHubSpacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                    verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Text("Team accounts", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Staff can use Home, Orders, and Walk-in Sales. Riders can update delivery status only.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        StaffLimitCard(used = members.size, limit = 5)
                    }
                    items(members, key = { it.uid }) { member ->
                        StaffRow(
                            member = member,
                            stationNames = stationNames,
                            onEdit = {},
                            onDelete = {}
                        )
                    }
                }
            }
        }
    }
}
