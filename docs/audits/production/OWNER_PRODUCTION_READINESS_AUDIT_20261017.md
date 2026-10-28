# AquaHub Owner Production Readiness and Release Audit

**Audit date:** 2026-10-17 (Asia/Manila)
**Scope:** Owner Android app, linked Supabase production project `pfkxrggexueltytxtqil`, local website source for `aquahub.aesprt.com`, and release evidence available in this workspace.
**Verdict: NO-GO.** No P0 exploit was confirmed; multiple P1 production gates remain open. The latest live check shows `offline-pos` v2 active with JWT verification enabled. No database, website, or Google Play resource was changed in this recheck.

## Executive result

The previously missing `offline-pos` Supabase Edge Function is active in production as v2 with `verify_jwt=true`; retrieving the deployed source confirmed an exact match with the local entrypoint. Its PostgreSQL dependencies were already present, and migration history matches at 69/69. Prior disposable-environment evidence records 226 SQL assertions across 22 files and a real Android offline sale/replay path with exactly one order, payment, and inventory movement. The complete expiry, revocation, clock/reboot, two-device, and tenant fault matrix remains open; that disposable environment is no longer available for a rerun. The Android verifier key and upload signing configuration are now present locally, and a signed production AAB has been generated. Production Edge signing-secret presence/key match and Play Console upload-certificate compatibility remain unverified. No production sale/grant request was sent.

The new `google-services.json` is in the Owner app module and identifies the expected Firebase project and package. QA unit tests and static website tests passed. The QA Lint hard error was fixed; final QA and production Lint runs completed with zero errors, leaving 87 and 85 warning-level findings respectively. The latest production release build passed and produced a signed AAB. Its signer fingerprint matches the configured local keystore alias, and its package/version/target SDK and native library alignment were inspected. Play Console certificate compatibility and upload acceptance have not been checked; no upload or publication was performed.

Local account-deletion copy and implementation were corrected. The updated website is not deployed or publicly verified: the custom domain returned HTTP 403 in this environment, while the legacy Firebase Hosting URL still returned old copy. The local deletion function now queues marketing-consent removal before deleting the Owner identity, but this change is not deployed and partial-failure/retry behavior has not been failure-injection tested.

## Findings matrix

| Requirement | Existing state | Required action | Severity |
|---|---|---|---|
| Offline POS | `offline-pos` v2 is active in production with JWT verification enabled; deployed entrypoint matches local source. The six tables and seven routines are present. | Verify matching Edge signing secrets, finish remaining failure matrix, and run approved non-destructive integration smoke tests. | P1 blocker (functional readiness) |
| Database dependencies | Local and production migration histories match 69/69; offline POS migrations are applied. Prior disposable SQL suite passed 226 assertions. | No migration needed. Verify rollback/recovery readiness and complete remaining conflict cases before relying on offline POS for release. | P1 gate |
| Google Services | Module config present; package and project IDs match Owner app. | Compare Firebase Android SHA clients with the actual Play upload certificate once available. | Open verification |
| Production AAB | Signed `productionRelease` AAB generated; signer matches configured local keystore alias. Play Console upload certificate and acceptance are not verified. | Owner compares certificate with Play Console and chooses whether to upload; complete device and 16 KB runtime checks. | P1 verification gate |
| Android Lint | Final QA and production Lint passed with zero errors (87 and 85 warning-level findings). | Review SARIF findings and fix actionable warnings; no broad suppression. | Pass with warnings |
| Billing | Backend functions and aggregate RTDN/token rows were inspected; no Play Console or license-tester access. | Test all purchase/renewal/cancel/refund/restore paths with license testers and verify product/base-plan setup. | P1 unverified |
| Account deletion | Local app flow, backend handler, privacy policy, and deletion page copy aligned. Production handler/site remain old. | Isolated retry/failure tests; approve and deploy website and handler; verify public URL and Play declaration. | P1 blocker |
| Supabase quota | Historical use exceeded included Free invocation/log-ingest allowances; recent month-to-date meter unavailable. 246 dispatch and one reconcile 503 appeared in the 24-hour sample. | Establish current meter trend, root-cause repeated 503s, verify any optimization against production telemetry; choose plan/monitoring. | P1 operational gate |
| Device testing | No attached ADB device or emulator. | Run phone/tablet, offline/reboot, billing, onboarding and POS suites on suitable devices. | BLOCKED |

## Scorecard

