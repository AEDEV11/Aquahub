# Owner Backend and Security Audit

## Phase 3 integration and inventory-writer audit — 2026-10-12

The offline POS migration and the follow-up inventory setter fix were applied only to `/tmp/aquahub-off03-disposable/supabase`. Current local DB suite: **226 assertions across 22 files passed**. The Android QA client exercised real Auth/session, Room, repositories, WorkManager, Edge Function and local Postgres; there was no production write, migration, Edge deployment or broad log query. Edge tests passed **87/87**; `deno check --no-lock` passed for both `offline-pos/index.ts` and `guest-order/index.ts`.

The real transaction committed one order, one paid cash payment and one inventory movement; the proxy dropped the successful response and duplicate replay returned the canonical result while preserving UUID/digest. The authenticated `set_inventory_quantity` REST call exposed a real `42702` ambiguity: `RETURNS TABLE station_id` conflicted with `ON CONFLICT(station_id,product_id)`. New local additive migration `supabase/migrations/20261012160001_fix_inventory_quantity_conflict_target.sql` uses `ON CONFLICT ON CONSTRAINT inventory_balances_station_id_product_id_key`. Post-fix setter call succeeded; stale revision returned `40001`; stock below reservation returned `23514`. The migration does not alter production.

The browser guest path was also exercised through the real disposable `guest-order` Edge Function, Kong/PostgREST and PostgreSQL. One available unit was added through the authenticated Owner `set_inventory_quantity` RPC; a guest pickup order created one `CUSTOMER_WEB` order for 2,500 centavos and one inventory movement. Retrying the same operation UUID and tracking token returned the same canonical order (`fbd60423-3750-470d-bb58-97b80b17529f`, operation `guest:7d7f05e1-8c7b-4c19-b350-befcd1d6365a`) with no duplicate side effects. After the unit was consumed, a new order against fully reserved stock hit `app_private.enforce_inventory_reservation_floor` and created no order or balance movement. Before the local Edge fix this database conflict surfaced as generic HTTP 503 because its error string was not in the function's validation classifier. `guest-order-errors.ts` now maps the reservation conflict to HTTP 409 with a safe cart-refresh message; the updated disposable Edge runtime returned 409 and stock stayed quantity=reserved=9.

A simultaneous authenticated `issue_offline_pos_grant_and_lease` PostgREST RPC and browser guest-order Edge request were released from a two-thread barrier against the same single unreserved unit. The lease won; the guest request returned 409; exactly one lease allocation existed; no guest order or movement was created; `quantity` and `reserved_quantity` remained equal. The temporary synthetic device/lease was closed via `reconcile_offline_pos_leases` with an audited physical count, then the stock fixture was restored through the revision-aware setter. This proves one real disposable lease-vs-browser contention case at the database/API boundary, not the full multi-Android race matrix.

### Sellable inventory mutation surface

| Mutation path found in disposable DB | Guard / contract | Evidence and remaining race |
|---|---|---|
| `public.issue_offline_pos_grant_and_lease` | Locks/allocates available quantity (`quantity - reserved_quantity`); lease binds org/station/device/product and has bounded expiry. | SQL competing allocation and expired-reservation tests pass. Android two-device race not run. |
| `app_private.commit_offline_pos_sale` | Validates signed grant, device, scope, lease and payload digest; atomic order/payment/movement and lease consumption. | Actual Android E2E and duplicate response-loss replay pass. Revocation/expiry/clock manipulation E2E open. |
| `public.create_order_internal` | Shared owner POS and guest web/mobile order implementation updates balance inside transaction; `app_private.enforce_inventory_reservation_floor` prevents consuming held units. | One Owner POS contention test passed; real guest-order Edge/Postgres create, duplicate retry, reserved-stock rejection, and one concurrent lease-vs-guest request passed. Stock-edit, refund/cancel and two-device races remain open. |
| `public.adjust_inventory` | Relative adjustment locks balance and is subject to reservation floor. | SQL suite passes; below-floor adjustment rejected. Simultaneous edit-vs-sale not run. |
| `public.set_inventory_quantity` | Absolute target requires expected version and explicit operation ID; stale version rejected; floor trigger prevents reducing below reserved. | Bug fixed by migration above; authenticated call and stale/below-floor cases executed. Two-client CAS race not executed. |
| `public.reject_order`, `app_private.cancel_order_core` | Reversal/restock runs inside transaction through guarded inventory balance. | Existing rejection/cancel SQL tests pass. No concurrent reservation/refund/cancel race. |
| `public.confirm_cancelled_order_return` | Physical return is explicit, ledgered and changes sellable product stock through guarded balance. | Refund/return SQL tests pass. Reservation race not run. |
| Refund completion RPCs | Financial refund alone does not create sellable stock; stock changes only after explicit verified physical return. | SQL refund ledger tests pass. Refund-vs-reservation ordering not raced. |
| Container tracking | Separate containers/container_movements ledger; not a product inventory_balances writer. | Static definition inventory; container concurrency/reconciliation remains outside tested product stock matrix. |
| Transfer | No inventory transfer RPC or function modifying product balance was found in current disposable schema or source scan. | Feature is not implemented in this schema; do not claim transfer support. |
| Direct table API writes | `inventory_balances` guarded by trigger, API roles tested for restricted mutation and RLS/role boundaries. | Disposable SQL only. Production PostgREST exposed-schema configuration still unavailable (BACK-01). |

