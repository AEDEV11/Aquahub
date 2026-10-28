# Offline POS Release Verdict — 2026-10-17

## Verdict: INSUFFICIENT EVIDENCE

The isolated SQL suite, Android JVM tests, QA APK build, and function unit/type checks passed. They do not prove the requested Android Room → `offline-pos` → PostgreSQL reconciliation path. No test owner, product, inventory, sale, payment, grant, lease, or pending Room operation was created during this run.

The production Edge Function had previously been observed ACTIVE at version 2 with JWT verification enabled. This run did not send it a request or change production. The local gateway did not expose the function, and the Android Tablet AVD’s system ANR prevented onboarding and workflow interaction.

Per the requested variant correction, validation was repeated on `productionDebug`: 190/190 JVM unit tests passed, production-flavor Lint passed with 87 warnings and 0 errors, and the app plus instrumentation APKs assembled. Package/version/backend/key wiring were checked without exposing key material. The APK is debug-signed, not Play-upload signed. It was not installed or used against production data. Retries of the low-resolution tablet and Pixel Tablet AVDs did not make an emulator device visible to ADB; the connected Samsung phone was left untouched. See `evidence/production-debug-build-summary.txt` and `evidence/production-debug-device-check.txt`.

## Release gates

| Gate | Status | Evidence / acceptance required |
|---|---|---|
| Local SQL invariants | PASS | 22 SQL files / 226 assertions passed in disposable DB. |
| Local/production RLS parity for `order_number_counters` | NOT VERIFIED | Local RLS is disabled; production RLS is enabled with grants limited to `service_role`. Reconcile disposable schema and re-run targeted authorization assertions. |
| Android JVM regression | PASS | 190 passed, 0 failed, 0 skipped. |
| QA APK and Android test APK build | PASS | `qaDebug`, v12 `aq-1.0.7`; artifacts built. |
| ProductionDebug APK, Android test APK, unit tests, and Lint | PASS | `productionDebug`; 190 tests passed, 87 Lint warnings / 0 errors; APK SHA-256 and sanitized production config checks in `evidence/production-debug-build-summary.txt`. APK signer is Android Debug, not upload signer. |
| ProductionDebug runtime / instrumentation | BLOCKED | AVD never appeared in ADB; no APK installation or connected instrumentation execution. The variant targets production, so no auth or transaction was attempted. |
| QA Deno function tests and typecheck | PASS | 87 unit tests passed; 14 function entrypoints typechecked. No offline-pos-specific unit file was found. |
| QA signer pair / fingerprint code path | PASS (QA only) | Direct Deno function check with a transient local Auth identity: wrong fingerprint rejected before RPC; matching QA fingerprint passed signer validation and reached expected nonexistent-station authorization rejection. The test identity was deleted. |
| Repository `offline-pos` JWT config | PASS | `supabase/config.toml` now explicitly enables the function with `verify_jwt=true`; TOML/value validation passed. Source-only change; not deployed. |
| QA tablet installation | PASS | APK installed on Pixel Tablet AVD. |
| Interactive tablet onboarding | BLOCKED | Android system pointer-dispatch ANR blocked input. Acceptance: stable tablet run completes registration/station setup without ANR. |
| QA app-to-function gateway integration | BLOCKED | Direct Deno function guards were exercised, but the local Supabase route returns 404 and CLI function serve cannot inspect the expected DB container. Acceptance: isolated valid-JWT authorize/commit/retry calls through the gateway. |
| Offline grant/lease/client signature verification | NOT VERIFIED | QA signer guard passed, but no grant/lease was issued and the installed app did not verify one. Acceptance: grant and lease verified by the correctly configured app and confirmed in DB. |
| Orders, payments, stock, idempotency, Room recovery | NOT VERIFIED | No transactions. Acceptance: OFF-001–OFF-015 evidence and exact financial/inventory reconciliation. |
| Production database safety | PASS | Read-only inspection only; no production mutations. |
| Public release readiness | BLOCKED | The required end-to-end transaction integrity gates remain unverified. |

## Actions required before reconsidering

1. Make the Edge Function available in an isolated local/QA runtime. Do not deploy or modify the production function merely to satisfy this test.
2. Run the tablet scenario on a stable AVD or QA tablet; resolve the host/emulator system ANR condition.
3. Create a fresh synthetic Owner through app UI and verify its isolated zero baseline.
4. Run all OFF-001–OFF-015 scenarios, preserving per-operation IDs and receipts.
5. Reconcile Room operations to exactly one canonical order, payment, and inventory movement per successful sale, with no negative/duplicate stock and no unexplained totals.
6. Save the rerun evidence and update this verdict only after all mandatory scenarios pass or are explicitly classified and accepted as nonblocking.

The transient local Auth probe was deleted and verified absent; no business-record cleanup was needed because no station, product, order, payment, grant, lease, or inventory row was created. Temporary QA keys and environment files remain outside the repository under `/tmp/aquahub-offline-pos-qa/` with restricted permissions for debugging; they are disposable and must never be reused for production.
