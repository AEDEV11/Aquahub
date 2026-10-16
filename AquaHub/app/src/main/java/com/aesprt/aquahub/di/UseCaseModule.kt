package com.aesprt.aquahub.di

import com.aesprt.aquahub.domain.usecase.auth.*
import com.aesprt.aquahub.domain.usecase.customer.*
import com.aesprt.aquahub.domain.usecase.inventory.*
import com.aesprt.aquahub.domain.usecase.order.*
import com.aesprt.aquahub.domain.usecase.product.*
import com.aesprt.aquahub.domain.usecase.report.*
import com.aesprt.aquahub.domain.usecase.rider.*
import com.aesprt.aquahub.domain.usecase.station.*
import com.aesprt.aquahub.domain.usecase.subscription.*
import org.koin.dsl.module

val useCaseModule = module {
    // Auth
    factory { LoginUseCase(get()) }
    factory { LoginWithGoogleUseCase(get()) }
    factory { ResetPasswordUseCase(get()) }
    factory { LogoutUseCase(get()) }
    factory { DeleteAccountUseCase(get()) }
    factory { ReauthenticateUseCase(get()) }
    factory { ReauthenticateWithGoogleUseCase(get()) }
    factory { GetCurrentSessionUseCase(get()) }
    factory { ObserveCurrentOwnerUseCase(get()) }
    factory { GetCurrentOwnerUseCase(get()) }
    factory { HasCompletedOnboardingUseCase(get()) }
    factory { SetCompletedOnboardingUseCase(get()) }
    factory { RegisterAndSetupStationUseCase(get(), get(), get(), get()) }

    // Station
    factory { ObserveStationUseCase(get()) }
    factory { GetStationUseCase(get()) }
    factory { UpdateStationUseCase(get()) }
    factory { ObserveActiveStationIdUseCase(get()) }
    factory { SetActiveStationIdUseCase(get()) }
    factory { ObserveAllStationsUseCase(get()) }
    factory { GetAllStationsUseCase(get()) }
    factory { CreateNewStationUseCase(get(), get(), get(), get()) }

    // Orders
    factory { ObserveOrdersUseCase(get()) }
    factory { ObserveFilteredOrdersUseCase(get()) }
    factory { ObservePagedOrdersUseCase(get()) }
    factory { GetOrderDetailUseCase(get()) }
    factory { ObserveRecentOrdersUseCase(get()) }
    factory { ObserveUncompletedOrdersCountUseCase(get()) }
    factory { AcceptOrderUseCase(get(), get()) }
    factory { RejectOrderUseCase(get(), get()) }
    factory { StartPreparingOrderUseCase(get(), get()) }
    factory { MarkOrderReadyUseCase(get(), get()) }
    factory { MarkOutForDeliveryUseCase(get(), get()) }
    factory { MarkDeliveredUseCase(get(), get()) }
    factory { CompleteOrderUseCase(get(), get()) }
    factory { CancelOrderUseCase(get(), get()) }
    factory { AssignRiderToOrderUseCase(get(), get(), get(), get()) }
    factory {
        CreateWalkInOrderUseCase(
            orderRepository = get(), customerRepository = get(), inventoryRepository = get(),
            transactionRunner = get(), productRepository = get(), canCreateOrderUseCase = get(),
            checkSubscriptionAccessUseCase = get(), paymentRepository = get(),
            supabaseOrderDataSource = get(), paymentCache = get(), supabasePaymentDataSource = get(),
            offlinePosRepository = get(), connectivityMonitor = get()
        )
    }

    // Customers
    factory { ObserveCustomersUseCase(get()) }
    factory { SearchCustomersUseCase(get()) }
    factory { GetCustomerDetailUseCase(get()) }
    factory { CreateCustomerUseCase(get(), get(), get()) }
    factory { UpdateCustomerUseCase(get(), get()) }
    factory { GetCustomerContainerBalanceUseCase(get()) }
    factory { ObserveCustomerAccountUseCase(get(), get()) }
    factory { RefreshCustomerOrderHistoryUseCase(get()) }
    factory { RecordCustomerPaymentUseCase(get(), get(), get(), get()) }
    factory { ExportCustomerStatementUseCase(get(), get()) }

    // Products
    factory { ObserveProductsUseCase(get()) }
    factory { CreateProductUseCase(get(), get(), get(), get()) }
    factory { UpdateProductUseCase(get(), get()) }
    factory { ToggleProductAvailabilityUseCase(get(), get()) }
    factory { UpdateProductStockUseCase(get(), get()) }
    factory { ArchiveProductUseCase(get(), get()) }

    // Riders
    factory { ObserveRidersUseCase(get()) }
    factory { ObserveAvailableRidersUseCase(get()) }
    factory { CreateRiderUseCase(get(), get(), get()) }
    factory { UpdateRiderUseCase(get(), get()) }
    factory { RemoveRiderUseCase(get(), get()) }
    factory { RecommendRidersUseCase(get()) }

    // Inventory
    factory { ObserveContainerInventoryUseCase(get()) }
    factory { ObserveContainerTransactionsUseCase(get()) }
    factory { ObservePagedContainerTransactionsUseCase(get()) }
    factory { EnsureContainerSizeUseCase(get(), get(), get()) }
    factory { ObserveCustomerContainerTransactionsUseCase(get()) }
    factory { RecordContainerDeliveryUseCase(get(), get(), get()) }
    factory { RecordContainerReturnUseCase(get(), get(), get()) }
    factory { AdjustContainerInventoryUseCase(get(), get(), get()) }

    // Daily operating expenses
    factory { com.aesprt.aquahub.domain.usecase.expense.ObserveExpensesUseCase(get()) }
    factory { com.aesprt.aquahub.domain.usecase.expense.ObserveExpenseTotalUseCase(get()) }
    factory { com.aesprt.aquahub.domain.usecase.expense.RefreshExpensesUseCase(get()) }
    factory { com.aesprt.aquahub.domain.usecase.expense.SaveExpenseUseCase(get(), get()) }
    factory { com.aesprt.aquahub.domain.usecase.expense.ArchiveExpenseUseCase(get(), get()) }

    // Reports & Analytics & Exports
    factory { ObserveDashboardSummaryUseCase(get()) }
    factory { GetTodaySalesUseCase(get()) }
    factory { GetWeeklySalesUseCase(get()) }
    factory { GetMonthlySalesUseCase(get()) }
    factory { GetTopProductsUseCase(get()) }
    factory { GetDailySalesSeriesUseCase(get()) }
    factory { com.aesprt.aquahub.domain.usecase.analytics.GetFullBusinessAnalyticsUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { com.aesprt.aquahub.domain.usecase.export.ExportReportUseCase(get(), get()) }

    // Subscriptions
    factory { ObserveCurrentSubscriptionUseCase(get()) }
    factory { GetCurrentSubscriptionUseCase(get()) }
    factory { GetCurrentBusinessSubscriptionUseCase(get()) }
    factory { GetSubscriptionStatusUseCase(get()) }
    factory { GetSubscriptionRemainingDaysUseCase(get()) }
    factory { GetAvailablePlansUseCase(get()) }
    factory { GetPlanEntitlementsUseCase(get()) }
    factory { CheckSubscriptionAccessUseCase(get(), get(), get(), get()) }
    factory { CheckSubscriptionLimitUseCase(get(), get(), get()) }
    factory { RenewSubscriptionUseCase(get()) }
    factory { CancelSubscriptionUseCase(get()) }
    factory { ResumeSubscriptionUseCase(get()) }
    factory { UpgradeSubscriptionUseCase(get()) }
    factory { ScheduleDowngradeUseCase(get()) }
    factory { ProcessSubscriptionExpirationUseCase(get()) }
    factory { GetSubscriptionHistoryUseCase(get()) }
    factory { RefreshSubscriptionStateUseCase(get()) }
    factory { CanCreateRiderUseCase(get(), get(), get()) }
    factory { CanCreateProductUseCase(get(), get(), get()) }
    factory { CanCreateCustomerUseCase(get(), get(), get()) }
    factory { CanCreateOrderUseCase(get(), get(), get()) }
    factory { CanCreateBranchUseCase(get(), get()) }

    // Location Use Cases
    factory { com.aesprt.aquahub.domain.usecase.location.SearchLocationsUseCase(get()) }
    factory { com.aesprt.aquahub.domain.usecase.location.GetPlaceDetailsUseCase(get()) }
    factory { com.aesprt.aquahub.domain.usecase.location.ReverseGeocodeUseCase(get()) }
    factory { com.aesprt.aquahub.domain.usecase.location.GetCurrentDeviceLocationUseCase(get()) }
    factory { com.aesprt.aquahub.domain.usecase.location.UpdateStationLocationUseCase(get()) }
}
