package com.aesprt.aquahub.di

import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.RoomTransactionRunner
import com.aesprt.aquahub.data.local.seeder.DatabaseSeeder
import com.aesprt.aquahub.domain.common.LocalTransactionRunner
import com.aesprt.aquahub.domain.common.SystemTimeProvider
import com.aesprt.aquahub.domain.common.TimeProvider
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val databaseModule = module {
    single<TimeProvider> { SystemTimeProvider() }
    single<LocalTransactionRunner> {
        RoomTransactionRunner(get())
    }

    single<AquaHubDatabase> {
        AquaHubDatabase.create(androidContext())
    }

    single { get<AquaHubDatabase>().stationDao() }
    single { get<AquaHubDatabase>().ownerDao() }
    single { get<AquaHubDatabase>().customerDao() }
    single { get<AquaHubDatabase>().productDao() }
    single { get<AquaHubDatabase>().riderDao() }
    single { get<AquaHubDatabase>().orderDao() }
    single { get<AquaHubDatabase>().orderItemDao() }
    single { get<AquaHubDatabase>().containerDao() }
    single { get<AquaHubDatabase>().containerTransactionDao() }
    single { get<AquaHubDatabase>().paymentDao() }
    single { get<AquaHubDatabase>().paymentRefundDao() }
    single { get<AquaHubDatabase>().subscriptionDao() }
    single { get<AquaHubDatabase>().subscriptionPlanDao() }
    single { get<AquaHubDatabase>().subscriptionHistoryDao() }
    single { get<AquaHubDatabase>().syncDao() }
    single { get<AquaHubDatabase>().expenseDao() }
    single { get<AquaHubDatabase>().offlinePosDao() }

    single {
        DatabaseSeeder(
            subscriptionPlanDao = get()
        )
    }
}