| Component | Result | Evidence |
|---|---|---|
| Offline POS deployed | PASS | Fresh live inventory: `offline-pos` v2 `ACTIVE`, `verify_jwt=true`; retrieved deployment source exactly matches local source. |
| Offline reconciliation | NOT VERIFIED | Core disposable Android→Edge→PostgreSQL sale, lost-response, and idempotent replay passed; expiry/revocation/reboot/two-device/tenant cases remain open. |
| Inventory consistency | NOT VERIFIED | Disposable reservation and one lease-vs-guest-order race passed; stock-edit, refund/cancel, and two-Android-device races remain open. |
| Financial consistency | NOT VERIFIED | No reconciliation/property suite; local money model still uses `Double` in places. |
| Supabase migrations | PASS | Production/local histories match 69/69; no pending migration. |
| Supabase quota mitigation | NOT VERIFIED | Current billing-period meter and patch impact unavailable; recurring 503s require investigation. |
| Authentication | NOT VERIFIED | Static review only; no device/onboarding/session/reauth end-to-end suite. |
| Google Services | PASS | Module file, Firebase project, and Owner package validated; release SHA match remains open. |
| Play Billing | NOT VERIFIED | No Play Console/license tester lifecycle executed. |
| Backend security | NOT VERIFIED | Prior disposable SQL role/tenant tests passed, but effective production Data API exposure and hostile tenant/RPC tests have not been verified. |
| Account deletion | FAIL | Local fixes only; production page/handler are not deployed and live domain verification failed. |
| Android Lint | PASS | Final QA Lint: zero errors, 87 warnings. Production Lint: zero errors, 85 warnings. |
| QA regression | NOT VERIFIED | 190 JVM tests, 87 Deno tests, 14 website tests, prior 30-test emulator suite, and core offline POS E2E pass; critical fault matrix remains incomplete. |
| Device testing | BLOCKED | `adb devices -l` showed no usable device; no emulator tooling available. |
| Signed production AAB | PASS | `bundleProductionRelease` succeeded; AAB is signed with the configured alias. Package/version metadata, ZIP integrity, signer fingerprint match, R8 mapping outputs, and 16 KB ELF segment alignment checked. Play Console acceptance is not verified. |
| Google Play compliance | NOT VERIFIED | Console declarations, listing, account deletion URL, signing, and testing track not accessible. |
| Operational readiness | FAIL | Free plan, backup/restore evidence absent, recurring 503s unresolved, monitoring/rollback not verified. |

## Local changes prepared

- Removed the QA manifest's stale Crashlytics provider removal that produced the Lint `MissingClass` error; corrected auth intent-filter structure and minor Kotlin lint findings.
- Added a strict production build gate for the expected Supabase project, a P-256 offline POS verification key, and the user's existing upload keystore configuration. No key material is checked in or printed.
- Fixed account-deletion reauthentication routing, clarified in-app deletion/retention/subscription behavior, and added a unit test.
- Updated local website deletion/privacy wording and aligned the Filipino FAQ with the Owner app path.
- Added the offline POS disable switch so future rollback can stop new offline grants while allowing already-authorized sales to reconcile.
- Updated deletion backend to remove active marketing consent through the authenticated preference path before deletion; this change needs isolated retry/failure tests before deployment.
- Refreshes the Owner Orders queue when the screen resumes to recover missed notifications; documents the refresh behavior in Owner architecture context.
- Moved the logo to `drawable-nodpi` and removed unused generated launcher drawables.

Existing user modifications to `AquaHub/gradle.properties`, `gradle/gradle-daemon-jvm.properties`, `gradle/libs.versions.toml`, and `gradle/wrapper/gradle-wrapper.properties` were present before these changes and were preserved.

## Test evidence and limitations

- `:app:testQaDebugUnitTest :app:assembleQaDebug --no-daemon --max-workers=2` — **190 passed, 0 failed, 0 skipped**; QA APK at `AquaHub/app/build/outputs/apk/qa/debug/app-qa-debug.apk`.
- `:app:lintQaDebug --no-daemon --max-workers=1` — final run succeeded in 7m59s with zero errors and 87 warning-level findings. `:app:lintProductionRelease --no-daemon --max-workers=1` — final run succeeded in 7m10s with zero errors and 85 warning-level findings. SARIF reports are under `AquaHub/app/build/reports/`.
- `/tmp/aquahub-deno/bin/deno test --allow-env --allow-net --allow-read=supabase/functions supabase/functions` — **87 passed, 0 failed**; these are not a dedicated offline POS reconciliation suite.
- `/tmp/aquahub-deno/bin/deno check supabase/functions/*/index.ts` — all 14 entrypoints typechecked.
- Website `node --test test/*.test.js` — **14 passed, 0 failed**.
- The generated AAB passed ZIP integrity verification; all 8 packaged native libraries across four ABIs have `PT_LOAD` alignment at least `0x4000`. Runtime behavior on a 16 KB page-size device remains unverified.
- `git diff --check` — passed.
- Production migrations were inspected read-only; no production schema or data was changed. Prior isolated tests recorded `supabase test db --local --workdir /tmp/aquahub-off03-disposable` with 226 assertions across 22 files, and an Android E2E covering offline persistence, force-stop recovery, a dropped successful response, idempotent retry, and exactly one order/payment/movement. One simultaneous lease-vs-guest-order race also passed. The temporary environment is gone, and Supabase CLI/Docker/psql are unavailable now, so the broader fault matrix cannot be rerun here.
- Prior QA instrumentation on an API 35 phone emulator ran 30/30 tests; the disposable offline POS path passed separately. `adb devices -l` currently returns no device, so physical-device, tablet/foldable, Play Billing, reboot, and remaining workflow testing are blocked.
- Initial bundle attempts exposed and then corrected the release-configuration guard. After local key and signing configuration became available, `:app:bundleProductionRelease` completed successfully in 20m41s. The generated signed AAB is documented in the Android build/Lint report; no Play upload was attempted.

