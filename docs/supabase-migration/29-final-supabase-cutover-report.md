# Final Supabase cutover report

Status: `SOURCE_CUTOVER_COMPLETE_VALIDATION_PENDING`  
Last reviewed: 2026-10-04

This report separates implementation, validation, and decommissioning. Missing external credentials are not treated as validation success. No Firebase business dependency, source data, or Storage object was deleted.

## Configuration readiness

The local validation environment and hosted Edge Function secrets are configured. The preferred aliases are `SUPABASE_SECRET_KEY`/`SUPABASE_PUBLISHABLE_KEY`; no secret values were printed or committed.

| Category | Result |
|---|---|
| Supabase fixture setup | `AVAILABLE` |
| Google Play verification | `AVAILABLE locally and synchronized to hosted functions` |
| FCM HTTP v1 | `AVAILABLE locally and synchronized to hosted functions` |
| Play RTDN OIDC/Pub/Sub | `AVAILABLE locally and synchronized to hosted functions` |
| Outbox scheduler auth | `AVAILABLE; hosted scheduler smoke passed` |

No secret values were printed or committed.

## Validation status

| Area | Result |
|---|---|
| Authenticated backend validation | PASS for available disposable order, payment, staff/settings, and subscription harnesses |
| Orders | PASS; authenticated create/cancel/ownership/idempotency/concurrency matrix passed |
| Payments | PASS; walk-in receipt, settlement, idempotency, and over-settlement matrix passed |
| Staff/settings | PASS; role, tenant, station, deactivation, and protected-write matrix passed |
| Subscriptions | PASS for authenticated trial/entitlement checks; real Play purchase token still pending |
| Play RTDN | Deployed; live Pub/Sub/OIDC delivery still pending |
| FCM outbox | Scheduler smoke PASS with zero queued rows; real device-token delivery still pending |
| Physical device | Owner debug APK installed/launched on Samsung SM-A366B; customer release AAB built at `AquaHubCustomer/app/build/outputs/bundle/release/app-release.aab` with versionCode 3, but still unsigned for manual Play upload |
| Historical import | Dry-run tooling exists; no production apply performed |

The consolidated backend harness passed with exit code 0. This does not constitute real Google Play purchase, live RTDN, physical-device workflow, or FCM delivery evidence. The owner debug APK was installed and launched on Samsung SM-A366B over USB ADB with no immediate crash observed. The existing customer package also launched with no immediate crash observed, but the newly built customer AAB is unsigned and cannot replace the installed Play-signed package directly; no uninstall or destructive package-state change was performed.

## Implemented Supabase architecture

- Supabase Auth and PostgreSQL are the intended application authority.
- Inventory, orders, payments, delivery, expenses, staff, settings, and subscriptions have Supabase implementations.
- Supabase Storage handles new product, station-logo, and payment-QR writes.
- Delivery events enqueue server-only notification outbox rows.
- `play-rtdn`, `verify-play-purchase`, `staff-admin`, `dispatch-notification-outbox`, and `send-fcm-notification` are deployed.
- The notification scheduler worker is [dispatch-notifications.mjs](../../tools/supabase-migration/dispatch-notifications.mjs).
- New owner/customer order creation and cancellation paths are Supabase-only. Legacy Firebase order Functions remain deployed as `RUNTIME_UNUSED_OBSERVATION_PENDING`; they are not yet undeployed.

## Firebase status

Retained intentionally:

- Firebase Cloud Messaging transport
- Firebase Analytics
- Firebase Crashlytics
- `google-services.json`/plugin where required by those SDKs

The active Android and backend source no longer contains Firebase Auth, Firestore, Firebase Storage, Firebase Functions, or App Check runtime dependencies. The former business Functions package and compatibility adapters were removed from source. Historical Firebase data, Storage objects, rules, and exports remain available for non-destructive migration/rollback work. Remaining Firebase runtime SDK usage is limited to FCM transport, owner-app Analytics, and owner-app Crashlytics.

## Import and rollback status

The importers remain dry-run-first, idempotent, restartable, and non-destructive. Production import has not been approved or run. Firebase source data and Storage objects remain available for rollback/read comparison. The dependency order and checkpoints are documented in [27-production-data-cutover.md](27-production-data-cutover.md).

## Current gates

- `OUTBOX_SCHEDULER_IMPLEMENTED_VALIDATION_PENDING`
- `FIREBASE_RUNTIME_DECOMMISSION_READY_PENDING_VALIDATION`
- `ORDER_BACKEND_CUTOVER_VALIDATED`
- `SUBSCRIPTIONS_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`
- `PLAY_RTDN_IMPLEMENTED_LIVE_VALIDATION_PENDING`
- `FCM_OUTBOX_IMPLEMENTED_LIVE_VALIDATION_PENDING`
- Payments validation pending
- `DELIVERY_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`
- `STAFF_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`
- `SETTINGS_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`
- `STORAGE_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`
- `EXPENSES_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`
- `CUSTOMER_PRODUCT_CUTOVER_PENDING_DEVICE_VALIDATION`

## Final decision

`FIREBASE BUSINESS BACKEND SOURCE DECOMMISSION: COMPLETE`.

Next step: complete the separately tracked authenticated/device/Play/FCM validation gates and historical import dry-runs from approved exports. These validation activities do not reintroduce Firebase business SDKs or Functions.

## 2026-10-04 Samsung deployment checkpoint

Device: Samsung `SM-A366B`, serial `RRCY5099DHB`, connected over USB ADB. Owner `com.aesprt.aquahub` debug APK install and launch passed. Customer `com.aesprt.aquahub_customer` release compilation and bundle generation passed with `versionCode = 3` / `aqc-1.0.2`; the workspace has no release signing configuration, so the AAB requires manual Play Console upload/signing and cannot replace the installed Play-signed package directly. No uninstall or destructive package-state change was performed. Customer workflow/offline validation therefore remains pending.

## 2026-10-04 authenticated validation checkpoint

`tools/supabase-migration/validate-backend.mjs` passed with disposable hosted fixtures and cleaned its exact fixture organizations/Auth users. Orders passed all nine checks, including foreign-customer ownership rejection and parallel stock-one safety. A targeted follow-up verified stock-one final balance zero, one deduction movement, and one order. Staff/settings passed OWNER/MANAGER role and station authorization, deactivation, and direct escalation denial checks. Payments passed the four authenticated receipt/settlement/idempotency/over-settlement checks. Subscriptions passed authenticated trial/entitlement checks but correctly reported `external_play: BLOCKED_BY_GOOGLE_PLAY_TEST_CONFIGURATION` and `ownership_conflict: REQUIRES_REAL_PURCHASE_TOKEN`.

The outbox scheduler was then invoked through the secret-protected hosted dispatcher and returned a bounded success summary with zero claimed rows. A server-only public RPC wrapper migration was required because the Edge Function cannot call the unexposed `app_private` schema through PostgREST. No notification was sent and no Firebase data or Storage object was deleted.

Hosted `supabase db lint --linked`, `supabase migration list --linked`, and `supabase db push --linked --dry-run` passed with the database up to date. Owner/customer Gradle validation, Firebase Functions build, and migration-script syntax checks also passed in this checkpoint. Deno was not installed locally, so standalone `deno check` was not run; deployed Edge Functions are listed active by Supabase.
