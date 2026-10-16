# Workspace Overview

## Detected structure

```text
WaterStationApp/
├── AquaHub/          owner/station Android application
└── AquaHubCustomer/  customer Android application and Firebase backend
```

Each directory is an independent Gradle build with one `:app` module and its own wrapper. There is no composite build or module dependency between them. No functional Git repository was detected at the workspace or child roots during the context review; protect files conservatively because change ownership cannot be inferred from Git.

## Build identity and stack

| Area | AquaHub | AquaHubCustomer |
|---|---|---|
| Namespace/application ID | `com.aesprt.aquahub` | `com.aesprt.aquahub_customer` |
| Android | compile/target 37, min 26 | compile/target 37, min 26 |
| Language/UI | Kotlin, Compose, Material 3 | Kotlin, Compose, Material 3 |
| Architecture | MVVM/Clean-style, use cases, repositories | MVVM-style, central ViewModel, repositories |
| DI | Koin | Koin |
| Local persistence | Room v3 + DataStore | no Room; Firebase SDK cache only |
| Backend | Firebase Auth/Firestore/FCM via sync | Firebase Auth/Firestore/FCM/callable Functions |
| Background work | WorkManager durable synchronization | Firebase listeners and messaging service |
| Location | Maps, Places, Fused Location, Geocoder fallback | Fused Location; no map UI |

Both builds currently declare AGP 9.2.1, Kotlin 2.4.10, Compose BOM 2026.08.00, Java 11, Koin 4.2.2, Navigation 2.10.0, Firebase BoM 34.18.0, and Gradle 9.4.1. Verify catalogs before changing versions.

Configuration dependencies exist in `local.properties` and Firebase configuration files. Document only the dependency, never its value. AquaHub needs a Maps API configuration; Google/Firebase sign-in also depends on valid generated resources and console configuration.

## Relationship and ownership

The owner app manages a station's operational data locally and synchronizes it to the named Firestore database `aquahub`. The customer app discovers stations/products in Firestore and creates/cancels orders through callable functions. The backend writes an owner-facing station order and a user-facing mirror. An order-write trigger mirrors subsequent owner status changes to the customer and sends notifications.

Firebase Functions live in `AquaHubCustomer/functions/`. Firebase CLI configuration points there. The sibling `AquaHubCustomer/aquahub-functions/` package is a dormant generated scaffold (`DEPRECATED/UNUSED` under current configuration).

## Existing requirements and documentation

- `AquaHub/AquaHub_Water_Station_Implementation_Plan.md`: prospective owner platform plan; its REST/Spring/Retrofit/WebSocket architecture is not implemented.
- `AquaHub/AquaHub_Customer_Booking_App_Implementation_Plan.md` and the same-named file in `AquaHubCustomer/`: byte-identical customer plan copies.
- `AquaHub/docs/firebase-migration-audit.md`: useful historical migration evidence; re-check current code before relying on it.
- `AquaHubCustomer/README.md`: setup/deployment guide with some claims broader than current behavior.

## Confirmed architectural decisions

| ADR | Decision | Evidence and implication |
|---|---|---|
| ADR-001 | Two separate Android apps | Independent settings/build files and application IDs; verify both when a shared contract moves. |
| ADR-002 | Jetpack Compose and Material 3 | All screens are composables; preserve unidirectional state/event handling and shared theme components. |
| ADR-003 | Koin dependency injection | Application classes load modules; register new repositories/use cases/ViewModels in the existing modules. |
| ADR-004 | Owner app is Room-first | Feature flows observe Room; cloud operations must respect the transactional outbox and sync mapping. |
| ADR-005 | Firebase is the implemented integration | No Retrofit/OkHttp API layer exists. Plans for a REST backend are `PLANNED`, not current architecture. |
| ADR-006 | Customer mutations are server-authoritative | Order creation/cancellation use callable Functions with App Check; do not replace with direct client writes. |
| ADR-007 | Backend money uses integer centavos | Customer domain and Firestore use `Long`/integer centavos; owner converts from local `Double`. Preserve boundary semantics. |

## Build entry points

From each project root, use its wrapper. Typical checks are `./gradlew testDebugUnitTest assembleDebug`; owner instrumented Room tests require a device/emulator. Customer backend verification uses the scripts declared in `functions/package.json`, and rules verification uses the root customer package scripts/configuration.

