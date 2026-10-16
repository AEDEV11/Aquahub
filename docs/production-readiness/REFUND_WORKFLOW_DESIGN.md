# Order cancellation, refunds and physical returns

## Group 4 reporting extension — 2026-10-09

`refund_obligation_report(uuid,timestamptz,timestamptz)` now provides a read-only station-scoped report grouped by organization/station/payment method/status, with selected-period requests/completions/failures, current pending and reserved centavos, current partial-refund totals, and disjoint unresolved-age buckets. The RPC applies Owner or assigned Manager authorization and `BASIC_REPORTS`; synthetic exact-centavo/period/role/tenant/aging assertions pass locally. The Owner Reports card exposes summary/loading/error/empty/retry states. This does not represent money movement or a balance-sheet statement and has not been verified against hosted PostgREST or an accounting operator.

Status: Group 2 source implementation present locally and verified by the final isolated migration replay/SQL suite plus a local PostgreSQL concurrency exercise. No refund was executed against a real payment. No backend function or migration was deployed.

## State model

```text
Collected payment (immutable receipt)
  └─ request refund (Owner/Manager; exact centavos; idempotency key)
       ├─ PENDING (amount reserved; no money claimed as returned)
       │    ├─ confirm actual return (cash attestation or external confirmation reference) → COMPLETED
       │    └─ record failure with reason → FAILED (reservation released)
       └─ same request key/payload → original refund row (no duplicate)

Order cancellation is independent:
  eligible in-progress order → CANCELLED + cancellation event
      ├─ unpaid: no refund event
      ├─ paid: create PENDING refund reservations in the cancellation transaction
      └─ previously OUT_FOR_DELIVERY: await physical item return before inventory increment
```

The original payment receipt is never edited or deleted to represent a refund. Refund state and history are appended to `payment_refunds` and `payment_refund_events`. `orders.payment_status` stays within legacy-compatible `PENDING`, `PAID`, and `REFUNDED`: a partial refund against a previously fully-paid total remains `PAID`; only net-full refund becomes `REFUNDED`. Exact partial amounts and states are read from the refund ledger.

## Transaction and ledger design

Migration `supabase/migrations/20261009104237_group2_refunds_and_order_integrity.sql` adds:

- `payment_refunds`: organization/station/order/original receipt IDs; positive `amount_centavos bigint`; original method; unique operation ID; PENDING/COMPLETED/FAILED; requester, confirmer/failer, reason, provider/external reference and timestamps.
- `payment_refund_events`: append-only actor/time/operation/amount/reason audit events.
- `order_cancellation_events`: unique operation ID, actor/reason/original status, amount reserved for refunds, and whether physical inventory return is pending.
- `order_return_events` + `order_return_items`: requested/confirmed returned product quantities, actual actor, inventory movement link, idempotency and reason.
- `orders.cancelled_from_status` and `inventory_return_completed_at` to preserve fulfillment history without overloading the payment enum.

`request_payment_refund` locks the order then receipt, validates Owner/Manager membership and scope, requires a paid receipt, reserves only unrefunded cents, and rejects an amount above the remainder. Pending and completed refunds count against the cap; FAILED rows do not. A unique operation ID binds one payment, amount and reason. Retry authorization is checked before returning an existing request.

`complete_payment_refund` and `fail_payment_refund` lock the ledger row, require Owner/Manager role, enforce one-way PENDING transitions, and use stable operation IDs. Cash/COD completion requires an explicit confirmation after money is physically handed back. Non-cash completion requires an external confirmation reference. The RPC records the owner attestation; it does **not** call GCash/Maya/card providers and cannot independently prove that the external transfer completed. A provider integration or independently reconciled confirmation remains a future requirement if automatic provider refunds are expected.

Payment receipts and append-only financial/audit rows have update/delete immutability triggers (except privileged PostgreSQL maintenance) and API-role TRUNCATE revoked. Refund state itself remains mutable only through guarded RPCs; the append-only event ledger preserves each transition.

## Authorization matrix

| Operation | Owner | Manager | Station staff | Assigned rider | Customer | Anonymous |
|---|---:|---:|---:|---:|---:|---:|
| Read full refund history | Yes | Yes | Yes, permitted station | No | Own order, sensitive fields masked | No |
| Request/complete/fail refund | Yes | Yes | No | No | No | No |
| Cancel unpaid eligible in-progress order | Yes | Yes | Scoped operator | No | Own order while PENDING/ACCEPTED | No |
| Cancel paid in-progress order and reserve refund | Yes | Yes | No | No | No | No |
| Confirm physical returned items | Yes | Yes | No | Rider assigned to that order | No | No |
| Directly insert/update/delete receipts or refund/audit rows | No | No | No | No | No | No |

