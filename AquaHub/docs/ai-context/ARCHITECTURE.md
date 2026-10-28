# AquaHub Architecture

## Runtime layers

```text
Compose screen
  ↕ lifecycle-aware UI state / user events
Feature ViewModel
  ↓ domain validation/use case (where present)
Repository contract
  ├─ Room transaction + durable pending-mutation row
  └─ Supabase data source / RPC / Storage (authoritative remote state)
       ↑ WorkManager sync/reconciliation when authenticated and online
```

Operational screens observe Room-backed flows. Local IDs map to stable Supabase UUIDs/legacy IDs through sync metadata. Supabase is the current account/business/order/catalogue/payment authority in executable source; Firebase remains for FCM and (Owner only) Analytics/Crashlytics. Firestore DTO/rules/configuration are legacy or migration compatibility unless a current caller proves otherwise.

## State and coroutines

ViewModels generally expose a data-class `StateFlow` and launch repository/use-case collection in `viewModelScope`. Some expose `SharedFlow` for one-shot effects. Compose should collect lifecycle-aware and send events upward.

Known inconsistencies to avoid copying:

- `ReportsViewModel` and `ManageStationsViewModel` use repository contracts directly.
- `SyncStatusViewModel` consumes `SyncDao` and `SyncScheduler` directly.
- `OnboardingViewModel` depends on `SupabaseAuthDataSource` for verification state.
- Some settings/profile flows nest indefinite collectors; use `flatMapLatest`/`combine` for switching owner/station sources.
- Settings/profile/subscription code may use the owner's original `stationId` instead of the active station context.

## Dependency injection

Koin modules under `di/` are loaded by `AquaHubApplication`:

- `DatabaseModule`: Room database, DAOs, transaction runner/time dependencies.
- `DataStoreModule`: user preferences and active station context.
- `ObservabilityModule`: app crash/error reporting dependencies.
- `SyncModule`: local account scope, mutation writer, Supabase coordinator, scheduler, worker dependencies/connectivity and remote-order refresh.
- `RepositoryModule`: Room, Supabase, Google Play and platform repository bindings.
- `UseCaseModule`: business operations as factories.
- `ViewModelModule`: feature ViewModels.

Prefer constructor injection. Do not instantiate repositories, DAOs or Firebase services in composables. `AquaHubMessagingService` creates a scheduler from application context to enqueue immediate sync; treat services as Android entry-point exceptions, not a general pattern.

## Authentication and tenant context

Supabase Auth is the owner-app identity authority. The app supports the current Supabase sign-in/provisioning paths; Firebase Auth is not an active business identity provider in the current Gradle/source path. Firebase Messaging is retained for push delivery, with Analytics/Crashlytics used for Owner telemetry.

Supabase requests use the Ktor OkHttp engine. The Android HttpURLConnection engine was replaced after Crashlytics issue `4e33aa1d8b62a1925a39b953bd5a964e` showed SSL stream cleanup during coroutine cancellation throwing `NetworkOnMainThreadException` on the main thread. Subscription billing observes Room state without making history HTTP requests per emission; history refresh runs on initial load and after returning from Play management, with one return refresh at a time. Coroutine cancellation propagates without continuing expiration processing. These changes require a new Android build and device validation before release.

DataStore stores session/active-station preferences and `local_account_scope_uid`, which binds the Room cache/outbox to one Supabase user. `LocalAccountScopeManager` serializes account reconciliation and sync replay. Switching identity with pending mutations is rejected; an empty prior cache may be cleared, while same-owner pending work is preserved for recovery. Focused policy tests pass, but device/account-switch, reinstall/restore and full migration tests remain required.

Registration/provisioning spans Supabase Auth, workspace/profile/station RPCs, Room and trial setup and is not one cross-system transaction. Retain idempotency and retry behavior; do not infer provisioning success from local UI state.

## Synchronization architecture

Operational repository mutations use Room transactions and `SyncQueueWriter`/`SupabasePendingMutationWriter`. `SyncScheduler` registers immediate unique work and periodic network-constrained recovery work. `SupabaseSyncWorker` calls `SupabaseSyncCoordinator`, which serializes replay with the local account scope, checks the current Supabase UID, drains bounded outbox batches, and refreshes required data.

Per-resource mappings retain local numeric IDs plus stable remote IDs. Order state mutations carry expected/requested states and an operation identity. Walk-in retries now keep one ID for the same intent; the local order snapshot adopts server-returned totals/status/items. Pull/refresh must not overwrite a row with an outstanding local mutation. The production flavor now disables offline POS checkout, lease requests, and offline reconciliation commits; unresolved Room receipts remain available for review and are not submitted as confirmed sales. QA retains the offline implementation. Firebase FCM is only a wake/notification path; the FCM worker carries a station ID for its targeted pull, and both Dashboard and Orders refresh the active station's remote order queue when their screens resume. The generic periodic worker still has no station ID and cannot independently refresh that queue, so missed-push recovery currently depends on a screen resume or a station-scoped FCM wake-up. Realtime/out-of-order push, process death, network-timeout-after-commit, and multi-device convergence still need integration tests. Supabase subscriptions remain server-authoritative rather than ordinary offline writes.

## Error handling

Domain/data code uses the project's result/error abstractions plus exceptions at external boundaries. UI state exposes messages/loading in feature-specific forms. Keep permanent auth/schema errors distinct from transient WorkManager retry. Surface sync failure state through the existing sync queue/status model.

## Architectural decisions

- Compose-only screen architecture and Navigation Compose.
- Koin constructor injection.
- Room-first cached operational state with a transactional outbox; Supabase is authoritative remotely.
- Separate local numeric IDs and remote string IDs rather than destructive schema replacement.
- Supabase Auth/Postgres/PostgREST/RPC/Storage/Edge Functions for current business data.
- Firebase Messaging (and Owner Analytics/Crashlytics) remains; Firebase Firestore/Auth compatibility files/config/rules require source and deployment verification before removal.
- Main navigation now uses Material3 `NavigationSuiteScaffold`; all-screen adaptive tablet content is still incomplete and unverified.
