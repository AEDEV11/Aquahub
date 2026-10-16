package com.aesprt.aquahub.feature.customers.list

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import com.aesprt.aquahub.core.designsystem.components.*
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.CustomerAddress
import com.aesprt.aquahub.feature.location.LocationPickerDialog
import com.aesprt.aquahub.feature.location.LocationPickerViewModel
import com.aesprt.aquahub.ui.theme.*
import org.koin.androidx.compose.koinViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun CustomersScreen(
    viewModel: CustomersViewModel,
    onNavigateToCustomerDetail: (customerId: Long) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    CustomersContent(
        uiState = uiState,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onOpenAddDialog = viewModel::openAddDialog,
        onCloseAddDialog = viewModel::closeAddDialog,
        onAddCustomer = viewModel::addCustomer,
        onNavigateToCustomerDetail = onNavigateToCustomerDetail
    )
}

@Composable
fun CustomersContent(
    uiState: CustomersUiState,
    onSearchQueryChange: (String) -> Unit,
    onOpenAddDialog: () -> Unit,
    onCloseAddDialog: () -> Unit,
    onAddCustomer: (String, String, CustomerAddress?) -> Unit,
    onNavigateToCustomerDetail: (customerId: Long) -> Unit
) {
    val currencyFormat = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-PH"))
    var selectedCustomerId by rememberSaveable { mutableStateOf<Long?>(null) }

    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }
    var newBarangay by remember { mutableStateOf<String?>(null) }
    var newCity by remember { mutableStateOf<String?>(null) }
    var newProvince by remember { mutableStateOf<String?>(null) }
    var newLatitude by remember { mutableStateOf<Double?>(null) }
    var newLongitude by remember { mutableStateOf<Double?>(null) }
    var showLocationPicker by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.showAddDialog) {
        if (!uiState.showAddDialog) {
            newName = ""
            newPhone = ""
            newAddress = ""
            newBarangay = null
            newCity = null
            newProvince = null
            newLatitude = null
            newLongitude = null
            showLocationPicker = false
        }
    }

    if (showLocationPicker) {
        val locationPickerViewModel: LocationPickerViewModel = koinViewModel(
            key = "add-customer-location-picker"
        )
        LocationPickerDialog(
            viewModel = locationPickerViewModel,
            initialAddress = newAddress,
            initialLat = newLatitude,
            initialLng = newLongitude,
            searchPlaceholder = "Search customer delivery address...",
            selectedAddressLabel = "Selected Delivery Address",
            markerTitle = "Customer Delivery Location",
            onDismiss = { showLocationPicker = false },
            onLocationConfirmed = { location ->
                newAddress = location.formattedAddress
                newBarangay = location.barangay
                newCity = location.city
                newProvince = location.province
                newLatitude = location.latitude
                newLongitude = location.longitude
                showLocationPicker = false
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenAddDialog,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = "Add Customer")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Header & Search
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                border = BorderStroke(0.5.dp, GlassBorderAqua),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = AquaHubSpacing.mediumLarge, vertical = AquaHubSpacing.medium)
                ) {
                    Text(
                        text = "Customers Directory",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = (-0.3).sp
                        )
                    )
                    Text(
                        text = "Manage customer accounts & delivery history",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.height(AquaHubSpacing.small))

                    AquaHubSearchField(
                        query = uiState.searchQuery,
                        onQueryChange = onSearchQueryChange,
                        placeholderText = "Search customer name or phone..."
                    )
                }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val showCustomerDetailPane = maxWidth >= 840.dp
            val selectedCustomer = uiState.customers.firstOrNull { it.id == selectedCustomerId }
                ?: uiState.customers.firstOrNull()
            LaunchedEffect(showCustomerDetailPane, selectedCustomerId, uiState.customers.firstOrNull()?.id) {
                if (showCustomerDetailPane && selectedCustomer?.id != null && selectedCustomerId != selectedCustomer.id) {
                    selectedCustomerId = selectedCustomer?.id
                }
            }
            AnimatedContent(
                targetState = uiState.isLoading,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "customersContentAnim"
            ) { loading ->
                if (loading) {
                    Column(
                        modifier = Modifier.padding(AquaHubSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall)
                    ) {
                        repeat(5) {
                            AquaHubSkeleton(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(84.dp)
                            )
                        }
                    }
                } else if (uiState.customers.isEmpty()) {
                    AquaHubEmptyState(
                        icon = Icons.Rounded.Groups,
                        message = "No Customers Found",
                        description = "Add a new customer or try another search query",
                        actionText = "Add Customer",
                        onActionClick = onOpenAddDialog
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 420.dp),
                            contentPadding = PaddingValues(AquaHubSpacing.medium),
                            horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.mediumSmall),
                            modifier = if (showCustomerDetailPane) Modifier.weight(0.58f).fillMaxHeight() else Modifier.fillMaxSize()
                        ) {
                        items(uiState.customers, key = { it.id }) { customer ->
                            val isSelected = customer.id == selectedCustomer?.id
                            AquaHubGlassCard(
                                modifier = Modifier.fillMaxWidth(),
                                borderColor = if (showCustomerDetailPane && isSelected) MaterialTheme.colorScheme.primary else GlassBorderAqua,
                                backgroundColor = if (showCustomerDetailPane && isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                onClick = {
                                    if (showCustomerDetailPane) selectedCustomerId = customer.id
                                    else onNavigateToCustomerDetail(customer.id)
                                }
                            ) {
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
                                            Text(
                                                text = customer.name.take(1).uppercase(),
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(AquaHubSpacing.mediumSmall))
                                        Column {
                                            Text(
                                                text = customer.name,
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Phone,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = customer.phone,
                                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                )
                                            }
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Surface(
                                            shape = AquaHubShapes.chip,
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = "${customer.totalOrders} Orders",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary
                                                ),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = currencyFormat.format(customer.totalSpent),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Medium
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (showCustomerDetailPane) {
                        selectedCustomer?.let { customer ->
                            CustomerDirectoryDetailPane(
                                customer = customer,
                                currencyFormat = currencyFormat,
                                onOpenAccount = { onNavigateToCustomerDetail(customer.id) },
                                modifier = Modifier.weight(0.42f).fillMaxHeight().padding(end = AquaHubSpacing.medium, top = AquaHubSpacing.medium, bottom = AquaHubSpacing.medium)
                            )
                        }
                    }
                }
            }
            }
        }
    }

    // Add Customer Dialog
    if (uiState.showAddDialog && !showLocationPicker) {
        AquaHubFormDialog(
            onDismissRequest = onCloseAddDialog,
            title = "Add New Customer",
            confirmButtonText = "Save Customer",
            onConfirm = {
                val deliveryAddress = if (
                    newAddress.isNotBlank() && newLatitude != null && newLongitude != null
                ) {
                    CustomerAddress(
                        label = "Delivery",
                        addressLine = newAddress,
                        barangay = newBarangay,
                        city = newCity,
                        province = newProvince,
                        latitude = newLatitude,
                        longitude = newLongitude,
                        isDefault = true
                    )
                } else {
                    null
                }
                onAddCustomer(newName, newPhone, deliveryAddress)
            },
            dismissButtonText = "Cancel",
            isConfirmEnabled = newName.isNotBlank() && newPhone.isNotBlank()
        ) {
            AquaHubTextField(
                value = newName,
                onValueChange = { newName = it },
                label = "Customer Name *",
                placeholder = "Full Name",
                leadingIcon = Icons.Rounded.Person
            )

            Spacer(modifier = Modifier.height(AquaHubSpacing.small))

            AquaHubPhoneField(
                value = newPhone,
                onValueChange = { newPhone = it },
                label = "Phone Number *",
                placeholder = "09XX XXX XXXX"
            )

            Spacer(modifier = Modifier.height(AquaHubSpacing.small))

            Surface(
                onClick = { showLocationPicker = true },
                shape = AquaHubShapes.cardSecondary,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
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
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, AquaHubShapes.chip)
                    ) {
                        Icon(
                            imageVector = if (newLatitude != null) Icons.Rounded.LocationOn else Icons.Rounded.Map,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(AquaHubSpacing.small))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (newLatitude != null) "Delivery location selected" else "Choose delivery location",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = newAddress.ifBlank { "Search an address or choose a point on the map" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2
                        )
                        if (newLatitude != null && newLongitude != null) {
                            Text(
                                text = String.format(Locale.US, "%.5f, %.5f", newLatitude, newLongitude),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search delivery location",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                text = "Optional. The selected map pin will be saved for delivery navigation.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
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

}

@Composable
private fun CustomerDirectoryDetailPane(
    customer: Customer,
    currencyFormat: NumberFormat,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = AquaHubShapes.cardPrimary,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AquaHubSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Text(customer.name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(customer.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(customer.phone, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                AquaHubMetricCard(
                    title = "Orders",
                    value = customer.totalOrders.toString(),
                    icon = Icons.AutoMirrored.Rounded.ReceiptLong,
                    iconBgColor = MaterialTheme.colorScheme.primaryContainer,
                    iconColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                AquaHubMetricCard(
                    title = "Total spent",
                    value = currencyFormat.format(customer.totalSpent),
                    icon = Icons.Rounded.Payments,
                    iconBgColor = MaterialTheme.colorScheme.tertiaryContainer,
                    iconColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
            }
            customer.email?.takeIf(String::isNotBlank)?.let { email ->
                CustomerDetailRow("Email", email)
            }
            customer.addresses.firstOrNull()?.let { address ->
                CustomerDetailRow("Default delivery address", address.addressLine)
            }
            customer.notes?.takeIf(String::isNotBlank)?.let { notes ->
                CustomerDetailRow("Notes", notes)
            }
            Spacer(Modifier.weight(1f, fill = false))
            Button(onClick = onOpenAccount, modifier = Modifier.fillMaxWidth()) {
                Text("Open customer account")
            }
        }
    }
}

@Composable
private fun CustomerDetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun CustomersScreenPreview() {
    AquaHubTheme {
        CustomersContent(
            uiState = CustomersUiState(
                customers = listOf(
                    Customer(id = 1, name = "Juan Dela Cruz", phone = "09171234567", totalOrders = 8, totalSpent = 1320.0),
                    Customer(id = 2, name = "Maria Reyes", phone = "09182345678", totalOrders = 4, totalSpent = 380.0)
                )
            ),
            onSearchQueryChange = {},
            onOpenAddDialog = {},
            onCloseAddDialog = {},
            onAddCustomer = { _, _, _ -> },
            onNavigateToCustomerDetail = {}
        )
    }
}