`list_order_refunds_for_orders(uuid[])` is authorized per order, rejects duplicate/empty/over-200 ID sets, and masks original payment ID, operation ID, provider reference and failure reason for the customer. Direct DML remains revoked; all transitions go through RPCs.

## Cancellation and inventory behavior

- An unpaid eligible order can be cancelled without creating a financial reversal.
- A paid cancellation creates PENDING refund reservations atomically with the order transition; cancellation does not assert that money was returned.
- A delivery still in `OUT_FOR_DELIVERY` records the origin status and defers stock restoration. `confirm_cancelled_order_return` increments inventory only for quantities physically recorded as returned, with product/quantity validation, row locks, movement ledger entries and duplicate-operation protection. Partial returns are supported; completion is recorded only after all tracked order quantities are accounted for.
- Cancellation before dispatch restores known app-created reserved stock once. `LEGACY_IMPORT` orders are excluded from blind stock reversal because the backend cannot prove their inventory was originally deducted.
- Refund completion and physical item return are separate events; refunding money does not itself add bottles back to stock.

## Owner and Customer UX/API

Owner Order Details includes payment receipt/refund history, partial-centavo amount/reason entry, PENDING/COMPLETED/FAILED states, explicit cash returned confirmation, and a mandatory external confirmation reference for non-cash methods. Only Owner/Manager can mutate; riders do not receive this section. Errors do not show success, local operations retain idempotency IDs for retry, and the UI reloads persisted state after completion.

Customer tracking retains the existing `cancel_order(uuid,text,text)` signature for eligible unpaid customer cancellations. For cancelled online orders it uses the authorized batch refund-history RPC and shows masked pending/completed/failed status, amount, and reason; a temporary unavailable RPC is surfaced as a status-refresh message without making the whole order query fail. The cancellation trigger enqueues the online customer notification; local SQL coverage asserts that path. The old Owner client is not expected to understand Group 2 RPCs; coordinate backend first, then the new client. No tablet redesign is included in this group.

## Sales and accounting semantics

Owner Room revenue aggregates exclude orders in CANCELLED or REJECTED state, so a cancelled order is not shown as a sale while its refund is pending or failed. Completed refund amounts are subtracted for eligible non-cancelled orders, and paid-order count/top-product revenue use the net eligible amount. Full analytics derives customer, rider and consolidated-station sales from the same net-by-order values; JVM regressions cover partial/full, pending/failed and out-of-period refund grouping. The Room DAO instrumentation regression covers partial completed refunds, pending/failed refunds and fully refunded paid-order counts; its device result is recorded in `TEST_MATRIX.md`. Accounting policy: pending amounts reserve refundable cents but do not reduce sales; failed attempts do not reduce sales and release their reservation; completed refunds reduce net sales in the period of `completed_at`. The receipt and per-order refund ledger make obligations individually visible, but no aggregate statement/dashboard for pending refund liabilities or failed obligations exists. This aggregate reconciliation report remains a release blocker; sales analytics are not a cash-flow or balance-sheet statement.

## Regression evidence and remaining limits

Group 2's historical isolated pgTAP run covered RPC grants/RLS, immutable receipt/audit objects, role/tenant denial, partial/full refund reservation, over-refund rejection, request/complete idempotency, failure/retry, Customer cancellation, masked reads, notification, physical return and payment-status compatibility (9 files / 43 assertions at that checkpoint). The latest clean 53-migration disposable replay passed 11 files / 81 assertions, documented in `TEST_MATRIX.md`. JVM regression coverage for net aggregation by customer/rider/station passed 2/2. Owner connected instrumentation subsequently passed 13/13 tests on SM-A366B Android 16, including Room DAO refund aggregation. A separate concurrent local PostgreSQL refund test confirmed an identical request key returns one refund row; different concurrent requests were serialized/capped so accepted reservations did not exceed the collected amount. These are local tests, not PostgREST or deployed integration evidence.

Not proven locally: a real GCash/Maya/card transfer, actual cash handoff, live rider proof storage, cross-process HTTP timeout/retry, simultaneous psql/API requests (unless separately reported in final test evidence), or deployed PostgREST behavior. No refund automation provider endpoint exists; no actual money movement is performed by these RPCs.
