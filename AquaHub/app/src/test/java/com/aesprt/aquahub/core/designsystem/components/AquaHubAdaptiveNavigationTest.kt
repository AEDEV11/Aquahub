package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.window.core.layout.WindowSizeClass
import org.junit.Assert.assertEquals
import org.junit.Test

class AquaHubAdaptiveNavigationTest {
    @Test
    fun compactWidthUsesBottomNavigation() {
        assertEquals(
            NavigationSuiteType.NavigationBar,
            aquaHubNavigationSuiteType(adaptiveInfo(widthClass = 0, heightClass = 480)),
        )
    }

    @Test
    fun mediumThroughExtraLargeWidthsUseNavigationRailWhenHeightAllows() {
        listOf(600, 840, 1200, 1600).forEach { widthClass ->
            assertEquals(
                "Expected a rail for width class $widthClass dp",
                NavigationSuiteType.NavigationRail,
                aquaHubNavigationSuiteType(adaptiveInfo(widthClass = widthClass, heightClass = 480)),
            )
        }
    }

    @Test
    fun compactHeightUsesBottomNavigationForLandscapeWindows() {
        assertEquals(
            NavigationSuiteType.NavigationBar,
            aquaHubNavigationSuiteType(adaptiveInfo(widthClass = 840, heightClass = 0)),
        )
    }

    @Test
    fun tabletopPostureUsesBottomNavigation() {
        assertEquals(
            NavigationSuiteType.NavigationBar,
            aquaHubNavigationSuiteType(
                adaptiveInfo(widthClass = 840, heightClass = 480, isTabletop = true),
            ),
        )
    }

    private fun adaptiveInfo(
        widthClass: Int,
        heightClass: Int,
        isTabletop: Boolean = false,
    ) = WindowAdaptiveInfo(
        windowSizeClass = WindowSizeClass(widthClass, heightClass),
        windowPosture = Posture(isTabletop = isTabletop),
    )
}
