# AquaHub Owner Free Plan Validation

## Play pricing and Free-screen failure follow-up — 2026-10-10

The user-reported subscription screen showed `Price unavailable` and stated
that Google Play checkout was not configured. Source tracing confirmed that
opening plan comparison did not request fresh `ProductDetails`, and the
view-model refresh path restored purchases without re-querying catalog prices.
The local correction now re-connects/re-queries when the plans screen opens or
refresh is requested. Only prices returned by Google Play are displayed; no
fallback paid prices were added. The QA application ID (`com.aesprt.aquahub.qa`)
is not the Play-distributed Owner package, and Play Console product/base-plan
availability for the released package remains **NOT VERIFIED**. Therefore the
QA build cannot establish the real customer-facing price display.
The client now logs sanitized returned product/base-plan/period identifiers
and Play's unfetched product IDs/status codes, without logging prices, account
data, or purchase tokens, so an authorized Play-installed test can distinguish
missing catalog entries from unsupported offer shapes.

The reported Walk-in Sales and Analytics crashes have no captured app stack
trace and were not reproduced in an authenticated Free account. Code review did
find unhandled failures in the Walk-in and Analytics data-observation paths;
the local fix exposes a retryable error state rather than allowing those
observer exceptions to escape, and replaces Analytics' indefinite skeleton
with an error/retry state. Treat this as a probable cause addressed in source,
not a confirmed runtime root cause or an end-to-end pass.

Focused QA verification: `testQaDebugUnitTest` (five focused classes) passed
42/42 tests and `assembleQaDebug` succeeded. The QA APK installed and its
process started on a temporary API 35 emulator. The emulator then showed an
Android **System UI** not-responding dialog over the notification-permission
prompt; its focused window was `com.android.systemui`, and no AquaHub fatal
exception was present in the captured crash buffer. Device smoke is therefore
**BLOCKED**, not passed. The app remained unauthenticated; POS, Analytics, and
Play prices were not exercised. The temporary emulator was stopped. The
final diagnostic-only logging change was rebuilt after this emulator attempt
and was not reinstalled because the emulator had become unresponsive. The
original Owner package/data was not touched.

**Assessment date:** 2026-10-10  
**Decision:** **FREE PLAN NOT READY / NOT FULLY VERIFIED**  
**Overall Google Play readiness:** remains **NO-GO**

## Free entitlement expiry fix — 2026-10-10

- **Confirmed cause:** Supabase provisions the perpetual Free subscription with
  `status = FREE`. Android's `SubscriptionStatus` enum had no `FREE` member, so
  `SubscriptionStatus.fromString("FREE")` fell back to `EXPIRED`. The access
  resolver also recognized only the legacy Free/`ACTIVE` combination. The
  hosted row was valid; this was a client contract-decoding defect.
- A privacy-safe aggregate read after the user's report found one Auth user,
  profile, organization, station, and non-deleted subscription; that
  subscription is plan/status `FREE`/`FREE`. Products, orders, payments, and
  inventory movements remain at zero. No identifiers or personal fields were
  queried, and Codex did not create or modify the account/workspace.
- Local fix: Android now decodes `FREE` explicitly and resolves Free/`FREE`
  (plus legacy Free/`ACTIVE`) as perpetual active access, independent of the
  zero-length billing-period timestamp. Unknown statuses still fail closed.
  No database or hosted function change is needed or deployed.
- Regression: `SubscriptionAccessManagerTest` passed 29/29, including server
  status parsing, zero-length period handling, Free inventory access, and
  premium container-tracking denial. `assembleQaDebug` passed; the updated
  separate QA APK was installed in place and launched on SM-A366B without an
  observed AndroidRuntime crash. The existing original package was not
  updated. The QA screen's displayed status was not independently captured,
  and the fix is not in a distributed release.
- The full `lintQaDebug` attempt was interrupted after more than 50 minutes
  without a result (exit 130); lint is **NOT VERIFIED**, not passed.
- Free remains **NOT READY**: product/POS/report workflows and hosted role and
  quota tests remain outstanding; overall Google Play readiness remains
  **NO-GO**.

## Latest Group 5 execution addendum — 2026-10-10

This records the earlier Group 5 snapshot; the Free-expiry addendum above is
the latest status and supersedes conflicting rows below.

- The narrowly scoped `20261010100000_free_plan_basic_inventory` migration is
  deployed to Supabase project `pfkxrggexueltytxtqil` and recorded in hosted
  migration history. Read-back confirms Free `BASIC_INVENTORY=true`,
  `BASIC_CONTAINER_TRACKING=false`, and quotas unchanged at 1 station, 20
  products, 100 monthly orders, and 50 customers. The protected paid-plan
  catalog digest is unchanged. No other migration was deployed.
