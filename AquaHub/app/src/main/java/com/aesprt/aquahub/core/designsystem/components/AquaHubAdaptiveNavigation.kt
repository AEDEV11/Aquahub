package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScope
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.ui.theme.AquaHubTheme

/**
 * AquaHub's responsive primary navigation. The Material 3 policy uses a bottom bar for compact
 * windows, short-height windows, and tabletop posture; other widths use a navigation rail.
 *
 * The adaptive info is read from the current window, so resizing changes only the navigation
 * presentation. Callers keep ownership of their NavHost and back stack.
 */
@Composable
fun AquaHubAdaptiveNavigationScaffold(
    navigationSuiteItems: NavigationSuiteScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    NavigationSuiteScaffold(
        navigationSuiteItems = navigationSuiteItems,
        modifier = modifier,
        layoutType = aquaHubNavigationSuiteType(adaptiveInfo),
        content = content,
    )
}

/** Uses Material 3's standard NavigationBar/NavigationRail policy for the supplied window. */
internal fun aquaHubNavigationSuiteType(adaptiveInfo: WindowAdaptiveInfo): NavigationSuiteType =
    NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)

@AquaHubPhoneAndTabletPreview
@Composable
private fun AquaHubAdaptiveNavigationPreview() {
    AquaHubTheme {
        val destinations = listOf(
            Triple("Home", "dashboard", Icons.Rounded.Home),
            Triple("Orders", "orders", Icons.AutoMirrored.Rounded.ReceiptLong),
            Triple("Inventory", "inventory", Icons.Rounded.Inventory2),
            Triple("Customers", "customers", Icons.Rounded.People),
            Triple("Settings", "settings", Icons.Rounded.Settings),
        )
        var selectedRoute by remember { mutableStateOf("dashboard") }

        AquaHubAdaptiveNavigationScaffold(
            navigationSuiteItems = {
                destinations.forEach { (label, route, icon) ->
                    item(
                        selected = selectedRoute == route,
                        onClick = { selectedRoute = route },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        alwaysShowLabel = true,
                    )
                }
            },
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("AquaHub Owner", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = destinations.first { it.second == selectedRoute }.first,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Primary navigation adapts to the available window width.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
