# Owner Feature Matrix

## Phase 3 evidence update — 2026-10-12

This update supersedes prior blanket “NOT VERIFIED” wording for the core disposable offline transaction and expense repository paths, while leaving untested cases explicit. Overall release remains NO-GO.

| Workflow | Actual Phase 3 verification | Status / remaining evidence |
|---|---|---|
| Owner sign-in and grant | Existing disposable owner signed in through the QA Android Supabase client; server-issued grant/lease was fetched and verified before going offline. | Core owner path PASS. Signup/recovery service contract was exercised separately against local Auth/Mailpit; production email policy remains BLOCKED. Expired/revoked grant, account switch and provider reauth remain NOT VERIFIED. |
| Email signup and recovery | Local Auth signup returned a session and `email_confirmed_at` immediately because `enable_confirmations=false`; password-recovery mail was delivered to Mailpit, redirect preserved `aquahub://auth/recovery`, password update and new login succeeded. | Disposable behavior PASS. Production config is unavailable; local auto-confirm may not meet the stated email-verification policy. AUTH-01 remains a P1 gate. |
| Offline cash walk-in POS | Real QA app created a ₱25.00 walk-in cash sale in airplane mode from a signed lease and immutable product/price snapshot; persisted receipt, tender ₱50.00/change ₱25.00, operation UUID/digest and item lines in Room. | Core path PASS through repository/worker; repeated UI taps, OS reboot, expiry/revocation and full conflict UX remain NOT VERIFIED. |
| Restart and sync | Force-stop/recovery retained the Room transaction. Real Edge/Postgres committed after connectivity returned; proxy held/dropped successful response; retry returned canonical order. | PASS for tested process-stop/timeout/replay scenario. Exactly one server order, PAID cash payment and inventory movement; Room COMMITTED; confirmed report included once and pending returned to baseline. |
| Inventory reservation | Offline lease holds inventory; absolute stock setter now compares expected version and uses named unique constraint in local follow-up migration. Reservation floor rejects stock below held amount. | Local setter and online POS contention pass; complete browser/refund/cancel/device race matrix remains open (SYNC-02 P1). |
| Browser/mobile ordering | A browser-shaped `guest-order` request passed through the disposable Edge endpoint and PostgreSQL, created one `CUSTOMER_WEB` order against unreserved stock, and an identical retry returned the canonical order. A second request with all stock reserved was rejected by the database floor and, after the local error-mapping fix, returned HTTP 409 without an order or balance change. | Edge/Postgres path PASS. Actual website UI → Owner queue/realtime/status, browser-vs-lease concurrent race, and customer tracking flow remain NOT VERIFIED. WEB-01's original description is absent from available audit history; see readiness report. |
| Financial reports | Initially empty payment cache regression hydrates authoritative receipt and completed refund; E2E offline sale moves from pending to confirmed exactly once in report/dashboard. | FIN-01 FIXED AND VERIFIED locally. Production ledger comparison remains NOT VERIFIED. |
| Expenses | Real Android `ExpenseRepository.refreshExpenses` fetched a server-only row, mapped it to Room, updated totals exactly, and did not duplicate on repeat. A server-side edit then converged to Room with updated centavos and description. | EXP-01 PARTIALLY VERIFIED; only one emulator, no second Android device/outbox conflict/large-history pagination. |
| Account deletion | Reauthentication and original UID binding code exists. | Unit policy verified; no destructive auth provider execution. |
| Billing | Subscription code and RTDN implementation exist. | Three prior unbound purchase hash groups remain unresolved; no token or entitlement changed. |
| QA package context | `ExampleInstrumentedTest` compares runtime to variant BuildConfig application ID. | 30 connected QA instrumentation tests passed on current API 35 emulator. |

The Android E2E uses the real QA repositories, Room, WorkManager, Supabase client, local Edge function and disposable PostgreSQL. It does not mock every backend response. The full failure-injection list and exact command outcomes are in `OWNER_TEST_RESULTS.md`.


## Historical remediation snapshot — 2026-10-11 (superseded by Phase 3 evidence above)

The approved OFF-03 work adds cash-only offline POS, server-reserved inventory leases, signed device/user/org/station authorization, immutable local receipts, idempotent server reconciliation, and pending-cash reporting. Verification includes 188 Production and 188 QA JVM tests, 29/29 QA instrumentation tests on Samsung SM-A366B Android 16, focused receipt UI tests on Pixel Tablet API 35, and 221/221 disposable Supabase SQL tests. Full app-to-Edge end-to-end and adversarial scenarios remain NOT VERIFIED; see current P1 disposition in `OWNER_PRODUCTION_READINESS.md`.

