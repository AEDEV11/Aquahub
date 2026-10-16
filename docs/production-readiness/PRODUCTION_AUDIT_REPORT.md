# AquaHub Owner production readiness audit

## Subscription pricing and Free-screen crash follow-up — 2026-10-10

Overall remains **NO-GO**. Local billing changes now re-query Google Play
`ProductDetails` when the plan-comparison screen opens and during refresh. The
app still displays no hardcoded fallback price. The screenshot's “not
configured” result is consistent with an empty or ineligible Play product
query, but the exact product/base-plan state for the Play-distributed package
cannot be established from the `.qa` build; Console/license-tester verification
remains outstanding. Sanitized query diagnostics now expose returned
product/base-plan/period and unfetched product/status IDs without account,
purchase-token, or price logging.

For the reported Free-plan Walk-in Sales and Analytics crash, no app stack
trace or authenticated reproduction was available. Source inspection found
uncaught observer failures and Analytics' indefinite loading fallback. Local
retry/error handling is implemented and its focused QA test classes passed
42/42; `assembleQaDebug` passed. A temporary API 35 emulator started the QA
process, then Android System UI stopped responding over its notification
permission prompt. This is not evidence of an AquaHub crash, nor successful
screen-flow verification. The production bug register adds AH-P1-14 as open
until authenticated device/API testing confirms the reported paths.

## Free subscription expiry fix — current update, 2026-10-10

**Overall decision remains NO-GO.** The user-reported Free `EXPIRED` symptom is
confirmed as a client status-decoding defect: Supabase uses a valid `FREE`
subscription status, which the Android enum omitted and mapped to the
fail-closed `EXPIRED` fallback. The resolver also only recognized the legacy
Free/`ACTIVE` combination. A privacy-safe hosted aggregate found one
Free/Free subscription, with no identifying values read; there are no product,
order, payment, or inventory-movement rows. Codex did not modify the account.

The local correction adds `SubscriptionStatus.FREE` and resolves it as
perpetually active only when the plan code is Free; legacy Free/`ACTIVE` remains
compatible and unknown statuses remain expired. The focused QA unit class
passed 29/29 and `assembleQaDebug` succeeded. The separate QA APK was updated
in place and launched on SM-A366B; no filtered AndroidRuntime fatal exception
was found. The original Owner installation was not touched. The status screen
was not independently captured, the distributed app was not changed, and
authenticated product/POS workflows remain unverified. The full QA lint was
interrupted after >50 minutes and has no result. Finding AH-P1-13 is added;
release remains **NO-GO**.

## Group 5 FREE-plan validation update — 2026-10-10

Overall decision remains **NO-GO**; current finding counts are P0 ×5, P1 ×14,
P2 ×6, P3 ×0 (AH-P1-14 added after the latest user-reported screen crash).
The explicitly authorized `20261010100000_free_plan_basic_inventory`
migration is the only remote change in this update and is recorded on project
`pfkxrggexueltytxtqil`. Hosted read-back confirms Free basic inventory on,
container tracking off, unchanged Free quotas, and unchanged paid-plan
catalog. The initial post-test aggregate had no rows. The later read described
in the latest update found one account/workspace and one Free subscription,
but no products, orders, payments, or inventory movements. Codex did not
create or modify that account/workspace.

The new `com.aesprt.aquahub.qa` APK built, installed, and rendered onboarding
and login on Samsung SM-A366B / Android 16. Production-debug and QA-debug JVM
suites passed 123/123 each; focused legacy-outbox instrumentation passed
2/2. This covers the quarantine DAO only. The original Owner installation was
not launched, changed, or cleared, and its existing local queue remains
uninspected. The user subsequently reported account creation and Free
selection; the latest aggregate confirms one Free subscription. Product/stock
writes, POS, revenue, post-fix screen state, and hosted role tests remain NOT
RUN. See `FREE_PLAN_PRODUCTION_VALIDATION.md` for the full matrix.

## Group 4 current status override — 2026-10-09