**SYNC-02 status: NOT VERIFIED / RELEASE BLOCKER.** The row-level reservation floor provides a common invariant across balance writers. Owner POS contention and one concurrent lease-vs-guest Edge request passed, and setter revision/floor cases passed. The complete stock-edit/lease, two-device, refund/cancel/transfer/container matrix is not proven. “All writers safe” is therefore not claimed.

### New P2 finding: WEB-02 — fixed locally

The reservation-floor trigger correctly rolled back a guest order that would consume held inventory, but `guest-order` classified the resulting `inventory change would consume reserved stock` database error as HTTP 503. The website showed a transient generic retry message for a permanent cart conflict. A pure Deno classifier now maps the error to HTTP 409 and a non-sensitive refresh-cart message while retaining generic 503 for unknown database errors. Three focused Deno regressions pass, and the live disposable Edge/PostgreSQL request returned 409 with zero order rows and unchanged quantity/reservation. This is a newly discovered P2, locally fixed and verified; no production Edge function was deployed.

### Financial and expense contracts

The Android empty-cache financial regression hydrates authoritative receipts and completed refunds (₱125 less ₱25 = ₱100). Real OFF-03 report assertions verify exactly-once movement from pending to confirmed revenue. Expense integration seeded a disposable-only Business plan and synthetic row; a real Android refresh hydrated ₱123.45 once, and a simulated remote change converged to ₱67.89 once. Same Android device only; full expense pagination, pending local conflict and second-device test remain open. Production ledger/schema/API comparison was not performed.

### AUTH-01 controlled disposable Auth result

`supabase/config.toml` sets `[auth.email].enable_confirmations=false`, matching the app source comment that workspace setup expects an immediate session and email verification is deferred to account settings. A fresh random disposable account returned an immediate Auth session and `email_confirmed_at` was already set; no mailbox confirmation was required. A local Mailpit password recovery email was received; the recovery link verified as `type=recovery`, redirected to `aquahub://auth/recovery`, accepted a new password, and new-password sign-in succeeded. This verifies the disposable GoTrue/Mailpit contract only. The user requirement lists email verification, so auto-confirming a supplied address may violate the intended policy. Production Auth settings and final owner policy are unavailable; AUTH-01 remains BLOCKED until verified. If verification must precede org/station provisioning, app onboarding/session flow and production Auth settings need a separately reviewed change; no policy/config was changed.

### Security boundary

Disposable RLS/role/grant tests remain pass. A targeted read-only production catalog query found `app_private` exists; `anon` has no schema USAGE and neither `anon` nor `authenticated` has SELECT on its six relations, while `authenticated` does have schema USAGE. Catalog checks show 11 `app_private` functions executable by `authenticated`, including SECURITY DEFINER helpers and `next_order_number`, which mutates order-number counters; `anon` can execute the SECURITY DEFINER trigger function `normalize_owner_walk_in_delivery_order`. No offline-POS tables or commit wrapper exist in production yet, consistent with the local-only migration policy. The effective PostgREST exposed-schema list was not available (`pgrst.db_schemas` was null in this database connection), so it is still unverified whether those function grants are reachable through the Data API. If `app_private` is exposed, `next_order_number` should be treated as an RPC surface and its execute grant reviewed before migration. No function was invoked and no production data or settings changed. BACK-01 remains BLOCKED; related grants remain in OPS-01 review.

