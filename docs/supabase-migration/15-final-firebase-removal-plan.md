# Final Firebase Removal Plan

Status: `SOURCE_CUTOVER_COMPLETE_VALIDATION_PENDING`

This document is the historical execution plan for the AquaHub Firebase to Supabase migration. The current source-level result is recorded in `23-firebase-runtime-removal.md`, `30-firebase-class-audit.md`, and `31-unmigrated-firebase-check.md`. It does not authorize deleting Firebase data, Storage objects, rules, or exports.

## Target architecture

Supabase is the system of record for:

- Auth and profiles
- PostgreSQL business data protected by RLS
- Storage objects and signed/public delivery URLs
- Realtime feeds where live updates are required
- Edge Functions, PostgreSQL RPCs, triggers, and scheduled jobs
- Reporting views and SQL aggregates
- Durable device-token registration metadata

Firebase remains only for:

- Analytics
- Crashlytics
- FCM transport

FCM token delivery uses a Supabase-authorized registration path. FCM is a notification transport, not a business-data store. Firebase Auth, Firestore, Firebase Storage, callable business Functions, and the Firebase compatibility bridge have been removed from the active source/runtime path. Historical data and non-destructive migration artifacts remain retained.

## Historical audit snapshot

The following counts describe the pre-cutover audit and are retained for traceability only; they are not current runtime counts.

- 195 Firebase-related references were found across the repository, excluding generated/build files and credential files.
- Owner app symbols remain in Auth, Firestore, Storage, Functions, Messaging, Analytics, Crashlytics, and App Check code.
- Customer app symbols remain in Auth, Firestore catalog/order repositories, Functions, Messaging, and App Check code.
- `AquaHubCustomer/functions/src/index.ts` exports 18 deployed functions. The compatibility bridge and business Functions are still present together.
- `docs/supabase-migration/15-final-firebase-removal-plan.md` did not exist before this audit.
- Hosted Supabase migration state is aligned and the hosted dry-run is up to date. No local Supabase instance is part of this migration workflow.
- Existing customer/product validation remains pending physical validation, but that physical gate no longer blocks backend migration implementation.

## Keep / migrate / remove classification

### KEEP — Firebase allowed after migration

| Area | Current locations | Required end state |
|---|---|---|
| Analytics | `AquaHub/app/**`, Firebase Analytics calls | Keep event telemetry only; no business reads/writes or identity authority. |
| Crashlytics | `AquaHub/app/**`, Firebase Crashlytics calls | Keep crash reporting and non-sensitive diagnostics; never log tokens, OTPs, credentials, or customer payloads. |
| FCM | `AquaHub/app/**/AquaHubMessagingService.kt`, `AquaHubCustomer/app/**/CustomerMessagingService.kt` | Keep FCM transport and service declarations. Move token ownership/registration metadata from Firestore to `public.device_tokens` through Supabase. |
| App Check, if still required by the retained Firebase services | debug/release App Check configurators | Keep only if needed for retained Firebase services. It must not authorize Supabase business access. |

### MIGRATE_NOW — active business dependencies

| Area | Current dependency | Replacement |
|---|---|---|
| Owner Auth/session | `AquaHub/.../SupabaseAuthRepository.kt`, Firebase compatibility bridge | Supabase Auth as authority. Keep only the temporary bridge until the final Firebase-backed screen is gone. |
| Owner profile/onboarding | `FirebaseAuthDataSource.kt`, setup use case, profile/station writes | Supabase Auth/profile/station transaction or RPC; display name and phone must be persisted in `profiles`. |
| Owner business CRUD | `FirebaseRemoteDataSources.kt`, Firebase sync coordinator/listeners/queue | Supabase PostgREST/RPC/Realtime with Room as the offline cache and durable mutation queue. |
| Owner images | `StorageAssetResolver.kt` legacy branch | Supabase Storage bucket and signed URL/upload path with RLS and idempotent migration; retain only the explicit `gs://` read branch. |
| Owner staff/trial/subscription | Supabase Staff/Settings/Subscription sources and legacy Functions | Supabase Edge Functions/RPCs and subscription tables; Google Play verification stays server-side. |
| Customer Auth | `SupabaseAuthRepository.kt` | Supabase Auth, profile provisioning, and RLS-scoped customer session. |
| Customer catalog | `SupabaseCatalogRepository.kt` | Supabase public station/product projections with legacy-ID lookup metadata. |
| Customer orders | `SupabaseOrderRepository.kt`, legacy Functions retained server-side | Transactional Supabase RPC/Edge Function with server-side pricing, stock, idempotency, and authorization. |
| Inventory | owner inventory flows and current Firebase transaction tests | New canonical inventory tables plus atomic PostgreSQL RPCs/transactions. Client-only multi-request writes are not acceptable. |
| Payments, delivery, expenses | Firebase data sources and Function paths | Supabase tables/RPCs/triggers with integer-centavo and enum compatibility preserved. |
| Firebase Functions | `AquaHubCustomer/functions/src/index.ts` business exports | Per-function replacement mapping below; retain only FCM-related transport integration where needed. |
| Firestore business mirrors | both Android apps and Functions | Stop writes only after every consumer is on Supabase and migration/reconciliation reports zero unresolved rows. |
| Firestore offline persistence | Firebase sync/offline paths | Room remains the only offline business cache. |
| Firebase Storage | owner image repository and any customer object paths | Supabase Storage; Firebase source is retained read-only until verified. |

