package com.aesprt.aquahub.di

import com.aesprt.aquahub.data.export.*
import com.aesprt.aquahub.data.repository.*
import com.aesprt.aquahub.data.offline.SupabaseOfflinePosDataSource
import com.aesprt.aquahub.data.offline.OfflinePosCryptography
import com.aesprt.aquahub.data.supabase.inventory.SupabaseInventoryDataSource
import com.aesprt.aquahub.data.supabase.inventory.SupabaseContainerDataSource
import com.aesprt.aquahub.data.supabase.order.SupabaseOrderDataSource
import com.aesprt.aquahub.data.supabase.storage.SupabaseStorageDataSource
import com.aesprt.aquahub.data.supabase.storage.StorageAssetResolver
import com.aesprt.aquahub.data.supabase.staff.SupabaseStaffDataSource
import com.aesprt.aquahub.data.supabase.settings.SupabaseSettingsDataSource
import com.aesprt.aquahub.data.supabase.subscription.SupabaseSubscriptionDataSource
import com.aesprt.aquahub.data.supabase.marketing.SupabaseMarketingPreferencesDataSource
import com.aesprt.aquahub.data.supabase.delivery.SupabaseRiderDataSource
import com.aesprt.aquahub.data.compatibility.LegacyCompatibilityResolver
import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.context.DataStoreActiveStationContext
import com.aesprt.aquahub.domain.export.ReportExporter
import com.aesprt.aquahub.domain.export.ReportFormat
import com.aesprt.aquahub.domain.repository.*
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val repositoryModule = module {
    single { LegacyCompatibilityResolver() }
    single<AuthRepository> {
        SupabaseAuthRepository(
            ownerDao = get(),
            dataStore = get(),
            authDataSource = get(),
            accountDataSource = get(),
            authTelemetry = get(),
            scheduler = get(),
            database = get(),
            databaseSeeder = get(),
            appContext = androidContext(),
            workspaceDataSource = get(),
            localAccountScope = get(),
            marketingPreferencesDataSource = get()
        )
    }

    single { SupabaseStaffDataSource() }
    single { SupabaseSettingsDataSource() }
    single { SupabaseSubscriptionDataSource() }
    single { SupabaseMarketingPreferencesDataSource() }
    single<StaffRepository> { SupabaseStaffRepository(get()) }

    single<StationRepository> {
        RoomStationRepository(stationDao = get(), database = get(), syncQueue = get(), syncDao = get(), supabaseSettings = get())
    }

    single<OrderRepository> {
        RoomOrderRepository(orderDao = get(), riderDao = get(), database = get(), syncQueue = get(), syncDao = get(), supabaseOrder = get(), supabaseDelivery = get(), supabaseRider = get(), compatibilityResolver = get())
    }

    single<PaymentRepository> {
        RoomPaymentRepository(
            paymentDao = get(),
            paymentRefundDao = get(),
            orderDao = get(),
            customerDao = get(),
            syncDao = get(),
            database = get(),
            syncQueue = get(),
            supabasePayment = get()
        )
    }
    single<PaymentCache> { get<PaymentRepository>() as PaymentCache }

    single<CustomerRepository> {
        RoomCustomerRepository(
            customerDao = get(),
            database = get(),
            syncQueue = get(),
            syncDao = get(),
            stationDao = get(),
            supabaseCatalog = get()
        )
    }

    single<ProductRepository> {
        RoomProductRepository(
            productDao = get(),
            database = get(),
            syncQueue = get(),
            syncDao = get(),
            stationDao = get(),
            supabaseCatalog = get(),
            supabaseInventory = get()
        )
    }

    single<RiderRepository> {
        RoomRiderRepository(riderDao = get(), database = get(), syncQueue = get(), syncDao = get(), supabaseRider = get())
    }

    single<InventoryRepository> {
        RoomInventoryRepository(
            containerDao = get(),
            transactionDao = get(),
            database = get(),
            syncQueue = get()
        )
    }

    single { SupabaseInventoryDataSource() }
    single { SupabaseOfflinePosDataSource() }
    single { OfflinePosCryptography() }
    single { SupabaseContainerDataSource() }
    single { SupabaseOrderDataSource(syncDao = get(), catalog = get()) }
    single { com.aesprt.aquahub.data.supabase.payment.SupabasePaymentDataSource() }
    single { com.aesprt.aquahub.data.supabase.delivery.SupabaseDeliveryDataSource() }
    single { SupabaseRiderDataSource() }
    single { com.aesprt.aquahub.data.supabase.expense.SupabaseExpenseDataSource() }
    single { SupabaseStorageDataSource(androidContext()) }
    single { StorageAssetResolver(get()) }

    single<ExpenseRepository> {
        RoomExpenseRepository(expenseDao = get(), database = get(), syncQueue = get(), syncDao = get(), supabaseExpenseDataSource = get())
    }

    single<ReportRepository> {
        RoomReportRepository(
            orderDao = get(),
            orderItemDao = get(),
            paymentDao = get(),
            offlinePosDao = get(),
            pendingOperationsProfileUid = { get<com.aesprt.aquahub.sync.LocalAccountScopeManager>().ownerUid() },
        )
    }

    single<com.aesprt.aquahub.domain.repository.OfflinePosRepository> {
        RoomOfflinePosRepository(
            context = androidContext(),
            database = get(),
            offlinePosDao = get(),
            syncDao = get(),
            productDao = get(),
            orderDao = get(),
            orderItemDao = get(),
            paymentDao = get(),
            remote = get(),
            scheduler = get(),
            cryptography = get()
        )
    }

    single<ActiveStationContext> {
        DataStoreActiveStationContext(dataStore = get())
    }

    single { CsvReportExporter() }
    single { ExcelReportExporter() }
    single { PdfReportExporter() }
    single<com.aesprt.aquahub.domain.export.CustomerStatementExporter> { PdfCustomerStatementExporter() }

    single<Map<ReportFormat, ReportExporter>> {
        mapOf(
            ReportFormat.CSV to get<CsvReportExporter>(),
            ReportFormat.EXCEL to get<ExcelReportExporter>(),
            ReportFormat.PDF to get<PdfReportExporter>()
        )
    }

    single<SubscriptionPlanRepository> {
        RoomSubscriptionPlanRepository(
            planDao = get()
        )
    }

    single<SubscriptionRepository> {
        RoomSubscriptionRepository(
            database = get(),
            subscriptionDao = get(),
            planDao = get(),
            historyDao = get(),
            stationDao = get(),
            syncDao = get(),
            timeProvider = get(),
            syncQueue = get(),
            subscriptionDataSource = get()
        )
    }

    single<com.aesprt.aquahub.domain.repository.GooglePlayBillingRepository> {
        GooglePlayBillingRepositoryImpl(
            context = androidContext(),
            subscriptionDataSource = get(),
            subscriptionRepository = get()
        )
    }

    single<LocationRepository> {
        GoogleLocationRepository(
            context = androidContext()
        )
    }
}