- The initial post-test aggregate snapshot found no hosted rows. It is
  superseded by the latest read in the expiry-fix addendum above: the user's
  account/workspace and Free subscription now exist, while no products, orders,
  payments, or inventory movements exist. Codex created none of those rows.
- Local source quarantines legacy pending/retry/failed/in-progress outbox rows
  before sync scheduling and preserves those rows. Focused device
  instrumentation passed 2/2 on Samsung SM-A366B / Android 16. This tests the
  quarantine DAO, not an upgrade of the original installed app or inspection
  of that app's persisted queue.
- Separate `com.aesprt.aquahub.qa` was built, installed, and launched. Its
  onboarding and login screens rendered; notification permission was denied.
  The original `com.aesprt.aquahub` remains installed at its prior APK path and
  was not launched, replaced, cleared, or uninstalled. Keep it unopened until
  a data-preserving update containing the quarantine guard is installed and
  its legacy queue is reviewed; the separate QA app does not quarantine it.
- Production-debug and QA-debug JVM suites each passed 123/123 in the earlier
  Group 5 run. The later focused subscription suite passed 29/29. `lintQaDebug`
  was interrupted and has no result.
- Registration, verification, product and stock writes, POS transactions,
  Sales History, and Reports remain unverified because no controlled test
  inbox/phone was provided. No authentication requirement was bypassed and no
  paid purchase was made.

## Executive summary

Owner's Free contract enables basic saleable-product inventory. The targeted
forward migration is now deployed and the hosted catalog read-back confirms
that only Free basic inventory was enabled; the premium container feature,
Free limits, and paid-plan catalog remain unchanged. The earlier disposable
SQL suite (13 files / 88 assertions) passed the allow/deny and idempotent-stock
checks.

This still does **not** prove that an authenticated owner can operate Free end
to end. The user reports creating an account and selecting Free; a later
aggregate confirms an account/workspace and Free subscription without
exposing identity fields. No product, stock movement, order, payment, refund,
or report row exists. The original installed app was not launched or
modified. Product persistence, the ₱60 POS sale, stock deduction, Sales
History, hosted Reports, the updated screen state, and original device queue
remain unverified.

The only hosted business configuration write was the explicitly authorized
FREE catalog migration. The account/workspace/subscription rows seen in the
later aggregate were not written or modified by Codex. No transaction,
Storage, Edge Function, or Play Console change was performed. No purchase was
initiated. The existing release AAB was not overwritten.

## Free entitlement contract

Source of intended local entitlements: `AquaHub/app/src/main/java/com/aesprt/aquahub/domain/model/SubscriptionModels.kt`. The exact migration is recorded in hosted history. Current hosted read-back confirms Free basic inventory enabled and the premium container feature disabled; authenticated API behavior is not yet tested.

