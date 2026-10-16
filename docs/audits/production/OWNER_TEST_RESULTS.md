# Owner Audit Test Results

## Phase 3 end-to-end and regression evidence — 2026-10-12

| Check | Environment / command | Result |
|---|---|---|
| Android unit tests | `./gradlew :app:testProductionDebugUnitTest :app:testQaDebugUnitTest --no-daemon` | BUILD SUCCESSFUL; 188 tests per flavor, 0 failures. Same source suite run twice, not 376 unique tests. |
| QA instrumentation compilation | `:app:compileQaDebugAndroidTestKotlin` as part of connected build | PASS; the added expense hydration phase compiled. |
| QA instrumentation | API 35 Medium Phone emulator, package `com.aesprt.aquahub.qa`; direct `adb shell am instrument -w -r com.aesprt.aquahub.qa.test/androidx.test.runner.AndroidJUnitRunner` | 30 tests, 0 failures. The opt-in disposable E2E case is run separately; the default suite does not exercise that long-running scenario. An initial Gradle invocation wrote an empty 0-test result because the runner filter property was not honored; it is not counted as a pass. Direct instrumentation was used for the actual 30-test run. |
| Live Android expense hydration | Same QA emulator, real Supabase client/Room/`ExpenseRepository`, local disposable Supabase proxy; opt-in `OfflinePosDisposableE2eTest` | PASS: server-only ₱123.45 fixture absent from Room was fetched once; remote ID metadata mapped to local ID; total updated by exact amount; repeat refresh created no duplicate/total drift. |
| Disposable email signup and password recovery | Local Supabase Auth + Mailpit over isolated QA proxy; random `example.com` test identity; credentials and user ID suppressed | PASS for current local config: signup returned an immediate session and `email_confirmed_at` was already set with `auth.email.enable_confirmations=false`; recovery email was received locally, `type=recovery` redirected to `aquahub://auth/recovery`, password update succeeded and new password sign-in succeeded. This demonstrates auto-confirm, not proof of the owner email. Production Auth settings remain inaccessible and policy match is unresolved. |
| Live Android expense convergence | Disposable expense updated server-side to ₱67.89 and description changed; same real Android repository refresh | PASS: previous Room amount was replaced exactly once; second refresh remained idempotent. This simulated a remote writer, not a second Android device. |
| Real offline POS flow | QA Android app, local proxy + disposable Supabase Edge Function/PostgreSQL | PASS for the tested critical path: authenticated owner, grant/lease, Android airplane mode, immutable cash transaction persisted in Room, force-stop/recovery, online real repository/worker sync, server commit response held/dropped, process stop, duplicate retry returned canonical prior result. Exactly one order, paid cash payment and inventory movement; original operation UUID/payload digest retained; local operation became COMMITTED and confirmed report/dashboard included the sale once. |
| Reservation and stock RPC | Disposable database / authenticated QA owner | PASS: repaired `set_inventory_quantity`; absolute target accepted at current revision; stale revision rejected `40001`; target below reservation rejected `23514`; relative adjustment below reservation rejected. One concurrent online Owner POS case yielded exactly one committed order/payment/movement while reservation floor remained intact. |
| Guest browser order Edge→PostgreSQL | `python3 /tmp/aquahub-off03-disposable/qa/guest_order_edge_e2e.py`; disposable local Supabase, synthetic station/catalog, seeded owner identity | PASS: browser-shaped pickup request through `guest-order` created exactly one `CUSTOMER_WEB` order (2,500 centavos) and one inventory movement from an unreserved unit. Replaying the same operation UUID/tracking token returned the same canonical order. A subsequent fully reserved-stock attempt initially surfaced as HTTP 503; the database error was confirmed as `inventory change would consume reserved stock`. After the local Edge classifier fix and disposable runtime restart, it returned HTTP 409 with a safe refresh-cart message and zero order/balance changes. No website browser UI or Owner realtime queue was exercised. |
| Owner order queue after missed push | Static trace of `OrdersViewModel`, `OrdersScreen`, `SyncScheduler` and `SupabaseSyncWorker` | FAIL as an implementation gap; runtime injection NOT VERIFIED. Periodic sync has no station ID, the screen has no resume refresh, and no Realtime subscription exists. If FCM is missed, the current Room-backed queue can remain stale until manual refresh or ViewModel recreation. Promoted to P1 SYNC-04. |
| Lease versus browser-order race | `python3 /tmp/aquahub-off03-disposable/qa/lease_vs_guest_race.py`; authenticated Owner lease RPC versus guest-order Edge request, two-thread barrier, same station/product and one free unit | PASS for this writer pair: lease RPC won; guest order returned 409; exactly one allocation, no guest order/movement; balance remained `quantity=reserved`. Cleanup used audited physical reconciliation for the synthetic lease/device, then restored the test stock via CAS. This did not use two Android devices. |
| Disposable SQL suite | `supabase test db --local --workdir /tmp/aquahub-off03-disposable` | PASS; 22 files, 226 assertions, 0 failures. Includes the new behavioral no-op setter/revision regression. Only disposable local Supabase. |
| Edge function tests/type check | `deno test --allow-env=GOOGLE_PLAY_CLIENT_EMAIL,GOOGLE_PLAY_PRIVATE_KEY,GOOGLE_PLAY_PACKAGE_NAME,PLAY_TOKEN_ENCRYPTION_KEY supabase/functions`; `deno check --no-lock supabase/functions/offline-pos/index.ts`; `deno check --no-lock supabase/functions/guest-order/index.ts` | PASS; 87 tests, including three new guest-order error classification cases; both Edge entrypoints type-check; test-scoped env permission, no production deployment. |
| Production lint/R8/AAB | `./gradlew :app:lintProductionRelease :app:bundleProductionRelease -x :app:uploadCrashlyticsMappingFileProductionRelease --no-daemon` | BUILD SUCCESSFUL in 23m47s. R8 and local mapping file generated; Crashlytics mapping upload task excluded. 93 lint warnings, 0 errors. |
| Fresh AAB validation | Bundletool 1.18.3 `validate`, `dump manifest`, `dump config`; `zipalign -c -P 16 -v 4` | PASS. Package `com.aesprt.aquahub`, versionCode 12, versionName `aq-1.0.7`, minSdk 26, targetSdk 37, `allowBackup=false`; `PAGE_ALIGNMENT_16K`; zip alignment validation pass. |
| Current local AAB identity | `app/build/outputs/bundle/productionRelease/app-production-release.aab`; SHA-256 | 16,280,189 bytes; `b503c5727bc6c53dc02c62c35be8da97a4ea824b718bbc44708d6d2dbf1aca2c`. Artifact is unsigned (`jarsigner`: “jar is unsigned”); no signing config/key used. |
| Native/16 KB static checks | ELF program-header PT_LOAD parse for all `.so` files in current AAB | 8 native libraries across arm64-v8a, armeabi-v7a, x86 and x86_64; 0 misaligned PT_LOAD segments. Runtime remains NOT VERIFIED: attached API 35 device reports 4096-byte page size. |
| Device/page-size matrix | `adb devices -l`; `adb shell getprop ro.build.version.sdk`; `adb shell getconf PAGE_SIZE` | Medium Phone and Pixel Tablet AVDs, both API 35 and actual 4096-byte pages. Samsung SM-A366B was not attached. No 16 KB runtime or older Android runtime available. |

