package com.aesprt.aquahub.core.navigation

import com.aesprt.aquahub.core.designsystem.components.SubscriptionGate
import com.aesprt.aquahub.domain.model.SubscriptionFeature

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import com.aesprt.aquahub.feature.auth.login.LoginScreen
import com.aesprt.aquahub.feature.auth.onboarding.IntroOnboardingScreen
import com.aesprt.aquahub.feature.auth.onboarding.OnboardingViewModel
import com.aesprt.aquahub.feature.auth.register.RegisterScreen
import com.aesprt.aquahub.feature.auth.setup.StationSetupScreen
import com.aesprt.aquahub.feature.auth.splash.SplashScreen
import com.aesprt.aquahub.feature.auth.subscription.SubscriptionScreen
import com.aesprt.aquahub.feature.customers.detail.CustomerDetailScreen
import com.aesprt.aquahub.feature.main.MainContainerScreen
import com.aesprt.aquahub.feature.orders.detail.OrderDetailScreen
import com.aesprt.aquahub.feature.products.ProductsScreen
import com.aesprt.aquahub.feature.reports.ReportsScreen
import com.aesprt.aquahub.feature.expenses.ExpensesScreen
import com.aesprt.aquahub.feature.riders.RidersScreen
import com.aesprt.aquahub.feature.settings.profile.StationProfileScreen
import com.aesprt.aquahub.feature.settings.delivery.DeliverySettingsScreen
import com.aesprt.aquahub.feature.settings.CustomerOrderingScreen
import com.aesprt.aquahub.feature.settings.PaymentMethodsScreen
import com.aesprt.aquahub.feature.staff.StaffManagementScreen
import com.aesprt.aquahub.feature.settings.subscription.PlanComparisonScreen
import com.aesprt.aquahub.feature.settings.subscription.SubscriptionBillingScreen
import org.koin.androidx.compose.koinViewModel

