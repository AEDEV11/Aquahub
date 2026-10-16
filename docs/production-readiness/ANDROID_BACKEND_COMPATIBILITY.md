# Owner and Customer backend compatibility

## Group 4 current environment finding — 2026-10-09

Current source package versions and limited device evidence are consolidated in `CLIENT_VERSION_COMPATIBILITY.md`. Neither installed metadata nor the Supabase CLI link proves Closed Testing provenance. Current local migration/RPC contracts are ahead of linked history; deployed Owner/Customer compatibility is **BLOCKED** pending isolated hosted REST/JWT tests. Do not restore broad direct-write grants for an older client.

Snapshot: 2026-10-09. Local source contract review; no live Android client was connected to a deployed/staging backend. Production release builds and Closed Testing versions remain unverified.

## Current source contracts

| Workflow | Current Owner source | Current Customer source | Compatibility status |
|---|---|---|---|
| Business reads | Supabase/PostgREST/RPC plus Room and sync outbox | Supabase authenticated reads plus polling snapshot | Source-compatible with intended Supabase schema; actual installed endpoint NOT VERIFIED. |
| Order creation | Server RPC for walk-in/owner operations | `create_order` with client operation key, selected products and server totals | Existing `create_order` signature is retained; local linked schema diff shows a legacy linked body, so behavior is not compatible until reviewed migration rollout. |
| Payment recording | `record_payment` RPC; rider collection requires proof metadata/storage authorization | Customer creates order but does not write paid receipt rows | New Group 1 guards intentionally reject direct table writes. No legitimate workflow should regain broad payment DML. |
| Delivery/status | Guarded delivery RPCs and station/rider roles | Reads authoritative order status | Linked functions have older definitions; staging PostgREST/JWT validation is required. |
| Cancellation | New Owner source uses `cancel_order_with_refunds` and stable request identity | Existing `cancel_order(uuid,text,text)` call preserved | Local Group 2 schema supports paid Owner cancellation with pending refund reservations; Customer legacy signature remains for eligible unpaid cancellation. Deployment order matters. |
| Refund status | Owner uses `request_payment_refund`, `complete_payment_refund`, `fail_payment_refund`, `list_order_refunds_for_orders` | Customer reads masked refund state for cancelled orders | New RPCs are absent from linked migration history and must deploy before shipping this source. |
| Subscription | Supabase verifier/entitlement RPC and Room cache | No owner plan mutation | Group 1 server limits remain local only; no client-side bypass should be enabled as a compatibility exception. |
| Offline retry | Room-backed pending mutations with account UID scope and idempotency | Customer app submits online order with server idempotency key; no full durable offline payment workflow proven | Lost-response/server-timeout behavior still needs staging integration validation. |

## Old-client contract and rollout implications

- Customer’s previous `cancel_order(p_order_id,p_operation_id,p_reason)` call continues to match the SQL signature. It can cancel only eligible unpaid customer orders; it cannot request or complete station refunds.
- The old Owner client cannot use the new refund workflow. Do not add compatibility RPCs that allow it to directly mutate orders, receipts, or refund rows.
- Group 1 revokes direct order/order-item/payment mutations. A previously released client that writes those tables directly will fail after deployment. The correct migration is to ship a client using existing guarded RPCs, stage both app versions, and coordinate backend rollout; restoring broad grants is prohibited.
- Group 2 status remains within legacy `PENDING`, `PAID`, `REFUNDED` values. Partial refunds are represented by the new ledger, not a novel status string that old app enums may fail to parse.
- New Owner code needs the refund/cancellation/return migration and RPCs before it can complete paid cancellation workflows. New Customer code requests refund summaries only for cancelled online orders; temporary endpoint failures surface as unavailable status rather than turning all order reads into empty success.
- Release/build versions may still use Firebase or a different Supabase project. Current working-tree contracts do not prove compatibility with either installed Closed Testing binary.

## Compatibility tests

Local isolated SQL coverage exercises direct-DML denial, guarded RPC grants, customer legacy-signature cancellation, Owner paid cancellation/refund reservation, customer masked refund reads, cancellation notification outbox enqueue, and refund/inventory invariants (9 files/43 assertions on the Group 2 snapshot). The separate newer guest-ordering migration is not in that snapshot. Latest Owner and Customer JVM suites passed 118/118 and 21/21; Owner instrumentation sources, including the Room refund-report regression, compile, but the connected task was stopped before assertions ran. These checks do not test hosted PostgREST, a currently released client binary, or a deployed function. No staging JWT compatibility test ran.

## Safe rollout order (not deployment authorization)

1. Identify production-of-record and exact Owner/Customer release versions/endpoints.
2. Reconcile the linked schema/migration history against a restorable nonproduction clone.
3. Validate Group 1 and Group 2 SQL/RPCs against Owner and Customer test JWTs over actual local/isolated PostgREST; include direct REST denial.
4. Deploy reviewed backend changes to staging, then run old and new app versions through create/order/payment/delivery/cancel/refund/status/notification workflows.
5. Deploy backend first only after independent authorization; release app changes afterward with a minimum-supported-version or staged transition if an old client cannot safely operate.
6. Retain rollback/forward-recovery and monitor RPC authorization errors, sync queues, payment/refund events and notifications. Never restore unsafe direct table grants to quiet client failures.

Production compatibility is **NOT VERIFIED** until those steps are evidenced.

## Group 3 billing contract addendum — 2026-10-09

The Owner client now displays only eligible `ProductDetails`/offer prices returned by Play Billing and submits purchase tokens to the server verifier; no static paid-price fallback is used. The local server contract requires a verified authenticated Owner membership, Google-verified product/base-plan and (for a new token) matching obfuscated account identity, then writes through a service-role RPC. The Customer app has no subscription purchase/refund mutation contract. JVM and Deno checks pass locally, but no hosted API or currently released binary was tested.

Compatibility risk: already distributed Owner clients that omit the expected obfuscated account identity for an unclaimed purchase token may be denied on first bind. Do not weaken the server check to accommodate them. Before any deployment, stage both currently released versions, determine their BillingClient account identifiers and token restore behavior, and provide a user-safe upgrade/reverification or minimum-supported-version transition. Existing bound tokens can only be accepted by their recorded owner under the guarded RPC; hash-only historical tokens are not sufficient for server polling. This source change does not establish that release binaries use the same backend or product IDs. Deployment order and authorization remain separate gates.