| Capability / limit | Intended Free access | Local Owner source | Hosted catalog | Evidence status |
|---|---|---|---|---|
| Station management and dashboard | Allowed | Enabled | Enabled per inspected catalog | Source/read-only catalog; device flow not tested |
| Order management and walk-in sales | Allowed | Enabled | Enabled per inspected catalog | SQL regression coverage only; full sale not tested |
| Customer management | Allowed | Enabled | Enabled per inspected catalog | Not exercised end to end |
| Product management | Allowed | Enabled; 20 products | Enabled per inspected catalog | Product creation UI not exercised |
| Basic saleable-product inventory | Allowed | Enabled | **Enabled** | Hosted catalog read-back after exact migration; authenticated product/stock RPC still not exercised |
| Basic sales reports | Allowed | Enabled | Enabled per inspected catalog | Not exercised against a hosted synthetic business |
| Station-owned container tracking / circulation | Upgrade required | Disabled | Disabled | Disposable SQL assertion rejects the container ledger write |
| Advanced inventory / alerts | Upgrade required | Not enabled | Not listed in Free seed; default-deny | No direct API test |
| Advanced container tracking | Upgrade required | Not enabled | Not listed in Free seed; default-deny | No direct API test |
| Rider fleet management | Upgrade required | Not enabled | Not listed in Free seed; default-deny | No direct API test |
| Data export | Upgrade required | Disabled | Disabled | Catalog/source inspection; no API test |
| CSV / Excel / PDF export | Upgrade required | Not enabled | Not listed in Free seed; default-deny | No direct API test |
| Mobile customer ordering | Upgrade required | Disabled | Disabled | Catalog/source inspection; no API test |
| Legacy QR ordering | Upgrade required | Not enabled | Not listed in Free seed; default-deny | No direct API test |
| QR ordering analytics | Upgrade required | Not enabled | Not listed in Free seed; default-deny | No direct API test |
| Delivery management | Upgrade required | Disabled | Disabled | Catalog/source inspection; no API test |
| Scheduled deliveries | Upgrade required | Not enabled | Not listed in Free seed; default-deny | No direct API test |
| Advanced reports and analytics | Upgrade required | Disabled | Disabled | Catalog/source inspection; no API test |
| Customer insights | Upgrade required | Disabled | Disabled | Catalog/source inspection; no API test |
| Accounts receivable / promotions | Upgrade required | Disabled | Disabled | Catalog/source inspection; no API test |
| Expense tracking / staff accounts | Upgrade required | Disabled | Disabled | Catalog/source inspection; no API test |
| Multi-branch / multi-station | Upgrade required | Multi-station disabled | Multi-station disabled in seed; branch not listed | No direct API test |
| Multi-station / consolidated reports | Upgrade required | Disabled | Disabled | Catalog/source inspection; no API test |
| Station comparison / delivery analytics | Upgrade required | Not enabled | Not listed in Free seed; default-deny | No direct API test |
| Priority support | Upgrade required | Not enabled | Not listed in Free seed; default-deny | No direct API test |
| Station count | 1 | 1 | 1 per inspected catalog | Catalog inspection; quota boundary not tested through REST |
| Product count | 20 | 20 | 20 per inspected catalog | Catalog inspection; quota boundary not tested through REST |
| Orders per month | 100 | 100 | 100 per inspected catalog | Catalog inspection; quota boundary not tested through REST |
| Customers | 50 | 50 | 50 per inspected catalog | Catalog inspection; quota boundary not tested through REST |
| Paid plan / Play purchase | Not required for Free | Free onboarding invokes trusted provisioning; no Play purchase path needed | Function deployment/config not revalidated in this turn | New-owner activation not executed |

The Free correction intentionally does **not** enable container tracking, delivery, staff, expenses, advanced analytics, export, multi-station, or paid billing features.

### Subscription row edge case

`CheckSubscriptionAccessUseCase` treats a missing local subscription row as expired, while the backend effective-entitlement function can resolve a missing active subscription to Free. The normal new-owner setup calls trusted `ensureOwnerTrial("FREE", …)` and mirrors the returned Free row into Room, so that is the intended route. Recovery of an older/incomplete workspace with no subscription row was not exercised; it could present an unnecessary renewal/upgrade gate if provisioning or local mirroring was interrupted. Verify this recovery case in isolated staging before release; do not grant entitlements locally as a shortcut.

## Root causes and local corrections

1. **Free could not create saleable opening stock.** Both the local entitlement seed and hosted plan catalog denied `BASIC_INVENTORY`; product creation records opening quantity through the guarded inventory ledger. The server therefore correctly rejected an essential Free workflow. The local plan now grants basic inventory, and the new SQL migration updates only the Free description and `BASIC_INVENTORY` flag. It leaves `BASIC_CONTAINER_TRACKING` and all quotas unchanged.
2. **Product size setup was coupled to premium container tracking.** Creating a product size could create a station-owned container-size record, which is properly premium-gated. Owner now stages a Free product's size label in its catalog UI instead of creating a container-ledger record. The inventory screen keeps saleable product stock visible and gates only container circulation/history operations.
3. **Editing stock did not persist as an inventory event.** The product catalog update intentionally excludes stock, but the edit screen did not call the existing stock adjustment use case. It now performs a guarded stock-ledger adjustment when quantity changes, then updates product metadata. Failures are surfaced rather than silently leaving an indefinitely saving dialog.
4. **Offline manual stock edits lacked durable retry identity.** The stock repository now queues connectivity-failed deltas with the same stable operation ID used for the immediate server request, so a committed request whose response was lost can be retried idempotently. Non-connectivity failures are not converted into success.

Affected Android files include `SubscriptionModels.kt`, `InventoryUseCases.kt`, `ProductUseCases.kt`, `RoomProductRepository.kt`, `ProductsViewModel.kt`, `ProductsScreen.kt`, `InventoryViewModel.kt`, `InventoryScreen.kt`, and `MainContainerScreen.kt`. Regression changes are in `SubscriptionAccessManagerTest.kt`.

## New-owner and business workflow outcomes

