# AquaHub Owner security audit

## Group 4 security verification update — 2026-10-09

The linked Supabase project remains environment-unknown. Read-only metadata still shows authenticated direct DML grants on orders, order_items and payments; current Group 1 protections are local only. Hosted PostgREST/JWT tests are BLOCKED, not passed. Group 4 changed local RTDN/reconciliation entrypoints to require Google OIDC workload identity and added synthetic verifier tests (7/7); Deno suite is 34/34 and 22 TypeScript files typecheck. These checks do not prove deployed handler identity, secret rotation, Pub/Sub configuration, or production protection. A new P1, AH-P1-12, tracks the prior service-role bearer authentication until the corrected endpoint is staging/deployment verified. No remote mutation or live-user security test occurred.

## Direct-to-production evidence refresh — 2026-10-09

A new empty disposable local Supabase project applied all 54 current migrations; the current SQL suite passed 12 files / 84 assertions, and a local grant query confirmed no authenticated DML on `orders`, `order_items`, and `payments`. This improves local verification only. Read-only linked metadata still reports broad authenticated DML on those linked tables, and the linked project has not been proved to serve either Play Closed Testing app. Play Console confirms Owner Production is inactive. No real REST/JWT role test, linked change, production backup, or deployed function comparison was performed. The linked authorization risks remain open and release-blocking.

Snapshot: 2026-10-09, updated after approved local security remediation. Defensive source review, read-only linked database grants/policy metadata, and synthetic local pgTAP checks. No disruptive penetration test, live test-identity mutation, production deployment, or production row modification was performed. Secrets, tokens and business records are intentionally omitted.

Backend provenance caveat: current checked-out Android business sources use Supabase, but workspace-level instructions and retained Firebase rules/deploy configuration still describe Firebase/Firestore as active; the Customer Firebase Functions source named by `firebase.json` is missing from this checkout. Linked Supabase metadata does not establish that this is the production-of-record or that Firebase identities/business history have been fully migrated. Security findings below are scoped to the current Supabase source/linked project; release backend selection and data lineage remain a P1 blocker.

## Threat model

Assets: orders, payments/receipt totals, customer balances/contact/address, inventory and container movements, staff identities/roles, station/org configuration, subscription entitlements, local pending outbox, auth sessions, delivery/payment proof images, and private exports.

Threat actors: a curious or malicious authenticated customer; a compromised/modified owner client; a station staff member or rider with a valid low-privilege account; a second owner using the same device; a replaying or forged webhook caller; and a network attacker. Main boundaries: Android process/Room, Supabase Auth JWT, exposed Postgres REST/RPC/RLS, Storage, Edge Functions/service role, Google Play API/RTDN, FCM and website deep links.

## Confirmed high-risk findings

### P0: Cross-tenant order idempotency disclosure

The baseline `create_order` migration queries `orders.client_operation_id` and returns the existing order summary before validating whether the caller may operate its station or owns the customer order (`20261003150000_orders_atomic_transactions.sql:112-121`). A caller must know the operation key; this is an authorization flaw, not a claim that keys were enumerated. The local Group 1 migration serializes same-key retries, validates order context, checks customer ownership or station-operation rights, and only then returns the existing result. A synthetic cross-tenant negative case passes in `walkin_delivery_payment.sql`. The fix is not linked/deployed; the linked function body was not independently fetched, so live exploitability remains unverified.

### P0: Direct order/payment table mutation bypass

Read-only metadata from the linked Supabase database reports authenticated INSERT/UPDATE/DELETE privileges for payments and broad order update. Local baseline migration grants these privileges (`supabase/migrations/008_rls_and_grants.sql:31-75`) and uses station-operator policies. The RPC invariants could therefore be bypassed through direct table writes. The new local migration `20261009093931_group1_backend_security_payment_entitlements.sql` revokes authenticated direct DML on orders, order items and payments, retaining only the non-financial `station_note` column update. In the isolated scratch schema, privilege checks returned false for anonymous order/payment DML, authenticated order/item/payment DML and authenticated access to private quota bookkeeping; authenticated execution of `record_payment` remained enabled. These are database-privilege checks, not actual HTTP/PostgREST attempts. The linked database is unchanged and this migration is not in linked history. Because production-of-record is unverified, this remains a confirmed linked-environment vulnerability and release blocker, not a proven exploit of the production app.

### P0: Cross-account local disclosure/replay

