# Owner Production Fix Plan

## Phase 3 remediation status — 2026-10-12

Do not restart OFF-03 or rewrite working architecture. The real core Android→Edge→PostgreSQL cash sale/replay path and a browser-shaped guest-order Edge→PostgreSQL create/replay path are verified on disposable infrastructure. Remaining P1 work is primarily missing required adversarial/runtime/owner evidence, complete SYNC-02 race coverage, and SYNC-04 missed-push recovery. A new P2 error-classification defect (WEB-02) was found by the reserved-stock integration and fixed locally. No production action is authorized by this report.

### P1 disposition after Phase 3

| Finding | Current status | Root cause / implemented correction | Next work / relative effort |
|---|---|---|---|
| SEC-01 | FIXED AND VERIFIED locally | Reauth UID is matched to original account before deletion. | Final regression with safe disposable identity flow; S. |
| SEC-02 | FIXED AND VERIFIED at config/test level | Backup disabled and auth/database/file domains excluded across legacy and Android 12+ transfer rules. | Owner final device restore policy evidence; S. |
| BUILD-01 | FIXED AND VERIFIED locally | QA/production endpoints flavor-bound, production host validator is a build dependency. | Tie artifact hash to reviewed release commit; S. |
| OFF-01 | FIXED AND VERIFIED at unit/policy level | Restore requires valid Supabase session and exact UID; offline shell only on transport failure, not invalid credentials. | Test expired refresh and OS reboot on device; S-M. |
| OFF-02 | FIXED AND VERIFIED for Room upgrade fixture | Existing outbox rows preserved/rebound only on server identity proof. | Verify real installed upgrade without deleting user data; S-M. |
| OFF-03 | NOT VERIFIED; core E2E passed | Signed grant/lease and immutable Room cash operation now commits exactly once across response loss/replay. | Add 429/503, grant/lease expiry, revocation, clock rollback, reboot, catalog mutation, tenant switch, duplicate UI taps, actual tablet and dual-device contention; M-L. No schema redesign currently indicated. |
| SYNC-02 | NOT VERIFIED; setter RPC bug fixed | SQLSTATE 42702 conflict target replaced with named unique constraint in additive local migration. One simultaneous lease-RPC vs guest-order Edge race passed with one winner and audited cleanup. | Execute stock edit-vs-sale, refund/cancel-vs-reservation, transfer/container and two-Android-device CAS races; M-L. |
| SYNC-04 | FAIL — P1 missed-order recovery gap | Periodic WorkManager request carries no station; worker only refreshes orders when a station ID is present. Orders screen has no resume refresh or Realtime subscription. A missed FCM can leave the current Room-backed queue stale. | Implement a station-scoped recovery path using existing synchronization contracts, then create a browser order while suppressing push and prove it appears in the real QA Orders queue; M. If reliable recovery requires a new Realtime architecture/dependency, return with a separate design plan. |
| FIN-01 | FIXED AND VERIFIED locally | Authoritative receipt/refund hydration plus exact-once confirmed offline cash aggregation. | Final production ledger/date-range reconciliation read-only; S-M. |
| AUTH-01 | BLOCKED (disposable flow passed; production policy unknown) | Local confirmations disabled; signup immediately auto-confirms and returns session. Mailpit recovery/password update/new login passed. | Owner confirms email-proof policy and provides read-only production Auth setting; if proof must precede onboarding, approve a revised registration/session design; M-L. |
| BILL-01 | BLOCKED | Three purchase-hash groups unbound; RTDN processing alone does not prove ownership. | Owner supplies Play Developer API result and independent owner/org evidence; separate explicit production approval for any binding; M. |
| PLAY-01 | NOT VERIFIED | Local artifact signature cannot establish Play App Signing identity. | Owner verifies upload/App Signing certificate and provenance; S-M. |
| PLAY-02 | NOT VERIFIED (local build/static gate PASS) | Release lint/R8/AAB completed: 93 warnings, 0 errors; bundletool/zipalign and 8 native ELF loads pass; AAB unsigned. | 16 KB runtime, minified install smoke and Play pre-launch/Console evidence; M. |
| BACK-01 | BLOCKED | Production exposed-schema allowlist unavailable. | Owner read-only Dashboard/schema and RPC-grant evidence; S. |

### P2/P3 and historical WEB-01

