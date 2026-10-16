package com.aesprt.aquahub.core.navigation

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object IntroOnboarding : Screen("intro_onboarding")
    data object Login : Screen("login")
    data object Subscription : Screen("subscription")
data object Register : Screen("register?planId={planId}&billing={billing}") {
        fun createRoute(planId: String = "business", billing: String = "monthly") = "register?planId=$planId&billing=$billing"
    }
    data object StationSetup : Screen("station_setup")
    data object MainContainer : Screen("main_container")
    
    // Bottom Nav Tabs / Top-level destinations
    data object Dashboard : Screen("dashboard")
    data object Orders : Screen("orders?tab={tab}") {
        fun createRoute(tab: String = "ALL") = "orders?tab=$tab"
    }
    data object Inventory : Screen("inventory")
    data object Customers : Screen("customers")
    data object More : Screen("more")
    
    // Sub-screens
    data object OrderDetail : Screen("order_detail/{orderId}") {
        fun createRoute(orderId: Long) = "order_detail/$orderId"
    }
    data object CustomerDetail : Screen("customer_detail/{customerId}") {
        fun createRoute(customerId: Long) = "customer_detail/$customerId"
    }
    data object Products : Screen("products")
    data object Riders : Screen("riders")
    data object Reports : Screen("reports")
    data object Expenses : Screen("expenses")
    data object Settings : Screen("settings")
    data object StationProfile : Screen("station_profile")
    data object CustomerOrdering : Screen("customer_ordering")
    data object PaymentMethods : Screen("payment_methods")
    data object DeliverySettings : Screen("delivery_settings")
    data object ManageStations : Screen("manage_stations")
    data object StaffManagement : Screen("staff_management")
    data object SubscriptionBilling : Screen("subscription_billing")
    data object PlanComparison : Screen("plan_comparison")
}
