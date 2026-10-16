# Orders Migration

Status: `SUPABASE_TRANSACTION_DEPLOYED — AUTHENTICATED VALIDATION PASSED, RUNTIME OBSERVATION PENDING`

## Audit result

The audited legacy paths are:

- `AquaHubCustomer/functions/src/index.ts:createCustomerOrder`: former Firebase callable transaction for order creation, customer mirroring, idempotency, and Firestore stock mutation. It is now `RUNTIME_UNUSED_OBSERVATION_PENDING`; Supabase `create_order` replaces the production transaction.
- `AquaHubCustomer/functions/src/index.ts:cancelCustomerOrder`: former Firebase callable cancellation and legacy restock path. It is now `RUNTIME_UNUSED_OBSERVATION_PENDING`; Supabase `cancel_order` replaces the production transaction.
- `mirrorCustomerOrderStatus` and `restoreOrderInventoryOnce`: former status mirroring and compensation/stock restoration path. They remain deployed for observation/rollback only; Supabase order/delivery RPCs and inventory movements replace new authoritative mutations.
- `AquaHub/.../domain/usecase/order/OrderUseCases.kt:CreateWalkInOrderUseCase`: validates subscription, station/customer ownership, products, promotions, containers, and payment/delivery inputs. The active path now calls `create_order` before local cache persistence and no longer performs a direct product stock mutation.
- `RoomOrderRepository`: Room is the owner-app cache and currently queues Firebase order mutations. It must not remain the remote authority after owner cutover.
- Customer `FirebaseOrderRepository` was removed after confirming zero production callers. `SupabaseOrderRepository` now supplies the authenticated Supabase order path; legacy Functions remain server-side compatibility only.

Legacy business rules found: customer orders allow 1–20 distinct lines, quantity 1–99, only active/available station products, server product prices and applicable promotions, station delivery fee for delivery, station-rule acceptance, and customer cancellation only in `PENDING`/`ACCEPTED`. Owner walk-in orders use the existing subscription/payment/container rules and may be pickup/completed immediately or delivery/ready for pickup. No separate `deliveries` table exists in the current Supabase schema; delivery metadata is stored on `orders`.

## Schema review

The deployed schema has `orders`, `order_items`, and `payments`; there is no `deliveries` table. `orders` preserves `firebase_id`, station/customer/profile links, order number, status, delivery metadata, integer-centavo subtotal/discount/delivery fee/total, payment state, timestamps, and cancellation timestamp. Migration `20261003150000_orders_atomic_transactions.sql` adds `order_source`, `tax_centavos`, `cancellation_reason`, `cancelled_by`, and a station/day order-number counter. There is no tax rule in the audited legacy flow, so tax is stored as zero.

## Deployed RPC contract

Migrations `20261003150000_orders_atomic_transactions.sql` and `20261003151000_fix_order_rpc_types.sql` deploy:

`create_order(station_id, client_operation_id, source, items, customer_id, customer_name, customer_phone, delivery_mode, delivery_address, delivery_place_id, delivery_latitude, delivery_longitude, delivery_fee_centavos, payment_method, customer_note)`

The client supplies only the structured product UUID/quantity list and presentation fields. The function authenticates the caller, checks owner station access or customer profile/preferred-station access, validates product station/availability, locks inventory rows in product UUID order, derives promotions and prices, calculates integer-centavo totals, generates a station/day-safe order number, inserts the order and items, deducts `inventory_balances`, and records `ORDER_DEDUCTION` movements in one PostgreSQL transaction. `order_source` is `CUSTOMER_APP`, `CUSTOMER_WEB`, or `OWNER_WALK_IN`. `CUSTOMER_WEB` is the browser ordering equivalent of the Android customer flow; it uses the same profile, preferred-station, RLS, pricing, inventory, delivery-fee, and idempotency rules.