AUTH-02 remains open: the current app uses `aquahub://auth/recovery`. A verified HTTPS App Link would require a reviewed HTTPS intent filter for `https://aquahub.aesprt.com/...` and a host-served `/.well-known/assetlinks.json` containing the Play App Signing SHA-256 certificate fingerprint; neither hosting nor manifest was changed because owner signing evidence and hosting approval are not available. No hostile external app or API test was run against production. The three unbound Play purchase token hashes remain unmodified and require Play Developer API ownership evidence.


## Remediation update — 2026-10-11

| ID | Updated disposition | Evidence and residual gate |
|---|---|---|
| SEC-01 | FIXED AND VERIFIED at client policy/source-test level | Reauthentication records a UID only after same-user verification; `deleteAccount()` rechecks it immediately before invoking `delete-account`. Unit regression passes. No live provider or destructive account test. |
| SEC-02 | FIXED AND VERIFIED by manifest/resource inspection and Android-test compilation | `allowBackup=false`; legacy and Android 12+ extraction rules exclude app data, databases and shared preferences for cloud and device transfer. Device/OEM restore behavior was not exercised. |
| BUILD-01 | FIXED AND VERIFIED | Production/QA Supabase values are flavor-specific; release pre-build validators require HTTPS and allowlisted/non-production hosts. Production validation passes; a synthetic HTTP endpoint is rejected; task graph includes the guard. No endpoint/key is printed. |
| BACK-01 | NOT VERIFIED | Effective production PostgREST schema allowlist remains inaccessible. Keep `app_private` exposure and grants as a manual read-only Console/database gate. |

An additive migration and RPCs were created locally and applied only to the disposable Supabase project; `app_private` and production schema were not changed. No production RLS, RPC, Edge Function, Supabase setting, or production record was changed. Live backend claims remain limited to the prior read-only evidence. Findings below describe the pre-remediation baseline where noted.

## OFF-03 disposable backend implementation update — 2026-10-11

Local migration `supabase/migrations/20261011150000_offline_pos_reservations.sql` adds `reserved_quantity` with a nonnegative constraint and stock-floor trigger, private device/grant/lease/operation/reconciliation ledgers, bounded 8h default/24h max lease and 8h default/12h max grant issuance, revision-aware absolute stock CAS, atomic offline cash order/payment/ledger/lease consumption, and audited single/bulk physical reconciliation. Reservations do not auto-release at expiry; reconciliation can release unused amounts only with authorized physical counts and event history. A service-role-only public RPC wrapper delegates to the private commit implementation because PostgREST resolves exposed public RPCs. Authenticated reconciliation RPCs enforce current organization/station authority; client roles cannot execute the server commit wrapper.

Disposable Supabase local reset applied the additive migration. `supabase test db --local --workdir /tmp/aquahub-off03-disposable` passed 221 tests across 22 SQL files, including grants, tenant boundaries, stale stock revision, reserved stock floor, idempotent digest-bound commit, expired lease retention, and audited release. Two-device reservation contention was tested in the disposable DB: first allocation succeeded and competing over-allocation was rejected. Edge TypeScript check and the 84-test Edge suite passed. This is not a production migration, deployment, or proof that every pre-existing order/refund/transfer/container writer is reservation-safe; full writer-path adversarial verification remains a release gate.

## Scope and production inspection

Read-only Supabase MCP inspection only. Project `pfkxrggexueltytxtqil` (`AquahubDB`) is ACTIVE_HEALTHY, Postgres 17, region `ap-southeast-2`. Live migration `20261011140000_play_rtdn_atomic_claims` exists; 14 active Edge Functions were listed, including `guest-order`, `verify-play-purchase`, `subscription-trial`, `delete-account`, and `play-rtdn` v26. No mutation, migration, deploy, or broad log query was performed. SQL/RLS tests against production were not run.

## Initial security findings before approved remediation (historical; current status is above)