Decision remains **NO-GO**. Current findings: P0 ×5, P1 ×12, P2 ×6 (Group 4 added AH-P1-12; details are in the bug register). Local verification: fresh isolated replay of all 54 migrations and 12 SQL files / 84 assertions PASS; local SQL lint passes with one unused-parameter warning; Deno 34/34 PASS; all 22 function TypeScript files typecheck. Owner/Customer local Gradle results are recorded in `TEST_MATRIX.md`. Play Console now confirms both apps are on Closed testing and Owner Production is inactive; exact Play-served artifacts/endpoints remain unverified. Hosted REST/JWT/E2E, recovery-grade backup/restore, customer device coverage, licensed billing tests, deployment, and tablet screenshots remain unverified. The linked project remains production-unknown; no remote mutation occurred.

Historical Group 4 audit snapshot: 2026-10-09, updated after approved local remediation. Group 5 adds one authorized hosted catalog migration and device QA evidence as described above. No business rows, purchases, Play Console settings, or backend functions were changed. This is not a claim that every runtime path was exercised.

## Decision

**NO-GO for Google Play Production.** The five tracked P0 risks cover direct authenticated order/payment mutation, delivery walk-ins incorrectly paid before collection, incomplete server entitlements, cross-tenant idempotency disclosure, and local account cross-use. Group 5 deployed only the Free basic-inventory catalog correction; the security/payment fixes remain local and the overall entitlement finding is still open pending role/API tests. The paid-order refund/cancellation path is implemented and isolated-tested locally, but deployed compatibility and actual cash/provider completion are not verified. Tablet layouts, licensed Play lifecycle, hosted adversarial authorization, and release artifact/policy validation remain release gates.

## Executive summary

In the current checked-out source, both Android apps use Supabase for business authentication/data paths; Owner also uses Firebase for FCM, Analytics, and Crashlytics, and Customer uses Firebase Messaging. This is a large, dirty, uncommitted Firebase-to-Supabase transition: Firestore rules/configuration remain, `AquaHubCustomer/firebase.json` still declares a `functions` source whose implementation/package files are absent from this checkout, and workspace-level instructions still describe Firebase/Firestore as live. A linked Supabase project has schema and active Edge Functions, but this audit did not verify that it is the production-of-record for installed users, that Firebase identities/business/order history were completely migrated, or that the release build points at the intended production project. Therefore Supabase is the execution path in this working tree; the deployed production backend and completed cutover are NOT VERIFIED. Treat the source/config/backend mismatch as a release blocker, not as proof migration is complete.

The source includes safety foundations: server-side order creation locks/checks stock and deduplicates operation IDs; payment RPCs use centavos and transaction locks; explicit Room migrations exist; release shrinking is enabled; and Play purchases are verified server-side. Approved local work adds UID-scoped sync/replay checks, stable walk-in retry identity, canonical order snapshots, FCM-triggered order refresh, serialized purchase-token ownership with encryption, verified-contact trial claims, refund/return ledgers, cancellation status decoding, least-privilege order/payment SQL, and an adaptive navigation shell. Fresh local SQL/Deno/Owner/Customer/device tests pass as recorded below. These are not deployed or fully end-to-end verified; production grants, backend lineage, billing configuration, schedule/reconciliation and full entitlement coverage remain open.

## Architecture and module inventory

