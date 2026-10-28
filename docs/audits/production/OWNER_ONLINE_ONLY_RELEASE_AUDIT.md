# AquaHub Owner Online-Only Google Play Release Audit

**Audit date:** 2026-10-17 (Asia/Manila)  
**Candidate:** `com.aesprt.aquahub`, version code 12, `aq-1.0.7`  
**Backend:** Supabase production project `pfkxrggexueltytxtqil`  
**Verdict: NO-GO — Confirmed production-blocking defects.**

**2026-10-18 follow-up:** The owner reports subsequent sales, web ordering, account, subscription, and cancellation tests, and confirms version 13 is uploaded. A real version-13 fatal crash on return from Google Play was retrieved from Crashlytics and patched for version 14 (`aq-1.0.9`). See [the focused crash investigation](OWNER_PLAY_CANCELLATION_CRASH_20261018.md) for current checks and remaining Android runtime validation. The dated version-12 evidence below is retained as history; it does not approve either newer candidate.

## Executive result

The production flavor now compiles with offline POS disabled, and the new version-12 release AAB was rebuilt, signed, and verified after that change. Production and QA JVM suites each passed 198 tests. Static inspection confirms that the current production binary blocks offline authorization, stock leases, local offline checkout and background reconciliation while leaving the online order flow and unrelated background work enabled.

This is not a launch approval. No real online checkout was submitted or reconciled. On a follow-up phone session, the production-debug APK installed in place and launched; the registration first step rendered with no observed crash/ANR in the sampled log window. The minified production release APK was rejected by Android because its signer differs from the installed package, so that artifact remains untested at runtime; the tablet runtime remains unavailable. Play Console purchase, declaration, reviewer-access and upload-certificate checks were unavailable. A read-only comparison also found that the deployed account-deletion function does not perform the marketing-consent removal step present in the local handler. After the user explicitly reauthorized a production reset, all non-catalog application rows, Auth users, and Storage objects were deleted; see the verification below. No schema, migration, function, Auth/Storage setting, bucket definition, or Play resource was changed.

## Production data reset performed

The user explicitly chose a production wipe while preserving the subscription plan catalog. The backup check listed no physical backup and PITR was disabled. Before deletion, the current snapshot contained 3 Auth users, 212 application rows across 48 non-catalog `public`/`app_private` tables, 115 catalog rows, and 3 Storage objects across 3 buckets.

| Reset item | Result | Verification |
|---|---|---|
| Auth users and dependent identities/sessions | PASS — deleted | `auth.users`, identities, and session tables now contain 0 user rows. Auth schema migration history remains intact. |
| Non-catalog application data | PASS — deleted | All 48 targeted tables in `public` and `app_private` now contain 0 rows (pre-wipe total: 212). |
| Subscription plan catalog | PASS — preserved | `subscription_plans` 4 rows, `plan_features` 89 rows, `plan_limits` 22 rows (115 total) remain. |
| Storage objects | PASS — deleted through Storage API | 0 objects remain in `payment-assets`, `product-images`, and `station-assets`. Bucket definitions remain (3). |
| Schema and configuration | PASS — preserved | No migrations, schema, Edge Functions, Auth/Storage settings, or bucket definitions were modified. No QA branch was created. |
| Recovery point | FAIL | No physical backup was listed and PITR was disabled when the wipe ran; deleted records and files are not recoverable from a known backup. |

The connected phone's app was force-stopped before the wipe to prevent an already-running sync from repopulating the empty database. Its local app data was not cleared. The newly built APK was installed in place and force-stopped again; it was not launched after the wipe. Supabase documents that a previously issued JWT can remain valid until its expiry even after the Auth user is deleted; clear or verify the app's local session and pending data before starting a fresh registration test.

## Online-only scope and offline POS disposition

