# Owner Production Release Checklist

## Phase 3 status — 2026-10-12

Local core offline sale/replay and expense hydration paths have real disposable Android/backend evidence; this does **not** authorize production. The current emulator is API 35 with measured 4096-byte pages. See `OWNER_TEST_RESULTS.md` for executed tests and `OWNER_PRODUCTION_READINESS.md` for remaining findings.

### Evidence captured locally

- [x] QA owner grants/lease, airplane-mode cash receipt, durable Room state, process force-stop/recovery, real Edge/Postgres commit and duplicate response-loss replay; exactly one order/payment/inventory movement, original UUID/digest, exact-once report transition.
- [x] Browser-shaped guest order passed through the disposable Edge/Postgres path; duplicate retry returned the canonical order; reserved-stock conflict returned HTTP 409 with no order/balance change after the local classifier fix.
- [x] Local disposable SQL suite: 22 files / 226 assertions; Edge: 87 tests; offline POS and guest-order functions type-check.
- [x] Android production and QA unit suites: 188 each; connected QA instrumentation: 30/30 on API 35 phone and 30/30 on Pixel Tablet after cold-start ART warmup; real expense hydration and remote-edit convergence each passed.
- [x] New stock setter conflict-target migration applied only to disposable DB; stale-version and reserved-floor behavior verified.
- [ ] Complete all OFF-03 fault/security/race cases and SYNC-02 all-writer races.
- [x] Fresh local production lint/R8/AAB, bundletool validation, `zipalign -P 16` and static ELF alignment. Current AAB is unsigned; mapping upload excluded.
- [ ] Play signing/provenance, generated Play APK/runtime verification on an actual 16 KB runtime, and owner Console pre-launch evidence.
- [ ] Verify AquaHub's package `com.aesprt.aquahub` is registered to the intended verified developer account in Play Console under Android developer verification; the September 30, 2026 registration deadline has passed.

### Owner-only Play Console and production gates