EXP-01 moves to **PARTIALLY VERIFIED** (real Android server hydration, repeat idempotency and remote-edit convergence; no distinct second Android device or pending-outbox conflict). SYNC-01 remains partial without actual 429/503 injection. SYNC-03/05, AUTH-02, SEC-03 and OPS-01 remain open. PERF-01, UI-01 and DOC-01 remain P3. The Phase 3 register contains 14 P1 findings (6 fixed locally; 8 unresolved) and 8 P2 findings (WEB-02 fixed locally; 7 open). SYNC-04 was promoted from P2 after source inspection confirmed that missed FCM events have no reliable station-scoped refresh fallback. See the readiness report for evidence and release effects.

**WEB-02 — P2 / FIXED AND VERIFIED LOCALLY:** the guest-order Edge Function treated the reservation-floor error `inventory change would consume reserved stock` as a generic 503. Added a safe HTTP 409 mapping and a recoverable cart-refresh message in `supabase/functions/_shared/guest-order-errors.ts`; Deno regressions pass, and the real disposable Edge→Postgres attempt created no order and did not change stock. Deployment remains separate and unauthorized.

`WEB-01` is not present in the available prior reports or repository search. The actual guest-order Edge→PostgreSQL create and duplicate replay, plus a reserved-stock rejection, are now verified. Do not treat WEB-01 as resolved: its source description/evidence must be recovered, then website UI → Owner queue/realtime/status tested. Its original severity is unknown, so it remains an untriaged historical risk outside the current 14-ID P1 register and may add a further blocker.

### Production deployment and rollback plan — separate approval required

1. Owner first reviews every local migration against current production migration history and schema; confirm exact migration order and production backup/restore plan. Do not run `db push`, migration, Edge deploy or production SQL during this phase.
2. After separate approval, stage additive schema/RPC migrations before any app referencing them. The new setter function keeps the same six-argument signature; verify deployed signature and grants against prior app versions. OFF-03 reservations are additive but require coordinated Edge secrets/config and server version; production Edge deploy is separate.
3. Apply only through the approved production release operator, run read-only post-migration catalog/health and narrowly scoped synthetic nonfinancial checks. No real purchase/account deletion or production POS sale for smoke without separate approval.
4. Release only a provenance-verified signed artifact after final regression and owner Play approval. Monitor errors, sync queue aging, inventory floor conflicts, RTDN backlog, payment reports and Android vitals.
5. Rollback: stop/disable rollout and revert to the prior app only if the additive schema remains backward-compatible. Do not drop reservation ledgers or reverse inventory history. Preserve unresolved pending receipts and leases for audited reconciliation. The faulty prior stock setter is not a valid rollback target; if the replacement RPC fails, halt stock edits and prepare a separately reviewed forward correction. Production rollback actions themselves require explicit approval.


## Historical remediation plan — 2026-10-11 (superseded by Phase 3 disposition above)

Local fixes and regressions now cover SEC-01, SEC-02, BUILD-01, OFF-01, OFF-02 and FIN-01; OFF-03 and SYNC-02 have local implementation plus disposable database and Android verification. SYNC-01 retry classification/manual retry and EXP-01 durable Room/outbox hydration paths are implemented locally, but their backend/cross-device integration evidence is incomplete; SYNC-05 stale-cache UI remains open. OFF-03 remains a P1 release blocker until app-to-Edge/offline adversarial workflows pass. SYNC-02 remains a P1 gate until every existing inventory writer is proven reservation-safe. AUTH-01, BILL-01, PLAY-01, PLAY-02 runtime/Console evidence and BACK-01 remain NOT VERIFIED/BLOCKED. No production change was made.

The table below preserves the original remediation scope. Statuses in the update above supersede this proposal baseline. Estimates are relative and assume one experienced engineer plus review; external Console and privacy work may take longer.

## P0 — Must fix immediately

No P0 was confirmed from the available audit evidence. Maintain an immediate stop if new evidence shows active cross-tenant data exposure or lost paid entitlements.

## P1 — Must fix before public release