| ID | Severity / status | Evidence and impact | Required evidence/fix |
|---|---|---|---|
| SEC-01 | P1 / FAIL | Google reauth can switch account; deletion function uses current bearer UID. See `AquaHub/app/src/main/java/com/aesprt/aquahub/feature/settings/SettingsViewModel.kt:233-241`, `data/supabase/auth/SupabaseAuthDataSource.kt:78-85,197`, `supabase/functions/delete-account/index.ts:23-25,65-68`. Wrong-account deletion risk. | Enforce same immutable auth user ID before deletion; regression for alternate account. |
| SEC-02 | P1 / FAIL | Manifest backup allowed; Supabase Auth auto-load persistence uses SDK shared preferences; backup exclusions cover Room and a separate DataStore file, not SDK preferences. Potential session transfer. | Exclude auth state/disable backup for it or secure storage; run backup/restore. Android guidance says shared preferences are backed up by default: [Auto Backup](https://developer.android.com/identity/data/autobackup?hl=en). |
| BUILD-01 | P1 / FAIL | Global Supabase URL/anon key in Gradle across production and QA flavors; endpoints are not flavor-bound. | Build variant config separation and host assertion; test merged configs without exposing values. |
| BACK-01 | P1 / NOT VERIFIED | Effective PostgREST exposed-schema setting for `app_private` unavailable. Private schema could be unavailable to intended RPC callers or mistakenly exposed. | Verify Dashboard API schema list and grants; ensure app_private remains non-exposed. |
| SEC-03 | P2 / NOT VERIFIED | `delete-account/index.ts:37-68` performs sequential anonymization/deletion; partial failure can leave incomplete account state. | Idempotent resumable deletion and partial-failure tests. |
| AUTH-02 | P2 / NOT VERIFIED | Custom `aquahub://auth/recovery|confirm` links declared for exported PasswordRecoveryActivity; custom schemes can be claimed by another app. | Prefer verified HTTPS App Links and verify domain association. |
| OPS-01 | P2 / NOT VERIFIED | Read-only advisors flag `pg_net` in public, multiple SECURITY DEFINER grants, leaked-password protection disabled, permissive station policy pair, 53 unindexed FKs, 9 unused indexes, 2 duplicate indexes. These are review signals, not individually confirmed exploits. | Review each definer function's grant/tenant checks and auth setting; tune indexes from workload evidence. |

## RLS, tenant separation, storage

Production metadata showed RLS enabled on 39/39 public tables and 6/6 `app_private` tables. Helpers such as station/org membership checks are used. Twenty-one RLS-enabled relations with no policy appear in advisor output and are default-deny unless privileged functions access them. Two permissive station SELECT policies were flagged but appear tenant-scoped from policy expressions. Storage buckets include private payment assets and public product/station images; path and size/MIME constraints exist. **No live JWT adversarial test was performed**, so isolation is NOT VERIFIED rather than PASS.

Required disposable-environment tests: anonymous/authenticated cross-org reads, staff role escalation, station switching, owner/staff/rider RPC invocation, private payment proof access, public image limits/path traversal, guest-order abuse/rate limits, and deletion ownership. Production data was not modified.

## API contracts and transactions

Order creation/payment/inventory uses RPC/Edge paths with centavo integer fields at the Supabase boundary and transaction/ledger semantics. Guest order source validates server price/availability, rejects duplicates, bounds bodies and uses explicit CORS plus throttling/idempotency mechanisms. There is no live browser→Owner E2E evidence. App-private schema allowlist and every nullable/type/enum contract were not exhaustively checked against generated schema. Expense list implementation is incomplete; direct payment settlement/refund provider integration was not verified.

## Firebase migration residue

Current Owner cloud data paths use Supabase. Firebase remains intentionally in FCM, Analytics and Crashlytics. Firebase Auth/Firestore/Functions compatibility code is not an active Owner repository path in this source snapshot; legacy `firebaseUid`/`firebase_id` fields and migration artifacts still exist. Do not delete those fields or migration history until the retained identity mappings and customer/browser integration dependencies have been verified. The current context documentation is partly stale about Firestore, schema and Room version.

## Privacy and permissions

Owner manifest requests INTERNET, fine/coarse location, network state, and POST_NOTIFICATIONS. Main launcher is exported; file provider is non-exported; recovery activity is exported with custom scheme. Local privacy-policy source describes Supabase and Firebase diagnostics/push but retains Firebase security-rules language. Website deletion page source says production should implement deletion even though an in-app endpoint exists; external hosted URL could not be verified. Validate published policy, deletion web path, Data Safety, consent, retention and the Sydney region/cross-border transfer disclosures with the privacy owner/legal counsel. This is not legal certification. Philippines National Privacy Commission guidance: [Advisory No. 2024-01](https://privacy.gov.ph/wp-content/uploads/2024/06/Published-NPC-Advisory-No.-2024-01-Contractual-Clauses-for-Cross-Border-Transfers_30May24.pdf).

## Operational health and quota

Do not query broad Supabase logs. Current function inventory and RTDN event aggregate are read-only snapshots, not uptime/latency or Pub/Sub backlog evidence. Prior quota incident warrants reviewing Supabase Usage, function invocations, egress, log ingestion, and alarms from dashboard aggregates only. Current 44/44 RTDN rows processed does not establish Pub/Sub delivery or entitlement completeness.
