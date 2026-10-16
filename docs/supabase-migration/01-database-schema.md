# Proposed Supabase database schema

Status: `DESIGNED — NOT APPLIED`. These files are a reviewable target and have not been connected to a Supabase project.

## Design decisions

- `organizations` represents the existing Firestore `businesses` tenant. There is no separate Branch aggregate; `stations` remains the station/branch entity.
- Supabase Auth owns credentials. `profiles.id` is the Supabase Auth UUID. `profiles.firebase_uid` is a unique migration correlation field and is not used for authorization.
- Business records use UUID primary keys plus nullable unique `firebase_id` text columns. This preserves comparison with existing Firestore IDs without assuming their format.
- `organization_members` and `member_stations` normalize the existing member `stationIds` array. Authorization is membership plus assigned station, never a client-provided role field.
- New financial fields are `bigint` integer centavos. Existing Room `Double` pesos must be rounded only in an explicit migration adapter and never accepted as a new API field.
- Public station/product data is a controlled projection. Customer order creation remains server-authoritative and is not a direct client insert.

## Firestore-to-PostgreSQL mapping

| Firestore | PostgreSQL | Mapping |
|---|---|---|
| `users/{uid}` | `profiles` | `firebase_uid` → `firebase_uid`; Auth user → `id`; profile/setup/acquisition fields retained. |
| `businesses/{businessId}` | `organizations` | document ID → `firebase_id`; `ownerUid` → `owner_profile_id` after profile mapping. |
| `businesses/{businessId}/members/{uid}` | `organization_members` | document ID → member profile; role/status retained. |
| member `stationIds[]` | `member_stations` | one row per assignment, FK to `stations`. |
| `.../stations/{stationId}` | `stations` | station document fields retained; `business_id` FK added. |
| `.../customers/{customerId}` | `customers` | station FK; `firebase_id`; centavo total replaces legacy peso aggregate. |
| `.../customerAddresses/{addressId}` | `customer_addresses` | customer/station FKs and nullable coordinate pair. |
| `.../products/{productId}` | `products` | product/promotion fields retained; all prices centavos. |
| `.../riders/{riderId}` | `riders` | station FK and operational status/location fields retained. |
| `.../orders/{orderId}` | `orders` | station/customer/rider FKs; order totals/status/timestamps; `firebase_id` retained. |
| embedded `orders.items[]` | `order_items` | one row per snapshot line; product reference nullable, snapshot fields mandatory. |
| `.../containerInventory/{id}` | `containers` | one station/size ledger row. |
| `.../containerMovements/{id}` | `container_movements` | immutable movement record with logical customer/order references. |
| `.../payments/{id}` | `payments` | receipt rows; `amount_centavos`; order FK. |
| `.../expenses/{expenseId}` | `expenses` | archived via `is_active`/`deleted_at`, not physical delete. |
| `subscriptionPlans/{planId}` | `subscription_plans` | plan catalog; normalized feature/limit child tables. |
| `.../subscription/current` | `subscriptions` | one current subscription per organization; plan FK. |
| `users/{uid}/devices/{tokenId}` | `device_tokens` | token data; token itself is never exposed in logs. |
| public projections | `public_stations`, `public_products` | server-maintained/sanitized projection; no client writes. |
| request/audit/trial/billing paths | corresponding server-only tables | retained for idempotency, audit, entitlement, and webhook replay protection. |

## Relationship outline

`auth.users → profiles → organizations → organization_members → member_stations → stations → {customers, products, riders, orders, containers, movements, payments, expenses}`.

`orders → order_items/payments`; `customers → customer_addresses`; `subscription_plans → subscriptions/plan_features/plan_limits`; `orders → customer_order_mirrors` is a customer-readable projection if a separate mirror is retained.

## Migration exclusions and compatibility fields

- Firestore path segments are not reproduced as nested PostgreSQL tables; relationships become foreign keys.
- Firestore document IDs are retained in `firebase_id` for every migrated aggregate and in mapping/audit tables where needed.
- Product `description` is currently part of the customer public model but not consistently present in the owner Room entity; it remains nullable in the public projection pending source reconciliation.
- `order_items` snapshots remain even when a product is archived or renamed.
- `customer.totalSpent` is retained as a derived compatibility aggregate only if validation proves it is used; canonical financial reporting should sum paid receipts.
- `updated_at`, `created_at`, `deleted_at`, `client_updated_at`, and `last_server_updated_at` support incremental migration/reconciliation.
- Firebase Storage URLs remain in `image_path` fields during the review period. Moving them to Supabase Storage is a separate decision.

## Apply order

1. Extensions and enums.
2. Profiles/organizations/membership/stations.
3. Customers/catalog/riders.
4. Orders/items/containers/payments/expenses.
5. Subscription and server metadata tables.
6. Public projections, indexes, helper functions/triggers, then RLS/grants.

