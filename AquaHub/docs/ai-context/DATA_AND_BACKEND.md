# AquaHub Data, Database and Backend

## Room database

`AquaHubDatabase` is `aquahub_database`, version 21, with explicit migrations through `MIGRATION_20_21` (nullable order-item container source). Schema export is disabled. Never use destructive fallback for production data; add migration and migration tests for schema changes.

All primary operational IDs are auto-generated `Long` except sync composite keys. Tables:

| Group | Tables | Key relationships/indexes |
|---|---|---|
| Tenant | `stations`, `station_owners` | Owner→station is a logical ID, not an FK. |
| Customer | `customers`, `customer_addresses` | Customers indexed by station/phone; addresses cascade on customer deletion. |
| Catalog/fulfilment | `products`, `riders` | Indexed by station; products separate temporary availability from archived active state; no Station FK. |
| Orders | `orders`, `order_items`, `payments` | Orders indexed by station/customer/status/createdAt; items and payments cascade on order deletion. Product/customer/rider/address links are mostly logical. |
| Containers | `containers`, `container_transactions` | Indexed by station and movement customer/order IDs; links are logical. Supabase `container_movements` is pulled into Room by stable movement identity after local cache clearing. |
| Expenses | `expenses` | Station/date indexes with an optional category filter; amounts are integer centavos and records are archived rather than physically deleted. |
| Subscription | `subscription_plans`, `subscriptions`, `plan_features`, `plan_limits`, `subscription_history` | Plan code and feature/limit pairs unique; subscriptions have Station/Plan FKs; history has Station FK. |
| Sync | `sync_metadata`, `sync_queue`, `sync_cursors` | Unique local↔remote mapping; unique operation ID; queue/cursor lookup indexes. |

Entities: `data/local/entity/`. DAOs: `data/local/dao/`. Mappers: `data/local/mapper/`. Repository implementations: `data/repository/`.

DAOs expose Flow queries for station-scoped screens and transaction relations such as order-with-items/customer-with-addresses. The main order list and container movement history additionally expose Room `PagingSource` queries; repositories preserve Room invalidation while mapping persistence rows into domain models, and the UI requests 20-row pages. Reporting uses date/count/sum queries plus repository aggregation. Preserve half-open time ranges (`start <= value < end`) where already used and standardize deliberately if fixing inclusive-range inconsistencies.

The owner customer-account ledger reuses the existing `orders` and `payments` tables. A paid walk-in creates one `PAID` payment row; an account charge keeps the order payment state `PENDING`; each later partial/full receipt adds another `PAID` row and updates the aggregate order payment state. Customer balances are derived from billable order totals minus paid rows. Dashboard collected revenue sums those paid rows by receipt date, so fulfilment completion does not recognize an unpaid balance as revenue. The legacy aggregate `PAID` order status is used only when an older order has no ledger rows. No Room migration is required for this feature.

Remote order reconciliation uses an in-place Room upsert. It must not use SQLite `REPLACE`, because deleting and reinserting the parent order activates foreign-key cascades and erases its local payment ledger before the payment pull can reconcile it.

Room schema version 21 adds nullable `order_items.containerSource`. Supabase guest refill orders may provide `CUSTOMER_OWNED`, `STATION_EXCHANGE`, or `STATION_BORROW`; delivery-order reads map this value into Room and Order Details. Null remains valid for older orders, Android-created orders, and non-refill items.

Room schema version 4 adds nullable `deliveryLatitude` and `deliveryLongitude` columns to orders. Remote sync maps the corresponding Firestore numeric fields in both directions; old/local and pickup orders remain valid with null coordinates.

Room schema version 7 stores nullable relative product stock deltas in the sync queue. Version 8 adds `products.isActive` so archive is distinct from temporary unavailability; archived products are hidden from active catalog/order queries and synchronized with the existing archive operation.

Room schema version 12 adds station-scoped Cash, COD, GCash, and Maya activation fields. Version 13 adds optional Firebase Storage QR image URLs for GCash and Maya. GCash/Maya require an account name and normalized mobile number before activation. These settings synchronize on the private station document and control checkout choices; wallet orders still require manual payment confirmation.

Version 14 embeds optional promotion fields on products. Version 15 adds the station-scoped `expenses` table. Expense creation, editing, and archival commit with an outbox row in the same Room transaction, so Business/Pro owners can keep recording daily operating costs offline. Expense totals and dashboard net cash use integer centavos and half-open date ranges.

## Local session and tenant data