### Phase 3 failure-injection disposition

| Scenario | Result |
|---|---|
| Duplicate backend operation / timeout after successful commit | PASS in core Android→Edge→PostgreSQL path; duplicate returns canonical order, exactly one effect per ledger. |
| Force-stop while offline and after remote commit response was held | PASS for tested force-stop/recovery sequence. Full OS reboot remains NOT VERIFIED. |
| Duplicate WorkManager/repository sync | PASS for duplicate retry of stable operation in disposable path; simultaneous independent workers were not separately stress-raced. |
| Missed FCM/browser-order recovery | FAIL by static code inspection; runtime injection NOT VERIFIED. Periodic work cannot refresh a station order queue without a station ID; no lifecycle resume pull or Realtime subscription provides recovery. |
| Repeated rapid sale button taps | NOT VERIFIED via UI; operation UUID/idempotency contract only. |
| HTTP 429/503, interrupted body/network, retry timing | NOT VERIFIED as live API fault injection; retry policy has JVM coverage. |
| Browser order competing with held offline inventory | Sequential PASS only: guest order consumes one unreserved unit; a second request with no unreserved quantity is rejected at the database reservation floor and returns 409 after the local fix. Simultaneous browser-vs-lease race NOT VERIFIED. |
| Grant/lease expiry, revocation, clock rollback | SQL contract/cryptographic tests exist; real Android/Edge end-to-end cases NOT VERIFIED. |
| Price/catalog change or deleted product during outage | Immutable receipt preservation is covered locally; server rejection/reconciliation through full client flow NOT VERIFIED. |
| Organization/station switch, hostile cross-tenant caller | SQL RLS/role tests pass; hostile client through app/Edge and station-switch queue isolation NOT VERIFIED. |
| Lost device / physical stock reconciliation | SQL audited reconciliation contract tests pass; authorized operator workflow/device revocation operational E2E NOT VERIFIED. |
| Browser order vs lease | PARTIAL PASS: one simultaneous authenticated lease-RPC vs guest Edge race produced one lease winner and a 409 browser rejection; audited cleanup restored the disposable fixture. |
| Stock edit vs sale, refund/cancel vs lease, transfer/container and two-Android-device races | NOT VERIFIED as concurrent multi-client races; see writer matrix in `OWNER_BACKEND_SECURITY_AUDIT.md`. |
| Pixel Tablet, Android 16 Samsung, 16 KB runtime, older Android | Not attached/available this pass. Prior 10/11 focused evidence is historical and does not replace this phase’s missing matrix. |

