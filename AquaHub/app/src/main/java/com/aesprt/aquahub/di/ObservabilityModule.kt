package com.aesprt.aquahub.di

import com.aesprt.aquahub.data.supabase.account.SupabaseAccountDataSource
import com.aesprt.aquahub.data.supabase.auth.SupabaseAuthDataSource
import com.aesprt.aquahub.data.supabase.catalog.SupabaseCatalogDataSource
import com.aesprt.aquahub.data.supabase.storage.SupabaseImageStorageRepository
import com.aesprt.aquahub.data.supabase.storage.SupabaseStorageDataSource
import com.aesprt.aquahub.data.supabase.storage.StorageAssetResolver
import com.aesprt.aquahub.data.supabase.workspace.SupabaseWorkspaceDataSource
import com.aesprt.aquahub.data.observability.AuthTelemetry
import com.aesprt.aquahub.data.observability.FirebaseAuthTelemetry
import com.aesprt.aquahub.data.observability.NoOpAuthTelemetry
import com.aesprt.aquahub.domain.repository.ImageStorageRepository
import com.aesprt.aquahub.BuildConfig
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Firebase is limited to Analytics, Crashlytics, and FCM transport. */
val observabilityModule = module {
    single<AuthTelemetry> {
        if (BuildConfig.QA_BUILD) NoOpAuthTelemetry() else FirebaseAuthTelemetry(androidContext())
    }
    single { SupabaseAuthDataSource() }
    single { SupabaseWorkspaceDataSource() }
    single { SupabaseAccountDataSource(get()) }
    single { SupabaseCatalogDataSource() }
    single { SupabaseStorageDataSource(androidContext()) }
    single { StorageAssetResolver(get()) }
    single<ImageStorageRepository> { SupabaseImageStorageRepository(androidContext(), get(), get()) }
}