Preferences DataStore stores onboarding/login/demo state, current owner/station identifiers and active station. Room contains operational/customer data. On logout, the app clears and reseeds the local Room cache when the durable sync queue is empty, and clears the recorded tenant owner; it retains the completed-onboarding flag and does not delete cloud data. Login sync restores the Supabase container movement ledger; billing history is fetched from Supabase when the subscription history screen opens. If pending, failed, in-progress, or quarantined sync operations remain, logout preserves the cache and owner scope for recovery by the original account. Do not store new credentials or plaintext passwords. `OwnerEntity.password` and `RoomAuthRepository` are legacy; production registration stores an empty local password.

## Firebase boundary

`data/firebase/dto/FirestoreDtos.kt` defines owner-side remote shapes; `FirebaseSyncPayloadMapper` maps local rows to DTOs and converts `Double` pesos to integer centavos. `FirebaseRemoteToLocalApplier` performs tolerant remote parsing and resolves dependencies using `sync_metadata`.

Remote resources are nested below business/station except account-scoped subscription/plan data. Full paths and shared order lifecycle are in workspace `SHARED_DOMAIN_AND_CONTRACTS.md`.

Product and station images upload to Firebase Storage under `owner-media/{ownerUid}/stations/{localStationId}/`. The client and Storage rules accept only JPEG, PNG, and WebP images up to 5 MB. Writes and deletes are owner-UID scoped; reads require an authenticated app user. The resulting download URL is stored in the existing `Product.imagePath` or `Station.logoPath` field and then follows the normal Room/outbox/Firestore projection flow.

Owner Supabase requests share `SupabaseClientProvider`, using the Kotlin Supabase modules and Ktor OkHttp engine with a bounded 30-second request timeout. The URL and public key are variant-specific build configuration; service-role credentials are never used by the Android client. The engine must support cancellation without synchronously draining an SSL stream on the UI thread. WorkManager provides operational sync scheduling; Firebase remains a messaging/telemetry dependency.

## Outbox guarantees and limits

- Mutation and queue row should commit in one Room transaction.
- Queue rows have globally unique operation IDs, retry state/error fields, and optional expected/requested status.
- Mapping retains local numeric IDs while remote documents use stable UUID-like strings.
- Cursors are keyed by business, station remote ID and resource type.
- Immediate work uses a unique-work policy; periodic 15-minute fallback work runs with connectivity constraints.
- Remote conflicts are not a general CRDT/merge system. Status preconditions and version/timestamp metadata provide targeted protection.
- Subscription queue writes are not pushed under current authority rules.

## Authentication and rules

Firebase user/business membership gates owner paths. `ensureOwnerAccount` can create the initial owner business/membership during registration. Rules must validate both identity and membership; role text alone is not sufficient.

`AquaHub/firestore.rules` and `AquaHubCustomer/firestore.rules` are synchronized copies of the cross-app ruleset. The two `storage.rules` files are likewise synchronized and included in both Firebase configurations. It permits first-owner bootstrap only when the new business is owned by the authenticated UID, then requires active membership and station/tenant matching for operational writes. Owner registration writes `role = OWNER` and `isActive = true`; the rules retain narrowly scoped compatibility for older owner builds that omitted those two fields.

Paid-plan team accounts are created by the deployed `createStaffAccount` callable and listed by `listStaffAccounts`. The callable re-checks active membership, paid subscription, role, station assignment, and plan-specific active-account limits before creating a phone-alias Firebase Auth user and its `businesses/{businessId}/members/{uid}` document. Supported non-owner roles are `MANAGER`, `STAFF`, and `RIDER`. Membership `stationIds` are Firebase station document IDs; the owner client resolves those IDs from local sync metadata before submitting assignment changes. The member role is authoritative; the owner client stores it locally only to restrict navigation and station pulls. Staff syncs the customer/catalog/container data required for Walk-in Sales, while Rider syncs riders and orders and is filtered to the local rider record matching the account phone. The Delivery Fleet UI only links active Rider team accounts, preventing duplicate phone links. Deleting or changing a Rider team account deactivates the matching fleet Rider after active assignments are reassigned.

When an order's remote `riderId` changes, the deployed `mirrorCustomerOrderStatus` trigger resolves the assigned Rider's phone, finds the matching Rider team account, and sends an FCM assignment notification to that account's registered devices.

Both index files are synchronized. Single-field overrides enable the required collection-group lookups for public station codes and customer ownership fields. `AquaHub/firebase.json` and the canonical deployment configuration in `AquaHubCustomer/firebase.json` explicitly target the named `aquahub` database. Keep both copies byte-identical and rerun the shared rules suite before future deployment.

## External/configuration dependencies

- Firebase configuration and console Auth/App Check/Firestore/FCM state.
- Maps API key and Google Maps/Places configuration.
- Network permission and runtime location/notification permission.
- FileProvider paths for report sharing.

Firestore rules and index configuration deployment are verified as described above. Functions deployment state, production signing and operational monitoring remain `UNKNOWN` from source alone.
