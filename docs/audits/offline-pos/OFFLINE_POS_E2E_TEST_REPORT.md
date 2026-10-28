# AquaHub Owner Offline POS E2E Run — QA E2E and ProductionDebug Follow-up

**Run date:** 2026-10-17

**Result:** INSUFFICIENT EVIDENCE
**Production data changes:** None

## Latest variant selection — 2026-10-17

Per the requested correction, build validation now uses **`productionDebug`**, not `qaDebug`. The production-flavor app and Android test APK assembled; `testProductionDebugUnitTest` passed 190/190 tests, and `lintProductionDebug` completed with 87 warnings and 0 errors. The production APK is `AquaHub/app/build/outputs/apk/production/debug/app-production-debug.apk` (package `com.aesprt.aquahub`, version 12 / `aq-1.0.7`, SHA-256 `ff6a4baba35c4f98882c5281308ebde43c96ab8035c628ba8f2d0257b4dc4e79`). Its DEX contains the production Supabase host and configured production verifier key; it does not contain the QA loopback host. The exact Gradle invocation and sanitized checks are recorded in `evidence/production-debug-build-summary.txt`; the complete build log is `evidence/production-debug-build.log`.

This was build/configuration validation only. `productionDebug` targets the live production project, so it was not installed or used to register an owner, create business data, issue an offline grant, submit a transaction, or exercise backend workflows. The prior tablet startup attempt used the isolated `qaDebug` package and remains separate evidence. I retried both the low-resolution Tablet AVD and the existing Pixel Tablet AVD for this variant; neither appeared in ADB, so the productionDebug APK was not installed. The Offline POS scenarios below therefore remain blocked; the productionDebug instrumentation APK was assembled but instrumentation tests were not run. The physical phone was not used.

## Scope and safety

The original tablet workflow attempt used the QA Android flavor with the disposable local Supabase stack. The follow-up build validation used `productionDebug` as requested. The production project `pfkxrggexueltytxtqil` was inspected read-only; no production user, business, order, inventory, payment, schema, function, secret, or setting was created or changed. Existing production records were treated as valuable and left untouched.

## Safe local changes made

- The QA Android flavor receives its separate `OFFLINE_POS_QA_VERIFY_PUBLIC_KEY` as `BuildConfig.OFFLINE_POS_VERIFY_PUBLIC_KEY`; the generated QA APK was checked to contain the isolated endpoint and matching QA verifier. Production key configuration was not changed.
- `supabase/config.toml` now explicitly declares the existing `offline-pos` function enabled with `verify_jwt=true`, matching the observed production setting. TOML/value validation passed; this was not deployed.
- No Offline POS business logic, production database schema/RLS, production Edge Function, or live data was modified.

The production inventory snapshot contained 3 Auth users, 2 owner profiles, 1 organization, 1 station, 1 product, 1 inventory balance, 8 stock movements, 2 customers, 7 orders, 7 order items, 5 payments, 0 refunds, 0 expenses, 1 subscription, and 3 Play purchase tokens. These are production counts, not test data, and were not used by this run.

The disposable local database began with zero rows in the relevant Auth/profile/organization/station/product/inventory/customer/order/payment/expense/offline-POS records. All repository SQL test files completed successfully. One transient `.test` Auth identity was created through the local stack’s configured email auto-confirm setting solely to test function JWT and signer validation; it was deleted immediately afterward (admin DELETE 200, lookup 404, profile query empty). No app owner profile, station, product, sale, or payment was created. This does not validate email/phone verification UX or count as an app registration.

| Resource | Required clean-run state | Local baseline / end state | Result |
|---|---|---|---|
| Test owner | Newly registered through app UI | No app owner; transient Auth-only probe was deleted | BLOCKED |
| Test station | One newly provisioned station | 0 stations; no test station created | BLOCKED |
| Products / inventory | 0 before seed | 0 products, balances, and movements | PASS — empty baseline |
| Customers / sales / orders / payments | 0 before workflows | 0 rows | PASS — empty baseline |
| Expenses / grants / leases / server sync records | 0 before workflows | 0 rows | PASS — empty baseline |
| Pending Room transactions | 0 before workflows | Room was not queried | NOT VERIFIED |

## Original tablet attempt environment (`qaDebug`)

| Item | Observed |
|---|---|
| Android project | `AquaHub/` |
| Variant used for the original tablet run | `qaDebug` |
| Original package / APK | `com.aesprt.aquahub.qa`; `AquaHub/app/build/outputs/apk/qa/debug/app-qa-debug.apk` |
| Original backend URL | Local emulator bridge to disposable Supabase at `10.0.2.2:24554` |
| Tablet profile | Pixel Tablet, Android API 35, Google Play x86_64, 2560×1600, 320 dpi |
| Renderer | Software ANGLE/SwiftShader workaround; host GPU mode had crashed in Mesa |
| Host / SDK | Fedora Linux, 16 GB RAM, KVM available, about 198 GB free on workspace filesystem; Android SDK `/home/adrielespiritu/Android/Sdk` |
| Tool versions | Java 25, Gradle wrapper 9.8.1, Supabase CLI 2.121.0 |
| Original QA app install | `adb install` succeeded on the Pixel Tablet AVD; no QA package was previously installed there |
| Final device state | Tablet AVD stopped after repeated system/ADB stalls; connected Samsung phone was not used |
| Release build | Not attempted as an E2E test target; no AAB was produced in this run |