| Workflow | Updated implementation evidence | Current verification |
|---|---|---|
| Google account deletion | `data/supabase/auth/SupabaseAuthDataSource.kt`, `data/repository/SupabaseAuthRepository.kt`, `feature/settings/SettingsViewModel.kt` | Same authenticated UID is required before deletion invocation; unit policy tests pass. Live Google identity flow and destructive deletion remain untested. |
| Backup / restore | `AndroidManifest.xml`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | Backups disabled; shared preferences, databases, files, root and external domains excluded for cloud backup and device transfer; instrumentation source compiles. Runtime backup/restore is NOT VERIFIED. |
| Release backend isolation | `app/build.gradle.kts` | Release variant pre-build tasks validate HTTPS and expected production/QA host; production pre-build passed, synthetic HTTP config failed closed, and release bundle task graph includes the validator. |
| Offline session / old outbox | `data/repository/SupabaseAuthRepository.kt`, `sync/LocalAccountScopeManager.kt`, `data/local/dao/SyncDao.kt` | Cached restore requires a valid restored Supabase session and matching UID; legacy rows are requeued only after authenticated server profile maps to exact local Firebase UID. Policy tests pass; offline process-death/refresh and Room upgrade scenarios are not run on device. |
| Reports | `domain/usecase/analytics/AnalyticsUseCases.kt`, `data/repository/RoomPaymentRepository.kt` | Analytics refresh authoritative orders/receipts and completed refunds before totals. Regression asserts ₱100 net from a ₱125 receipt less ₱25 completed refund on an initially empty payment cache. No production report comparison was run. |
| QA context fixture | `src/androidTest/.../ExampleInstrumentedTest.kt` | Compares runtime package to variant `BuildConfig.APPLICATION_ID`; both QA and Production instrumented sources compile. Connected run unavailable. |

The approved OFF-03 scope is implemented locally; no production migration/deployment occurred. Price/catalog conflicts preserve the original receipt and require owner review. Offline refunds/cancellations and electronic tender remain unavailable by design.

Scope is the current modified Owner source tree. `IMPLEMENTED` means a source path exists; it does not imply end-to-end production verification. Runtime status is listed separately.