| Requirement | Result | Evidence |
|---|---|---|
| Release feature flag | PASS | `AquaHub/app/build.gradle.kts` sets `OFFLINE_POS_ENABLED=false` for production and keeps QA enabled. The generated production-release `BuildConfig.java` also contains `OFFLINE_POS_ENABLED = false`; the rebuilt AAB is version 12. |
| Old navigation, UI and direct-call bypasses | PASS at source and JVM-test level | Production UI hides lease controls and offers an internet-required message plus saved-receipt review. `WalkInSaleViewModel`, `CreateWalkInOrderUseCase`, `RoomOfflinePosRepository` and `SupabaseOfflinePosDataSource` each reject disabled offline operations. Deep links only target the exported app/recovery activities; no exported offline route was found. Runtime/deep-link testing is BLOCKED. |
| New offline authorization and stock lease | PASS for this binary; backend endpoint remains active | Authorization/lease and offline commit calls are guarded by the production flag. Production Supabase still reports `offline-pos` v2 ACTIVE with `verify_jwt=true`. We made no backend change, so older builds or another authorized caller are not globally disabled. |
| Network loss cannot create a new offline sale | PASS at source and unit-test level | The production submit path returns a connectivity error before local recording. Online network/timeout errors report that confirmation is unavailable and instruct the owner to keep checkout open and check history before retry. No local offline fallback is used. Runtime failure-path proof is BLOCKED. |
| Offline receipt cannot become a confirmed server sale | PASS at source level | The online-only `OfflinePosSyncCoordinator` recovers interrupted local work and returns without calling remote commit. Local unresolved receipts remain pending and separate from confirmed revenue; reports count paid server-confirmed receipts. No local device database was inspected. |
| Pending data preservation | PASS at source/unit-test level | Disabled reconciliation does not delete local rows. The POS sheet exposes saved-receipt review, and logout/account switching is blocked while unresolved work exists. Existing local pending receipts, if any, were not read or changed. Production offline POS tables currently have zero rows in the inspected server aggregate. |
| Unrelated online work | PASS at source scope | Only the offline POS coordinator is short-circuited; general Supabase refresh, order, inventory, notification and WorkManager paths remain wired. Their end-to-end behavior is not established by this change. |
| Trusted transaction state | PARTIAL | Online order/payment/inventory state comes from server order RPC results and database transaction logic; no client-side offline sale is promoted. This audit did not complete a real online transaction. |

Regression coverage added for the production flag, disabled offline UI state, direct use-case rejection, remote offline datasource rejection and pending local account-scope policy. Relevant files include `AquaHub/app/src/main/java/com/aesprt/aquahub/core/config/ReleaseFeatureFlags.kt`, `AquaHub/app/src/main/java/com/aesprt/aquahub/sync/OfflinePosSyncCoordinator.kt`, and the tests under `AquaHub/app/src/test/java/com/aesprt/aquahub/{core/config,data/offline,domain/usecase/order,feature/orders/walkin,sync}`.

**Backend limitation:** the deployed `offline-pos` function and database policy were not changed. This preserves the no-deploy instruction, but means the server still supports its prior offline protocol for callers outside this version-12 production client. If “disable offline authorization” is intended to cover every installed/older client and direct API caller, a separately approved backend change is required.

## Online POS end-to-end and reconciliation

**Result: BLOCKED — no new production test owner or station was created.** The user authorized using the production debug variant but did not provide an accessible QA mailbox/test owner. Production is the only configured backend. Creating an unverified account with an invented address would not prove authentication or email verification and could leave an orphan production identity. We did not create test records or run the prior existing owner’s account.

| Workflow step | Result |
|---|---|
| Register owner and verify authentication | BLOCKED — no isolated account/mailbox |
| Create station, products, starting inventory and customer | BLOCKED — no isolated owner/station |
| Open walk-in POS, cart multiple products, calculate total | BLOCKED — no usable Android runtime/account |
| Complete online cash sale and verify backend order/payment/stock | BLOCKED — no transaction submitted |
| Verify receipt, dashboard totals, force-stop/reopen and history | BLOCKED — no transaction/runtime |
| Required example (100 units; sell 3 × ₱35; one ₱105 cash payment; stock 97; one order/payment/movement) | NOT VERIFIED — none of these rows were created |

Pre-wipe production aggregates showed 2 profiles, 1 organization, 1 station, 1 product, 7 orders, 5 payments, 1 inventory balance and 8 movements. Offline POS device, authorization, lease, operation and reconciliation tables had zero rows in that snapshot. These counts are not evidence of the required POS workflow. The subsequent authorized reset cleared all 212 non-catalog rows; no test order, payment, or inventory movement was created.

## Network and transaction reliability

