package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aesprt.aquahub.ui.theme.AquaHubTheme
import com.aesprt.aquahub.ui.theme.AquaHubSpacing

/** Width classes describe the space available to this composable, not a device category. */
enum class AquaHubWindowWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
    LARGE,
    EXTRA_LARGE
}

typealias AdaptiveCardItem = @Composable (Modifier) -> Unit

fun aquaHubWindowWidthClass(width: Dp): AquaHubWindowWidthClass = when {
    width < 600.dp -> AquaHubWindowWidthClass.COMPACT
    width < 840.dp -> AquaHubWindowWidthClass.MEDIUM
    width < 1200.dp -> AquaHubWindowWidthClass.EXPANDED
    width < 1600.dp -> AquaHubWindowWidthClass.LARGE
    else -> AquaHubWindowWidthClass.EXTRA_LARGE
}

/**
 * Centers task content and gives prose/forms a useful reading width while retaining a
 * wider workspace for grids and dashboards. The max width is recalculated as the window resizes.
 */
@Composable
fun AdaptiveContentFrame(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 1560.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .fillMaxSize(),
            content = content
        )
    }
}

/** Use for authentication and settings forms whose fields become hard to scan when stretched. */
@Composable
fun AdaptiveFormFrame(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 640.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    AdaptiveContentFrame(
        modifier = modifier,
        maxWidth = maxWidth,
        content = content
    )
}

/** Lays out metric or management cards as compact rows that gain columns on wide windows. */
@Composable
fun AdaptiveCardGrid(
    cards: List<AdaptiveCardItem>,
    modifier: Modifier = Modifier,
    wideBreakpoint: Dp = 1100.dp,
    compactColumns: Int = 2,
    wideColumns: Int = 4,
    itemSpacing: Dp = 12.dp
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (if (maxWidth >= wideBreakpoint) wideColumns else compactColumns)
            .coerceAtLeast(1)
            .coerceAtMost(cards.size.coerceAtLeast(1))
        Column(verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
            cards.chunked(columns).forEach { rowCards ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {
                    rowCards.forEach { card -> card(Modifier.weight(1f)) }
                    repeat(columns - rowCards.size) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * A width-aware two-pane arrangement. Compact and medium windows show the primary task only;
 * callers can keep selection in state and promote the secondary content when space is available.
 */
@Composable
fun AdaptiveTwoPaneLayout(
    modifier: Modifier = Modifier,
    minimumWidth: Dp = 840.dp,
    primaryWeight: Float = 0.58f,
    secondaryWeight: Float = 0.42f,
    paneSpacing: Dp = 20.dp,
    primary: @Composable (Modifier) -> Unit,
    secondary: @Composable (Modifier) -> Unit
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= minimumWidth) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(paneSpacing),
                verticalAlignment = Alignment.Top
            ) {
                primary(Modifier.weight(primaryWeight))
                VerticalDivider(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxSize(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                secondary(Modifier.weight(secondaryWeight))
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                primary(Modifier.weight(1f))
            }
        }
    }
}

@AquaHubPhoneAndTabletPreview
@Composable
private fun AdaptiveLayoutsPreview() {
    AquaHubTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(AquaHubSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.medium)
        ) {
            Text("Adaptive content frame", style = MaterialTheme.typography.titleMedium)
            AdaptiveFormFrame(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(AquaHubSpacing.small)) {
                    Text("Form content stays readable on wide windows.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.OutlinedTextField(
                        value = "AquaPure Main",
                        onValueChange = {},
                        label = { Text("Station name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Text("Responsive card grid", style = MaterialTheme.typography.titleMedium)
            AdaptiveCardGrid(
                cards = (1..4).map { number ->
                    { cardModifier ->
                        Surface(
                            modifier = cardModifier.height(76.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("Metric $number", color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            )
            Text("List and detail panes", style = MaterialTheme.typography.titleMedium)
            AdaptiveTwoPaneLayout(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                primary = { paneModifier ->
                    Surface(
                        modifier = paneModifier.fillMaxSize(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("List", style = MaterialTheme.typography.titleMedium) }
                    }
                },
                secondary = { paneModifier ->
                    Surface(
                        modifier = paneModifier.fillMaxSize(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text("Details", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            )
        }
    }
}
