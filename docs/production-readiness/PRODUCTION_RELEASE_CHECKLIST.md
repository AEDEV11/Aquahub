# Production release checklist

Status at audit snapshot 2026-10-09, including approved local changes. **Release decision: NO-GO.** Local source/database test fixes do not alter the linked backend; the linked environment's production role is not established, and any production operation still needs its own explicit authorization.

## Free status expiry follow-up — 2026-10-10

- [x] Confirmed hosted Free subscription uses the `FREE` plan/status contract
  using aggregate-only SQL; no IDs or personal fields were queried.
- [x] Fixed Android parsing/resolution for `FREE`; unknown statuses still fail
  closed and legacy Free/`ACTIVE` remains compatible.
- [x] Focused subscription QA unit tests passed 29/29; `assembleQaDebug`
  succeeded; updated separate QA package installed/launched without observed
  AndroidRuntime fatal exception.
- [ ] Visually verify the updated QA subscription state after restart and run
  Free product/POS workflows against persisted data.
- [ ] Produce/distribute an update to the affected Owner release channel only
  after separate release authorization; the original app was not modified and
  nothing was published in this task.
- [ ] Re-run `lintQaDebug`; the previous run was interrupted after >50 minutes
  and is **NOT VERIFIED**. Overall release remains **NO-GO**.

## Group 5 FREE-plan/device update — 2026-10-10

- [x] Applied only `20261010100000_free_plan_basic_inventory` to explicitly
  designated project `pfkxrggexueltytxtqil`; hosted history and catalog
  read-back verified `BASIC_INVENTORY=true`, premium container tracking
  `false`, unchanged Free quotas, and unchanged paid catalog digest.
- [x] Initial aggregate check found no hosted rows; the later account state is
  recorded in the Free status expiry follow-up above. No product or sale rows
  were present in that later aggregate.
- [x] Built, installed, and launched isolated QA application ID
  `com.aesprt.aquahub.qa` without clearing/replacing the original package;
  onboarding/login UI rendered on SM-A366B Android 16.
- [x] Focused outbox instrumentation passed 2/2; legacy queue operations are
  retained/quarantined in the new source and newly created queue operations
  remain eligible for sync.
- [ ] Original app remains unopened and its persisted legacy queue remains
  uninspected. Install a data-preserving guarded update and review/quarantine
  old rows before launching the original application.
- [ ] Verify the updated QA app resolves the existing Free status after
  restart; complete product/stock operations, exact POS sale, sales/report
  reconciliation, and hosted role/limit tests.
- [ ] Re-run `lintQaDebug` after the interrupted run and finish remaining
  production security, subscription, tablet, and release gates. Overall
  remains **NO-GO**.

## Group 4 gate refresh — 2026-10-09

- [x] Added local refund-obligation reporting RPC/UI; disposable report fixture passed with the full 12-file/84-assertion SQL suite.
- [x] Hardened local RTDN/reconciliation request authentication with Google OIDC; full Deno suite 34/34 and all 22 TypeScript files typecheck.
- [ ] Production-of-record and released binary provenance remain NOT VERIFIED; linked direct-DML grants remain a confirmed linked-project risk.
- [ ] Hosted staging provisioning, JWT/REST tests, clean post-Group4 54-migration replay, backup/restore, Owner/Customer E2E, Pub/Sub/Cloud Scheduler validation, licensed billing and secret rotation remain outstanding.
- [ ] Group 4 P1 AH-P1-12 and existing P0/P1 findings remain OPEN until hosted/deployed evidence exists. No deployment, scheduler, Play Console change, upload or remote repository push occurred.

## Direct-to-production preflight refresh — 2026-10-09

- [x] Replayed all 54 current migrations from an empty disposable local Supabase project; 12 SQL files / 84 pgTAP assertions passed.
- [x] Refreshed read-only migration history: 54 local / 45 linked; nine local-only versions; no linked-only version; historical `20261009042725` absent from both current source and linked history.
- [x] Read-only Play Console review confirms Owner and Customer are on Closed testing; Owner Production is inactive. Owner Alpha label is `11 (aq-1.0.6)`.
- [ ] Match the exact Play-served Owner and Customer artifacts, Supabase endpoint/project ref, Firebase dependencies and source revisions. Available local AABs point to `pfkxrggexueltytxtqil` but are not provenance-matched to Play uploads; local Owner `aq-1.0.7` differs from visible Alpha label.
- [ ] Reconcile the linked schema against every local-only migration. Current diagnostic diff contains DROP statements and must not be applied.
- [ ] Produce and restore-test a recovery-grade backup for the confirmed target, including a separately approved plan for Auth identities, Storage metadata/objects and infrastructure secrets/config. No production export was made; local synthetic dumps are incomplete and not off-site.
- [ ] Validate exact currently released Owner/Customer API compatibility and hosted REST/JWT authorization before tightening/deploying any contract.
- [ ] Establish exact migration/function deployment set, pre/post smoke tests, monitoring and forward recovery. Until then direct-to-production deployment is **NO-GO**; no remote write or history repair occurred.