| Workflow | Result | Evidence / blocker |
|---|---|---|
| New owner registration and verification | **PARTIAL** | User reports creating the account and selecting Free; aggregate confirms an Auth user/profile. Verification and full registration flow were not independently exercised by Codex. |
| Organization/station provisioning | **PARTIAL** | Aggregate confirms one organization and station associated with the current test state; Codex did not inspect IDs or modify them. |
| Free subscription activation and session restore | **PARTIAL** | User reports account creation and Free selection; privacy-safe aggregate confirms one account/workspace and Free/Free subscription. Android post-fix restart/display state was not asserted. |
| Product creation with opening quantity | **NOT RUN E2E** | Hosted catalog now enables the feature and isolated SQL authorization passed. No authenticated product RPC/UI operation or persisted product row was tested. |
| Edit name/price and stock | **NOT RUN E2E** | Local stock-edit implementation and JVM tests exist; no hosted operation was performed. |
| Walk-in cash sale, change, payment and receipt | **NOT RUN** | No product, order, or payment exists in the latest aggregate; no sale was attempted. |
| Duplicate sale / lost response / restart retry | **NOT RUN in this validation** | Existing project-level idempotency work is not evidence for this complete Free workflow; the current SQL test covers idempotent stock movement only. |
| Sales history and basic reports | **NOT RUN** | No hosted synthetic sale exists. |
| Paid-only restrictions and quota boundaries | **PARTIAL** | Hosted read-back confirms container tracking remains disabled and quotas unchanged; authenticated REST boundary tests were not run. |
| Physical device / responsive screen behavior | **PARTIAL** | Separate QA app launched; onboarding/login rendered. Product/POS, landscape, tablet, and resize behavior were not tested. |

No account or business was created by Codex. The user reports creating an account/workspace; aggregate-only verification found a Free subscription but did not inspect identity fields. Continue only with authorized test data and preserve authentication controls. The designated Supabase project's production-of-record status is not established; treat all other writes as production-unknown.

## Test results

| Check | Result | Evidence |
|---|---|---|
| Apply Free catalog migration to disposable Supabase DB | **PASS** | Applied only `20261010100000_free_plan_basic_inventory.sql` to the local disposable DB at `127.0.0.1:56322`; linked/hosted project untouched. |
| SQL/pgTAP suite | **PASS** | `supabase test db --db-url 'postgresql://postgres:postgres@127.0.0.1:56322/postgres?sslmode=disable' --workdir /home/adrielespiritu/AndroidStudioProjects/WaterStationApp /home/adrielespiritu/AndroidStudioProjects/WaterStationApp/supabase/tests` — 13 files, 88 assertions passed. Includes Free inventory allow, unchanged limits, idempotent stock adjustment, and container-ledger deny assertions. Synthetic fixtures are transactionally rolled back. |
| Deno functions tests | **PASS** | `deno test --allow-env --allow-read supabase/functions` — 34 passed, 0 failed. |
| Deno Edge Function type check | **PASS** | `deno check` over all 10 `supabase/functions/*/index.ts` entrypoints — exit code 0. |
| Earlier non-flavor Owner JVM run | **PASS — HISTORICAL** | `testDebugUnitTest` — 122 XML test cases, 0 failures/errors; superseded by the current flavor-specific 123-test runs above. |
| Production/QA JVM suites | **PASS** | `testProductionDebugUnitTest testQaDebugUnitTest assembleQaDebug` — each variant 123 tests, 0 failures/errors; QA APK built. |
| Owner debug build | **PASS** | `GRADLE_USER_HOME=/tmp/aquahub-freeplan-gradle.r4kuyE ./gradlew assembleDebug --console=plain` — `BUILD SUCCESSFUL`; generated the debug APK only. Existing release AAB was untouched. |
| Owner device install | **PASS** | Confirmed installed package `com.aesprt.aquahub`, version `aq-1.0.7` / code 12, identical V2 signing certificate SHA-256 between installed APK and generated debug APK, and unchanged `firstInstallTime`. `adb install -r app/build/outputs/apk/debug/app-debug.apk` succeeded; no app data was cleared. |
| QA outbox connected instrumentation | **PASS** | `connectedQaDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.aesprt.aquahub.sync.SyncQueueRecoveryTest` — 2/2 passed on Samsung SM-A366B / Android 16. Legacy rows stayed quarantined/preserved and fresh queue rows remained pending. In-memory Room fixture; not the original app's persisted queue. |
| QA install and screen smoke | **PASS / LIMITED** | `com.aesprt.aquahub.qa`, version code 12 / `aq-1.0.7`, installed alongside original. Onboarding and login rendered; notification permission denied; no QA crash observed in filtered AndroidRuntime logs. No registration or business workflow. |
| Original Owner app state | **PRESERVED / NOT VERIFIED** | Original `com.aesprt.aquahub` remains at its prior APK path and was not launched, updated, cleared, or uninstalled. Its local queue was not inspected or quarantined. Keep unopened pending a guarded data-preserving update/review. |
| Android Lint | **NOT VERIFIED** | `lintQaDebug` was interrupted after >50 minutes without a result; exit 130. |
| Customer JVM/device integration | **NOT RUN** | This Free Owner change does not modify the Customer contract; no Customer build was run in this turn. |
| Hosted REST/RPC / authenticated synthetic test | **BLOCKED** | No designated verified test identity; catalog flag is enabled, but authenticated product/POS RPC, quota, and tenant/role tests were not run. |
| Play purchase | **NOT APPLICABLE** | Free workflow must not require a purchase; no purchase was attempted. |
| `git diff --check` | **PASS** | Ran in the AquaHub repository after the implementation edits; no whitespace errors. Existing unrelated dirty worktree was preserved. |

