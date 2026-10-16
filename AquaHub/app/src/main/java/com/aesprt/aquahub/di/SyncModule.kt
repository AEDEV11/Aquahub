package com.aesprt.aquahub.di

import com.aesprt.aquahub.sync.*
import com.aesprt.aquahub.domain.repository.OrderRepository
import com.aesprt.aquahub.domain.repository.RemoteOrderReader
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val syncModule = module {
    single<ConnectivityMonitor> { AndroidConnectivityMonitor(androidContext()) }
    single { SyncScheduler(androidContext()) }
    single { LocalAccountScopeManager(get()) }
    single<RemoteOrderReader> { get<OrderRepository>() }
    single { RemoteOrderRefreshCoordinator(remoteOrderReader = get(), connectivityMonitor = get()) }
    single { OfflinePosSyncCoordinator(get(), get(), get(), get(), get(), get(), get()) }
    single { SyncQueueWriter(get(), get()) }
    single {
        ExistingLocalDataMigrationPlanner(
            stationDao = get(), customerDao = get(), productDao = get(), riderDao = get(),
            orderDao = get(), containerDao = get(), transactionDao = get(), paymentDao = get(),
            queueWriter = get()
        )
    }
    single {
        SupabasePendingMutationWriter(
            customerDao = get(), expenseDao = get(), productDao = get(), orderDao = get(), stationDao = get(),
            riderDao = get(), paymentDao = get(),
            containerDao = get(), containerTransactionDao = get(), syncDao = get(),
            database = get(),
            supabaseCatalog = get(), supabaseInventory = get(), supabaseContainer = get(),
            supabaseSettings = get(), supabaseDelivery = get(), supabaseRider = get(),
            supabasePayment = get(), supabaseExpense = get(), supabaseOrder = get()
        )
    }
    single {
        SupabaseSyncCoordinator(
            syncDao = get(),
            pendingMutationWriter = get(),
            scheduler = get(),
            localAccountScope = get()
        )
    }
}