- [ ] Confirm Play App Signing enabled and compare upload certificate fingerprint; never send private keys.
- [ ] Confirm developer verification and package registration for `com.aesprt.aquahub`; official [Play Console registration guidance](https://developer.android.com/developer-verification/guides/google-play-console?hl=en).
- [ ] Provide signed candidate provenance: reviewed commit, versionCode/versionName, AAB SHA-256, signing certificate and mapping file availability. No AAB upload has occurred.
- [ ] Verify Data Safety answers against SDKs and observed data collection, privacy policy content/URL, consent and retention statements.
- [ ] Verify in-app account deletion and public deletion URL/functionality, including retained/legal records. Do not run destructive production deletion as an audit smoke.
- [ ] Verify Play Billing products/base plans/prices, acknowledgement, restore, pending/grace/hold/refund/revocation using license-test accounts; resolve three unbound purchase hashes only with evidence and separate production approval.
- [ ] Confirm reviewer app access/instructions, content rating, target audience, ads declaration, store listing/screenshots, countries/distribution and accurate feature claims.
- [ ] Review pre-launch report, Android vitals, closed-test to production update compatibility and supported-device warnings.
- [ ] Verify effective production PostgREST schemas/RPC grants and email confirmation policy read-only; local config disables confirmation and auto-confirms signups. Obtain the owner decision whether verified email is required before workspace provisioning.
- [ ] Approve production migration/deployment plan, monitoring and rollback separately.
- [ ] Give explicit authorization before any production migration, Edge deploy, Play upload or rollout.


## Remediation update — 2026-10-11

Historical 2026-10-11 evidence: 188 JVM tests per flavor; 29/29 connected QA tests on Samsung SM-A366B / Android 16; focused Compose receipt UI on Samsung and Pixel Tablet / API 35; 221/221 disposable SQL tests; 84/84 Edge tests and Deno type check; release lint/R8/AAB with mapping upload excluded; bundletool validation, merged manifest and static 16 KB checks. The 2026-10-12 evidence above supersedes test counts. The fresh AAB is unsigned and must not be uploaded. No production change or Play upload occurred.

Still mandatory before GO: OFF-03 end-to-end authorization/reconciliation and all-device flows; SYNC-02 all-writer reservation/CAS proof; live auth and purchase-token ownership; PostgREST exposure; Play signing/provenance; 16 KB runtime; and owner-supplied Console policy, Data Safety, privacy/deletion, billing, listing, pre-launch and vitals evidence. Production migration/deploy and rollout each require separate explicit approval. This checklist does not authorize release.

All unchecked items are **NOT VERIFIED** and block a GO decision unless the release owner documents an acceptable risk. Do not use this checklist as rollout authorization.

## Source and build

- [ ] Confirm source commit/tag and clean/reviewed diff correspond to the intended release; current audit tree is extensively modified and uncommitted.
- [ ] Verify application ID `com.aesprt.aquahub`, versionCode/versionName monotonicity, minimum/target SDK and supported-device coverage.
- [ ] Confirm production Supabase endpoint is flavor-bound and no test/staging endpoint or credentials enter release.
- [ ] Build production AAB from approved commit; inspect merged manifest, R8 behavior, mapping file and native libraries.
- [ ] Verify bundle ZIP alignment and 16 KB page compatibility on a 16 KB runtime/device; check 64-bit ABIs.
- [ ] Confirm Play App Signing and upload certificate match; preserve signing credentials and mapping artifacts securely.
- [ ] Install and update over the current closed-test build without wiping data; verify Room migrations and login.

## Play Console / policy

- [ ] Review **Policy and programs → Policy status** and resolve warnings/rejections.
- [ ] Verify **App content**: Data Safety, privacy policy URL, account deletion in-app and external URL, app access instructions, ads, target audience, content rating.
- [ ] Verify website privacy/deletion pages are published, reachable, current and accurately describe Supabase/Firebase processing.
- [ ] Verify store listing title, description, screenshots and claims against working Owner features; do not claim unverified offline POS/automated payment capabilities.
- [ ] Verify countries/distribution, availability, release notes, version and rollout configuration.
- [ ] Review **Testing → Pre-launch report**, crashes, accessibility and device compatibility.
- [ ] Review **Quality → Android vitals** and baseline crash/ANR thresholds.
- [ ] Review **App bundle explorer/App integrity** for signing and artifact identity.

## Backend and privacy

- [ ] Confirm `app_private` is not exposed in Supabase API schemas and intended RPC grants are exact.
- [ ] Run tenant, role, storage and guest-order abuse tests against a disposable project.
- [ ] Review SECURITY DEFINER grants and leaked-password protection; resolve actual exploitable findings.
- [ ] Verify Supabase Usage/function invocation/log ingestion from aggregate dashboard; do not run broad log queries.
- [ ] Review Sydney hosting and cross-border processing disclosure with privacy owner/legal counsel.
- [ ] Validate account deletion completion, partial failure recovery, retention and external request link.

## Offline and core business workflows

- [ ] Upgrade a seeded prior-version database with queued operations; verify each is replayed exactly once or clearly presented for action.
- [ ] Verify cached signed-in access offline, expired session behavior, station switch, logout with pending operations, reconnect and process death.
- [ ] Verify POS repeated tap, timeout, server commit/local failure, payment confirmation, inventory ledger, receipt and cancellation/refund behavior.
- [ ] Compare financial reports to authoritative payment/order data on a fresh device and across date ranges.
- [ ] Verify browser order routes to correct station, owner notification, status update and customer-facing consistency.
- [ ] Suppress FCM in a disposable run, submit a browser order while the Owner Orders ViewModel remains alive, and verify station-scoped recovery updates the queue without manual refresh. This is P1 SYNC-04 and currently fails by source inspection.
- [ ] Verify staff/rider permissions and multi-station isolation with test identities.

## Billing

- [ ] Confirm actual Play Console products/base plans/prices/offers against server catalog: Free ₱0; Starter ₱299/mo / ₱2,870.40/yr; Business ₱699/mo / ₱6,710.40/yr; Pro ₱1,299/mo / ₱12,470.40/yr (confirm current before release).
- [ ] Confirm trial eligibility, regions, taxes and subscription terms/disclosures.
- [ ] Use Play license testers to verify purchase, acknowledgement, cancel, pending, renewal, restore, expiry, grace, hold, recovery, refund and revocation.
- [ ] Verify server entitlement enforcement and token-to-organization ownership; resolve three historical unbound token hashes.
- [ ] Confirm RTDN Pub/Sub subscription delivery and v26 processing with dashboard evidence; no token values in logs.

## Final approval gates

- [ ] Close all P1 findings, including OFF-03, SYNC-02 all-writer verification, and SYNC-04 missed-push order recovery, or document an acceptable owner risk decision that still meets data-integrity and policy requirements.
- [ ] Attach final regression evidence, device matrix, backend test target, test counts and artifact provenance.
- [ ] Obtain explicit owner authorization for the exact production rollout target and rollout action. Production rollout is never implied by source-code approval or this checklist.