| Area | Inventory / observed responsibility |
|---|---|
| Android build | AquaHub is an independent Gradle app, one `:app` module; Kotlin 2.4.20, AGP 9.4.1, Compose BOM 2026.09.00, Material 3 plus adaptive navigation suite, Navigation Compose 2.10.2, Room 2.8.5, Koin, WorkManager, DataStore, Billing 9.1.0, Supabase Kotlin 3.8.0; Java 17. |
| Android source | Core/common, Room entities/DAOs/database, design system, navigation, domain models/repositories/use cases, Supabase data sources/repositories, compatibility/migration paths, sync/outbox, and feature packages for auth/onboarding, dashboard, orders/POS, customers, products/inventory, riders/delivery, expenses, reports, staff, stations, settings, subscription, and sync. |
| UI routes | 27 `Screen` declarations (including `More`, an alias), 26 feature `*Screen.kt` composables, plus exported `PasswordRecoveryActivity`; the responsive matrix additionally covers the shell and non-route POS sheet. 23 Compose previews found. See `RESPONSIVE_UI_AUDIT.md` for the enumerated screen list. |
| Local persistence | Room database version 20, explicit migration path including `Migration_19_20`, no destructive fallback observed; DataStore stores session/context preferences. WorkManager queues network-constrained sync, with pending mutations in Room. |
| Remote backend | Workspace `supabase/`: 54 local migration files in the current checkout, SQL RPCs/RLS/storage policies, and Edge Functions. Eight linked functions were reported ACTIVE by read-only inventory; the current local tree has ten endpoints including guest ordering and Play reconciliation. Twelve SQL regression files are present. The linked project's production role was not established. |
| Related app | `AquaHubCustomer/` is a separate Gradle app; current source uses Supabase Auth/catalogue/orders and Firebase FCM. Its checked-out AGENTS context says workspace `supabase/functions` is active, while workspace-level instructions say Firebase Functions/Firestore are active. `firebase.json` still declares a Firebase Functions source with no implementation/package in this checkout. |
| Runtime config | Supabase URL/anon key and Maps key are build-config inputs. No service-role credential was found in the Android build/source scan. Release build requires configured Supabase values. Secrets and local configuration values were not emitted. |

### Detailed owner source inventory

Discovery currently counts 200 Kotlin source files under the Owner app package, 32 JVM test sources, and 6 instrumentation test sources. The module/package ownership map is:

- `core/common` (5 files), `core/database` (1), `core/designsystem/components` (8), `core/navigation` (2), `ui/theme` (4); dependency injection: 7 modules (`DataStore`, `Database`, `Observability`, `Repository`, `Sync`, `UseCase`, `ViewModel`).
- Feature packages: auth/login, onboarding, recovery, registration, station setup, splash and subscription; customer list/detail; dashboard; expenses; inventory; location picker; main shell; orders/list/detail/walk-in; products; reports/export; riders; settings/delivery/profile/subscription; staff; station management; sync status. Twenty-three ViewModels were found: Login, Onboarding, Splash, CustomerDetail, Customers, Dashboard, Expenses, Inventory, LocationPicker, MainContainer, OrderDetail, Orders, WalkInSale, Products, Reports, Riders, Settings, DeliverySettings, StationProfile, SubscriptionBilling, StaffManagement, ManageStations and SyncStatus.
- Domain: 16 use-case files grouped under analytics, auth, customer, expense, export, inventory, location, order, product, report, rider, station and subscription; 9 model files; 18 repository/port/support files (Auth, Customer, Expense, Google Play Billing, Image Storage, Inventory, Location, Order, Payment/PaymentCache, Product, Report, Rider, Staff, Station, SubscriptionPlan/Subscription, WorkspaceProvisioning exceptions); 3 export contracts/content files.
- Data: 17 repository-area files (Room auth/customer/expense/inventory/order/payment/product/report/rider/station/subscription-plan/subscription; Supabase auth/staff; Google location; Google Play billing; paging mapper); 21 Supabase data files across client/device token, account, auth/redirect/phone normalization, catalog, delivery/rider, expense, inventory/container, order, payment, settings, staff, storage/image, subscription and workspace. Room includes 4 DAO files, 4 entity files, 2 mapper files, a seeder, and the version-20 database.
- Runtime/sync/export: `MainActivity`, `AquaHubApplication`, exported `PasswordRecoveryActivity`, Firebase `AquaHubMessagingService`, `SupabaseSyncWorker`, `SupabaseSyncCoordinator`, `SupabasePendingMutationWriter`, `SyncQueueWriter`, `SyncScheduler`, `ConnectivityMonitor`, `ExistingLocalDataMigrationPlanner`, `RemoteOrderRefreshCoordinator`; CSV, Excel, PDF report/customer statement exporters and shared export utilities.
- Backend integration (current Group 4 snapshot): 54 SQL migrations; ten function entrypoints (`delete-account`, `dispatch-notification-outbox`, `guest-order`, `play-rtdn`, `reconcile-play-subscriptions`, `send-fcm-notification`, `send-sms`, `staff-admin`, `subscription-trial`, `verify-play-purchase`); 12 SQL test files and 34 Deno tests. All 22 TypeScript function/test files typecheck. The customer app is a separate Gradle build, not an Android module of Owner; it shares this backend in current source. `AquaHubCustomer/functions` is declared by retained Firebase CLI configuration but absent from the checkout, whereas current app context identifies workspace `supabase/functions` as the active backend.