## Must fix before production

- [ ] Deploy the locally implemented authenticated order/item/payment DML revocation and guarded RPC migration after history/provenance review; test actual linked/staging REST denial and allowed roles (AH-P0-01).
- [ ] Deploy and test the authorization check on `create_order` idempotent replay so one tenant cannot retrieve another tenant's order summary by operation ID (AH-P0-05).
- [ ] Deploy and role-test the locally implemented unpaid delivery/client-account flow and rider proof gate; establish a reviewed policy for existing records without automatic financial rewrites (AH-P0-02).
- [ ] Validate UID-bound Room/outbox behavior with A→logout→B, queued offline work, same-owner re-login, upgrade/restore, and device tests; implementation and focused policy test exist locally (AH-P0-03).
- [ ] Deploy and validate the locally implemented server plan/feature/quota guards across every role and operation (AH-P0-04).
- [ ] Resolve the backend-cutover contradiction: prove the production-of-record, release endpoint/project, Auth identity mapping, business/history migration, Customer Functions/notification path and rollback across both apps (AH-P1-09). Do not ship this source transition based only on local compilation or a linked Supabase project.
- [ ] Establish a versioned/reviewable source and release provenance path for `supabase/` and `docs/production-readiness/`, currently outside both Android Git roots; verify the release pipeline consumes the reviewed migration/function source.
- [ ] Fault-inject checkout lost-response retries and concurrent price/stock updates; source idempotency/canonical-snapshot fixes and unit tests pass locally (AH-P1-01/02).
- [ ] Verify order refresh on actual FCM/resume; source coordinator fix has unit coverage. Classify retryable HTTP failures (AH-P1-03/P2-02).
- [ ] Review and apply missing migrations in approved order; verify cancellation, proof and payment guard RPCs/storage on disposable DB before separate production deployment authorization (AH-P1-04).
- [x] Implement the local idempotent centavo refund/cancellation/physical-return workflow and regression coverage (AH-P1-10); [ ] validate via isolated hosted-API/staging clients and define external refund/cash/liability accounting.
- [x] Earlier Deno function test run passed 8/8; current full function-tree typecheck passes for all nine entrypoints. [ ] Rerun Deno tests after remote std-module fetch is available, run licensed lifecycle tests, and close repeat-trial weakness (AH-P1-05/06/07).
- [ ] Complete adaptive phone/medium/tablet UX for all production screens. Shell adaptive navigation exists locally; route layouts, screenshots, resize and business-flow tests remain (AH-P1-08).
- [ ] Re-run DB/RLS/function tests and full regression after fixes; obtain real screenshots/device tests and re-audit.

## Repository checks verified in this snapshot

- [x] Source target SDK is 37 and compile SDK is 37; current published Play minimum target requirement is API 36 for the relevant submission window. Re-check official policy immediately before submission.
- [x] Billing Library source declaration is 9.1.0, currently supported in reviewed official release notes.
- [x] Local owner JVM test task passed: 108 cases, no failures/errors.
- [x] Latest Group 2 Owner and Customer JVM suites passed: Owner 118/118 (including refund-adjusted customer/rider/station analytics); Customer 21/21, no failures/errors.
- [x] Owner Android-test Kotlin sources compile after applying the existing Compose BOM to the androidTest dependency configuration.
- [x] Run focused Owner connected instrumentation. On 2026-10-09, 13/13 tests passed on Samsung SM-A366B / Android 16 (API 36), including Room refund aggregation, sync queue and selected payment/customer UI checks. This is not a full workflow or screenshot matrix.
- [ ] Run Customer device smoke tests and end-to-end Owner/Customer backend workflows using isolated identities; none were run.
- [x] Post-remediation debug Kotlin compile succeeded.
- [x] Focused post-remediation JVM run passed: 46 tests / 6 classes, 0 failures/errors.
- [x] Local Supabase pgTAP suite passed: 9 files / 43 synthetic assertions after clean replay of the disposable 51-migration snapshot through Group 2 `09104237`. Latest workspace migration `09112029` was not included in that isolated snapshot. Linked database was not changed.
- [x] Debug lint task and minified release AAB task completed; 96 lint warnings remain to triage, including Credential Manager handling and intent-filter data warning.
- [x] AAB contains native shared libraries; no 16 KB page alignment/compatibility conclusion is available.
- [ ] Build a fresh release artifact from the current approved source and validate signing/provenance; no AAB was built after the latest fixes.