### REMOVE_AFTER_MIGRATION — deletion gate

Remove Firebase Auth/Firestore/Storage/business Functions and compatibility bridge only after all of the following are true:

1. Replacement code exists and is deployed/available.
2. Historical and live data reconciliation reports no unresolved required rows.
3. RLS and server-side authorization tests pass for owner, staff, customer, cross-tenant, and unauthenticated cases.
4. Offline Room queue replay and retry behavior pass.
5. The final comprehensive Android validation passes on the supported device path.
6. Observability shows no production reads/writes to the Firebase business path during the agreed observation window.
7. A recoverable export/backup and rollback note exists.

Until then, Firebase source, rules, indexes, and data remain intact. No `supabase db reset --linked` is permitted.

## Supabase schema baseline and gaps

The existing hosted schema already contains the primary relational boundaries for profiles, organizations, stations, memberships, customers, addresses, products, riders, containers, container movements, orders, order items, payments, expenses, subscriptions, devices, audit events, and migration metadata. The existing tables retain `firebase_id` columns where legacy identity mapping is required.

Gaps to close before removing Firebase:

- Canonical inventory and inventory-movement tables are not yet present. `inventory_compensations` is not a substitute for inventory state or an atomic stock ledger.
- Transactional RPCs are required for inventory mutation, order creation/cancellation, payment application, delivery transitions, and other stateful workflows.
- Storage bucket/object policy and a restartable object migration tool must be defined.
- Device-token registration must be moved from Firestore subcollections to `device_tokens` while retaining FCM delivery.
- Reporting views/RPCs must replace client-side Firestore aggregation.
- Customer and owner realtime subscriptions must be mapped to Supabase Realtime channels.
- Function replacements and migration tooling need explicit dry-run/apply/retry/validation behavior.

## Function replacement map

| Firebase export | Replacement | Removal condition |
|---|---|---|
| `issueFirebaseCompatibilityToken` | Temporary bridge only | Remove after all Firebase Auth consumers and Firebase-backed business paths are gone. |
| `listStaffAccounts`, `createStaffAccount`, `updateStaffAccount`, `deleteStaffAccount` | `staff-admin` Edge Function plus `staff_directory()`; legacy source retained during observation | Authenticated staff validation, no runtime callable calls, and the final removal gate pass. |
| `ensureOwnerTrial`, `checkOwnerTrialEligibility` | Deployed `subscription-trial` Edge Function over owner trial and subscription tables | Trial UX and authenticated idempotency tests pass without Firestore. Legacy callables remain source-only until observation/validation. |
| `verifyPlaySubscription` | Deployed `verify-play-purchase` Edge Function plus Supabase subscription state | Google Play server secrets, real purchase replay, token-reuse denial, and entitlement tests pass. |
| `playSubscriptionNotifications` | Retained Firebase RTDN compatibility path; target Supabase receiver | Supabase RTDN receiver is deployed and validated with replay/idempotency evidence. |
| `createCustomerOrder` | Deployed `public.create_order` RPC; temporary Firebase callable remains until customer Supabase session cutover | Customer checkout and stock-safety matrix passes without Firestore stock mutation. |
| `setCustomerPreferredStation` | Supabase RPC/table update | Customer preference persistence and RLS tests pass. |
| `cancelCustomerOrder` | Deployed `public.cancel_order` RPC; temporary callable remains until customer Supabase session cutover | Cancellation/state-transition tests pass. |
| `mirrorCustomerOrderStatus` | Supabase Realtime/trigger/view; no Firestore mirror | Both apps consume the canonical Supabase order state. |
| `publishPublicStation`, `publishPublicProduct`, `syncPublicStationsForSubscription` | SQL views/RPCs or Supabase Edge Function projection job | Public catalog and entitlement projection tests pass. |
| `deleteAquaHubAccount` | Supabase account-deletion Edge Function/RPC with audit and cascade policy | Deletion dry-run/apply and retention checks pass. |