### Real data flow and authority

Compose screen → feature ViewModel → domain use case → repository/data source → Room for cached state/outbox and Supabase RPC/PostgREST/Storage for authoritative server state. WorkManager replays queued mutations and refreshes data. Firebase Messaging wakes sync and displays notifications; it is not the order database. Subscription purchase callback → Supabase Edge Function → Google Play Developer API verification → organization subscription/tokens/history tables; RTDN function reconciles provider events. Local source now writes the server-returned order snapshot, uses a stable checkout operation identity, requests remote order refresh after FCM-triggered sync, and prevents account-mismatched outbox replay. End-to-end convergence under network loss, duplicate delivery, account switching, and multi-device concurrency is still unverified.

This describes the executable path in the current checkout, not a verified production cutover. Firebase deploy descriptors, legacy rules, workspace instructions, and Supabase sources do not agree; backend identity, historical-data migration completeness, and release endpoint/project alignment must be proven before shipping.

### Source-of-truth matrix

| Capability | Current checked-out implementation | Production authority / cutover status |
|---|---|---|
| Authentication | Supabase Auth in Owner and Customer app source; Firebase messaging remains integrated. | NOT VERIFIED for installed/released users; legacy identity mapping and the actual release endpoint are unknown. |
| Profiles | Supabase `profiles` rows keyed to the Supabase Auth identity; Room caches related app state. | NOT VERIFIED; no complete Firebase-to-Supabase identity reconciliation evidence. |
| Organizations and stations | Supabase Postgres/RPCs are the remote path; Room provides local cache/outbox state. | NOT VERIFIED; linked project presence does not prove it serves production accounts or contains the complete migrated history. |
| Customers | Supabase catalog/customer repositories with Room caching and sync. | NOT VERIFIED; no cross-provider customer counts, normalized identity reconciliation, or production binding evidence. |
| Products | Supabase catalog/repositories and station-scoped inventory data; Room cache/outbox. | NOT VERIFIED; remote release target and historical stock reconciliation are unknown. |
| Orders | Supabase RPC/PostgREST in current app source; Room stores local snapshots/pending work. | NOT VERIFIED; linked schema is behind local source and migration history diverges. |
| Payments and balances | Supabase receipt RPC/table and centavo values in current source; Room cache. Group 1 payment guards exist locally only. | NOT VERIFIED; linked grants/payment state have not been updated or verified against production. |
| Inventory | Supabase transactional movement/order RPC path with Room cache/outbox. | NOT VERIFIED end-to-end; linked cutover, retries and cross-device convergence are unproven. |
| Staff and rider access | Supabase membership/station roles plus `staff-admin` Edge Function; current Group 1 limit checks are local source. | NOT VERIFIED; linked function version, RLS matrix and production project association remain unproven. |
| Subscriptions | Google Play is purchase authority; Supabase verification functions and subscription rows supply app entitlements. | NOT VERIFIED; Play Console linkage, deployed function version, RTDN and lifecycle results are unavailable. |
| Reports | Derived through current app repositories from Supabase-backed business data and local cached state. | NOT VERIFIED for historical parity, paid-only sales accounting, or production dataset completeness. |
| Notifications | Firebase Cloud Messaging is the delivery transport; Supabase Edge Functions/outbox are present in current checkout. Firebase deploy config also points to a missing Customer Functions source. | NOT VERIFIED; active production trigger/dispatch path and cross-app Firebase/Supabase cutover are unresolved. |

### Production-critical workflows

Authentication/provisioning and business/station selection; staff roles; subscription purchase/restore/renew/cancel; product/customer CRUD; atomic order creation, inventory reservation, cancellation and restock; payment recording/receipt totals; rider assignment/delivery and payment proof; customer balance; expenses/reports; FCM and sync/outbox retry; data export/account deletion; and navigation/responsive operation on phones/tablets.

## Findings and severity

