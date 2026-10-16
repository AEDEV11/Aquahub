# Engineering Guide

## Kotlin and architecture conventions

- Follow existing package-by-feature presentation code and data/domain separation. Classes use PascalCase; functions/properties use camelCase; use cases end in `UseCase`, repository contracts in `domain`, and implementations identify their backing store.
- Expose immutable UI state through `StateFlow`; emit one-shot feedback through `SharedFlow`/events where already established. Collect lifecycle-aware in composables.
- Keep I/O and transactions in repositories/data sources. AquaHub features normally use use cases; customer features route through `CustomerViewModel` and repository interfaces. Do not inject Firebase/DAO objects into new UI code.
- Use injected dispatcher/time abstractions where the owner app already supplies them. Preserve coroutine cancellation; avoid indefinite nested `collect` calls and unmanaged scopes.
- Map Entity ↔ Domain ↔ Firestore DTO explicitly. Treat missing/invalid remote fields as observable errors where user action depends on them; do not silently erase incompatible records.
- Register dependencies in existing Koin modules. Repositories are generally singletons, use cases factories, and ViewModels `viewModel` definitions.
- Keep navigation destinations centralized in each navigation package. Encode/decode arguments consistently and avoid string-built duplicate routes.

## Compose and visual system

Both apps use Material 3, Plus Jakarta Sans, a blue/cyan water-service palette, light/dark schemes, rounded cards/buttons, semantic status colors, and reusable Compose components. AquaHub also centralizes spacing and shape tokens in `ui/theme/Dimens.kt`; customer has its own equivalent.

- Reuse `core/designsystem/components` in AquaHub and `ui/components` in AquaHubCustomer before introducing a new primitive.
- Use theme colors and typography rather than hard-coded alternatives. Maintain existing primary/secondary card and button hierarchy.
- Preserve owner bottom navigation/dashboard operational density and customer bottom navigation/station-product-checkout patterns.
- Provide explicit loading, empty, error, disabled, and offline/stale states. Do not label tracking “live” without a live source.
- Icons that trigger actions need meaningful content descriptions or an accessible parent label; decorative icons may use null descriptions.
- Keep touch targets, contrast, font scaling, screen-reader order, and motion reduction in mind. Verify dynamic lists use stable keys where identity matters.
- Existing animation is local Compose animation, not a centralized motion system. Match nearby duration/easing rather than inventing a new system.

## Business and data safety

- Validate order transitions through existing use cases/state-machine rules, not only button visibility.
- Use integer centavos for new backend/customer monetary values and server-side totals for customer checkout.
- Couple inventory adjustments and their ledger/outbox operations transactionally. Never claim remote stock reservation until it exists and is tested.
- Scope owner data by the active station. Check whether a legacy screen uses the owner's default station before copying its pattern.
- Customer app direct writes must stay limited by rules; order mutations belong in callable Functions with authentication, App Check, authorization, idempotency, and validation.
- Never trust role strings alone for business access; use membership/ownership checks. Never expose private station collections through a broad public read without explicit review.
- Do not log credentials, Firebase configuration values, full notification tokens, or customer-sensitive payloads.

## Performance-sensitive areas

- AquaHub: Room queries and relationship materialization, all-record sync pulls, dashboard/report in-memory aggregation, outbox retries, station switching, and export generation.
- Customer: unbounded collection-group station discovery, client-side distance/search sorting, live product/order listeners, map/location permission cycles, and full-list recomposition.
- Backend: multi-document order transactions, idempotency documents, token fan-out, and status-trigger duplicate handling.

Before expanding a collection, add appropriate bounds/index/pagination rather than relying on current small demo datasets. Keep blocking export/geocoding/file work off the main thread.

## Error and connectivity behavior

Use established result/error types in AquaHub and clear UI error state in both apps. Firebase listeners must surface permission, parsing, and connectivity failures when they affect correctness. WorkManager retry should be reserved for transient failures with bounded backoff; permanent authorization/schema failures need visible diagnostics.

## Test expectations

- Run the affected project's `testDebugUnitTest` and `assembleDebug` at minimum.
- Add owner unit tests for use-case/state rules and mapper conversions; add Room instrumented tests for transactions, migrations, and outbox persistence.
- Add customer repository/ViewModel tests for listener errors, session changes, cart/checkout, and mapping. Compose UI tests are currently sparse.
- Add Functions emulator tests for authorization, server totals, idempotency, inventory concurrency, cancellation races, and malformed documents.
- Run Firestore rules tests whenever a path, identity, role, membership, public field, or query changes.
- Do not infer that local Functions/rules are deployed. Record deployment verification separately.

## Context update rule

Update context when a durable fact above changes. Keep the affected project document and root shared-contract/risk document consistent, and remove resolved risk text rather than accumulating history.