FCM notification sends may remain in an Edge Function using FCM HTTP v1, but recipient lookup, authorization, deduplication, and audit state must come from Supabase.

## Runtime audit checkpoint — 2026-10-04

The complete Firestore/Auth/Storage callsite table is maintained in [23-firebase-runtime-removal.md](23-firebase-runtime-removal.md). Owner delivery reads refresh canonical mapped orders/items from Supabase into Room; customer delivery reads already use the Supabase order repository. There is no dedicated rider repository or screen. `LegacyCompatibilityResolver` centralizes delivery compatibility: only a missing canonical UUID plus an available Firebase compatibility session may use legacy Firebase. Supabase 401/403, Postgres `42501`, tenant mismatch, role/station validation, and business failures never fall back.

The customer app's active FCM token registration now writes Supabase `device_tokens`; Firebase Messaging remains transport. The server-only `notification_outbox` and `send-fcm-notification` Edge Function prepare delivery notification migration. Order notification Functions remain deployed for observation/rollback after `ORDER_BACKEND_CUTOVER_VALIDATED`; they are no longer the validated new-order authority. Firebase Storage SDK removal is blocked only by owner legacy `gs://` reads in `StorageAssetResolver`.

### Complete Firestore runtime classification

| Runtime area | Classification | Reason |
|---|---|---|
| Owner order sync/listener and order Functions | `RUNTIME_UNUSED_OBSERVATION_PENDING` for new order authority | Supabase order/inventory proof passed; retain only for observation/rollback |
| Owner generic resource sources | `LEGACY_READ_ONLY` / `MIGRATION_ONLY` | Retained for historical pulls/import compatibility by domain |
| Owner device-token mirror | `FCM_LEGACY_NOTIFICATION_PENDING` | Retained order notification Functions still read Firestore devices |
| Customer public catalog repository | `LEGACY_READ_ONLY` | Public catalog projection has not yet been cut over |
| Customer Firebase order repository | `RUNTIME_UNUSED_OBSERVATION_PENDING` source only | Customer DI selects Supabase; observe before deleting source |
| Customer FCM token write | `REMOVE_NOW` | Replaced by Supabase `device_tokens` |
| Customer/staff/settings/expense/product/customer new writes | `REMOVE_NOW` where Supabase path is authoritative | Offline replay now completes in Supabase and does not continue to Firestore |
| Payment compatibility writes | `PAYMENT_VALIDATION_PENDING` | Provider/refund and required runtime validation remain pending |

## Execution order

1. **Contract and schema completion** — finalize canonical IDs, enums, tenant scope, inventory ledger, RPC contracts, RLS, Realtime, Storage buckets, and reporting views.
2. **Migration tooling** — build dry-run/apply/restartable/idempotent tools for Firestore business rows and Storage objects. Preserve legacy IDs and produce row-level failure reports; never delete source data in the migration step.
3. **Owner/customer data access** — replace repositories and listeners with Supabase paths while keeping Room for offline operation.
4. **Inventory** — implement atomic PostgreSQL inventory mutations and migrate inventory consumers/tests.
5. **Orders, payments, delivery, expenses** — move server-authoritative workflows and reporting together; preserve centavos and exact status values.
6. **Auth completion** — remove compatibility dependence after all consumers use Supabase Auth; keep Analytics/Crashlytics/FCM identity handling separate.
7. **Storage migration** — migrate objects, verify checksums/ownership/path references, then switch reads/writes; retain Firebase source until verification.
8. **Function cutover** — deploy and observe Supabase replacements; stop Firebase business writes only after parity and reconciliation.
9. **Firebase removal** — remove dependencies, rules/data paths, bridge, and unused Functions only after the deletion gate above.
10. **One comprehensive validation pass** — execute auth, tenant isolation, customer/product, inventory, orders, payments, delivery, expenses, storage, offline Room replay, Realtime, FCM, and failure/retry matrices on the supported device path.

