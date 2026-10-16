# Payments Migration

Status: `PAYMENTS_MIGRATION_IN_PROGRESS — FOUNDATION_DEPLOYED_VALIDATION_PENDING`

This work is independent of the order validation gate, which is now `ORDER_BACKEND_CUTOVER_VALIDATED`; payment provider/refund validation remains separate and pending.

## Current-state audit

Supported methods are `CASH`, `CASH_ON_DELIVERY`, `GCASH`, and `MAYA`. GCash/Maya account details and QR paths are station instructions, not settlement proof. No PayMongo/card/bank-transfer provider, provider callback, signature verifier, automated refund, or void flow exists. Customer checkout selects a method and creates a pending order. Owner walk-in payments are locally recorded as paid receipts; the legacy Room sync queue can still write those receipts to Firestore. Customer account payments support multiple paid receipt rows and partial settlement. Google Play subscription billing is a separate domain and remains unchanged.

## Supabase schema and state model

Migration `20261003170000_payments_foundation.sql` is additive. It adds organization/customer ownership, provider fields, JSON metadata, stable operation IDs, `updated_at`, unique operation/provider transaction keys, status validation, and an order-payment index. Existing rows are backfilled from orders without deletion.

Supported statuses and transitions:

```text
PENDING -> PAID | FAILED | CANCELLED
PAID -> REFUNDED | PARTIALLY_REFUNDED
PARTIALLY_REFUNDED -> REFUNDED
```

`record_payment(...)` derives the caller, validates order access and positive integer-centavo amount, rejects cancelled/rejected orders, prevents overpayment, and is idempotent by operation ID/provider transaction ID. It never invokes a provider or changes inventory. A database trigger creates one paid receipt for immediately-paid owner walk-in orders, excluding `CLIENT_ACCOUNT`, so an atomic cash sale cannot leave a paid order without a receipt.

## Consistency rules

- Owner walk-in CASH/GCASH/MAYA/COD orders marked paid receive an atomic Supabase receipt.
- `CLIENT_ACCOUNT` remains receivables behavior and does not create a paid receipt during order creation.
- Customer orders remain pending until an explicit settlement records a receipt.
- Payment confirmation/refund does not restock inventory; cancellation and inventory reversal remain separate.
- Future webhooks must verify provider signatures and apply one idempotent state transition through Supabase Edge Functions.

## Client cutover

Owner order creation now uses Supabase and the database creates the paid walk-in receipt. The owner Room cache keeps its existing Long key for local compatibility but now stores the authoritative Supabase payment UUID, operation ID, provider fields, and metadata through an additive Room 16→17 migration. Supabase-backed walk-in creation reads the returned receipt into that cache and does not enqueue a Firebase payment write; the explicit local/offline fallback still uses the legacy repository until offline reconciliation is implemented. Customer-account receipt writes remain compatibility paths pending authenticated validation. No customer payment-record provider callback was found.

Google Play subscriptions are a separate provider domain. New owner purchase/restore verification and trial provisioning use Supabase Edge Functions and `get_effective_entitlements()`; Firebase remains only for the retained RTDN compatibility function until a Supabase receiver is validated.

## Historical importer

`tools/supabase-migration/migrate-payments.mjs` is dry-run by default. `--apply --confirm-hosted-production` is required for writes. It preserves legacy IDs, references, timestamps, and statuses; maps legacy order IDs; validates positive centavo amounts; reports unmatched orders/conflicts; is restartable; and never invokes providers, sends notifications, or changes inventory. It uses only server-side environment credentials and contains no hardcoded secrets.

## Firebase classification

- `FirebasePaymentDataSource`, payment sync mapper, and payment queue: `TEMPORARY_COMPATIBILITY`.
- Firestore payment history: `HISTORICAL_IMPORT_ONLY` after approved export/reconciliation.
- Customer order payment-method validation/mirror: `TEMPORARY_COMPATIBILITY` until authenticated Supabase order validation passes.
- Google Play Functions: `KEEP_FOR_SUBSCRIPTION_BILLING`.
- FCM: `KEEP_FOR_FCM`; future payment notifications may originate from Supabase Edge Functions.
- No order-payment provider/refund Function was found.

## QR/storage boundary

Station GCash/Maya QR images are configuration assets, not payment proof. New uploads now use the private `payment-assets` bucket at `organizations/{organization_id}/stations/{station_id}/payment-assets/{wallet}/...` with station-manager policies. Existing Firebase paths remain readable/importable until the reviewed Storage manifest is migrated; no object deletion is claimed.

## Refund/cancellation matrix

| Event | Payment | Order | Inventory |
|---|---|---|---|
| Cancellation before completion | No automatic provider refund | `CANCELLED` | `cancel_order` restores once |
| Paid cash sale | Receipt `PAID` | Paid/completed per order flow | Already deducted |
| Failed future provider payment | `FAILED` | Remains unpaid | None |
| Provider refund | `REFUNDED` or `PARTIALLY_REFUNDED` | Explicit future policy | None unless separately authorized |

No provider or refund UX is implemented until a real provider is selected and its webhook contract is available.
# Runtime dependency note

Authenticated hosted checkpoint (2026-10-04): `validate-payments.mjs` passed walk-in authoritative receipt creation, authenticated customer settlement, operation idempotency, and over-settlement rejection. The four checks use normal user access tokens for payment/order RPCs. Real provider purchase/refund validation remains absent, so the payment gate remains `PAYMENTS_MIGRATION_IN_PROGRESS — FOUNDATION_DEPLOYED_VALIDATION_PENDING`.

Payment notifications/token delivery remain subject to the shared FCM architecture audit in `23-firebase-runtime-removal.md`. No provider refund flow or notification behavior was invented, and payment settlement remains separate from delivery completion.

Google Play subscriptions remain a separate entitlement domain: purchase verification, acknowledgement, trial provisioning, restore, and RTDN re-verification are Supabase server paths. Google Play credentials are not present in this environment; no real purchase or refund validation is claimed. The Firebase RTDN callable remains retained until the Supabase `play-rtdn` receiver is live-tested.