The original source allowed Room/outbox data to cross authenticated identities. Local source now serializes login and sync through a `LocalAccountScopeManager`, stores the immutable Supabase UID owning the local cache, prevents replay unless it matches the current session, clears an empty cache on account switch, and blocks switching when another account has pending work. Focused account-scope policy tests pass. This meaningfully mitigates the identified path, but no two-account device test, upgrade/restore test, or full station-screen query audit was run; do not treat it as release-verified yet. No customer rows were read.

### P0: Server entitlement/limit enforcement gap

The original local source relied on Owner UI subscription gates for some feature and quota decisions while database writes authorized membership/station operation. The new local migration resolves the latest provider-state/period-valid plan (falling back to Free on expiry or invalid paid state), and adds mutation-time feature/quota triggers for supported station, order, product, customer, member, rider, expense, inventory, container and order-item paths. `staff-admin` now uses current period/grace, the configured `STAFF_COUNT` limit and fails closed on lookup errors. Synthetic SQL tests cover Free-plan station/order restrictions, backdated-import quota bypass, and expired-plan fallback. This fixes the source gap locally, but no linked deployment or complete plan × role × operation matrix has been verified; P0 remains open for release.

### P1: Play token binding and lifecycle

The original `verify-play-purchase` used check-then-act token binding, did not validate Google’s verified external account identifier, and ignored token persistence errors. Local source now compares the verified account hash, uses the unique token claim as the serialization point before entitlement persistence, and fails closed. Three token-binding tests passed in the earlier 8/8 Deno suite; all nine current function entrypoints typecheck. A repeat test fetch stalled before assertions. No Edge Function deployment occurred, so the linked function remains unchanged; its production association is not verified. Android cancellation-state mapping is fixed locally with a passing JVM boundary test; actual Play lifecycle remains unverified. See billing audit.

### P1: Payment-state bug

The linked schema/migration history leaves `create_order` and its receipt trigger treating walk-in delivery as already PAID; it can report revenue before collection. Local migration `20261009093931_group1_backend_security_payment_entitlements.sql` keeps delivery/client-account orders pending until a guarded receipt, requires the assigned delivered-order rider to own an uploaded proof object, and ignores client-supplied paid status during legacy import. Synthetic local SQL assertions pass; the migration is not applied to the linked database. Whether that database is production-of-record is unverified. This is covered in the P0 financial finding in the bug register. Paid-order cancellation is intentionally rejected until a refund is reconciled, so the refund/cancellation workflow remains open.

## Authentication and client review

- Current checked-out Owner sign-in sources use Supabase Auth (Google/email/phone OTP/PKCE deep link); Firebase Auth/Firestore are not active in that source path. This is not verified as the provider used by an already released app or all existing users. Runtime credentials/OTP policies, provider settings, resend/rate controls, email verification, session revocation, MFA and account-recovery settings were not inspected in Supabase Auth dashboard.
- Password recovery is handled by an exported Android activity and custom URI scheme. Validate host/path/state/PKCE handling against malicious and duplicate intents; test expired/replayed links on device. Main Activity is also exported for launcher. Other reviewed FCM service and FileProvider are not exported.
- Manifest asks Internet, fine/coarse location, network state and notifications. Location is business/delivery-related but exact runtime rationale and least-privilege timing need UX test. Notification permission is currently asked at startup.
- Android backup is enabled with explicit rules excluding database/session stores. Verify all sensitive files/caches/exports are excluded on both cloud and device transfer; no restore test was run.
- No service-role key was found in reviewed Android source/build configuration. Supabase anon key is client-facing and safe only with correct RLS. Maps API key restrictions and Firebase sender/project restrictions require Console verification. No scanner/vulnerability tool was available; absence of scanner findings is not asserted.
- TLS/cleartext policy: no explicit cleartext opt-in was found in the reviewed main manifest; actual merged release manifest/network-security configuration and endpoint TLS were not dynamically validated. WebView hardening not applicable in reviewed screens.

## Supabase RLS, SQL and Edge Functions