## Safety requirements

- Hosted Supabase only; use linked migration inspection/dry-run commands. Never run `supabase db reset --linked`.
- Never put a service-role key in Android, logs, tests shipped to devices, or committed files.
- Never print access tokens, refresh tokens, custom tokens, OTPs, API secrets, or full authorization headers.
- Do not weaken RLS to make a client request succeed. Fix tenant context, policies, or server-side RPC authorization.
- Never trust client-supplied role, organization, station, price, stock, or Firebase UID values.
- All migration jobs must be restartable, idempotent, resumable, and explicit about dry-run versus apply.
- Destructive cleanup requires a separate confirmed operation after reconciliation and backup verification.

## Validation and release gates

Current gate: `CUSTOMER_PRODUCT_CUTOVER_PENDING_DEVICE_VALIDATION`.

This gate describes the existing physical validation status; it is not a blocker for continuing backend migration implementation under the new strategy. The final target is not set until the comprehensive validation pass succeeds.

Required final evidence:

- hosted migration list and dry-run clean;
- Gradle/unit/instrumentation/backend checks green;
- RLS negative tests for unauthenticated, cross-account, and cross-tenant access;
- migration reports with zero unresolved required records and zero duplicate canonical identities;
- inventory atomicity/stock-safety tests;
- order/payment/delivery/expense replay and idempotency tests;
- Storage checksum/path/authorization verification;
- Room offline create/read/update/replay and reconnect reconciliation;
- Realtime update and FCM delivery checks;
- final Firebase audit containing only Analytics, Crashlytics, FCM, and explicitly retained App Check references.

No claim of final Firebase removal or final readiness is made by this planning document.

## Scheduler and final decommission checkpoint — 2026-10-04

The outbox scheduler boundary is implemented by `tools/supabase-migration/dispatch-notifications.mjs`. It is an external one-shot worker because the linked project has no configured `pg_cron`, `pg_net`, or Vault path. The Edge dispatcher uses atomic leases, stale-claim recovery, bounded sender retries, terminal failure state, and a per-run identifier. Hosted secrets are configured and a zero-row dispatcher smoke passed; recurring scheduling and device delivery remain unvalidated.

The explicit compatibility reasons are centralized in `LegacyCompatibilityResolver`: only an unmigrated/missing canonical mapping may select a legacy delivery path, and authorization/RLS/tenant/validation errors never select Firebase. The final dependency order, source-level inventory, and non-destructive production import checkpoints are in `26-notification-scheduler.md`, `27-production-data-cutover.md`, and `28-firebase-dependency-removal-manifest.md`. The overall decommission status is `FIREBASE_RUNTIME_DECOMMISSION_READY_PENDING_VALIDATION`, not a deletion authorization.

## 2026-10-04 implementation status

Order validation is now `ORDER_BACKEND_CUTOVER_VALIDATED`. The authenticated harness uses disposable hosted fixtures; its business RPC requests use normal user access tokens, its nine-check matrix passed, and the targeted stock-one proof verified final balance/movement/order invariants. Firebase order-stock Functions remain intentionally deployed pending device/observation cutover evidence.

Payments implementation has started independently. The additive Supabase payment foundation adds ownership/provider/idempotency fields, explicit payment statuses, a server-validated `record_payment` RPC, and an atomic paid walk-in receipt trigger. The historical payment importer is dry-run-first and provider-neutral. Payment compatibility writes and Firebase payment sync remain until the UUID-backed client adapter is validated.

Staff/settings implementation checkpoint (2026-10-04): `staff-admin` is deployed and Android create/update/delete staff calls use the Supabase session. OWNER/MANAGER authorization, organization/station assignment checks, password non-logging, and compensating Auth cleanup are server-side. Staff delete is reversible deactivation. `update_station_settings` is deployed with a field whitelist and direct authenticated station updates revoked. The authenticated staff/settings matrix passed with disposable hosted fixtures; no Firebase staff Function is removed because observation/device validation is still pending. No Firebase staff write is authoritative in the Android path.

Delivery implementation checkpoint (2026-10-04): the hosted `delivery_events` ledger plus server-authorized assignment/status RPCs are deployed. Owner online reads refresh mapped orders/items from Supabase into Room; assignment/status mutations and eligible offline replays use Supabase without new Firestore delivery writes. Customer tracking is Supabase-primary, and no separate rider delivery repository exists. COD settlement is intentionally separate from delivery completion. The delivery gate is `DELIVERY_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`; authenticated/device validation remains pending.

