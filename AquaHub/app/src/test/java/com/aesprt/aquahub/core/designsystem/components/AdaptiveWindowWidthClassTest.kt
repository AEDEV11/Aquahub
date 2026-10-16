package com.aesprt.aquahub.core.designsystem.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveWindowWidthClassTest {
    @Test
    fun usesTheConfiguredWindowWidthBreakpoints() {
        assertEquals(AquaHubWindowWidthClass.COMPACT, aquaHubWindowWidthClass(599.dp))
        assertEquals(AquaHubWindowWidthClass.MEDIUM, aquaHubWindowWidthClass(600.dp))
        assertEquals(AquaHubWindowWidthClass.MEDIUM, aquaHubWindowWidthClass(839.dp))
        assertEquals(AquaHubWindowWidthClass.EXPANDED, aquaHubWindowWidthClass(840.dp))
        assertEquals(AquaHubWindowWidthClass.EXPANDED, aquaHubWindowWidthClass(1199.dp))
        assertEquals(AquaHubWindowWidthClass.LARGE, aquaHubWindowWidthClass(1200.dp))
        assertEquals(AquaHubWindowWidthClass.LARGE, aquaHubWindowWidthClass(1599.dp))
        assertEquals(AquaHubWindowWidthClass.EXTRA_LARGE, aquaHubWindowWidthClass(1600.dp))
    }
}
