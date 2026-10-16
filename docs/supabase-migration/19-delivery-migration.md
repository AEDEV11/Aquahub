# Delivery Migration

Status: `DELIVERY_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`

Delivery is currently represented by order fields (`delivery_mode`, address/place/coordinates, fee, rider, and delivery timestamps) plus station/customer address data. There is no standalone delivery table. Owner statuses include rider assignment, ready for pickup, out for delivery, delivered, and completed; the customer app reads order status. Delivery fees are integer centavos. New delivery assignment and delivery-status mutations use the Supabase RPC path when the authenticated session and canonical UUID mapping are available; the legacy queue remains only as a compatibility fallback for unmapped/offline/order-blocked cases.

The Supabase order schema preserves the core delivery fields and `create_order` derives customer delivery fees or validates owner-provided fees. Owner walk-in delivery orders are normalized to `READY_FOR_PICKUP` at insert time, matching the owner app's rider-ready flow. Migration `20261003180000_delivery_foundation.sql` adds an RLS-closed `delivery_events` operation ledger and two authenticated, security-definer RPCs: `assign_delivery` and `transition_delivery_status`; migration `20261004140334_fix_walk_in_delivery_sync.sql` extends the latter to cover station preparation transitions and adds the insert normalization.

## Server contract

| Operation | Allowed caller | Preconditions | Result |
|---|---|---|---|
| `assign_delivery` | station operator | DELIVERY order in `READY_FOR_PICKUP`; rider belongs to station, active, available | sets rider and `RIDER_ASSIGNED` |
| `transition_delivery_status` | station operator or assigned active rider; preparation states require station operator | DELIVERY order and valid next state | `PENDING → ACCEPTED → PREPARING → READY_FOR_PICKUP`, then `RIDER_ASSIGNED → OUT_FOR_DELIVERY → DELIVERED → COMPLETED` |

Both RPCs require a non-empty operation ID and are idempotent through the unique `delivery_events.operation_id`. They lock the order/rider rows, enforce station ownership, and reject direct client table access. Delivery completion does not mark COD paid; cash collection remains a separate owner payment confirmation through `record_payment`. No refund, provider, or inventory behavior is inferred.

The owner app has no separate delivery repository or delivery table. Delivery actions are part of the order detail flow. `RoomOrderRepository` now uses `SupabaseDeliveryDataSource` for online delivery assignment/status transitions and refreshes mapped station orders/items from Supabase into Room before the online owner list is displayed. Offline replay uses the same RPCs through `SupabasePendingMutationWriter`; completed Supabase delivery mutations are not mirrored as new Firestore delivery writes. Non-delivery order transitions remain on the blocked order compatibility path. No dedicated rider delivery screen/repository exists in the current owner app, so no rider feature was invented. The compatibility boundary permits Firebase only for an unmapped legacy record or unavailable compatibility session; Supabase authorization/business failures never retry Firebase.

The customer order repository already reads order delivery status from Supabase and scopes rows to the authenticated customer. No separate customer delivery listener was found. FCM remains transport only; provider secrets and delivery business state must not live in Android or Firestore.

## Historical/import and remaining dependencies

No separate legacy delivery collection was found. Delivery history is embedded in legacy order fields. `tools/supabase-migration/migrate-deliveries.mjs` is a dry-run-first reconciliation tool for legacy order exports; it maps order/station/rider IDs, preserves status/timestamps/coordinates, is idempotent by canonical `firebase_id`, never creates a duplicate delivery table, and has no notification/payment side effects. Proof-of-delivery objects still require a future Supabase Storage bucket/path and authorization migration. Realtime/FCM notification migration and physical device validation remain pending.

Dependencies: authenticated order/delivery validation, payment settlement policy, Supabase Storage for proof-of-delivery objects, rider/station RLS, and production outbox worker/FCM secret deployment. Customer delivery status already reads Supabase. Authenticated delivery success/negative tests remain pending the trusted disposable fixture setup. The delivery gate is `DELIVERY_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`.

Delivery assignment/status events now enqueue server-only notification outbox work. FCM delivery remains asynchronous and cannot roll back delivery state. Automatic dispatch still requires the supported scheduler/worker invocation plus `NOTIFICATION_DISPATCH_SECRET` and FCM HTTP v1 credentials; until device delivery is tested the separate gate is `FCM_OUTBOX_IMPLEMENTED_LIVE_VALIDATION_PENDING`.