The QA flavor is intentionally isolated: it has the `.qa` application ID, local backend URL, and a disposable QA verifier key. It does not validate the production signing key or the production backend integration. The local public-key fingerprint was `750489e28f101e3e36a1c39edb23c6963a2c205e6d73195bf3b056514973cb15`; the private key remained in a mode-600 temporary environment file and is not included in this report.

## Startup observation

The app process reached AquaHub’s onboarding UI. Android then displayed **“Process system isn’t responding.”** `dumpsys activity lastanr` identified the system process and reported: `Input dispatching timed out (PointerEventDispatcher0 (server) is not responding. Waited 14183ms for MotionEvent).` The collected reason does not establish an AquaHub main-thread ANR, but the dialog blocked reliable interaction. Registration, station provisioning, and business workflows were not completed.

The emulator was using software rendering and consumed over two host CPU cores during the first tablet attempt. A separate fresh profile (`AquaHub_OfflineQA_Tablet_API35_LowRes`) and a subsequent cold boot did not become ADB-ready. Existing AVDs and snapshots were not deleted. Screenshots are in `evidence/tablet-notification-permission.png` and `evidence/tablet-system-process-anr.png`.

## Backend and signing observations

- Production `offline-pos` was previously observed ACTIVE at version 2 with `verify_jwt=true`; this run did not call or modify it.
- The repository config initially lacked an explicit `offline-pos` section. I added `[functions.offline-pos] enabled=true verify_jwt=true` to `supabase/config.toml`, matching the observed production setting. TOML parsing and value assertions passed. This source-only change was not deployed.
- The production signing secrets were not read or printed. Their existence had been verified by a prior read-only configuration check, but this run did not perform a production cryptographic challenge.
- The QA APK verifier matched the disposable local signing pair’s public-key fingerprint. An authenticated direct-function challenge with a deliberately wrong fingerprint returned `503 OFFLINE_SIGNING_CONFIGURATION_INVALID`; the matching QA fingerprint passed the private/public pair and fingerprint check and reached station authorization, which safely rejected a random nonexistent station with `403 DEVICE_REGISTRATION_REJECTED`. No grant, lease, device, or stock reservation was created. This validates only QA test key material.
- Local Auth health returned HTTP 200. A POST to the local gateway route `/functions/v1/offline-pos` returned HTTP 404, body `Function not found`.
- `supabase functions serve offline-pos` could not start against this native local stack: `ServeLocalDbInspectError: no such container "supabase_db_WaterStationApp"`. The route was not made available by this CLI/runtime combination. No production deployment was attempted to work around it.
- The function source was served directly with Deno on loopback against the disposable stack to test its own auth and signer code. No-auth and invalid-JWT requests returned 401; GET returned 405; OPTIONS returned 200. This bypassed the Supabase gateway, so gateway `verify_jwt=true` behavior was not exercised locally.
- Therefore grant issuance, lease signing to the Android client, client-side verification, transaction commit/reconciliation, and idempotency remain unverified by this run.

The local disposable schema has one relevant parity gap: `public.order_number_counters` had RLS disabled locally, while the read-only production check showed RLS enabled and direct grants limited to `service_role`. No schema or policy was modified. The local SQL suite passed, but its results do not establish production RLS behavior for this table.

## Commands and automated results