| Failure scenario | Result | Evidence/limit |
|---|---|---|
| No connection before checkout | PARTIAL | Production UI/ViewModel/use-case fail closed and the regression suite passed; no device execution. |
| Disconnect during request / timeout before response | NOT VERIFIED | Not exercised against Android and backend. |
| Server commits but client loses response | NOT VERIFIED | Server order RPC has a unique client operation ID and transaction-level replay path. Same-view-model retry reuses the key. The key/fingerprint owner is in memory, not a durable pending online-operation record; process-death retry has no demonstrated replay identity. |
| Repeated taps / duplicate retries | PARTIAL | In-flight submit guard and server idempotency are present; no runtime or backend replay test for this online path. |
| Server 500/503 or backend unavailable | NOT VERIFIED | Source presents confirmation-unavailable messaging and no offline-success fallback; no injected failures. |
| Authentication expiry / refresh during checkout | NOT VERIFIED | No authenticated runtime session. |
| Two devices sell final stock simultaneously | NOT VERIFIED | No two-device transaction/race test. |
| Force-stop immediately after submission | NOT VERIFIED | No device test. Durable same-operation recovery across process death remains an open P1 gate. |
| Connectivity recovery | NOT VERIFIED | No runtime recovery/reconciliation test. |

**P1 transaction risk:** server-side idempotency is useful only when retries retain the same operation ID. The current online walk-in identity manager lives in the ViewModel; force-stop clears it. Before launch, persist and recover an in-flight operation identity/payload or otherwise demonstrate that a post-process-death retry cannot create another paid order or stock movement.

## Android build and runtime

### Emulator diagnosis

- Host reported 13.9 GiB available at emulator startup, 4 host CPU cores, no `/dev/kvm`, and `emulator -accel-check` reported VT disabled or the KVM module unavailable. The emulator therefore used software QEMU/SwiftShader.
- Existing `AquaHub_OfflineQA_Tablet_API35_LowRes` previously stayed `emulator-5554 offline` for 292 seconds. Existing `Pixel_Tablet` and `AquaHub_OfflineQA_Tablet_API37` exited before boot with a pending-snapshot timeout.
- A fresh API 35 Play Store tablet AVD (2560×1600, emulator-selected 4 GiB guest RAM, 2 vCPUs, SwiftShader, 3–4 GiB virtual data partition) reached emulator startup but never became usable in ADB after several minutes. It was stopped without wiping any existing AVD.
- Existing Android 37 phone AVD (1080×2400, emulator-selected 4 GiB guest RAM, 2 vCPUs, SwiftShader) likewise remained ADB-offline and exited with host process status 139 when stopped. Neither emulator installed or launched the app.
**Earlier phone smoke check (before the production reset):** ADB mDNS reconnected the Samsung `SM-A366B` (`a36xq`), Android 16 / API 36, display 1080×2340. The user-requested production-debug APK then installed successfully with `adb install -r -t`; the package was version 12 / `aq-1.0.7`, target SDK 37. APK SHA-256 at that time: `3ca4a7f015949fa50ceee46755ada4d57281e7ca142fe61ccadacffc6fa41364`. It launched to sign-in and the registration first step rendered. No credentials were entered and no registration or checkout was submitted. The minified production release APK was rejected in place because its signer differs from the installed package.

**Current phone install (after the production reset):** `:app:assembleProductionDebug` was freshly re-executed and succeeded. The resulting APK (SHA-256 `55eb1ca430d57aa211507ba08866db3c16d9bedc33d75e877802f64defe119d3`) installed successfully in place on the same device. `dumpsys package` reports version code 12, `aq-1.0.7`, target SDK 37. The app was force-stopped after installation and not relaunched, because its preserved local session/cache could replay old data into the freshly cleared backend. This verifies installability and package identity, not post-wipe runtime behavior.

**Runtime result: PARTIAL; release-equivalent validation remains BLOCKED.** The fresh production-debug APK installed and passed only launch and registration-form smoke checks. The minified production release APK could not be installed over the existing package due to the signature mismatch. Checkout, receipt, dashboard, subscriptions, account deletion, adaptive/tablet navigation, extended crash/ANR checks, and network recovery remain untested at runtime. `connectedProductionDebugAndroidTest` and full visual UI checks were not run. No GO decision can rely on the debug smoke check or static tests in place of release-equivalent and transaction runs.

### JVM tests, Lint and artifacts

