# Inventory Migration

Status: `APPLICATION_CUTOVER_IN_PROGRESS — ORDER CLIENTS REMAIN`

Supabase `inventory_balances` and `inventory_movements` are now the authoritative product-stock model. Firebase product `stockQuantity` remains only as legacy migration input/compatibility data; no new Firebase catalog write is allowed to mutate it.

## Mutation audit

### Legacy paths found

| Path | Location | Classification | Cutover state |
|---|---|---|---|
| Product creation initial stock | `AquaHub/app/src/main/java/com/aesprt/aquahub/data/repository/RoomProductRepository.kt` | `MIGRATE_NOW` | Catalog creation omits stock; opening quantity uses Supabase `adjust_inventory` with `LEGACY_OPENING_BALANCE`. |
| Product manual stock adjustment | `RoomProductRepository.updateStock` and `UpdateProductStockUseCase` | `MIGRATE_NOW` | Calls Supabase RPC with a stable operation key derived from product mapping, prior local timestamp, and target quantity. |
| Product catalog update | `RoomProductRepository.updateProduct` and `FirebaseRemoteDataSources.kt` | `MIGRATE_NOW` | Product catalog writes omit `stockQuantity`; Room value is cache-only. |
| Firebase product stock transaction | `FirebaseProductDataSource.adjustStock` | `TO_REMOVE_WITH_ORDERS` | No longer called by the sync coordinator. Retained source code only for legacy compatibility until all old queue/data paths are retired. |
| Firebase order-created deduction | `AquaHubCustomer/functions/src/index.ts:createCustomerOrder` | `LEGACY_ORDER_DEPENDENCY` | Firestore order transaction still validates/decrements Firebase `stockQuantity`; order migration must replace this atomically. |
| Firebase order cancellation/rejection restock | `restoreOrderInventoryOnce` and `cancelCustomerOrder` | `LEGACY_ORDER_DEPENDENCY` | Firestore compensation transaction still restores product stock; replace with order cancellation + inventory reversal RPC. |
| Owner walk-in order deduction | `AquaHub/.../domain/usecase/order/OrderUseCases.kt` | `LEGACY_ORDER_DEPENDENCY` | Existing use case still sequences product stock update before order persistence; it must move into `create_order`. |
| Container stock-in/out/damaged/lost/returns | `RoomInventoryRepository` and `InventoryUseCases.kt` | `MIGRATE_NOW` for container inventory | These are container ownership movements, not product stock. They remain Room/Firebase-backed until the container ledger is mapped to Supabase. |
| Room product stock cache updates | `ProductEntity.stockQuantity`, `ProductDao`, Firebase applier | `MIGRATION_READER` | Kept temporarily because existing UI/domain contracts expose `Product.stockQuantity`; values are refreshed from Supabase balances when available. |
| Customer/order product reads | Customer Firestore catalog and order Functions | `LEGACY_ORDER_DEPENDENCY` | Must use Supabase product plus inventory projection after order transaction cutover. |

No Realtime Database, `FieldValue.increment` outside the identified order paths, or separate Firebase inventory collection was found in the active source. Cloud Functions contain both deduction and idempotent cancellation compensation paths and must not be bypassed by a client-side substitute.

## Authority rules

- `products`: descriptive/commercial fields only. `stock_quantity` is legacy compatibility data and is not written by the migrated Android catalog path.
- `inventory_balances`: current non-negative integer unit count, protected by station authorization and row locking.
- `inventory_movements`: immutable movement/audit history with operation id, movement type, source id, actor, and timestamp.
- Every mutation has a stable idempotency key. Retrying the same operation returns the existing movement/balance and cannot apply the delta twice.
- Product identity is the Supabase UUID; `products.firebase_id` is used only for legacy mapping.

## Deployed RPC behavior

`adjust_inventory` validates the authenticated caller, station membership, product-to-station relationship, non-zero delta, operation metadata, and non-negative resulting balance. It locks the balance row with `FOR UPDATE`, rejects operation conflicts, increments a version, and records actor/timestamp metadata. The eight-argument overload additionally records `movement_type` and `source_id`. The deployed order RPCs now use the same ledger directly inside their order transaction and record `ORDER_DEDUCTION`/`ORDER_REVERSAL` movements.

The SQL migration added no destructive operation. Cross-station and concurrency integration tests remain required before the final release gate.

## Room behavior

Room remains the offline-first UI cache. The existing `ProductEntity.stockQuantity` is explicitly a compatibility cache while the product UI contract is being separated. The next Room slice should add a dedicated local inventory-balance entity/DAO, keyed by local station/product plus Supabase product mapping, so the final product model no longer stores mutable stock.

Offline manual inventory adjustments must preserve the same operation id through retry. Legacy Firebase stock queue rows are replayed to Supabase and are not sent to the Firebase transaction path.

## Opening-balance migration

The migration tool must read legacy Firebase product stock, resolve station and product by preserved Firebase IDs, and create one `LEGACY_OPENING_BALANCE` movement per product. Missing mappings and discrepancies are failures in the report, not silent zero balances. Apply is never implicit.

Implementation: `tools/supabase-migration/migrate-inventory.mjs`. It defaults to `--dry-run` behavior, requires `--input`, validates through hosted Supabase REST using environment credentials, and requires both `--apply` and `--confirm-hosted-production` for writes. It never deletes or mutates Firebase source data.

## Remaining Firebase references

Remaining stock references are limited to legacy readers, the old Firebase product transaction implementation, remote product DTO compatibility, the Firebase order deduction/restock dependency, and the container inventory path. These are classified above and cannot be deleted until Orders and containers are migrated.

## Verification completed

- Owner `compileDebugKotlin`, `compileReleaseKotlin`, `testDebugUnitTest`, and `assembleDebug`: passed.
- Hosted `supabase db push --linked --dry-run`: up to date.
- Hosted `supabase migration list --linked`: aligned through `20261003151000`.
- Hosted `supabase db lint --linked`: no schema errors.
- Read-only contract checks were added in `supabase/tests/inventory_invariants.sql`.
- Disposable authenticated order success/concurrency/cross-tenant RPC tests were not run in this implementation pass; they remain required before client cutover.

## Order dependency update

`20261003150000_orders_atomic_transactions.sql` and `20261003151000_fix_order_rpc_types.sql` are deployed and lint-clean. Firebase order deduction/restock is still present only because the customer and owner order consumers have not yet been switched; no new product/manual inventory path writes Firebase stock.