| Command | Environment | Result |
|---|---|---|
| `./gradlew :app:testQaDebugUnitTest :app:assembleQaDebug --no-daemon --max-workers=2` | QA Gradle build | PASS; 190 JVM tests, 0 failures/errors/skips; APK assembled. |
| `set -a; . /tmp/aquahub-offline-pos-qa/android-build.env; set +a; ./gradlew :app:assembleQaDebug :app:assembleQaDebugAndroidTest --no-daemon --max-workers=2` | QA Gradle build with local-only URL, anon key, and verifier | PASS; app APK and instrumentation APK assembled; APK DEX contains the local endpoint and matching QA public key. |
| `./gradlew :app:lintQaDebug --no-daemon --max-workers=1` | Final QA source and manifest | PASS; 87 warnings, 0 errors; task completed successfully in 9m04s. |
| `supabase test db --local` | Disposable local Postgres | PASS; 22 SQL test files, 226 assertions. This is database/RLS coverage, not UI or complete Edge Function E2E coverage. |
| `/tmp/aquahub-deno/bin/deno test --allow-env --allow-net --allow-read=supabase/functions supabase/functions` | Local function unit tests | PASS; 87 passed, 0 failed. The suite has no `offline-pos`-specific test entrypoint. |
| `/tmp/aquahub-deno/bin/deno check supabase/functions/*/index.ts` | Local Deno | PASS; exit code 0 for the 14 function entrypoints. |
| `adb -s emulator-5554 install /home/adrielespiritu/StudioProjects/Aquahub/AquaHub/app/build/outputs/apk/qa/debug/app-qa-debug.apk` | Pixel Tablet AVD | PASS; package installation completed. |
| `adb -s emulator-5554 shell am start -W -n com.aesprt.aquahub.qa/com.aesprt.aquahub.MainActivity` | Pixel Tablet AVD | BLOCKED; activity was surfaced, then system ANR blocked interaction. |
| `./gradlew :app:connectedQaDebugAndroidTest --no-daemon --max-workers=2` | Tablet instrumentation | BLOCKED / not executed; there was no stable ADB-ready tablet after the system ANR. 0 tests executed; the connected physical phone was intentionally not used. |
| `supabase functions serve offline-pos ...` | Native local stack | BLOCKED; missing `supabase_db_WaterStationApp` container. |
| Python `tomllib` parse plus `functions.offline-pos` assertions | Repository config | PASS; enabled and `verify_jwt=true`; no deploy performed. |
| `POST http://127.0.0.1:24554/functions/v1/offline-pos` | Local gateway | FAIL for test setup; HTTP 404 `Function not found`. This is not evidence that the deployed production function is missing. |
| `SUPABASE_URL=http://127.0.0.1:24554 /tmp/aquahub-deno/bin/deno run --allow-env --allow-net --allow-read --env-file=/tmp/aquahub-offline-pos-qa/functions.runtime.env supabase/functions/offline-pos/index.ts` plus loopback auth/signature probes | Isolated local function and database | PASS for function-level Auth and signer guards; wrong fingerprint rejected before RPC, correct QA fingerprint reached expected nonexistent-station authorization rejection; test Auth user deleted. No grant or transaction was created. |
| `git diff --check` | Workspace | PASS. |
| `./gradlew :app:testProductionDebugUnitTest :app:lintProductionDebug :app:assembleProductionDebug :app:assembleProductionDebugAndroidTest --no-daemon --max-workers=2 --console=plain` | Production flavor, debug build type | PASS; 190 unit tests passed, 0 failures/errors/skips; `productionDebug` Lint completed with 87 warnings and 0 errors; production app APK and instrumentation APK assembled. This command did not run connected instrumentation tests. |

The original QA Lint run had 87 unsuppressed warnings: ExifInterface 2, Typos 35, debug receiver export 1, obsolete SDK check 1, unused resources 9, icon location 2, and `UseKtx` 37; its report is `AquaHub/app/build/reports/lint-results-qaDebug.html`. The follow-up `productionDebug` Lint run also completed with 87 warnings and 0 errors; its report is `AquaHub/app/build/reports/lint-results-productionDebug.html`.

## Business scenarios

None of the specified sales were entered. The expected POS-001 amount and the four required offline sales total ₱425; actual test sales, orders, and payments are all 0 because setup stopped before creating a test owner or catalog. This is a blocked run, not a successful reconciliation.

The proposed five-sale expected inventory (Purified 95, Alkaline 48, Mineral 198, Empty Container 19) is not applicable: no products or stock were seeded. Room pending operation count, WorkManager state, device reboot/clock behavior, and local receipts were not queried.

## Evidence index

- `evidence/tablet-notification-permission.png` — onboarding notification prompt.
- `evidence/tablet-system-process-anr.png` — Android system ANR over AquaHub onboarding.
- `evidence/runtime-findings.txt` — observed ANR, build identity, and local gateway result.
- `evidence/local-offline-pos-route-response.txt` — local route response body.
- `evidence/direct-function-auth-crypto-results.txt` and `evidence/transient-auth-cleanup-verification.txt` — sanitized HTTP outcomes and cleanup check; no credentials or tokens.
- `evidence/qa-test-resource-manifest.txt` — exact ID and lifecycle for the temporary local Auth probe; no business resources were created.
- APK SHA-256: `69e41c8d2657a31b33e524312b29932dde72c3747e861813995f8e64dd5a9456`.
- ProductionDebug APK SHA-256: `ff6a4baba35c4f98882c5281308ebde43c96ab8035c628ba8f2d0257b4dc4e79`; metadata/config checks are in `evidence/production-debug-build-summary.txt`.
- Historical disposable integration evidence from 2026-10-12 remains documented in `../production/OWNER_TEST_RESULTS.md`; it was not rerun and is not counted as evidence for the scenarios below.

## Required next acceptance run

1. Run the local Edge Function using a supported isolated Supabase development stack, or provision an authorized disposable QA/staging project; confirm the function route returns an authenticated response and all required environment variables without exposing their values.
2. Boot a Google APIs/Play Store tablet AVD or physical QA tablet with stable rendering and no system ANR. Capture OS/API/display properties and keep the run scoped to the QA backend.
3. Register a new synthetic test owner through app UI, create one station, seed the four products through UI, and verify the zero-data baseline first.
4. Execute OFF-001 through OFF-015; retain Room IDs, server order/payment/movement counts, WorkManager states, screenshots, and failure traces.
5. Reconcile all amounts and inventory before considering this offline POS release gate passed.