| Check | Result | Evidence |
|---|---|---|
| `:app:testProductionDebugUnitTest :app:testQaDebugUnitTest` | PASS | Fresh `--rerun-tasks` run completed: 198 tests per flavor; 0 failures, 0 errors, 0 skipped, parsed from Gradle XML results. |
| Production debug Lint | PASS with warnings | 0 errors, 87 warnings in `lint-results-productionDebug.xml`. |
| Release lint/build gate | PASS | `lintVitalProductionRelease`, R8 minification, release packaging and signing completed in the successful production bundle/APK task. Full non-vital release Lint was not rerun after the final change. |
| Fresh production debug APK | PASS install; runtime BLOCKED after reset | Fresh `:app:assembleProductionDebug` succeeded after the online-only changes; SHA-256 `55eb1ca430d57aa211507ba08866db3c16d9bedc33d75e877802f64defe119d3`. Installed in place on Samsung SM-A366B, then force-stopped. It was not relaunched after the cloud wipe to prevent preserved local data from syncing into the empty backend. Earlier pre-wipe smoke check is recorded above. |
| Fresh release AAB and APK | PASS | `:app:bundleProductionRelease :app:assembleProductionRelease` succeeded in 12m44s after the flag change. `jarsigner` verified the AAB; `apksigner` verified the APK. AAB and APK signer fingerprints matched the configured local upload-alias fingerprint previously recorded for this workspace. No Play certificate comparison was possible. |
| AAB identity | PASS | AAB-generated universal APK reports package `com.aesprt.aquahub`, version code 12, version name `aq-1.0.7`, min SDK 26, target/compile SDK 37. AAB SHA-256: `ac9cb37b7aa15ceac24e6eeb810ef55c07f76555410f49d2997771786c15f37a`. Release APK SHA-256: `6e027ff386b45b5714027cb5dc0fdfdc43c6afceb83a59fbf115b46302278488`. |
| APK generated from actual AAB | PASS (artifact only) | Official `bundletool` 1.18.3 generated a universal APK from the new AAB; package/version/target SDK and APK signature verified. It used a temporary audit-only test signer, not the Play upload signer, and was not installed. On the follow-up phone session, the production release APK signed with the local upload key was rejected for in-place install because the existing package has a different signer. |
| 16 KB native compatibility | PASS static / BLOCKED runtime | AAB config reports `PAGE_ALIGNMENT_16K`; all 8 packaged native libraries across arm64-v8a, armeabi-v7a, x86 and x86_64 have `PT_LOAD` alignment at least `0x4000`. No 16 KB page-size Android runtime was available. |

## Backend, security and other online features

| Area | Result | Evidence/limit |
|---|---|---|
| Registration/station provisioning and multi-station access | NOT VERIFIED | No production Auth/session test or two-tenant Android/RLS matrix. |
| Customer create/update; inventory/catalog management | NOT VERIFIED end-to-end | Source and unit coverage exist; no isolated backend records or live Android test. |
| Browser orders, delivery status and payment confirmation | NOT VERIFIED in the Owner app | Prior disposable Edge/Postgres tests exist in workspace records, but no current website-to-Owner queue, delivery/payment, or production workflow was run in this audit. |
| Financial reports and dashboard | NOT VERIFIED against production sale | Pending local offline cash remains excluded from confirmed revenue by source. No online sale was available to reconcile. |
| Staff permissions, expenses, notifications | NOT VERIFIED end-to-end | No cross-role test, expense mutation/retry run, or notification delivery test in this audit. |
| Authentication refresh, logout and account deletion | NOT VERIFIED at runtime | Pending-work logout/account-switch policy has JVM coverage. No auth provider or deletion request was executed. |
| Supabase RLS/authorization | NOT VERIFIED | Production metadata showed RLS enabled on inspected public/private tables; advisor findings include no-policy internal tables and SECURITY DEFINER grants. No hostile two-tenant JWT/RPC/storage test was performed. |
| Backend Deno tests | PASS | `/tmp/aquahub-deno/bin/deno test --allow-env --allow-net --allow-read=supabase/functions supabase/functions`: 87 passed, 0 failed. `deno check supabase/functions/*/index.ts` passed. These tests do not prove production data-plane behavior. |
| Backend SQL/RLS tests | BLOCKED | No local Docker, `psql` or `pg_prove` runtime was available. Production SQL mutation tests were not run. The Supabase CLI could not write its home telemetry file under the read-only home, so it did not provide a local DB test run. |
| Production migration state | PASS (read-only) | Prior same-day read-only check showed local/production migration histories matched 69/69. No migration or config change was made. |
| `git diff --check` | PASS | No whitespace errors. |