No production database write/migration, Edge deployment, Cloud/Pub/Sub change, purchase, entitlement edit, destructive account test, signing-key change, AAB upload or release occurred. The connected Gradle command’s empty XML was not counted; direct instrumentation output confirms the 30-test execution.

## OFF-03 implementation and final verification — 2026-10-11

| Check | Command/environment | Result |
|---|---|---|
| Production JVM tests | `./gradlew :app:testProductionDebugUnitTest :app:testQaDebugUnitTest --no-daemon` | BUILD SUCCESSFUL; 188 tests, 0 failures/errors/skips. |
| QA JVM tests | Same command | BUILD SUCCESSFUL; 188 tests, 0 failures/errors/skips; same source suite, not 372 unique tests. Includes stale Room product stock/price versus signed reservation snapshot regression. |
| SYNC-01 regression | Included in both JVM suites; `SyncRetryPolicyTest` | Retryable 408/425/429/5xx vs permanent 4xx/409 classifications and 8-failure bound pass. |
| Manual retry persistence | Added to `SyncQueuePersistenceTest`; included in connected Samsung QA suite. | PASS: FAILED row resets retry count/error/attempt timestamp while retaining immutable operation UUID. |
| QA connected suite | `./gradlew :app:connectedQaDebugAndroidTest --no-daemon`; Samsung SM-A366B, Android 16/API 36 | BUILD SUCCESSFUL; 29/29 passed, 0 failures/errors/skips. Covers Room v21→v22 migration, outbox preservation, pending/confirmed revenue, backup exclusion, core UI and recovery. An earlier run had 1 stale UI assertion failure; fixed and rerun full suite passed. |
| Offline recovery UI | Focused `CustomerAndPaymentUiTest`; Samsung SM-A366B / Android 16 and Pixel Tablet AVD / Android 15/API 35 | 3/3 passed on each device. The receipt’s immutable values and permanent-conflict review message are asserted; unsafe retry CTA is absent. |
| Disposable Supabase SQL | `supabase test db --local --workdir /tmp/aquahub-off03-disposable` | PASS; 22 files, 221 tests. Temporary local DB only. Includes RLS/role grants, tenant scope, CAS stale revision, reserved stock floor, idempotent digest-bound sale, lease expiry retention and audited reconciliation. |
| Reservation contention | Disposable Supabase local DB, concurrent allocations against one station/product | First reservation succeeded; competing allocation beyond unreserved stock rejected; no over-allocation. |
| Edge function suite | `env -u GOOGLE_PLAY_CLIENT_EMAIL -u GOOGLE_PLAY_PRIVATE_KEY -u GOOGLE_PLAY_PACKAGE_NAME -u PLAY_TOKEN_ENCRYPTION_KEY deno test --allow-env=GOOGLE_PLAY_CLIENT_EMAIL,GOOGLE_PLAY_PRIVATE_KEY,GOOGLE_PLAY_PACKAGE_NAME,PLAY_TOKEN_ENCRYPTION_KEY supabase/functions` | 84 passed, 0 failed. No production secrets supplied. |
| Edge TypeScript | `deno check --no-lock supabase/functions/offline-pos/index.ts` | PASS. |
| Release lint and AAB | `./gradlew :app:lintProductionRelease :app:bundleProductionRelease -x :app:uploadCrashlyticsMappingFileProductionRelease --no-daemon` | BUILD SUCCESSFUL in 23m56s. R8/minification and mapping generated locally; Crashlytics mapping upload excluded. 93 lint warnings, 0 errors. |
| Historical 2026-10-11 AAB structure/manifest | Bundletool 1.18.3 `validate` + `dump manifest --module=base` | PASS. Package `com.aesprt.aquahub`, versionCode 12, versionName `aq-1.0.7`, minSdk 26, targetSdk 37, `allowBackup=false`. Size 16,279,661 bytes; SHA-256 `7466898d08cc585f1c7e901599ba4da5750a295f879e15b5b2a0ad7a35e2507d`. Current 2026-10-12 artifact identity is in the Phase 3 section above. |
| 16 KB static compatibility | Fresh AAB `dump config` and `readelf -lW` on bundled `.so` | `PAGE_ALIGNMENT_16K`; 8 native libraries, 0 misaligned PT_LOAD segments across 4 ABIs. No 16 KB runtime device/emulator test. |
| Signing | `jarsigner -verify` and signature-entry inspection | AAB unsigned; zero signature entries. No signing key changed/used, upload or Play Console operation. |