See the complete numbered register in `PRODUCTION_BUG_REPORT.md`. The current Group 4 register is P0 ×5, P1 ×12, P2 ×6, P3 ×0; code implemented locally does not close production findings. Group 1 order/payment restrictions, proof checks, plan/limit guards and authorized idempotent replay are preserved. Group 2 adds auditable refund/cancellation/physical-return ledgers, guarded transactional RPCs, and Owner/Customer status integration. Group 3 adds server-verified Play purchase ownership/state, encrypted reconciliation tokens, and verified-contact trial claims. Group 4 adds the local refund obligation report and OIDC caller-authentication correction. Linked schema and app endpoints remain unverified for production and unchanged. Migration/source lineage, hosted REST authorization, backend cutover, residual trial identity weakness, unscheduled reconciliation, secret rotation, retry classification, device/account isolation, external refund verification, and adaptive tablet layouts remain open.

### Group 1 — initial remediation snapshot (historical; superseded where noted below)

Implemented in `supabase/migrations/20261009093931_group1_backend_security_payment_entitlements.sql` and `supabase/functions/staff-admin/index.ts`. The migration centralizes effective plan resolution (expired or invalid paid rows fall back to Free), adds write-time feature/limit guards, revokes authenticated direct DML on orders/order items/payments while retaining only the `station_note` column update, restricts payment recording to authorized station operators (or the assigned delivered-order rider with an owned proof object), rejects unverified provider settlements, keeps imported orders pending regardless of client-supplied paid state, and authorizes the `create_order` idempotent-return path before revealing an existing order. A guard rejects cancellation/rejection of orders with paid receipts until a refund is reconciled.

The migration applied cleanly to a disposable, schema-only scratch database. Eight SQL test files passed (37 aggregate pgTAP assertions); a direct privilege query confirmed no `anon` order/payment DML, no authenticated order/item/payment DML, and no authenticated access to private quota bookkeeping. The sole retained authenticated order-column update is `station_note`. Deno is unavailable, so the Edge Function received syntax-only `node --check`, not Deno type/test validation.

At the Group 1 snapshot, the read-only linked migration list appeared to show six local-only migrations and linked-only `20261009042725`. The latest Group 2 query supersedes that observation: five pre-existing local-only versions, shared `09043510`, two additional local candidates, and no current local or linked `09042725`. No history repair or deployment was attempted. The Group 1 snapshot also preceded the Group 2 refund workflow described below.

Version-control boundary: the workspace has two Git roots (`AquaHub/` and `AquaHubCustomer/`) and no Git root at the workspace parent. `supabase/` and `docs/production-readiness/` are outside both app repositories. The local Group 1 SQL/function changes and audit updates are therefore not tracked by either app repo and have no commit/provenance chain in this checkout; this must be resolved in the deployment/source-management process before release.

## Business integrity, offline and sync

Positive code evidence: `create_order` uses centavos, a transaction, stock row locks, stock-availability checks and an operation-ID lookup; the inventory ledger has unique movement semantics; `record_payment` locks an order and derives paid state from receipt totals. The new local migration adds server-side plan/limit guards, corrects delivery collection and receipt rules, prevents client-forged paid imports, and closes direct order/payment DML. Scratch SQL tests cover Free-plan station/order feature limits, backdated-import quota protection, unpaid delivery/client-account orders, duplicate receipts, overpayment, cross-tenant/customer denial, idempotent order replay authorization, expired subscriptions, and rider proof. The linked Supabase schema still has the previously observed broad grants and payment behavior because no deployment occurred; its production role is not established. Paid-order cancellation is deliberately blocked until a refund is recorded, leaving the refund workflow open. Outbox replay checks the active Supabase UID against a DataStore-bound cache owner and blocks account switching with outstanding work. Transient HTTP/PostgREST 5xx classification remains a source gap.

No business records were selected or changed. No two-device, network fault, duplicate-delivery, force-stop, account-switch, or live order/payment scenario was executed. Thus end-to-end consistency is not demonstrated.

## Google Play Billing