| Feature/workflow | Source evidence | Status | Verification / gap |
|---|---|---|---|
| Sign-in/session | `feature/auth/`, Supabase auth repository/data source | PARTIAL | Email/Google/phone paths exist. Offline cache fallback now requires a restored valid Supabase session and exact owner UID; policy tests pass. Production identity/OTP/session expiry and device process-death flows remain unverified. |
| Email confirmation/password recovery | `SupabaseAuthDataSource.kt`, `PasswordRecoveryActivity` | NOT VERIFIED | Signup assumes session mode; production confirmation settings unavailable. Recovery uses exported custom URI, not verified HTTPS link. |
| Owner onboarding / org and station provisioning | onboarding, station setup and Supabase RPCs | PARTIAL | Source routes exist; idempotency, restart, duplicate-account and new-device flows not tested. |
| Multi-station management | station/profile/manage-stations feature and tenant-scoped policies | PARTIAL | Present. No live multi-tenant JWT isolation or station-switch queued-work test. |
| Dashboard | dashboard feature and Room report repository | PARTIAL | Pending offline cash is explicitly separate from server-confirmed revenue; authoritative paid payment/refund hydration tested. Production comparison and freshness matrix remain open. |
| Walk-in POS | `domain/usecase/order/OrderUseCases.kt`, `data/repository/RoomOfflinePosRepository.kt`, `sync/OfflinePosSyncCoordinator.kt`, `data/offline/` | CORE E2E PASS / P1 NOT VERIFIED | Cash-only offline pickup uses immutable signed lease snapshots and durable Room operations; the real QA Android→Edge→PostgreSQL path survived force-stop and response loss, replayed exactly once, and updated confirmed reporting once. Revocation, clock rollback, reboot, price conflicts and two-device races remain release blockers. |
| Browser mobile ordering | website `/s/`, `guest-order` Edge Function, station catalog | EDGE→POSTGRES PASS / WEBSITE FLOW NOT VERIFIED | Guest ordering is intended browser flow; no customer APK required. Disposable Edge create/replay and reservation-floor 409 are verified. Actual website UI routing/tracking and Owner queue freshness remain unverified; see P1 SYNC-04 and historical WEB-01. |
| Orders, queue, details, status | `feature/orders/list/OrdersViewModel.kt`, `sync/SupabaseSyncWorker.kt`, Supabase order RPCs/Room | PARTIAL / SYNC-04 P1 | Queue refresh is triggered by initial flow setup/filter or station changes and manual refresh. Periodic work has no station ID; there is no lifecycle-resume pull or Realtime subscription. A missed FCM can leave a browser order absent until a manual refresh or ViewModel recreation. |
| Payments/refunds | payment repository, manual payment methods, server order/payment functions | PARTIAL | Manual confirmation path exists; provider settlement/refund automation not established. No real/test Play purchase or financial mutation. |
| Pricing / discounts / tax | order and product data model | PARTIAL | Server centavo fields and order RPC snapshot present; no complete evidence for configurable tax or all promotions. Avoid assuming tax is supported. |
| Inventory/catalog | product/inventory repositories, `set_inventory_quantity` CAS RPC and reservation migration | PARTIAL / SYNC-02 P1 | Absolute target + expected revision rejects stale writes; reservations reduce allocatable quantity and direct stock decreases are guarded by a trigger. Disposable SQL tests pass; all existing writer paths and multi-device browser/order race coverage remain NOT VERIFIED. |
| Stock transfers/container tracking | inventory-related screens and schemas | PARTIAL | Some code exists, but full lifecycle and tenant isolation not exercised. |
| Low-stock notifications | notification/sync path | NOT VERIFIED | No end-to-end alert delivery test or notification permission flow. |
| Customers | customer repository/details | PARTIAL | CRUD paths present; stale-cache UX and privacy/isolation tests remain open. |
| Staff, roles, invitations | staff feature and `staff-admin` function/RPC | PARTIAL | Server-backed code/policies exist; invitation acceptance and role escalation tests absent. |
| Riders/delivery | rider screens and delivery functions/storage | PARTIAL | Assignment/status path exists; cross-role and proof upload checks untested. |
| Expenses | Room expense repository, durable sync outbox, Supabase write and remote hydration | PARTIAL | Offline durable save/hydration paths exist. Cross-device consistency, retry, pending conflict, and report totals need dedicated disposable integration coverage (EXP-01). |
| Analytics and financial reports | RoomReportRepository and remote hydration | PARTIAL | Server-backed paid receipts/refunds hydrate before authoritative totals. Pending offline cash is shown separately from confirmed revenue until committed. Unit and connected regressions pass; production comparison remains unverified. |
| Notifications | FCM plus local routing | PARTIAL | Firebase remains for FCM; background delivery and routing not tested. No Firebase Auth/Firestore app path found in active Owner flow. |
| Offline sync | Room v22, durable sync/outbox, WorkManager and `OfflinePosSyncCoordinator` | PARTIAL / P1 release gate | v21→v22 migration preserves queued operations; offline cash receipts are durable and pending revenue separate. SQL/unit/device contracts pass. Full offline/reconnect/reboot/revocation and tenant E2E remains NOT VERIFIED. |
| Subscription tiers | Free/Starter/Business/Pro catalog and entitlement checks | PARTIAL | Live Supabase prices: Free ₱0; Starter ₱299/mo / ₱2,870.40/yr; Business ₱699/mo / ₱6,710.40/yr; Pro ₱1,299/mo / ₱12,470.40/yr. Actual Play listing/offer configuration unverified. |
| Subscription purchase/restore | Billing Library 9.1.0, server verify and RTDN | PARTIAL | Source has dynamic offer selection and server verification. No Play test transaction; 3 historical unbound hashes need reconciliation. |
| Settings/account deletion | SettingsViewModel + `delete-account` Edge Function | FAIL release gate | Identity mismatch could delete wrong account after Google reauth (SEC-01); deletion sequence may partially complete (SEC-03). |
| Printer/receipt | receipt/printer settings and rendering | PARTIAL | No physical printer compatibility test, receipt amount/tax legal review, or peripheral matrix. |
| Exports | report/export UI source | NOT VERIFIED | No exported artifact validation or large-data test. |

## Critical path coverage

| Critical path | Source complete? | Automated coverage | Release evidence |
|---|---|---|---|
| Sign in → owner profile → station | Partial | Unit/limited device tests | No production-auth or provisioning E2E |
| Browser order → correct station → owner status | Present | Disposable guest-order Edge→Postgres create and duplicate replay; reservation-floor rejection | Website UI→Owner queue/realtime/status still not verified; no concurrent lease race |
| POS sale → payment → stock ledger → receipt | Partial | Inventory/order tests exist | Online only; no full transaction E2E |
| Offline queue → reconnect → exactly-once persistence | Partial | Queue tests exist | Legacy quarantine + conflict path unverified |
| Payment data → financial report | Partial | Revenue unit coverage | Fresh-device undercount risk unresolved |
| Subscription purchase → verify → entitlement | Present | 21 selected billing/RTDN tests included in 84 backend tests | Play configuration, live identity binding and restoration unverified |
| Account deletion | Present | No destructive E2E | Wrong-account identity risk |
| Staff/rider permission boundary | Present | No role matrix E2E | Live tenant/role JWT testing absent |
| Logout/account switch with local queue | Partial | Queue recovery tests | Cross-account leakage/recovery unverified |
| Tablet/accessibility workflow | Present | Sparse Compose tests | Device/font/TalkBack matrix absent |
