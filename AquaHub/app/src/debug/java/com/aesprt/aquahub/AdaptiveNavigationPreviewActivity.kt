package com.aesprt.aquahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.core.designsystem.components.AquaHubAdaptiveNavigationScaffold
import com.aesprt.aquahub.feature.main.BottomNavItem
import com.aesprt.aquahub.feature.main.isSelectedForRoute
import com.aesprt.aquahub.feature.main.mainNavigationItems
import com.aesprt.aquahub.ui.theme.AquaHubTheme

/** Debug-only harness for capturing the shared phone/tablet navigation at runtime. */
class AdaptiveNavigationPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AquaHubTheme {
                AdaptiveNavigationPreview()
            }
        }
    }
}

@Composable
private fun AdaptiveNavigationPreview() {
    val destinations = mainNavigationItems(isFieldWorker = false)
    var selectedRoute by rememberSaveable { mutableStateOf(BottomNavItem.Dashboard.route) }

    AquaHubAdaptiveNavigationScaffold(
        navigationSuiteItems = {
            destinations.forEach { destination ->
                item(
                    selected = destination.isSelectedForRoute(selectedRoute),
                    onClick = { selectedRoute = destination.route },
                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                    label = { Text(destination.label) },
                    alwaysShowLabel = true,
                )
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    Text("AquaHub Owner", style = MaterialTheme.typography.titleSmall)
                    Text("AquaPure Main Station", style = MaterialTheme.typography.headlineSmall)
                }
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        destinations.first { it.isSelectedForRoute(selectedRoute) }.label,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "Navigation layout preview · sample content",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PreviewMetricCard(Modifier.weight(1f), "Sales today")
                    PreviewMetricCard(Modifier.weight(1f), "Open orders")
                    PreviewMetricCard(Modifier.weight(1f), "Stock alerts")
                }
            }
        }
    }
}

@Composable
private fun PreviewMetricCard(modifier: Modifier, label: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("—", style = MaterialTheme.typography.headlineMedium)
        }
    }
}