- RLS appears enabled across application tables in migration 008. Policies broadly bind row access to station/org membership, but grants remain a separate required layer; the live grant result is the confirmed bypass above. RLS is not equivalent to least-privilege column operations.
- `SECURITY DEFINER` RPCs were inspected for `search_path`, role checks and transaction intent in critical order/payment/inventory paths. The new least-privilege migration removes the direct-table bypass in local DB only. The local `order_number_counters` table has RLS disabled; a read-only privilege check found no SELECT/INSERT/UPDATE grants to `anon` or `authenticated`, so this is a defense-in-depth issue rather than confirmed public exposure.
- Eight Edge Functions were reported ACTIVE. Reviewed `verify-play-purchase`, `subscription-trial`, `staff-admin`, `play-rtdn`, and `delete-account` source. Functions use server-side service-role secrets; callers must be JWT/role-checked and service writes validated. Token claim is fixed in local source and the current nine-entrypoint tree typechecks; no function was deployed. Trial UID-reuse risk remains.
- The final local SQL suite passed **9 files / 43 assertions** after replaying the disposable 51-file migration snapshot through Group 2 `20261009104237`, including refund, cancellation, return, role/tenant, immutability and report invariants. The newer workspace migration `20261009112029` was not in that isolated snapshot. A local PostgreSQL concurrency exercise verified duplicate refund-key idempotency and capped concurrent refund requests. These are not deployed REST authorization tests. Latest linked history has five pre-existing local-only versions plus local candidates `09104237` and `09112029`; `09043510` is shared and `09042725` appears on neither side. No history repair or production deployment occurred.
- An earlier current-suite Deno run passed **8/8**; the current whole-tree typecheck passes for all nine function entrypoints. A repeat test invocation stalled fetching the remote standard-library assertion module before assertions started. Physical device and screenshot checks were not run.
- Storage delivery proof policy in the unapplied 09051935 migration scopes object access by assigned rider/order; production policy behavior is unverified until migration and isolated identity tests.

## Additional risk register / recommendations

1. P0: Reconcile and deploy the reviewed direct-DML restriction only after explicit authorization; then test actual PostgREST denial and least-privilege owner operations across anonymous/customer/staff/rider/foreign-tenant identities.
2. P0: Verify the local Room/outbox UID guard with two-account, queued-offline, same-owner recovery, restore and screen-query integration tests; preserve recoverable pending work.
3. P0: Validate the new local entitlement/limit triggers against the complete plan/role/operation matrix in staging; the source fix is not yet deployed.
4. P1: Run Deno tests and isolated concurrent cross-org token claims; deploy the local claim fix only after separate authorization; reconcile server failures safely.
5. P1: Validate all deep-link fields and recovery PKCE state; run stale/forged/replay intents on isolated device.
6. P1/P2: Audit rate limits and enumeration for phone OTP, password reset, customer lookups, owner/staff endpoints; verify Supabase Auth and Edge Function limits externally.
7. P2: Verify storage bucket privacy, signed URL expiry, file type/size bounds, private export handling, and deletion retention.
8. P2: Validate merged release manifest, backup behavior, API-key referrer/package restrictions, obfuscation mapping upload, and privacy/data-retention disclosures.

## Safe verification plan

Use a disposable local Supabase instance and isolated identities for Owner A/Owner B, manager/staff/rider/customer/anonymous. Assert tenant read/write isolation and direct SQL API privilege boundaries. Test transaction/role denials with synthetic records only. Test deep links and OTP abuse controls against staging with rate-limited test accounts. No production pen test or customer data access is authorized by this audit.

## Group 3 billing security addendum — 2026-10-09

The latest checked-out billing verifier now uses Google `subscriptionsv2` as the purchase-state authority, checks authenticated Owner membership and Google obfuscated account identity for new token binding, and persists the binding/subscription/history atomically via service-role-only RPCs before acknowledging. Token values are encrypted for reconciliation and raw RTDN token values are stripped before event persistence; token hashes remain lookup keys. Trial claims use HMACs of normalized verified email/phone contacts with atomic claim + subscription/history provisioning. The local SQL suite contains rollback-only Owner/trial/token ownership, replacement, stale-event and synthetic scrub tests; Deno tests cover provider mapping, token crypto, and identity normalization. Latest counts and exact commands are in `TEST_MATRIX.md`.

These are local-source and disposable-database results only. No deployed verifier, RTDN identity, service-role permissions, licensed purchase, Play Console product, or hosted cross-tenant REST/RPC identity was verified. `reconcile-play-subscriptions` is not scheduled; historical hash-only tokens cannot be decrypted, and trial claims associated only with deleted UIDs cannot be translated to contact HMACs safely. Those remain release blockers. No production security test or mutation was performed.