| ID | Fix | Effort | Dependencies | Regression evidence | Deployment implications |
|---|---|---:|---|---|---|
| SEC-01 | Bind account deletion reauthentication to original auth UID; abort mismatch | S | Google auth test identity | Same-user succeeds; alternate Google identity cannot delete either account | App + possibly function contract; no migration expected |
| SEC-02 | Exclude/securitize Supabase Auth persistence in Android backup | S-M | Backup policy decision | Backup/restore test proves tokens absent and app can recover | App update; user reauthentication may be required |
| BUILD-01 | Separate Supabase configuration by variant and assert allowed production endpoint | S | Correct QA/prod project/config values | Inspect merged variants; no secret values in logs | Build config/app update; QA backend choice required |
| OFF-01 | Restore local signed-in shell offline; perform remote reconciliation later | M | Session semantics decision | Offline/expired-session/restart tests | App update |
| OFF-02 | Implement idempotent migration/requeue for legacy outbox states | M-L | Define meaning of quarantined rows and server idempotency | Upgrade fixture across all states; exactly-once persistence | App + possibly SQL compatibility, staged rollout and monitoring |
| OFF-03 | Complete approved implementation and end-to-end validation of scoped grants, reserved leases, immutable cash receipts, atomic commit/recovery | L | Disposable project and test identities; no production dependencies | Network loss/restore, expiry, reboot/clock rollback, revocation, duplicate, timeout, catalog conflict, lost device, tenant, phone/tablet | Local Room/Edge/Postgres changes present; production migration/deploy separately approved. NO-GO until E2E and manual reconciliation approval pass. |
| SYNC-02 | Verify revision-aware absolute quantity CAS and enforce reservation floor in every order/browser/refund/transfer/container writer | M-L | Disposable DB and all stock-writer contract inventory | Concurrent stale revision/oversubscription tests | Local CAS/RPC implemented; do not release until all mutation paths and cross-client races pass. |
| FIN-01 | Ensure reports hydrate/aggregate authoritative payments consistently | M | Canonical financial reporting contract | Compare fresh-device dashboard, date ranges and server ledger | App/backend possibly required; financial audit before release |
| AUTH-01 | Align email signup with actual confirmation/session configuration | M | Supabase Auth settings evidence | Sign-up, verify, restart, resend, recovery and duplicate account tests | App/auth configuration change; may affect existing users |
| BILL-01 | Resolve three prior unbound token hashes by ownership-safe review | M | Owner-approved identity/Play receipt review | Reconcile each receipt to one organization; no token reuse | Read-only diagnosis first; any entitlement mutation requires explicit target authorization |
| PLAY-01 | Prove upload/Play App Signing certificate and build provenance | S-M | Play Console owner access | Certificate match and controlled candidate provenance | Release operation only after separate authorization |
| PLAY-02 | Build controlled AAB and run zipalign/16 KB runtime validation | M | Release build config, emulator/device | bundletool, ELF, 16 KB runtime and minified smoke | Local artifact generation only; no upload |
| BACK-01 | Verify PostgREST schema allowlist and RPC exposure | S | Supabase Dashboard read access | Confirm app_private absent and required RPC behavior in disposable env | Configuration changes require separate approval |

## P2 — Should fix before or shortly after release, depending on risk

| ID | Fix | Effort | Dependencies/tests | Deployment |
|---|---|---:|---|---|
| EXP-01 | Verify remote expense hydration and durable offline write/sync under multi-device conflicts | M | Offline and second-device fixture; source paths now exist | No migration/deploy expected for local contract |
| SYNC-01 | Retry transport/408/425/429/5xx with an 8-failure cap and exponential WorkManager backoff; policy/Room regression implemented | S | Disposable failure injection remains | App update |
| SYNC-03 | Paginate catalog, inventory movement, order history | M | Large-data test and stable ordering key | App/API update |
| SYNC-05 | Show product/customer stale cache and retry state | S-M | Offline UX tests; currently open | App update |
| AUTH-02 | HTTPS App Links and OTP cooldown/rate-limit UX | S-M | Domain association and Auth limits | App + website hosting |
| SEC-03 | Make deletion resumable/idempotent and observable | M | Disposable data tests | Edge Function update, deployment separately authorized |
| OPS-01 | Review definer grants/auth protections and tune query-backed indexes | M | Database security/query plan review | SQL migration/config needs approval; avoid blanket changes |

## P3 — Optional or operational quality

| ID | Improvement | Effort | Verification |
|---|---|---:|---|
| PERF-01 | Measure and reduce startup blocking work | S-M | Macrobenchmark/cold-start trace |
| UI-01 | Complete adaptive, TalkBack, large-font, contrast and reduced-motion sweep | M | Device/screenshot/accessibility matrix |
| DOC-01 | Refresh stale Firebase-era architecture and schema documents | S | Cross-check docs against current source and live contracts |

## Sequencing

1. Review OFF-03/SYNC-02 local implementation and complete blocked disposable Android↔Edge race/recovery/security scenarios.
2. Resolve AUTH-01 configuration, owner-approved BILL-01 token reconciliation, and BACK-01 API schema evidence.
3. Complete Play App Signing/provenance, 16 KB runtime and all Play Console evidence.
4. Close applicable P2 issues (EXP-01, SYNC-01, SYNC-05) and re-evaluate their release impact.
5. Run a separate final full regression and release verification.
6. Request distinct explicit approvals for production migration/deployment and production rollout; follow the rollback plan in `OWNER_OFFLINE_SYNC_AUDIT.md`.