## Billing and account deletion

### Billing

- Billing Library 9.1.0 and server-side purchase verification/RTDN code are present. Deployed `verify-play-purchase`, `play-rtdn` and reconciliation functions were inspected as active; local/deployed source matched for the reviewed functions.
- **FAIL / P1:** the pre-wipe aggregate had 3 unbound purchase-token-hash groups and 15 related RTDN events; 44/44 sampled events were processed. The authorized reset deleted billing-token and event rows with the rest of non-catalog data. It did not change Google Play subscription state, so active subscriptions may now lack server-side ownership/entitlement records. Ownership, restore, and lifecycle remain unverified.
- **NOT VERIFIED:** Play Console product/base-plan/offer configuration, license-tester purchase, acknowledgement, pending purchase, restore, renewal, replacement, cancellation, grace/account hold, refund/revocation, expiry, and RTDN recovery. No Play tester account was available.
- Google’s Billing Library deprecation schedule currently lists the PBL 9 support deadline as 2028-08-31: [Google Play Billing deprecation FAQ](https://developer.android.com/google/play/billing/deprecation-faq). Version 9.1.0 is within that window, but this does not validate subscriptions or entitlements.

### Account deletion

- **PASS:** public privacy and account-deletion pages currently resolve to HTTP 200 at `https://aquahub.aesprt.com/privacy` and `https://aquahub.aesprt.com/account-deletion`; the deletion page identifies AquaHub Owner and has a request path. The user-authorized database/Auth reset was not an in-app account deletion request and did not validate that flow.
- **FAIL / confirmed P1:** deployed `delete-account` v20 differs from the local handler. The deployed handler omits the local pre-deletion marketing preference withdrawal/removal request. The production subscriber row uses `ON DELETE SET NULL` for its owner reference, so deleting an Auth identity alone may orphan a still-granted provider subscription. Local code is not deployed; no backend deployment was attempted.
- **NOT VERIFIED:** Play Console Data Safety deletion declaration, external link entry, destructive provider deletion, account ownership/re-auth flow, retries/partial failure, subscription cancellation and data retention. The Play Console was inaccessible.
- Google Play requires an in-app account deletion path and an external web deletion resource, with associated Data Safety disclosures: [Google Play account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en). The public pages alone do not satisfy verification of the live deletion handler or Console declarations.

## Google Play readiness

| Gate | Result | Evidence/limit |
|---|---|---|
| Target SDK | PASS | Candidate target SDK 37. Google Play’s 2026 policy requires API 36 or higher for new apps and updates from 2026-08-31; candidate exceeds that level. [Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en) |
| Billing Library level | PASS (level only) | PBL 9.1.0 is in source and within the current support window; purchase lifecycle remains NOT VERIFIED. |
| Release signing / R8 | PASS locally | Fresh version-12 AAB and release APK signed by configured local upload alias; R8 ran; AAB/APK signatures verified and matched each other. |
| Play upload certificate / version-code availability | NOT VERIFIED in Console | Current signer matches the locally configured alias, not independently compared with Play’s upload certificate. Version code 12 availability is owner-supplied status; no Console check or upload was performed. |
| 16 KB page size | PASS static / BLOCKED runtime | Bundle alignment and ELF segment checks pass; runtime test unavailable. Google Play’s announced update restriction begins 2027-02-01. [16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes) |
| Privacy policy / external deletion URL | PASS reachability only | Both public URLs returned HTTP 200. Policy accuracy, retention/transfer declarations and Play Data Safety answers are NOT VERIFIED. |
| In-app deletion / Data Safety | NOT VERIFIED | No destructive test or Play Console access. Live handler mismatch is a confirmed blocker. |
| Subscription products, restore and reviewer instructions | NOT VERIFIED | No Play Console or license-tester access. |
| Store listing accurately says online-only | NOT VERIFIED | Listing is not accessible in this audit. The release artifact has offline POS disabled, but Console listing copy was not inspected. |
| Play warnings/blocking declarations | NOT VERIFIED | No Play Console access; no upload or publication was performed. |

## Defects and priorities

| ID | Priority | Finding | Status |
|---|---|---|---|
| DEL-01 | P1 | Deployed account-deletion handler does not withdraw owner marketing consent before deleting the identity; local correction is not deployed. | FAIL |
| BILL-01 | P1 | Three production token-hash groups remain unbound; billing ownership, restore and lifecycle are unverified. | FAIL / NOT VERIFIED |
| TXN-01 | P1 | Online walk-in idempotency key/fingerprint is in ViewModel memory; no durable force-stop recovery identity or real lost-response test. | NOT VERIFIED; source risk confirmed |
| POS-01 | P1 | No real online cash transaction was completed/reconciled with the production backend and isolated test owner. | BLOCKED |
| QA-01 | P1 | Phone/tablet runtime and Android UI suite could not run because emulators stayed ADB-offline without KVM. | BLOCKED |
| REL-01 | P1 | Play Console upload certificate, code 12 availability, declarations, listing and blocking warnings not checked. | NOT VERIFIED |
| SEC-01 | P1 | No adversarial two-tenant RLS/RPC/storage test; metadata inspection is not authorization proof. | NOT VERIFIED |
| OPS-01 | P1 | Prior same-day operational snapshot reported 246 marketing dispatch 503s and one reconcile 503 in 24 hours; current quota/backup/recovery health was not established. | NOT VERIFIED |
| AUTH-01 | P1 | Production registration/email verification, token refresh, reauthentication and deletion flow not exercised with a QA identity. | BLOCKED |
| POS-OFFLINE-01 | P2 | Production Supabase offline-pos function remains active for older/other authorized clients; current release binary is disabled. | NOT VERIFIED globally; backend change needs separate approval |
| UI-01 | P2 | Adaptive/tablet navigation, accessibility, TalkBack, crash/ANR and visual receipt/dashboard flows have no current runtime evidence. | BLOCKED |
| FIN-01 | P2 | No production reconciliation for integer-centavo order, payment, inventory movement, receipt and dashboard totals. | NOT VERIFIED |
| LINT-01 | P2 | Production debug Lint passed with 87 warning-level findings; full non-vital production release Lint was not rerun after the final feature-gate edit. | PASS with warnings / NOT VERIFIED fully |
| P0 | — | No P0 defect was confirmed in this audit. | PASS |
| P3 | — | No P3-only finding was used to decide release readiness. | PASS |

## Exact release blockers

1. Fix and safely deploy the account-deletion marketing-consent removal path; verify retry/partial-failure behavior and that the provider subscription is removed. This requires separate deployment approval.
2. Resolve the three unbound Play token groups against current Google receipt state and verified owner/organization identities; complete purchase, restore, renewal, expiry and cancellation lifecycle with license testers.
3. Add durable online checkout retry identity or demonstrate an equivalent server-reconciliation flow across force-stop; complete the real lost-response, duplicate-tap and final-stock race matrix.
4. Obtain an isolated test mailbox/owner plus safe QA records or a disposable backend; complete the required online cash transaction and reconcile exactly one order, payment and stock deduction, receipt and dashboard.
5. Run the production-equivalent debug and AAB-derived release APK on a working Android phone and tablet, including account deletion/subscription screens and recovery after network loss. Production-debug received only a brief phone smoke check; the minified release APK still needs a Play-signed installation path for that device or another suitable runtime. Tablet runtime is still blocked.
6. Verify in Play Console the upload certificate, code 12 availability, Data Safety/account-deletion declarations, reviewer access, listing copy and all blocking warnings. Do not upload or publish until the owner approves.
7. Verify backend tenant isolation and current production quota/recovery health. The last backup check found no physical backup and PITR disabled; establish a recovery point before any further production data changes. This audit did change production data only through the user-authorized reset described above.

## Final verdict

**NO-GO — Confirmed production-blocking defects.** Offline checkout is demonstrably disabled in the rebuilt version-12 production client, and the fresh signed release artifacts pass the checks listed above. The live account-deletion handler has a confirmed consent-removal gap. Billing ownership, durable online transaction recovery, real online POS reconciliation, Android runtime behavior and Play Console compliance remain release gates. Version 12 has not been uploaded by this audit.
