package com.aesprt.aquahub.feature.main

import com.aesprt.aquahub.core.designsystem.components.SubscriptionGate
import com.aesprt.aquahub.domain.model.SubscriptionFeature

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aesprt.aquahub.core.designsystem.components.AdaptiveContentFrame
import com.aesprt.aquahub.core.designsystem.components.AquaHubAdaptiveNavigationScaffold
import com.aesprt.aquahub.core.designsystem.components.AquaHubTopBar
import com.aesprt.aquahub.core.navigation.Screen
import com.aesprt.aquahub.feature.customers.list.CustomersScreen
import com.aesprt.aquahub.feature.dashboard.DashboardScreen
import com.aesprt.aquahub.feature.inventory.InventoryScreen
import com.aesprt.aquahub.feature.orders.list.OrdersScreen
import com.aesprt.aquahub.feature.orders.walkin.QuickWalkInSaleSheet
import com.aesprt.aquahub.feature.orders.walkin.WalkInSaleViewModel
import com.aesprt.aquahub.feature.settings.SettingsScreen
import com.aesprt.aquahub.feature.sync.SyncStatusViewModel
import com.aesprt.aquahub.sync.SyncUiState
import com.aesprt.aquahub.ui.theme.*
import org.koin.androidx.compose.koinViewModel

sealed class BottomNavItem(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : BottomNavItem(Screen.Dashboard.route, "Home", Icons.Rounded.Home)
    data object Orders : BottomNavItem(Screen.Orders.createRoute("ALL"), "Orders", Icons.AutoMirrored.Rounded.ReceiptLong)
    data object Inventory : BottomNavItem(Screen.Inventory.route, "Inventory", Icons.Rounded.Inventory2)
    data object Customers : BottomNavItem(Screen.Customers.route, "Customers", Icons.Rounded.People)
    data object More : BottomNavItem(Screen.Settings.route, "Settings", Icons.Rounded.Settings)
}

internal fun BottomNavItem.isSelectedForRoute(currentRoute: String?): Boolean = when (this) {
    BottomNavItem.Dashboard -> currentRoute == Screen.Dashboard.route
    BottomNavItem.Orders -> currentRoute?.startsWith("orders") == true
    BottomNavItem.Inventory -> currentRoute == Screen.Inventory.route
    BottomNavItem.Customers -> currentRoute == Screen.Customers.route
    BottomNavItem.More -> currentRoute == Screen.Settings.route
}

internal fun mainNavigationItems(isFieldWorker: Boolean): List<BottomNavItem> =
    if (isFieldWorker) {
        listOf(BottomNavItem.Dashboard, BottomNavItem.Orders)
    } else {
        listOf(
            BottomNavItem.Dashboard,
            BottomNavItem.Orders,
            BottomNavItem.Inventory,
            BottomNavItem.Customers,
            BottomNavItem.More,
        )
    }