Billing Library 9.1.0 is declared. Owner displays eligible Google Play returned ProductDetails/offer pricing and terms without a local paid-price fallback; local intended catalog values are Starter/Business/Pro ₱199/₱499/₱999 monthly with annual 20% discount, not Play Console evidence. The current local verifier uses subscriptionsv2, checks authenticated Owner/token binding, stores encrypted reconciliation tokens, and applies token/subscription/history state atomically before acknowledgement; RTDN re-fetches provider state. Trial claims use verified-contact HMACs, with historical UID-only claims still unresolved. Fresh Deno tests pass 23/23 and all 10 function entrypoints typecheck, but no function is deployed. Play Console IDs/base plans/offers/prices, service-account permissions, RTDN delivery/schedule, licensed lifecycle, release package alignment and live behavior remain NOT VERIFIED. No real or licensed test purchase was made.

See `GOOGLE_PLAY_BILLING_AUDIT.md` for the scenario matrix and manual license-test procedure.

## Security and privacy

The strongest confirmed concern is not a speculative client issue: read-only inspection of linked Postgres grants/policies shows authenticated table DML for payments and orders with `can_operate_station` RLS, while the intended constraints live in SECURITY DEFINER RPCs. Because assigned staff/rider roles are included in station operation authorization, the broad grants can expose high-impact payment/order field writes to lower roles. A separate local identity-isolation defect can reveal cached station/order/rider data and replay pending mutations after a second owner signs in. Service-role credentials remain server-side in reviewed sources; Android uses the public anon key as expected, subject to RLS. Manifest permissions observed: Internet, fine/coarse location, network state, notifications. Backup is enabled but explicit rules exclude sensitive local stores; exported Main and recovery Activities need deep-link/intent validation in device testing. No dynamic penetration test was run.

See `SECURITY_AUDIT.md`. No destructive or disruptive production tests were performed.

## UI/UX, responsiveness and accessibility

The app uses Compose/Material 3 and has 23 previews. MainContainer now uses `NavigationSuiteScaffold` to select an adaptive navigation presentation while retaining Navigation Compose. This only adapts the shell: all route-specific tablet workspaces (POS, order/customer list-detail, inventory, reports, settings) remain absent, and no screenshot/device validation exists. The all-screen tablet release gate therefore still FAILS. The project skill `.agents/skills/aquahub-adaptive-ui/SKILL.md` and its four references now exist; the `android` CLI does not provide `skills add`. Screenshots, font scaling, TalkBack, keyboard/IME, landscape, split-screen and resizing remain NOT TESTED. No Navigation 3 migration was introduced.

## Group 1 tests and validation (historical baseline)

The following results capture the earlier Group 1 snapshot. The Group 2 addendum later in this report and the current `TEST_MATRIX.md` are authoritative for newer test counts, Deno availability, migration history, and device availability.