`client_operation_id` is unique. A committed retry returns the existing order result and does not repeat inventory deduction. The order ID is the Supabase UUID; `firebase_id` remains available for historical mapping.

`cancel_order(order_id, operation_id, reason)` locks the order, authorizes the owner or customer, applies the legacy customer cancellation window, marks the order cancelled, restores each item once, and records `ORDER_REVERSAL` movements using `order:<order-id>:cancel:<product-id>`. Repeated cancellation returns `already_cancelled=true` and does not restore twice. Delivered/completed/rejected orders are not cancellable.

## Inventory authority

The RPCs use `inventory_balances` as the current authority. Firebase product `stockQuantity` is not read by the deployed RPCs and must become historical compatibility data only after both clients are cut over. Firebase order deduction/restock remains a legacy dependency until the customer and owner consumers are switched.

## Validation and remaining cutover

Authenticated hosted fixture checkpoint (2026-10-04): `validate-orders.mjs` passed all nine checks: normal Auth/session setup, customer and owner `create_order`, cancellation/idempotent retry, multi-item rollback rejection, customer foreign-station rejection, foreign-customer station ownership rejection, and parallel stock-one safety. A targeted concurrency proof additionally verified initial stock one, exactly one successful request, final balance zero, exactly one `ORDER_DEDUCTION`, and exactly one resulting order item/order. The harness uses normal user access tokens for business RPCs and service credentials only for disposable fixture setup/cleanup. The order validation gate is now `ORDER_BACKEND_CUTOVER_VALIDATED`; runtime observation and device evidence remain required before decommissioning legacy Firebase code.

This validation checkpoint also deployed the additive customer-station ownership guard, order-number and inventory-balance uniqueness repairs, and the walk-in payment partial-index conflict repair discovered by the authenticated run. No Firebase order source data or Functions were deleted.

Hosted `supabase db push --linked --dry-run` showed only the new migration before deployment; the two migrations are now applied. `supabase db lint --linked` reports no schema errors. The SQL contract is ready for disposable tests covering success, insufficient stock rollback, idempotency, cancellation retry, authorization, cross-station product rejection, and concurrent stock=1 requests.

The customer app now selects Supabase Auth and `SupabaseOrderRepository` in Koin; its debug Kotlin compilation passes. The owner walk-in path now selects the deployed RPC and writes a Room cache row without enqueuing a Firebase order mutation. Full owner build, authenticated RPC tests, concurrency/idempotency validation, and historical import remain pending.

The customer project contains `data/supabase/SupabaseClientProvider.kt`, `SupabaseAuthRepository.kt`, `SupabaseCatalogRepository.kt`, and `SupabaseOrderRepository.kt`. Koin selects the Supabase repositories. Auth provisioning preserves display name metadata, preferred-station writes use the protected `set_customer_preferred_station` RPC, and orders resolve legacy station/product IDs before calling `create_order`. The former Firebase order repository had zero production callers and was removed.

## Browser ordering client

`aquahub-website/order-app/` is the browser ordering client for the same station QR contract. The owner QR continues to publish `https://aquahub.aesprt.com/s/{publicStationCode}?source=qr`; Android App Links open the installed customer app, while the Firebase Hosting rewrite serves the web client when no app handles the link. The browser resolves the public station/catalog projections, authenticates with Supabase email/password, completes the same customer profile and preferred-station setup, and calls `public.create_order` with `CUSTOMER_WEB`. It does not use Firebase business SDKs or a service-role key. Cart state is local to the browser, order tracking polls the scoped Supabase order read, and pricing/inventory/order authority remains PostgreSQL.

The deployed static client is built from `aquahub-website/order-app/` into `aquahub-website/order/` and hosted by Firebase Hosting only as static delivery. The live route was checked on 2026-10-05 with a real public station code and returned the station catalog. Browser checkout remains subject to authenticated customer verification and the same deployed order RPC authorization; no unauthenticated order-success claim is made here.

