# AquaHub Owner App Agent Guide

AquaHub is the owner/station operations Android app. Read `docs/ai-context/PROJECT_OVERVIEW.md`, then only the feature-specific document needed.

## Architecture route

- App/navigation: `app/src/main/java/com/aesprt/aquahub/MainActivity.kt`, `core/navigation/`.
- Screens/ViewModels: `feature/`.
- Domain contracts and rules: `domain/model/`, `domain/repository/`, `domain/usecase/`.
- Room/data implementations: `core/database/`, `data/local/`, `data/repository/`.
- Firebase boundary and sync: `data/firebase/`, `sync/`.
- Koin: `di/`; design system: `core/designsystem/` and `ui/theme/`.

## Change rules

1. Preserve Room as the feature source of truth. A cloud-backed mutation normally updates Room and enqueues its outbox row in one transaction.
2. Scope operational data to `ActiveStationContext`; do not copy legacy default-station behavior into new work.
3. Put validation/state transitions in use cases or domain logic, data operations in repositories, and UI state/events in ViewModels.
4. Never bypass local↔remote ID mapping or integer-centavo conversion at the Firestore boundary.
5. Any Station/Product/Customer/Address/Order/Delivery/Payment/status change is shared: read the workspace contract document and inspect AquaHubCustomer plus Functions.
6. Subscription data is currently split between local simulation and remote authority. Do not extend billing behavior without first resolving that boundary.
7. Do not bind or revive `RoomAuthRepository` casually; Firebase auth is the active implementation.
8. Keep routes centralized in `core/navigation`. No deep-link contract currently exists.
9. Reuse AquaHub theme/components and maintain the established blue/cyan water-service UI.
10. Never expose Maps/Firebase configuration values.

## Verification

Run `./gradlew testDebugUnitTest assembleDebug` from this directory for ordinary changes. Add/run Android Room tests for schema, migration, transaction, or outbox changes. A Firebase contract/rules change also requires customer Functions/rules tests.

Deeper context:

- `docs/ai-context/ARCHITECTURE.md`
- `docs/ai-context/FEATURES_AND_FLOWS.md`
- `docs/ai-context/NAVIGATION.md`
- `docs/ai-context/DATA_AND_BACKEND.md`
- workspace `../docs/ai-context/SHARED_DOMAIN_AND_CONTRACTS.md`

