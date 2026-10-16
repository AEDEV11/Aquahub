package com.aesprt.aquahub.di

import com.aesprt.aquahub.feature.auth.login.LoginViewModel
import com.aesprt.aquahub.feature.auth.onboarding.OnboardingViewModel
import com.aesprt.aquahub.feature.auth.splash.SplashViewModel
import com.aesprt.aquahub.feature.main.MainContainerViewModel
import com.aesprt.aquahub.feature.customers.detail.CustomerDetailViewModel
import com.aesprt.aquahub.feature.customers.list.CustomersViewModel
import com.aesprt.aquahub.feature.dashboard.DashboardViewModel
import com.aesprt.aquahub.feature.inventory.InventoryViewModel
import com.aesprt.aquahub.feature.orders.detail.OrderDetailViewModel
import com.aesprt.aquahub.feature.orders.list.OrdersViewModel
import com.aesprt.aquahub.feature.orders.walkin.WalkInSaleViewModel
import com.aesprt.aquahub.feature.products.ProductsViewModel
import com.aesprt.aquahub.feature.reports.ReportsViewModel
import com.aesprt.aquahub.feature.riders.RidersViewModel
import com.aesprt.aquahub.feature.settings.SettingsViewModel
import com.aesprt.aquahub.feature.settings.profile.StationProfileViewModel
import com.aesprt.aquahub.feature.settings.delivery.DeliverySettingsViewModel
import com.aesprt.aquahub.feature.settings.subscription.SubscriptionBillingViewModel
import com.aesprt.aquahub.feature.sync.SyncStatusViewModel
import com.aesprt.aquahub.feature.staff.StaffManagementViewModel
import com.aesprt.aquahub.feature.expenses.ExpensesViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { ExpensesViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { MainContainerViewModel(get(), get(), get(), get()) }
    viewModel { StaffManagementViewModel(get(), get(), get(), get(), get()) }
    viewModel { SyncStatusViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel {
        SplashViewModel(
            getCurrentSessionUseCase = get(),
            hasCompletedOnboardingUseCase = get()
        )
    }
    viewModel {
        LoginViewModel(
            loginUseCase = get(),
            loginWithGoogleUseCase = get(),
            resetPasswordUseCase = get(),
            authDataSource = get(),
            authRepository = get()
        )
    }
    viewModel {
        OnboardingViewModel(
            registerAndSetupStationUseCase = get(),
            setCompletedOnboardingUseCase = get(),
            supabaseAuthDataSource = get(),
            googlePlayBillingRepository = get()
        )
    }
    viewModel {
        DashboardViewModel(
            observeCurrentOwnerUseCase = get(),
            riderRepository = get(),
            activeStationContext = get(),
            observeAllStationsUseCase = get(),
            observeDashboardSummaryUseCase = get(),
            observeStationUseCase = get(),
            getDailySalesSeriesUseCase = get(),
            observeCurrentSubscriptionUseCase = get(),
            getSubscriptionStatusUseCase = get(),
            getSubscriptionRemainingDaysUseCase = get(),
            refreshSubscriptionStateUseCase = get(),
            syncScheduler = get(),
            connectivityMonitor = get(),
            syncCoordinator = get(),
            remoteOrderRefreshCoordinator = get()
        )
    }
    viewModel {
        OrdersViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            observePagedOrdersUseCase = get(),
            riderRepository = get(),
            syncScheduler = get(),
            syncCoordinator = get(),
            remoteOrderRefreshCoordinator = get()
        )
    }
    viewModel {
        WalkInSaleViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            observeProductsUseCase = get(),
            observeCustomersUseCase = get(),
            observeStationUseCase = get(),
            observeContainerInventoryUseCase = get(),
            observeContainerTransactionsUseCase = get(),
            checkSubscriptionAccessUseCase = get(),
            createWalkInOrderUseCase = get(),
            offlinePosRepository = get(),
            connectivityMonitor = get()
        )
    }
    viewModel {
        OrderDetailViewModel(
            getOrderDetailUseCase = get(),
            acceptOrderUseCase = get(),
            rejectOrderUseCase = get(),
            startPreparingOrderUseCase = get(),
            markOrderReadyUseCase = get(),
            assignRiderToOrderUseCase = get(),
            markOutForDeliveryUseCase = get(),
            markDeliveredUseCase = get(),
            completeOrderUseCase = get(),
            cancelOrderUseCase = get(),
            recommendRidersUseCase = get(),
            recordContainerDeliveryUseCase = get(),
            observeContainerInventoryUseCase = get(),
            observeCustomerContainerTransactionsUseCase = get(),
            observeStationUseCase = get(),
            observeCurrentOwnerUseCase = get(),
            riderRepository = get(),
            orderRepository = get(),
            remoteOrderRefreshCoordinator = get(),
            paymentRepository = get(),
            imageStorageRepository = get()
        )
    }
    viewModel {
        CustomersViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            observeCustomersUseCase = get(),
            searchCustomersUseCase = get(),
            createCustomerUseCase = get()
        )
    }
    viewModel {
        CustomerDetailViewModel(
            getCustomerDetailUseCase = get(),
            getCustomerContainerBalanceUseCase = get(),
            recordContainerReturnUseCase = get(),
            observeContainerTransactionsUseCase = get(),
            observeCustomerAccountUseCase = get(),
            refreshCustomerOrderHistoryUseCase = get(),
            recordCustomerPaymentUseCase = get(),
            exportCustomerStatementUseCase = get(),
            checkSubscriptionAccessUseCase = get(),
            getStationUseCase = get()
        )
    }
    viewModel {
        ProductsViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            observeProductsUseCase = get(),
            createProductUseCase = get(),
            updateProductUseCase = get(),
            toggleProductAvailabilityUseCase = get(),
            updateProductStockUseCase = get(),
            archiveProductUseCase = get(),
            canCreateProductUseCase = get(),
            checkSubscriptionAccessUseCase = get(),
            observeCurrentSubscriptionUseCase = get(),
            observeContainerInventoryUseCase = get(),
            ensureContainerSizeUseCase = get(),
            imageStorageRepository = get()
        )
    }
    viewModel {
        InventoryViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            observeContainerInventoryUseCase = get(),
            observePagedContainerTransactionsUseCase = get(),
            adjustContainerInventoryUseCase = get(),
            observeProductsUseCase = get(),
            checkSubscriptionAccessUseCase = get(),
            observeCurrentSubscriptionUseCase = get()
        )
    }
    viewModel {
        RidersViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            observeRidersUseCase = get(),
            createRiderUseCase = get(),
            updateRiderUseCase = get(),
            canCreateRiderUseCase = get(),
            staffRepository = get()
        )
    }
    viewModel {
        ReportsViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            stationRepository = get(),
            paymentRepository = get(),
            subscriptionRepository = get(),
            getFullBusinessAnalyticsUseCase = get(),
            checkSubscriptionAccessUseCase = get(),
            exportReportUseCase = get()
        )
    }
    viewModel {
        com.aesprt.aquahub.feature.stations.ManageStationsViewModel(
            stationRepository = get(),
            activeStationContext = get(),
            createNewStationUseCase = get(),
            getCurrentBusinessSubscriptionUseCase = get(),
            getSubscriptionStatusUseCase = get(),
            subscriptionPlanRepository = get(),
            orderRepository = get(),
            riderRepository = get()
        )
    }
    viewModel {
        SettingsViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            observeStationUseCase = get(),
            logoutUseCase = get(),
            deleteAccountUseCase = get(),
            reauthenticateUseCase = get(),
            reauthenticateWithGoogleUseCase = get(),
            authDataSource = get(),
            marketingPreferencesDataSource = get()
        )
    }
    viewModel {
        StationProfileViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            observeStationUseCase = get(),
            updateStationUseCase = get(),
            imageStorageRepository = get()
        )
    }
    viewModel {
        DeliverySettingsViewModel(
            observeCurrentOwnerUseCase = get(),
            activeStationContext = get(),
            observeStationUseCase = get(),
            updateStationUseCase = get()
        )
    }
    viewModel {
        SubscriptionBillingViewModel(
            observeCurrentOwnerUseCase = get(),
            observeCurrentSubscriptionUseCase = get(),
            getAvailablePlansUseCase = get(),
            getSubscriptionStatusUseCase = get(),
            getSubscriptionRemainingDaysUseCase = get(),
            getSubscriptionHistoryUseCase = get(),
            refreshSubscriptionStateUseCase = get(),
            googlePlayBillingRepository = get(),
        )
    }
    viewModel {
        com.aesprt.aquahub.feature.location.LocationPickerViewModel(
            searchLocationsUseCase = get(),
            getPlaceDetailsUseCase = get(),
            reverseGeocodeUseCase = get(),
            getCurrentDeviceLocationUseCase = get()
        )
    }
}