### Still blocked / not verified

The core Android-to-Edge offline sale, timeout-after-commit retry and force-stop recovery passed as described above. Remaining NOT VERIFIED cases include duplicate UI taps, process death at each commit point, long outage/reboot/clock rollback, revoked grant, authorization expiry, price/catalog changes and owner resolution, lost-device physical reconciliation, full tablet POS flow, two-device Android stock contention, station/account switch, hostile cross-org API clients, missed-FCM queue recovery, 16 KB runtime, Play signing and Console evidence. No production migration/deploy or record change occurred. The current reports retain NO-GO.

# Owner Audit Test Results

## Remediation verification — 2026-10-11

| Check | Command/environment | Result |
|---|---|---|
| Production debug JVM suite | `./gradlew :app:testProductionDebugUnitTest :app:testQaDebugUnitTest` | BUILD SUCCESSFUL; 188 tests, 0 failures, 0 errors, 0 skipped. |
| QA debug JVM suite | Same command | BUILD SUCCESSFUL; 188 tests, 0 failures, 0 errors, 0 skipped. This is the same test source run in two flavors, not 372 unique cases. |
| Account-scope recovery regression | Included in both JVM suites: `LocalAccountScopePolicyTest` | Exact profile-to-legacy UID mapping is accepted; missing mapping, mismatched local UID, conflicting bound account and same-UID false migration are rejected. |
| Financial hydration regression | Included in both JVM suites: `AuthoritativeFinancialReportTest` | Fresh-device empty Room payment cache receives remote receipt and completed refund; ₱125 receipt less ₱25 refund yields ₱100 revenue. |
| Android instrumentation compilation | `./gradlew :app:compileProductionDebugAndroidTestKotlin :app:compileQaDebugAndroidTestKotlin` | BUILD SUCCESSFUL; both source sets compile, including backup/config and Room outbox recovery fixtures. No device execution. |
| Release endpoint guard | `./gradlew :app:bundleProductionRelease --dry-run`; `./gradlew :app:preProductionReleaseBuild`; synthetic HTTP URL on same pre-build task | Validator appears before bundle; valid local production config passes; synthetic HTTP endpoint fails with an HTTPS validation error before packaging. No key printed. |
| QA/production endpoint isolation | Synthetic production host supplied to QA validator; wrong HTTPS host supplied to production validator | Both failed closed before packaging with host-specific errors; no key value was printed. Generated Production BuildConfig host was allowlisted and contained no QA config identifier. |
| Release lint and minified AAB | `./gradlew :app:lintProductionRelease :app:bundleProductionRelease` | BUILD SUCCESSFUL in 20m57s. 93 lint warnings, 0 errors. R8 completed and generated `app/build/outputs/mapping/productionRelease/mapping.txt` (136,350,956 bytes). Compose mapping parser warned on obfuscated internals in Google Places SDK. |
| Fresh AAB structure/manifest | `bundletool validate` and `bundletool dump manifest --module=base` against generated bundle | PASS. `com.aesprt.aquahub`, versionCode 12, versionName `aq-1.0.7`, minSdk 26, targetSdk 37; merged `allowBackup=false` and backup-rule references verified. File size 16,115,988 bytes; SHA-256 `58026c9bd6d53f286552f26737a5183017c8d4112b474abfae7c4568e68e48e0`. |
| Production billing-hash investigation | Targeted read-only SQL aggregate joining token hashes to event hashes; no hash/token values selected | 3 unbound hash groups, 15 events, 0 unprocessed events, all 3 lack token rows. No entitlement or billing record changed. |
| Legacy identity field protection | Targeted read-only PostgreSQL catalog query on `profiles_protect_fields` | Trigger is enabled; live function includes a Firebase UID immutability check and role guard. No profile row or policy changed. |
| Supabase Edge Function suite | `env -u GOOGLE_PLAY_CLIENT_EMAIL -u GOOGLE_PLAY_PRIVATE_KEY -u GOOGLE_PLAY_PACKAGE_NAME -u PLAY_TOKEN_ENCRYPTION_KEY deno test --allow-env=GOOGLE_PLAY_CLIENT_EMAIL,GOOGLE_PLAY_PRIVATE_KEY,GOOGLE_PLAY_PACKAGE_NAME,PLAY_TOKEN_ENCRYPTION_KEY supabase/functions` | 84 passed, 0 failed (2s); test-only environment permission, real secret variables unset. No deployment. |
| Native/16 KB static check | Parse all native `.so` PT_LOAD headers in the fresh AAB | 8 libraries, ABIs arm64-v8a/armeabi-v7a/x86/x86_64, 0 misaligned PT_LOAD segments. Runtime on a 16 KB device/emulator remains NOT VERIFIED. |
| Release signing | `jarsigner -verify` and AAB entry inspection | AAB is unsigned; no `META-INF` signature entries. Play App Signing/upload certificate and artifact provenance remain NOT VERIFIED. No AAB was uploaded to Play. |
| Connected devices | `adb devices -l`; Samsung SM-A366B Android 16 and Pixel Tablet AVD Android 15 | QA suite passed 29/29 on Samsung; focused recovery Compose class passed 3/3 on Samsung and 3/3 on tablet. Physical 16 KB runtime, full tablet POS, backup restore and actual POS process-death/reconnect flows remain NOT VERIFIED. |
| QA release bundle | QA release pre-build with a synthetic production endpoint | Rejected as expected. A valid QA endpoint/key was not available, so no QA release bundle was built. |
| Crashlytics build task | Gradle output from the release bundle command | `uploadCrashlyticsMappingFileProductionRelease` executed as part of the task graph. Treat the R8 mapping file as potentially uploaded to Firebase Crashlytics; the quiet Gradle log did not expose remote acceptance details. This was not a Play AAB upload, backend deployment, or Cloud configuration change. No cleanup action was attempted. |

