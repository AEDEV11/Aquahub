package com.aesprt.aquahub.feature.main

import com.aesprt.aquahub.core.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomNavItemSelectionTest {
    @Test
    fun ownerAndFieldWorkerKeepTheirExistingDestinationSets() {
        assertTrue(mainNavigationItems(isFieldWorker = false).contains(BottomNavItem.Inventory))
        assertTrue(mainNavigationItems(isFieldWorker = false).contains(BottomNavItem.Customers))
        assertFalse(mainNavigationItems(isFieldWorker = true).contains(BottomNavItem.Inventory))
        assertFalse(mainNavigationItems(isFieldWorker = true).contains(BottomNavItem.Customers))
        assertEquals(5, mainNavigationItems(isFieldWorker = false).size)
        assertEquals(2, mainNavigationItems(isFieldWorker = true).size)
    }

    @Test
    fun ordersTabRouteKeepsOrdersSelected() {
        assertTrue(BottomNavItem.Orders.isSelectedForRoute(Screen.Orders.createRoute("READY")))
    }

    @Test
    fun eachTopLevelRouteSelectsOnlyItsDestination() {
        assertTrue(BottomNavItem.Dashboard.isSelectedForRoute(Screen.Dashboard.route))
        assertTrue(BottomNavItem.Inventory.isSelectedForRoute(Screen.Inventory.route))
        assertTrue(BottomNavItem.Customers.isSelectedForRoute(Screen.Customers.route))
        assertTrue(BottomNavItem.More.isSelectedForRoute(Screen.Settings.route))

        assertFalse(BottomNavItem.Dashboard.isSelectedForRoute(Screen.Inventory.route))
        assertFalse(BottomNavItem.Orders.isSelectedForRoute(Screen.Customers.route))
        assertFalse(BottomNavItem.More.isSelectedForRoute(null))
    }
}