The successful Kotlin compile reported non-fatal deprecation and existing source warnings; no lint result is inferred from those compiler messages.

## Hosted and release implications

- Hosted Free catalog is **corrected for this capability**. Hosted history includes the exact migration; read-back confirms Free `BASIC_INVENTORY=true`, `BASIC_CONTAINER_TRACKING=false`, unchanged quotas, and unchanged paid-plan digest. This is configuration evidence, not an authenticated product/POS transaction.
- Do not treat source changes, local migration replay, or SQL test success as deployment or production verification.
- The targeted correction is deployed: it changes only the Free description and `BASIC_INVENTORY` flag. It preserves the one-station, 20-product, 100-orders/month, and 50-customer limits and leaves premium container tracking off.
- Before any production migration, validate app compatibility and execute the full hosted REST/RPC entitlement test in an isolated staging project. Production deployment needs separate authorization.
- Full Owner business-flow device testing remains blocked. The separate QA UI smoke passed; the original Owner app and its data were preserved and not launched.

## Required follow-up before calling Free plan ready

1. Re-run `lintQaDebug` when the host can complete it and record warnings/errors.
2. Verify the updated QA app visibly resolves the existing Free subscription as active after app restart; verify confirmation and session restoration without exposing credentials.
3. Run authenticated product create/edit/stock and premium-feature/quota REST/RPC tests using the user's controlled test account or another synthetic owner.
4. On the SM-A366B, execute the ₱60 sale with ₱100 tender, validate ₱40 change and 50→48 stock, then verify persisted order/payment/ledger plus Sales History and Reports. No sale has been recorded yet.
5. Exercise duplicate submit, committed-response loss/retry, offline/restart, insufficient inventory, cancellation/refund, and Account A→B isolation; compare persisted state and exact centavos.
6. Keep the original app unopened until its data-preserving guarded update is installed and its legacy queue is reviewed. The QA app's empty Room DB does not protect that original queue.
7. Keep overall release status **NO-GO** until broader security, subscription, tablet, and release gates pass.

## Final answers

1. **Can a newly registered owner use Free without purchasing?** The user reports selecting Free, and an aggregate confirms the server persisted a Free/Free subscription. The local Android status-decoding fix passes focused tests, but the updated screen state and complete owner workflow are not fully verified.
2. **Can Free create products?** Hosted catalog enables basic inventory, but authenticated product creation was **not exercised**.
3. **Can Free complete an ordinary walk-in sale?** **Not verified.** An account/workspace exists, but no product, order, or payment row exists.
4. **Are stock, sales, and reporting correct end to end?** **Not verified.** Only local stock-ledger authorization/idempotency was tested.
5. **Are all Free features and limits correctly applied?** The intended contract is documented above; hosted authenticated limit and restriction testing remains outstanding.
6. **Are premium features properly restricted?** Container ledger denial passed in disposable SQL. All premium paths have not been comprehensively exercised through hosted REST/RPC.
7. **Any known release-blocking Free defect?** The catalog denial is fixed on the designated project. Authenticated product/POS/report behavior and the original device's legacy queue remain release-blocking verification gaps.
8. **Is Free ready for real business owners?** **No—not yet.** Catalog configuration and isolated code tests pass, but registration, authenticated hosted workflows, exact stock/revenue results, and complete device verification are absent.