### Before/after summary

The audit baseline had 171 passing JVM tests per flavor and a connected QA run of 21/22 because the generic instrumentation test hardcoded the production package ID. The current suites contain 188 tests per flavor. The workspace already had extensive uncommitted migration/test work, so the 11-test count increase is not attributed entirely to this remediation. This pass adds explicit exact-UID legacy-mapping coverage and strengthens the existing financial report fixture. The QA fixture now compares against variant `BuildConfig.APPLICATION_ID` and both instrumentation source sets compile. Final connected rerun now passes 29/29 on Samsung after updating the incorrect permanent-conflict retry assertion; focused UI passes on Samsung and Pixel Tablet.

## Baseline audit verification before remediation

**Date:** 2026-10-11. Commands were run in the Owner Gradle root unless noted. The following table preserves pre-remediation evidence; it is superseded where the remediation verification above has a newer result. No production backend writes/migrations, Edge Function deployment, Pub/Sub or Cloud setting change, real purchase, signing-key use/change, Play AAB upload, or rollout occurred. The Crashlytics mapping task disclosure above is separate.

| Check | Command/environment | Result |
|---|---|---|
| Production debug JVM tests | `./gradlew :app:testProductionDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL; JUnit XML: 171 tests, 0 failures, 0 errors, 0 skipped; 5m04s. |
| QA debug JVM tests | `./gradlew :app:testQaDebugUnitTest` (also earlier combined run) | BUILD SUCCESSFUL; 171 tests, 0 failures/errors/skips. These are the same suite in another flavor, not 342 unique test cases. |
| QA connected device tests | `./gradlew :app:connectedQaDebugAndroidTest`; Samsung SM-A366B, Android 16/API 36 | **22 tests, 21 passed, 1 failed**, 13.736s test time. Failure: `ExampleInstrumentedTest.useAppContext` expects `com.aesprt.aquahub`, actual QA package is `com.aesprt.aquahub.qa` (`ExampleInstrumentedTest.kt:22`). Feature-focused tests passed. |
| Backend function suite | `env -u GOOGLE_PLAY_CLIENT_EMAIL -u GOOGLE_PLAY_PRIVATE_KEY -u GOOGLE_PLAY_PACKAGE_NAME -u PLAY_TOKEN_ENCRYPTION_KEY deno test --allow-env=GOOGLE_PLAY_CLIENT_EMAIL,GOOGLE_PLAY_PRIVATE_KEY,GOOGLE_PLAY_PACKAGE_NAME,PLAY_TOKEN_ENCRYPTION_KEY supabase/functions` | 84 passed, 0 failed. Test-only env permissions; real secret variables unset. |
| Billing/RTDN selected tests | Deno subset under `supabase/functions` | 21 passed, included in 84 total (do not add again). |
| Release lint | `./gradlew :app:lintProductionRelease` | Reached `lintAnalyzeProductionRelease`; task was interrupted after several minutes while the Gradle daemon was still busy. **NOT VERIFIED.** |
| Release minification | `./gradlew :app:minifyProductionReleaseWithR8` | R8 began but did not finish after more than 12 minutes; run was stopped without a result. **NOT VERIFIED.** No release signing config was found in `app/build.gradle.kts`. |
| Release bundle | Not submitted or published. | Do not infer an AAB pass unless generated and inspected in a controlled build. |
| AAB 16 KB ELF check | Existing modified `app/release/app-release.aab` parsed in memory | 8 native libraries, 0 misaligned ELF LOAD segments; ABIs arm64-v8a, armeabi-v7a, x86, x86_64. This does not certify source provenance, ZIP alignment, signing, or runtime. |
| Live Supabase read-only metadata | Supabase MCP project/API metadata and advisor | ACTIVE_HEALTHY, migration present, play-rtdn v26, RTDN rows 44/44 processed. No broad logs query; no SQL write. |

## Not run / blocked

- Full production flavor instrumented install: avoided to protect the installed `com.aesprt.aquahub` app and its data.
- Offline/process-death/upgrade/multi-device end-to-end tests; auth and tenant adversarial tests; disposable Supabase migration/rules suite (no explicitly identified disposable target); Play license transactions; physical tablet/older Android matrix; TalkBack and accessibility scan; 16 KB runtime; Play Console pre-launch report and Android vitals.
- APK/AAB upload/signing, production deployment, billing mutation and destructive account tests were not authorized and not performed.

Build emits deprecated Gradle/API/compiler warnings noted in logs; no test failure was attributed to these. Audit reports are evidence summaries, not a claim that all feature behavior is verified.
