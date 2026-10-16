# AquaHub Project Overview

## Purpose and identity

AquaHub is the Android application for water-refilling station owners/operators. It manages station setup, local operations, orders, inventory, customers, riders, reports and subscription entitlements. Namespace/application ID: `com.aesprt.aquahub`. The Gradle build contains one `:app` module.

## Actual stack

- Kotlin, Android SDK 37/min 26, Java 11, Jetpack Compose and Material 3.
- Navigation Compose and Koin.
- Coroutines, `Flow`, `StateFlow`, `SharedFlow`.
- Room database v8, Preferences DataStore, WorkManager.
- Firebase Auth, named Firestore database `aquahub`, Cloud Storage, FCM.
- Google Maps/Places/Fused Location with Geocoder fallback.
- Coil, owner-scoped Firebase Storage image uploads, and CSV/XLSX/PDF report exporters with user-selected folder saving.
- No Retrofit, OkHttp, REST API service, WebSocket, or Kotlin serialization layer.

## Architecture summary

The intended path is Compose → feature ViewModel → use case → domain repository → Room repository/DAO. Room is authoritative for operational screens. Local mutations also enqueue durable sync work, and WorkManager reconciles Firestore. This is a Clean/MVVM-inspired structure rather than strict Clean Architecture: several ViewModels call repository contracts directly, `SyncStatusViewModel` uses `SyncDao`, and onboarding reaches a Firebase data source.

Koin loads database, DataStore, Firebase, sync, repository, use-case, and ViewModel modules from `AquaHubApplication`. Repositories are typically singletons; use cases are factories; feature ViewModels use Koin ViewModel definitions.

## Primary source map

| Concern | Source |
|---|---|
| Startup/DI scheduling | `app/src/main/java/com/aesprt/aquahub/AquaHubApplication.kt` |
| Root navigation/routes | `core/navigation/AppNavGraph.kt`, `core/navigation/Screen.kt` |
| Domain models/enums | `domain/model/` |
| Repository contracts | `domain/repository/` |
| Business operations | `domain/usecase/` |
| Room schema/DAO/mapping | `core/database/`, `data/local/` |
| Implementations | `data/repository/` |
| Firestore DTO/data sources | `data/firebase/` |
| Durable synchronization | `sync/` |
| Feature UI/state | `feature/` |
| UI system | `core/designsystem/components/`, `ui/theme/` |

## Configuration and boundaries

`local.properties` supplies Android SDK and Maps configuration. `google-services.json` supplies Firebase build configuration. Do not copy their values. The manifest declares network/location/notification permissions, an FCM service, and a FileProvider used by exports. Backup is enabled but database/session paths are excluded by backup rules.

There are debug/release build types and no product flavors or production signing configuration in source. Release uses R8 resource/code shrinking and packages full native debug symbols when the native inputs provide them; confirm the current Gradle files before modifying build behavior.

## Status at a glance

The operational local-first core is substantial. Firebase sync is implemented in source, including foreground order wake-up, durable relative stock adjustments, product archive, assigned-staff station scoping, and authoritative subscription restoration. Eligible first-time paid onboarding uses a server-backed, app-managed 30-day trial without opening Play checkout; payment activation starts through Play after the trial expires, while previously consumed trial identities go directly to verified Play purchase. Production completeness is still limited by external Play Console/service-account/Functions deployment validation, partial notification UX, incomplete customer-order E2E verification, and missing live delivery/payment/QR/AI features. See `FEATURES_AND_FLOWS.md` and workspace `KNOWN_RISKS_AND_STATUS.md`.