Subscription checkpoint (2026-10-04): Google Play product verification and owner trial provisioning now use deployed Supabase Edge Functions. `get_effective_entitlements()` is server-derived and Android only caches its resolved plan features/limits for offline display and gating continuity. Authenticated trial/entitlement checks passed, while real purchase-token and live RTDN validation remain pending. The notification outbox retry/claim migration and dispatcher/sender functions are deployed with hosted secrets configured; live FCM delivery remains pending. The new subscription gate is `SUBSCRIPTIONS_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`.

The Supabase `play-rtdn` receiver is now deployed but remains `PLAY_RTDN_IMPLEMENTED_LIVE_VALIDATION_PENDING`: Pub/Sub OIDC authentication, package validation, Google Publisher re-verification, message-id replay protection, and provider-event ordering are implemented. The old Firebase RTDN Function remains until Play Console routing and live delivery are confirmed. The outbox pipeline remains `FCM_OUTBOX_IMPLEMENTED_LIVE_VALIDATION_PENDING` until scheduler, server credentials, and a real device token are configured.

Expenses and Storage implementation have now started. New authenticated expense writes use Supabase with Room cache fallback only for offline/unprovisioned sessions; the hosted expense importer is dry-run-first. New product, station-logo, and payment-QR uploads use Supabase Storage buckets with tenant-scoped paths and Firebase remains legacy read/import compatibility. No Firebase Storage object or source row is deleted.

Storage audit: the owner’s new product, station-logo, and station GCash/Maya QR uploads now target the deployed Supabase Storage buckets through `SupabaseImageStorageRepository`, and all active owner image reads pass through `StorageAssetResolver`; its Firebase branch is legacy-read only. The deployed buckets and policies are `STORAGE_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`. Profile photos, receipts, and proof-of-delivery objects were not found as implemented flows and were not invented. A reviewed manifest with checksum/path ownership verification is still required before any Firebase Storage removal. Analytics, Crashlytics, and FCM remain the intended final Firebase services.

## First deployed schema slice

The hosted project now includes `supabase/migrations/20261003130000_inventory_ledger_rpc.sql`, the function-only repair `20261003131000_fix_inventory_rpc_ambiguity.sql`, and movement metadata overload `20261003140000_inventory_movement_metadata.sql`. Hosted verification on 2026-10-03 reported:

- `supabase migration list --linked`: all local and remote migrations aligned through `20261003151000`.
- `supabase db push --linked --dry-run`: no pending migrations after deployment.
- `supabase db lint --linked`: no schema errors.
- Destructive-operation review: both migrations contain no `drop`, reset, or data-delete operation.

The deployment creates tenant-scoped `inventory_balances` and `inventory_movements` tables, indexes, read-only RLS policies, and the `adjust_inventory` security-definer RPC with explicit movement/source metadata. It also deploys atomic `create_order` and `cancel_order` RPCs with deterministic locking, integer-centavo totals, stable operation IDs, and order deduction/reversal movements. Android manual/product-opening paths now use Supabase inventory; Firebase order deduction/restock remains a documented transitional dependency until both order consumers are cut over.

## Latest validation checkpoint — 2026-10-04

The configured disposable hosted authenticated matrix passed for orders, payments, staff/settings, and subscription trial/entitlement checks. Real Play purchase-token, RTDN delivery, FCM device delivery, physical-device/offline, and approved historical-export validation remain pending. The notification dispatcher smoke passed with zero queued rows after the service-role-only RPC wrapper repair. These results do not authorize Firebase Function/Auth/Firestore/Storage removal; the order Functions and compatibility bridge remain retained pending observation and device gates.

## Final source audit correction — 2026-10-04

The implementation state above is historical planning context. The current
source of truth is the final runtime audit in [23-firebase-runtime-removal.md](23-firebase-runtime-removal.md): both Android apps are now free of
Firebase Auth, Firestore, Functions, Storage, and App Check runtime SDKs and
callers. The Firebase Functions package/scaffold was removed from the active
workspace after its responsibilities were replaced by Supabase RPCs and Edge
Functions. Only FCM, Analytics, and Crashlytics remain as Firebase runtime
services. Historical Firestore/Storage data and legacy ID columns remain for
non-destructive mapping/import and were not deleted. Pending validation gates
remain pending; this source audit does not claim device or authenticated
cutover evidence.
