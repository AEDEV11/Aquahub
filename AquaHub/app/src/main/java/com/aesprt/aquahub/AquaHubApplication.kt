package com.aesprt.aquahub

import android.app.Application
import com.aesprt.aquahub.data.local.seeder.DatabaseSeeder
import com.aesprt.aquahub.di.*
import com.aesprt.aquahub.sync.SyncScheduler
import com.aesprt.aquahub.migration.MigrationValidationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class AquaHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MigrationValidationConfig.initialize(this)
        val koinApplication = startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.DEBUG else Level.NONE)
            androidContext(this@AquaHubApplication)
            modules(
                databaseModule,
                dataStoreModule,
                observabilityModule,
                syncModule,
                repositoryModule,
                useCaseModule,
                viewModelModule
            )
        }
        // Keep the local entitlement catalog current for existing installations.
        // This is intentionally completed before the first sync so a newly added
        // paid feature cannot be hidden by an older cached plan row.
        runBlocking(Dispatchers.IO) {
            koinApplication.koin.get<DatabaseSeeder>().seedPlansIfEmpty()
            koinApplication.koin.get<com.aesprt.aquahub.sync.LocalAccountScopeManager>()
                .prepareLegacyOutbox {
                    koinApplication.koin.get<com.aesprt.aquahub.data.local.dao.SyncDao>()
                        .quarantineLegacyOutstanding()
                }
        }
        koinApplication.koin.get<SyncScheduler>().apply {
            schedulePeriodic()
            enqueueImmediate()
        }
        if (!BuildConfig.QA_BUILD) {
            com.aesprt.aquahub.data.firebase.messaging.AquaHubMessagingService.createNotificationChannel(this)
            com.aesprt.aquahub.data.firebase.messaging.AquaHubMessagingService.registerCurrentDeviceToken(this)
        }
    }
}