Owner walk-in creation now fails clearly when the Supabase order datasource is unavailable; it does not commit a local/Firebase order. Owner cancellation persists the returned Supabase order UUID in Room metadata and calls `cancel_order` directly. Authorization, RLS, and transport failures do not reactivate Firebase order authority. The only local order-write path is an explicitly named unit-test fixture path.

## Current validation checkpoint — 2026-10-03

- Hosted project `pfkxrggexueltytxtqil`: linked migration list, dry-run, and lint are clean. The only additional migration in this checkpoint revokes exposed execution of the administrative `public.rls_auto_enable()` helper; it does not alter business data.
- Read-only order invariants passed: the order RPCs, source/tax constraints, insert-context trigger, anonymous execution denial, and private order-number counter checks all passed.
- Owner build/unit tests and customer build/unit tests passed. The historical importer syntax check passed.
- `tools/supabase-migration/migrate-orders.mjs` is dry-run by default, restartable/idempotent, preserves Firebase IDs/timestamps/statuses, validates integer-centavo totals, reports mappings/conflicts, writes only `orders`/`order_items` with `LEGACY_IMPORT`, and performs zero inventory mutations. Apply requires explicit `--apply --confirm-hosted-production` plus server-only environment credentials; it never deletes Firebase source rows.
- `tools/supabase-migration/validate-orders.mjs` is the safe hosted harness for the authenticated proof. It creates pre-confirmed disposable Auth users through trusted admin setup, signs them in through the normal password token endpoint, and sends all business RPCs with those user access tokens. It includes customer/owner create, cancellation retry, rollback rejection, idempotency, foreign-station/foreign-customer denial, actual parallel stock-one requests, and a targeted final-balance/movement/order proof. It never logs credentials or uses service-role as an RPC request identity.
- Authenticated RPC validation passed with disposable hosted sessions. The order validation gate is `ORDER_BACKEND_CUTOVER_VALIDATED`; runtime observation, historical import, and final device validation remain pending.
- Production owner offline order creation is explicitly unsupported at this checkpoint. The Supabase RPC is authoritative; the local fallback is test-only/explicit fallback. Durable Room reconciliation must be implemented and validated before offline order entry is advertised.
- Firebase callable order creation/cancellation/status-mirror code still contains Firestore stock mutation and remains a legacy dependency until authenticated Supabase validation and a reconciliation/observation window prove that no business consumer depends on it.
- Disposable hosted fixtures were removed by exact IDs. Follow-up counts are zero for fixture Auth users, profiles, organization, station, products, customer, orders, and compatibility users.

Current gate: `ORDER_BACKEND_CUTOVER_VALIDATED`.

Authenticated validation is no longer blocked by fixture setup. The remaining order work is controlled runtime observation: confirm no normal customer/owner create or customer-cancel caller reaches Firebase, then retire legacy listeners/Functions only after rollback evidence. Firebase order-stock Functions remain deployed and source-compatible during that observation window.

Run later from the repository root with secrets supplied out-of-band: `SUPABASE_URL=... SUPABASE_SERVICE_ROLE_KEY=... SUPABASE_ANON_KEY=... node tools/supabase-migration/validate-orders.mjs --fixtures /secure/path/order-fixtures.json`. Never commit the fixture file or credentials, and never print them.
# Runtime dependency note

Order Functions and their Firestore notification/device dependencies remain intentionally deployed for rollback/source compatibility after `ORDER_BACKEND_CUTOVER_VALIDATED`. The active customer repository and owner walk-in create path use Supabase RPCs; the owner Room cache and legacy transition/listener adapters still require observation before removal. The independent FCM HTTP v1/outbox architecture is documented in `23-firebase-runtime-removal.md`.

Subscription/FCM work does not change the order boundary. Existing Firebase order notification Functions remain deployed for rollback/source compatibility until runtime observation proves that Supabase order events and the outbox can replace them safely.