## Not verified / external owner actions

- [ ] Play Console application identity, subscription product activation and exact monthly/annual base plans/offers, prices, markets, trial eligibility and renewal disclosure.
- [ ] Google Play license tester purchase, pending payment, renewal, cancel, grace, hold, upgrade/downgrade, refund/revoke and restore tests (see billing audit).
- [ ] Google Play Developer API/service account permissions and RTDN Pub/Sub topic delivery, redelivery, secret/config, package name and publication state.
- [ ] Play App Signing/upload key and AAB install/sign/upload verification.
- [ ] 16 KB page-size validation for bundled native libraries on API 35+ 64-bit profile and pre-launch report.
- [ ] Data Safety, privacy policy, app access/reviewer credentials, account deletion URL and verified website/legal content.
- [ ] Play Console policy alerts, Android vitals, crash/ANR history, staged rollout and rollback controls.
- [ ] Supabase production RLS/grant verification after fix, isolated two-tenant/staff/rider negative tests, backup/restore and deletion/retention policies.
- [ ] Reconcile migration history before deployment: refreshed read-only comparison is 54 local files / 45 linked entries, nine local-only (`20261009030621`, `20261009051935`, `20261009060109`, `20261009080803`, `20261009093931`, `20261009104237`, `20261009112029`, `20261009124434`, `20261009135735`), shared `20261009043510`, no linked-only entry. `20261009042725` is absent from current source and linked history (it appeared only in a separate local DB ledger). Do not repair or apply linked migrations until target identity and schema effects are reconciled; none of these changes was deployed.
- [ ] Reconcile retained Firebase rules/config and the absent Customer Functions source with the intended deployed Supabase/Firebase architecture; document verified cutover and data lineage.
- [ ] Samsung SM-A366B / Android 16 workflow smoke test, supported phone/tablet matrix, TalkBack/font scale, landscape/split-screen/foldable and screenshot review. Device is connected; no workflow/screenshot test has passed.

## Group 3 local verification update — 2026-10-09

- [x] Fresh isolated migration replay through `20261009124434_group3_subscription_security_and_catalog.sql`; current pgTAP suite passed 11 files / 81 assertions.
- [x] Deno 2.9.7 tests 23/23 and all 10 current Edge Function entrypoints typecheck; no function deployed.
- [x] Owner/Customer JVM tests passed 120/120 and 21/21; Owner connected instrumentation passed 13/13 on the SM-A366B Android 16 device.
- [ ] Production-of-record, linked migration reconciliation and deployed RLS/PostgREST tests remain unverified; no remote change is authorized by these local results.
- [ ] Google Play Console catalog, licensed lifecycle matrix, scheduled RTDN reconciliation, external refund liability report, tablet screenshot/accessibility matrix, Customer device smoke, fresh signed release artifact, policy and rollout evidence remain outstanding.

## Release artifact/configuration

- Package: `com.aesprt.aquahub`; current source version code 12, version name `aq-1.0.7`.
- Release uses R8 and resource shrinking. No repository signing configuration was found; local credentials were not inspected or reported. Confirm signing via approved Play release workflow.
- Production Supabase endpoint and public anon key are build inputs. Confirm release variant endpoint/config, TLS, Maps-key package/API restrictions and Firebase FCM/Crashlytics application setup externally; no secrets are included here.
- The tracked release AAB was already modified in the starting worktree and was targeted by the local bundle task; prior local uncommitted bytes are unavailable. Confirm the intended release artifact from source control/build provenance before distribution.

## Go/no-go rule

Do not submit or roll out until all P0/P1 findings are closed, required subscription/security/inventory/offline checks pass, responsive tablet gate is met, and all Console/account-specific gates above are evidenced. This checklist does not authorize upload, publication, deployment, account changes, or real purchases.