- Previous full validation: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:bundleRelease --console=plain --no-daemon` — **BUILD SUCCESSFUL**; 108 JVM tests, 0 failures/errors; lint had 0 errors and 96 warnings; release bundle completed with shrinking. The command targeted an already-modified tracked AAB; do not treat that artifact as a clean candidate.
- Final post-cleanup `./gradlew :app:compileDebugKotlin --console=plain --no-daemon` — **BUILD SUCCESSFUL in 3m 4s** after adaptive navigation dependency cleanup; Kotlin deprecation/nullability warnings remain.
- Final focused `:app:testDebugUnitTest` command over six classes — **46 tests passed, 0 failures/errors**. Covered subscription cancellation state, account-scope policy, walk-in operation identity, canonical order snapshot, remote order refresh coordinator and inventory invariants.
- Local Supabase SQL suite — **8 files, 37 pgTAP assertions passed** across order, inventory, subscription/notifications, verification-first auth, walk-in payment, RLS/grants, container movement and staff/storage. Synthetic fixtures roll back. This is local DB evidence only.
- Final read-only `supabase migration list --linked` shows six local-only IDs (`20261009030621`, `20261009043510`, `20261009051935`, `20261009060109`, `20261009080803`, `20261009093931`) and linked-only `20261009042725` whose source is missing locally. No linked migration was applied. The new Group 1 migration was applied only to a disposable schema-only scratch database; SQL fixtures rolled back. Existing migration history was not repaired.
- Deno is unavailable, so the new Edge Function tests/typecheck/format checks were not run. `adb devices -l` reports no connected devices; no tablet AVD/screenshot run occurred.
- A release AAB exists at `AquaHub/app/release/app-release.aab` (about 15 MB). AAB generation is not Play Console validation; no upload, signing identity, 16 KB native alignment, Play pre-launch report, or install/smoke test was verified.
- 32 JVM test source files; 6 Android instrumentation source files; 8 SQL regression files; 2 Deno test files. Deno is unavailable; local Supabase DB is running and its 8 SQL files/37 assertions passed. No connected device is currently listed by ADB and no tablet AVD is configured. Instrumentation, Edge Function tests and screenshots were not run.
- Read-only linked Supabase inspection: 8 Edge Functions ACTIVE; current migration list shows six local migrations absent remotely and one linked migration without a local source file (details in `PRODUCTION_BUG_REPORT.md`). Group 1 SQL invariants ran only against an isolated schema-only scratch DB; production-backend identity/cutover was not verified.

The working tree contains many unrelated pre-existing modifications across both app repos. Local remediation touched that dirty checkout; changes are not committed or isolated in a clean branch. The earlier release build task targeted an already-modified tracked AAB path; its previous uncommitted bytes are not recoverable. No new AAB was built after the current source changes.

## External verification gaps

Play Console account/product/base-plan/offer and actual prices; Play App Signing and upload certificate; RTDN topic/subscription and Developer API service permissions; license purchase lifecycle; Play Data safety and privacy declarations; account-deletion URL and website correctness; review credentials; pre-launch report, Android vitals, policy alerts; remote backup/retention policy; RLS identity tests in isolated Supabase; and physical phone/tablet matrix. Target SDK 37 meets the currently published API 36 minimum for new apps/updates, but this alone does not prove compliance ([Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)). Billing 9.1.0 appears in [official Billing Library release notes](https://developer.android.com/google/play/billing/release-notes); Play Console product configuration remains unverified. Native 16 KB page compatibility needs separate validation ([Android 16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes)). Verify the public deletion path against [Google Play account-deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en-en).

## Group 1 initial remediation plan (historical; superseded by Group 2 status below)

At the Group 1 checkpoint, local fixes were not production-verified and the refund path was still pending. Group 2 has since implemented and locally tested the refund/cancellation path; remaining release sequence is (1) reconcile local/linked migration history and backend lineage, then obtain separate authorization before any production DB/function deployment; (2) run hosted/staging direct-REST and role/tenant tests, including the refund contract; (3) add fault-injected sync and account-switch integration coverage; (4) close trial eligibility abuse and run licensed Play tests; (5) complete adaptive layouts for all routes/components and run phone/tablet screenshots/workflow tests; (6) rebuild and inspect a clean signed release artifact and complete Console/policy checks. No production migration/function deployment, historical financial reconciliation, paid purchase, upload or publication was performed or authorized by source approval.

## Group 2 remediation and verification addendum — 2026-10-09

This records the Group 2 status checkpoint; the Group 3 addendum below supersedes its test counts, replay endpoint and current blocker count. Worktree was already extensively dirty in both Android Git roots; those changes and the existing modified AAB were preserved. No source was committed, pushed, deployed, uploaded, or used to mutate real business/subscription data.

- **Backend source of truth:** current Owner and Customer checkout source uses Supabase for business/auth/data workflows while Firebase Messaging remains. The actual Closed Testing/release backend and user/data migration are still NOT VERIFIED. Read-only linked schema inspection shows material grant/RPC drift; no linked writes occurred.
- **Migration history at that checkpoint:** the last read-only linked list showed five pre-existing local-only versions (`09030621`, `09051935`, `09060109`, `09080803`, `09093931`), `09043510` shared, and local candidates `09104237` and `09112029`; `09042725` was absent from that query and the checkout. Group 3 candidate `09124434` was created afterward and its linked presence was not rechecked. The Group 2 replay endpoint was superseded by the Group 3 clean replay below.
- **Refunds and compatibility at that checkpoint:** implemented integer-centavo refund reservation/event ledger, guarded partial/full request/complete/fail RPCs, safe paid-order cancellation, deferred physical return stock restoration, report netting, Owner refund controls, and masked Customer refund status while retaining the Customer's old cancellation RPC signature. The Group 2 suite passed nine isolated SQL files/43 assertions; separate PostgreSQL concurrency checks verified same-key refund idempotency and no over-refund. Actual provider/cash movement, hosted REST/JWT roles and older installed binary compatibility remained unverified.
- **Deno and database quality at that checkpoint:** an earlier Deno run passed 8/8; a repeat stalled before assertions. This was superseded by Group 3 Deno 23/23 and 10-entrypoint typecheck below. SQL lint exited 0 with one unused `p_payment_status` warning in `public.import_legacy_order`.
- **Android verification at that checkpoint:** Owner JVM 118/118 and Customer 21/21; instrumentation had not completed. This was superseded by the latest Owner 120/120, Customer 21/21 and 13/13 connected Owner instrumentation results below.
- **Remaining release status at that checkpoint:** P0 ×5 / P1 ×10 / P2 ×6. The current register is P0 ×5 / P1 ×11 / P2 ×6, including the unscheduled-reconciliation finding. Neither local code nor tests establish deployment or production closure. Decision remains **NO-GO**.

## Group 3 billing and final local verification addendum — 2026-10-09

This is the latest local evidence and supersedes older Group 1/2 test counts, Deno availability, and the isolated replay endpoint stated above. It does not supersede any production/deployment limitation.

- **Billing source remediation:** Owner billing still declares Billing Library 9.1.0. The UI now uses eligible Google Play ProductDetails/offers instead of fallback paid prices and displays introductory versus recurring price terms. Server source verifies purchases through `purchases.subscriptionsv2.get`; pending purchases do not grant access; the server checks authenticated Owner membership and Google obfuscated account identity; token binding, subscription state/history, and replacement lineage are persisted through service-role-only atomic RPCs. Purchase acknowledgement follows commit. RTDN re-verifies current provider state, sanitizes tokens before persistence, and uses idempotent events. Encrypted token storage, atomic HMAC verified-contact trial claims, and a reconciliation endpoint were added locally.
- **Catalog configuration:** local intended prices are Starter ₱199 / ₱1,910.40 annual, Business ₱499 / ₱4,790.40 annual, Pro ₱999 / ₱9,590.40 annual (annual values are 20% below twelve months), represented in centavos. These are not Play Console verified prices. Local `plan_features`/`plan_limits` configuration exists; the full plan × role × mutation matrix remains unverified against a hosted API.
- **Migration replay:** fresh localhost disposable DB replay reached `20261009124434_group3_subscription_security_and_catalog.sql`, after `20261009112029` and `20261009104237`. No linked history repair, SQL deployment, Edge Function deployment, or production data access occurred. The linked migration history was not rechecked after creation of `09124434`; production-of-record remains unknown.
- **SQL/Deno/Android:** 11 SQL files / 81 pgTAP assertions pass, including 27 billing-specific assertions with rollback-only Owner/trial/token fixtures and a synthetic historical token-scrub case. Fresh Deno 2.9.7 tests pass 23/23; all 10 local Edge Function entrypoints typecheck. `supabase db lint` exits 0 with one unused `p_payment_status` warning. Owner JVM passes 120/120; Customer 21/21; Owner connected instrumentation passed 13/13 on Samsung SM-A366B / Android 16 (API 36). The exact command/result is in `TEST_MATRIX.md`.
- **Refund reporting policy:** completed refunds reduce the net collection/revenue for their completion period; pending and failed refunds do not reduce sales and remain visible at order level as operational obligations. There is no aggregate pending/failed liability statement, and the sales report is not a cash-flow or balance-sheet report.
- **Remaining severity/release state:** NO-GO. The local work has not been staged, deployed, or production verified. Play Console product/price/offer setup, license lifecycle, RTDN delivery, scheduled reconciliation, old encrypted-token gaps, backend source-of-truth, full hosted entitlement/security tests, production compatibility, refund-provider/cash evidence, complete tablet experience, and fresh release artifact/policy verification remain outstanding. The bug register adds a P1 for unscheduled reconciliation; local source fixes do not close P0/P1 findings.