@Composable
fun MainContainerScreen(
    rootNavController: NavHostController
) {
    val bottomNavController = rememberNavController()
    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val syncViewModel: SyncStatusViewModel = koinViewModel()
    val syncState by syncViewModel.state.collectAsState()
    val mainViewModel: MainContainerViewModel = koinViewModel()
    val uncompletedOrdersCount by mainViewModel.uncompletedOrdersCount.collectAsState()
    val role by mainViewModel.role.collectAsState()
    val isStaff by mainViewModel.isStaff.collectAsState()
    val isRider by mainViewModel.isRider.collectAsState()
    val isFieldWorker = isStaff || isRider
    val canUseWalkInSale = role != null && !isRider

    var isFabExpanded by remember { mutableStateOf(false) }
    var showWalkInSaleSheet by remember { mutableStateOf(false) }
    val isRefreshing = syncState == SyncUiState.Syncing
    val mainGateLoading = remember { mutableStateMapOf<String, Boolean>() }
    val currentGateKey = when {
        currentRoute == Screen.Dashboard.route -> "dashboard"
        currentRoute?.startsWith("orders") == true -> "orders"
        currentRoute == Screen.Inventory.route -> "inventory"
        currentRoute == Screen.Customers.route -> "customers"
        else -> null
    }
    // Keep quick actions unavailable until the current tab's access gate has
    // settled. Otherwise a walk-in sale can mount a second full-screen gate
    // loader over the tab's loader.
    val isCurrentGateLoading = currentGateKey?.let { mainGateLoading[it] ?: true } ?: false
    LaunchedEffect(isCurrentGateLoading) {
        if (isCurrentGateLoading) isFabExpanded = false
    }
    val fabRotation by animateFloatAsState(
        targetValue = if (isFabExpanded) 45f else 0f,
        label = "fabRotation"
    )

    val bottomNavItems = mainNavigationItems(isFieldWorker)

    AquaHubAdaptiveNavigationScaffold(
        navigationSuiteItems = {
            bottomNavItems.forEach { item ->
                val isSelected = item.isSelectedForRoute(currentRoute)
                item(
                    selected = isSelected,
                    onClick = {
                        isFabExpanded = false
                        if (!isSelected) {
                            bottomNavController.navigate(item.route) {
                                popUpTo(bottomNavController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    label = { Text(item.label) },
                    badge = if (item == BottomNavItem.Orders && uncompletedOrdersCount > 0) {
                        { Badge { Text(if (uncompletedOrdersCount > 99) "99+" else uncompletedOrdersCount.toString()) } }
                    } else {
                        null
                    }
                )
            }
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                SyncStatusBanner(syncState = syncState, onSyncNow = syncViewModel::syncNow)
            },
            floatingActionButton = {
            if (canUseWalkInSale && currentRoute != Screen.Customers.route && !isCurrentGateLoading) {
                if (isStaff) {
                    // Staff have one quick action only: process a walk-in sale.
                    FloatingActionButton(
                        onClick = { showWalkInSaleSheet = true },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PointOfSale,
                            contentDescription = "Walk-in Station Sale"
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Expandable Menu Items
                        AnimatedVisibility(
                            visible = isFabExpanded,
                            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
                        ) {
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                        FabMiniAction(
                            label = "Walk-in Station Sale (POS)",
                            icon = Icons.Rounded.PointOfSale,
                            onClick = {
                                isFabExpanded = false
                                showWalkInSaleSheet = true
                            }
                        )
                        FabMiniAction(
                            label = "Add Product",
                            icon = Icons.Rounded.AddShoppingCart,
                            onClick = {
                                isFabExpanded = false
                                rootNavController.navigate(Screen.Products.route)
                            }
                        )
                        FabMiniAction(
                            label = "Add Rider",
                            icon = Icons.Rounded.PersonAdd,
                            onClick = {
                                isFabExpanded = false
                                rootNavController.navigate(Screen.Riders.route)
                            }
                        )
                        FabMiniAction(
                            label = "Sales Report",
                            icon = Icons.Rounded.Analytics,
                            onClick = {
                                isFabExpanded = false
                                rootNavController.navigate(Screen.Reports.route)
                            }
                        )
                            }
                        }

                        // Main Action FAB
                        FloatingActionButton(
                            onClick = { isFabExpanded = !isFabExpanded },
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Quick Actions",
                                modifier = Modifier
                                    .size(26.dp)
                                    .rotate(fabRotation)
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = syncViewModel::syncNow,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
        AdaptiveContentFrame(
            modifier = Modifier.fillMaxSize(),
            maxWidth = 1600.dp
        ) {
        NavHost(
            navController = bottomNavController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Dashboard.route) {
                SubscriptionGate(
                    SubscriptionFeature.DASHBOARD,
                    { rootNavController.navigate(Screen.SubscriptionBilling.route) },
                    onLoadingChanged = { mainGateLoading["dashboard"] = it }
                ) {
DashboardScreen(
                    viewModel = koinViewModel(),
                    onNavigateToOrders = { tab ->
                        bottomNavController.navigate(Screen.Orders.createRoute(tab))
                    },
                    onNavigateToOrderDetail = { orderId ->
                        rootNavController.navigate(Screen.OrderDetail.createRoute(orderId))
                    },
                    onNavigateToInventory = {
                        bottomNavController.navigate(Screen.Inventory.route)
                    },
                    onNavigateToCustomers = {
                        bottomNavController.navigate(Screen.Customers.route)
                    },
                    onNavigateToReports = {
                        rootNavController.navigate(Screen.Reports.route)
                    },
                    onNavigateToProducts = {
                        rootNavController.navigate(Screen.Products.route)
                    },
                    onNavigateToExpenses = {
                        rootNavController.navigate(Screen.Expenses.route)
                    },
                    onNavigateToManageStations = {
                        rootNavController.navigate(Screen.ManageStations.route)
                    },
                    onNavigateToSubscription = {
                        rootNavController.navigate(Screen.SubscriptionBilling.route)
                    },
                    isStaff = isFieldWorker,
                    onLogout = {
                        mainViewModel.logout {
                            rootNavController.navigate(Screen.Login.route) {
                                popUpTo(Screen.MainContainer.route) { inclusive = true }
                            }
                        }
                    }
                )
            }
            }

            composable(
                route = Screen.Orders.route,
                arguments = listOf(
                    navArgument("tab") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val tab = backStackEntry.arguments?.getString("tab")
                SubscriptionGate(
                    SubscriptionFeature.ORDER_MANAGEMENT,
                    { rootNavController.navigate(Screen.SubscriptionBilling.route) },
                    onLoadingChanged = { mainGateLoading["orders"] = it }
                ) {
OrdersScreen(
                    viewModel = koinViewModel(),
                    initialTab = tab,
                    onNavigateToOrderDetail = { orderId ->
                        rootNavController.navigate(Screen.OrderDetail.createRoute(orderId))
                    },
                    onNavigateToSubscription = {
                        rootNavController.navigate(Screen.SubscriptionBilling.route)
                    },
                    canCreateWalkInSale = canUseWalkInSale
                )
            }
            }

            composable(Screen.Inventory.route) {
                SubscriptionGate(
                    SubscriptionFeature.BASIC_INVENTORY,
                    { rootNavController.navigate(Screen.SubscriptionBilling.route) },
                    onLoadingChanged = { mainGateLoading["inventory"] = it }
                ) {
                InventoryScreen(
                    viewModel = koinViewModel(),
                    onBackClick = {
                        bottomNavController.navigate(Screen.Dashboard.route)
                    },
                    onOpenSubscription = { rootNavController.navigate(Screen.SubscriptionBilling.route) }
                )
            }
            }

            composable(Screen.Customers.route) {
                SubscriptionGate(
                    SubscriptionFeature.CUSTOMER_MANAGEMENT,
                    { rootNavController.navigate(Screen.SubscriptionBilling.route) },
                    onLoadingChanged = { mainGateLoading["customers"] = it }
                ) {
CustomersScreen(
                    viewModel = koinViewModel(),
                    onNavigateToCustomerDetail = { customerId ->
                        rootNavController.navigate(Screen.CustomerDetail.createRoute(customerId))
                    }
                )
            }
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = koinViewModel(),
                    onNavigateToStationProfile = {
                        rootNavController.navigate(Screen.StationProfile.route)
                    },
                    onNavigateToCustomerOrdering = {
                        rootNavController.navigate(Screen.CustomerOrdering.route)
                    },
                    onNavigateToPaymentMethods = {
                        rootNavController.navigate(Screen.PaymentMethods.route)
                    },
                    onNavigateToDeliverySettings = {
                        rootNavController.navigate(Screen.DeliverySettings.route)
                    },
                    onNavigateToManageStations = {
                        rootNavController.navigate(Screen.ManageStations.route)
                    },
                    onNavigateToStaffManagement = {
                        rootNavController.navigate(Screen.StaffManagement.route)
                    },
                    onNavigateToRiders = {
                        rootNavController.navigate(Screen.Riders.route)
                    },
                    onNavigateToProducts = {
                        rootNavController.navigate(Screen.Products.route)
                    },
                    onNavigateToReports = {
                        rootNavController.navigate(Screen.Reports.route)
                    },
                    onNavigateToSubscription = {
                        rootNavController.navigate(Screen.SubscriptionBilling.route)
                    },
                    onAccountDeleted = {
                        rootNavController.navigate(Screen.Login.route) {
                            popUpTo(Screen.MainContainer.route) { inclusive = true }
                        }
                    },
                    onLogoutSuccess = {
                        rootNavController.navigate(Screen.Login.route) {
                            popUpTo(Screen.MainContainer.route) { inclusive = true }
                        }
                    }
                )
            }
        }
        }
        }
        }
    }

    if (showWalkInSaleSheet) {
        val walkInViewModel: WalkInSaleViewModel = koinViewModel()
        SubscriptionGate(SubscriptionFeature.ORDER_MANAGEMENT, { rootNavController.navigate(Screen.SubscriptionBilling.route) }) {
        QuickWalkInSaleSheet(
            viewModel = walkInViewModel,
            onDismiss = {
                walkInViewModel.resetState()
                showWalkInSaleSheet = false
            },
            onSaleCompleted = { orderId ->
                rootNavController.navigate(Screen.OrderDetail.createRoute(orderId))
            },
            onNavigateToSubscription = {
                walkInViewModel.resetState()
                showWalkInSaleSheet = false
                rootNavController.navigate(Screen.SubscriptionBilling.route)
            }
        )
            }
    }
}

@Composable
private fun SyncStatusBanner(syncState: SyncUiState, onSyncNow: () -> Unit) {
    AnimatedVisibility(visible = syncState !is SyncUiState.Synced) {
        val (message, icon) = when (syncState) {
            is SyncUiState.Offline ->
                if (syncState.pendingChanges == 0) {
                    "Offline · online orders will refresh when you reconnect" to Icons.Rounded.CloudOff
                } else {
                    "Offline · ${syncState.pendingChanges} change${if (syncState.pendingChanges == 1) "" else "s"} will sync automatically" to Icons.Rounded.CloudOff
                }
            is SyncUiState.Pending ->
                "${syncState.pendingChanges} change${if (syncState.pendingChanges == 1) "" else "s"} pending sync" to Icons.Rounded.CloudUpload
            is SyncUiState.Failed ->
                (syncState.message ?: "${syncState.failedChanges} sync change${if (syncState.failedChanges == 1) "" else "s"} need attention") to Icons.Rounded.SyncProblem
            SyncUiState.Syncing -> "Synchronizing…" to Icons.Rounded.Sync
            SyncUiState.Synced -> "Synced" to Icons.Rounded.CloudDone
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onSyncNow
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(message, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun FabMiniAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Surface(
            shape = AquaHubShapes.chip,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 3.dp,
            border = BorderStroke(0.5.dp, GlassBorderAqua)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = CircleShape
        ) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(18.dp))
        }
    }
}

@com.aesprt.aquahub.core.designsystem.components.AquaHubPhoneAndTabletPreview
@Composable
private fun MainContainerScreenPreview() {
    AquaHubTheme {
        var selectedRoute by remember { mutableStateOf(Screen.Dashboard.route) }
        AquaHubAdaptiveNavigationScaffold(
            navigationSuiteItems = {
                mainNavigationItems(isFieldWorker = false).forEach { item ->
                    item(
                        selected = item.isSelectedForRoute(selectedRoute),
                        onClick = { selectedRoute = item.route },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        ) {
            Scaffold(
                topBar = {
                    AquaHubTopBar(
                        title = "AquaHub Owner",
                        subtitle = "AquaPure Main Station",
                        onBackClick = {}
                    )
                },
                containerColor = MaterialTheme.colorScheme.background
            ) { padding ->
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${selectedRoute.replace('_', ' ').replaceFirstChar(Char::uppercase)} workspace")
                }
            }
        }
    }
}