@Composable
fun AppNavGraph(
    navController: NavHostController
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Splash.route
    ) {
        composable(Screen.Splash.route) {
            SplashScreen(
                viewModel = koinViewModel(),
                onNavigateToOnboarding = {
                    navController.navigate(Screen.IntroOnboarding.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = {
                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                },
                onNavigateToDashboard = {
                    navController.navigate(Screen.MainContainer.route) {
                        popUpTo(Screen.Splash.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.IntroOnboarding.route) {
            val onboardingViewModel: OnboardingViewModel = koinViewModel()
            IntroOnboardingScreen(
                onFinishOnboarding = {
                    onboardingViewModel.completeIntroOnboarding {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.IntroOnboarding.route) { inclusive = true }
                        }
                    }
                },
                onNavigateToSubscription = {
                    onboardingViewModel.completeIntroOnboarding {
                        navController.navigate(Screen.Register.route)
                    }
                },
            )
        }

        composable(Screen.Login.route) {
            LoginScreen(
                viewModel = koinViewModel(),
                onLoginSuccess = {
                    navController.navigate(Screen.MainContainer.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                },
                onNavigateToSubscription = {
                    navController.navigate(Screen.Register.route)
                }
            )
        }

        navigation(
            route = "registration_flow",
            startDestination = Screen.Register.route
        ) {
            composable(Screen.Subscription.route) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("registration_flow")
                }
                val onboardingViewModel: OnboardingViewModel = koinViewModel(viewModelStoreOwner = parentEntry)
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    onboardingViewModel.prepareGoogleOnboardingIfNeeded()
                }
                SubscriptionScreen(
                    viewModel = onboardingViewModel,
                    onBackClick = { navController.popBackStack() },
                    onPlanSelected = { plan, activity ->
                        onboardingViewModel.setPlan(plan)
                        onboardingViewModel.completeSetup(activity = activity) { _ ->
                            navController.navigate(Screen.MainContainer.route) {
                                popUpTo("registration_flow") { inclusive = true }
                            }
                        }
                    }
                )
            }

            composable(
                route = Screen.Register.route,
                arguments = listOf(
                    navArgument("planId") {
                        type = NavType.StringType
                        defaultValue = "business"
                    },
                    navArgument("billing") {
                        type = NavType.StringType
                        defaultValue = "monthly"
                    }
                )
            ) { backStackEntry ->
                val planId = backStackEntry.arguments?.getString("planId") ?: "business"
                val billing = backStackEntry.arguments?.getString("billing") ?: "monthly"
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("registration_flow")
                }
                val onboardingViewModel: OnboardingViewModel = koinViewModel(viewModelStoreOwner = parentEntry)
                RegisterScreen(
                    viewModel = onboardingViewModel,
                    planId = planId,
                    billing = billing,
                    onBackClick = { navController.popBackStack() },
                    onContinueToSetup = {
                        navController.navigate(Screen.StationSetup.route)
                    }
                )
            }

            composable(Screen.StationSetup.route) { backStackEntry ->
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry("registration_flow")
                }
                val onboardingViewModel: OnboardingViewModel = koinViewModel(viewModelStoreOwner = parentEntry)
                StationSetupScreen(
                    viewModel = onboardingViewModel,
                    onBackClick = { navController.popBackStack() },
                    onContinueToPlan = {
                        navController.navigate(Screen.Subscription.route)
                    }
                )
            }
        }

        composable(Screen.MainContainer.route) {
            MainContainerScreen(rootNavController = navController)
        }

        composable(
            route = Screen.OrderDetail.route,
            arguments = listOf(navArgument("orderId") { type = NavType.LongType })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getLong("orderId") ?: 0L
            SubscriptionGate(SubscriptionFeature.ORDER_MANAGEMENT, { navController.navigate(Screen.SubscriptionBilling.route) }) {
OrderDetailScreen(
                orderId = orderId,
                viewModel = koinViewModel(),
                onBackClick = { navController.popBackStack() }
            )
            }
        }

        composable(
            route = Screen.CustomerDetail.route,
            arguments = listOf(navArgument("customerId") { type = NavType.LongType })
        ) { backStackEntry ->
            val customerId = backStackEntry.arguments?.getLong("customerId") ?: 0L
            SubscriptionGate(SubscriptionFeature.CUSTOMER_MANAGEMENT, { navController.navigate(Screen.SubscriptionBilling.route) }) {
CustomerDetailScreen(
                customerId = customerId,
                viewModel = koinViewModel(),
                onBackClick = { navController.popBackStack() },
                onNavigateToOrderDetail = { orderId ->
                    navController.navigate(Screen.OrderDetail.createRoute(orderId))
                }
            )
            }
        }

        composable(Screen.Products.route) {
            SubscriptionGate(SubscriptionFeature.PRODUCT_MANAGEMENT, { navController.navigate(Screen.SubscriptionBilling.route) }) {
ProductsScreen(
                viewModel = koinViewModel(),
                onBackClick = { navController.popBackStack() }
            )
            }
        }

        composable(Screen.Riders.route) {
            SubscriptionGate(SubscriptionFeature.RIDER_MANAGEMENT, { navController.navigate(Screen.SubscriptionBilling.route) }) {
RidersScreen(
                viewModel = koinViewModel(),
                onBackClick = { navController.popBackStack() },
                onNavigateToSubscription = { navController.navigate(Screen.SubscriptionBilling.route) }
            )
            }
        }

        composable(Screen.Reports.route) {
            SubscriptionGate(SubscriptionFeature.BASIC_REPORTS, { navController.navigate(Screen.SubscriptionBilling.route) }) {
ReportsScreen(
                viewModel = koinViewModel(),
                onBackClick = { navController.popBackStack() }
            )
            }
        }

        composable(Screen.Expenses.route) {
            SubscriptionGate(SubscriptionFeature.EXPENSE_TRACKING, { navController.navigate(Screen.SubscriptionBilling.route) }) {
                ExpensesScreen(
                    viewModel = koinViewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.StationProfile.route) {
            SubscriptionGate(SubscriptionFeature.STATION_MANAGEMENT, { navController.navigate(Screen.SubscriptionBilling.route) }) {
                StationProfileScreen(
                    viewModel = koinViewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.CustomerOrdering.route) {
            SubscriptionGate(SubscriptionFeature.MOBILE_APP_ORDERING, { navController.navigate(Screen.SubscriptionBilling.route) }) {
                CustomerOrderingScreen(
                    viewModel = koinViewModel(),
                    onBackClick = { navController.popBackStack() },
                )
            }
        }

        composable(Screen.PaymentMethods.route) {
            SubscriptionGate(SubscriptionFeature.ORDER_MANAGEMENT, { navController.navigate(Screen.SubscriptionBilling.route) }) {
                PaymentMethodsScreen(
                    viewModel = koinViewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.DeliverySettings.route) {
            SubscriptionGate(SubscriptionFeature.DELIVERY_MANAGEMENT, { navController.navigate(Screen.SubscriptionBilling.route) }) {
                DeliverySettingsScreen(
                    viewModel = koinViewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.ManageStations.route) {
            SubscriptionGate(SubscriptionFeature.STATION_MANAGEMENT, { navController.navigate(Screen.SubscriptionBilling.route) }) {
                com.aesprt.aquahub.feature.stations.ManageStationsScreen(
                    viewModel = koinViewModel(),
                    onBackClick = { navController.popBackStack() },
                    onNavigateToSubscription = {
                        navController.navigate(Screen.SubscriptionBilling.route)
                    }
                )
            }
        }

        composable(Screen.StaffManagement.route) {
            SubscriptionGate(SubscriptionFeature.STAFF_ACCOUNTS, { navController.navigate(Screen.SubscriptionBilling.route) }) {
                StaffManagementScreen(
                    viewModel = koinViewModel(),
                    onBackClick = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.SubscriptionBilling.route) {
            SubscriptionBillingScreen(
                viewModel = koinViewModel(),
                onBackClick = { navController.popBackStack() },
                onNavigateToPlanComparison = {
                    navController.navigate(Screen.PlanComparison.route)
                }
            )
        }

        composable(Screen.PlanComparison.route) {
            PlanComparisonScreen(
                viewModel = koinViewModel(),
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