Detailed records: [defect register](OWNER_DEFECT_REGISTER_20261017.md), [deployment manifest](OWNER_DEPLOYMENT_MANIFEST_20261017.md), [database verification](OWNER_DATABASE_MIGRATION_VERIFICATION_20261017.md), [Edge Function report](OWNER_EDGE_FUNCTION_DEPLOYMENT_20261017.md), [Android build/Lint report](OWNER_ANDROID_BUILD_LINT_20261017.md), [E2E QA results](OWNER_END_TO_END_QA_20261017.md), [Play checklist](OWNER_GOOGLE_PLAY_COMPLIANCE_20261017.md), [release notes](OWNER_RELEASE_NOTES_20261017.md), and [rollback/incident instructions](OWNER_PRODUCTION_ROLLBACK_20261017.md).

## Supabase plan and quota assessment

The linked organization reports the Free plan. Historical figures supplied for the last billing cycle (747,541 Edge invocations, 4.479 GB log ingestion, 244.176 GB log query) exceed the included Free invocation and ingestion quantities, but do not establish current month-to-date use. Current docs state Free includes 500,000 Edge invocations and 1 GB log ingestion; log ingestion above the included amount is not billed during the announced grace period before 2027. Log query has a 100 GB included quantity and is not enforced/billed before 2027; excess can affect log availability. A 24-hour aggregate sample returned 246 `marketing-reach-dispatch` 503 calls plus one `marketing-reach-reconcile` 503; that sample is not a billing meter. Do not claim the reported optimization is effective until a comparable current usage trend and root-cause analysis are available.

Free may pause after a week of inactivity and has no downloadable database backups/PITR. For a public business app, Pro is the minimum sensible plan absent an independently verified backup/restore and uptime design. No plan change or billing action was taken. Sources: [Supabase pricing](https://supabase.com/pricing), [Free project pausing](https://supabase.com/docs/guides/platform/free-project-pausing), [backups](https://supabase.com/docs/guides/platform/backups), [log ingestion policy](https://supabase.com/docs/guides/platform/manage-your-usage/logs-ingest), and [log query policy](https://supabase.com/docs/guides/platform/manage-your-usage/logs-query).

## Deployment decision and next acceptance gates

The user authorized deployment of the missing `offline-pos` function, and that deployment is complete. The latest live inventory confirms version 2 active with `verify_jwt=true`; the retrieved deployed source matches the local entrypoint. No production database, website, or Play Console change was made in the latest recheck. Signing-key consistency and Edge secret presence cannot be verified through the available tools, the broader fault matrix is incomplete, and a business-mutating smoke request was not sent. This deployment did not include the separate local `delete-account` update.

1. Recreate an isolated Supabase instance and rerun the migration/RPC/RLS/concurrency/idempotency suite; test offline grant and lease expiry, response-loss retries, changed payloads, concurrent final-stock sales, and rollback switch behavior. Prior 226-assertion suite and core E2E are evidence, but not a complete current matrix.
2. Verify the production offline signing keys correspond and required Edge secrets exist without exposing their values. Confirm rollback/recovery procedure and a recoverable production backup/restore point.
3. Failure-inject account deletion through each cleanup step and verify retries complete safely. Validate local web copy and hosting artifact in a preview/staging channel.
4. Investigate recurring marketing dispatch/reconcile 503s, verify the recent usage optimization against live telemetry, and document operational thresholds.
5. Verify that the Android public verifier key matches the production Edge signing pair, compare the built AAB signer fingerprint with the Play Console upload certificate, and complete owner-controlled Play upload acceptance without publishing. The signed AAB is already built locally.
6. Complete license-tester Billing, tenant isolation, and device matrix tests. Verify Play Console data safety/account-deletion declarations.

After those remaining gates pass, run a safe, approved production integration check for `offline-pos`. Do not include unrelated functions, database changes, or website changes without separate authorization. The `offline-pos` Edge Function deployment has been performed; production database and website changes have not. Play upload/publication/rollout have not been performed.

## Official Play policy references

As of this audit, Google Play requires new apps and updates to target API 36 or higher; this app targets 37. Account deletion for apps that allow account creation requires an in-app path and a publicly accessible web resource to request deletion. Target API and account deletion still need Console verification and live URL verification. See [target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en), [account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en), and [Android 16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes).
