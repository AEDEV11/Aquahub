# Firebase audit

Status: `HISTORICAL AUDIT — SUPERSEDED BY FINAL SOURCE AUDIT`.

Current source truth (2026-10-04): Firebase is retained only for FCM transport,
Analytics, and Crashlytics. Supabase Auth, PostgreSQL/RPCs, Storage, and Edge
Functions are authoritative for application business behavior. The Firebase
Auth, Firestore, Firebase Storage, Firebase Functions, and App Check SDK/runtime
surfaces described below are historical findings and are no longer active in
the Android applications or backend source. Historical Firebase data and
configuration were not deleted.

Scope inspected: `AquaHub/`, `AquaHubCustomer/`, `AquaHubCustomer/functions/`, both synchronized Firestore rules/index files, both synchronized Storage rules files, Gradle catalogs/build files, manifests, application initializers, Room entities/DAOs, Firebase DTOs/data sources, messaging services, WorkManager sync, and tests.

## Service inventory

| Service | Evidence | Classification | Migration note |
|---|---|---|---|
| Firebase Authentication | Both apps use `FirebaseAuth`; owner supports email/password, Google, phone-alias staff accounts, verification/reset/reauthentication. | MIGRATE | Supabase Auth is a future application cutover; Firebase users remain during transition. |
| Cloud Firestore | Owner Room sync, customer catalog/profile/order mirrors, Functions transactions/triggers. | MIGRATE | Preserve Firestore until module-by-module validation and rollback window close. |
| Firebase Cloud Functions | `AquaHubCustomer/functions/src/index.ts`, callable and document/pubsub triggers. | MIGRATE | Map each function in `04-functions-migration.md`; do not delete deployed functions in this phase. |
| Firebase Storage | Owner product/station/payment QR media under `owner-media/{ownerUid}/stations/...`. | REVIEW | Supabase Storage is proposed only for media attached to migrated modules. Keep Firebase Storage operational for now. |
| Firebase Cloud Messaging | `AquaHubMessagingService`, `CustomerMessagingService`, Functions fan-out through device subcollections. | KEEP | FCM remains the push transport. Device token storage may later move to Supabase. |
| Firebase Analytics | Owner Gradle dependency and Firebase initialization. | KEEP | Do not remove dependency or initialization. |
| Firebase Crashlytics | No direct current source hit found in the inspected app code/build catalogs. | KEEP | Required by migration brief, but current repository usage is `UNKNOWN`; verify console/build configuration before cleanup. |
| Firebase Performance | No direct current source hit found. | KEEP | Preserve if later confirmed in generated/console configuration. |
| Firebase App Check | Both apps configure debug/Play Integrity App Check; Functions require App Check for sensitive callables. | KEEP DURING TRANSITION | Keep while Firebase callables remain. |
| Firebase Remote Config | No source/config usage found. | REVIEW | No migration work proposed. |

## Firestore paths and structures

### Tenant and account paths

| Path | Shape/role | Future PostgreSQL target |
|---|---|---|
| `users/{uid}` | Firebase profile, customer setup, preferred business/station, role and status fields. | `profiles` |
| `users/{uid}/devices/{tokenId}` | FCM token, platform/device metadata and timestamps. | `device_tokens` |
| `users/{uid}/orders/{orderId}` | Customer-readable order mirror written by Functions. | `customer_order_mirrors` or a restricted `orders` projection |
| `businesses/{businessId}` | Business tenant with `ownerUid`, active state and subscription context. | `organizations` |
| `businesses/{businessId}/members/{uid}` | Membership role, active state and assigned station IDs. | `organization_members` + `member_stations` |
| `businesses/{businessId}/subscription/current` | Server-authoritative current subscription. | `subscriptions` |
| `subscriptionPlans/{planId}` | Plan catalog read by authenticated clients. | `subscription_plans` |

### Station-scoped paths

Under `businesses/{businessId}/stations/{stationId}` the implementation uses:

`stations`, `customers`, `customerAddresses`, `products`, `riders`, `orders`, `containerInventory`, `containerMovements`, `payments`, and `expenses`.

The owner DTOs preserve stable remote IDs and include `clientUpdatedAt`, nullable `deletedAt`, and logical `isActive`/deletion state. Orders embed item snapshots rather than relying on a live product row. Prices and financial totals are integer centavos at the Firebase boundary; older Room `Double` pesos are converted at that boundary.

### Public and server-only paths

| Path | Access/behavior | Future target |
|---|---|---|
| `businesses/{businessId}/publicStations/{stationId}` and `/products/{productId}` | Functions-maintained sanitized customer catalog projection. | `public_stations` / `public_products` views or tables with controlled grants. |
| `customerOrderRequests/{uid}_{idempotencyKey}` | Idempotency and prior callable result; client cannot write. | `customer_order_requests`. |
| `auditEvents/{eventId}` | Functions-only audit records. | `audit_events`, server-only write path. |
| `ownerTrialClaims/{identityHash}` | Server-only identity-level trial reuse prevention. | `owner_trial_claims`. |
| `googlePlaySubscriptionTokens/{tokenHash}` | Server-only billing token correlation. | `google_play_subscription_tokens`. |
| `googlePlayBillingEvents/{messageId}` | Server-only idempotent Pub/Sub event record. | `google_play_billing_events`. |
| `.../inventoryCompensations/{orderId}` | Server-only stock compensation marker. | `inventory_compensations`. |

## Authentication providers and account behavior

- Owner and customer apps use Firebase email/password and Google sign-in. Owner staff accounts are provisioned by callable Functions using phone-alias email credentials; phone is also used for rider/member matching.
- Existing onboarding creates/updates a `users/{uid}` profile and, for owners, a business and owner membership. Customer profile fields include display name, email, phone, address, setup completion, email verification state, acquisition source/time, and preferred station.
- Session restoration is handled by Firebase SDK. FCM registration is tied to the Firebase UID and must not be broken during auth migration.
- Password values must not be copied from Room legacy fields. Existing Firebase user transition requires an explicit export/import or reset-link strategy; see `03-auth-migration.md`.

## Realtime, transactions, batches, and offline behavior

- Owner does not use broad Firestore snapshot listeners for operational data; Room is the UI source of truth. WorkManager performs initial/incremental pulls and outbox pushes using cursors and sync metadata.
- Customer catalog and order mirror repositories use Firestore listeners/cache behavior. Functions use Firestore transactions for customer order creation/cancellation, stock decrement/restore, idempotency, and status mirroring; batches are used for account deletion and other multi-document cleanup.
- Room provides durable offline behavior for owner operational data through `sync_queue`, `sync_metadata`, `sync_cursors`, connectivity-constrained WorkManager, retries, and status preconditions. The customer app has Room profile/address cache, but cart/onboarding/checkout scheduling remain in-memory.
- Supabase must not be treated as a drop-in offline replacement. The Room/outbox boundary is retained in the proposed architecture.

## Rules, indexes, and Storage

- `AquaHub/firestore.rules` and `AquaHubCustomer/firestore.rules` are byte-identical (same SHA-256). They enforce business membership, assigned-station scope, role limits, server-only customer order writes, profile privilege protection, subscription authority, and validation for product promotions, payments, expenses, and order transitions.
- The two `firestore.indexes.json` files are byte-identical. They define collection-group/single-field overrides for public station codes, order ownership, and customer ownership; no composite indexes are currently declared.
- `AquaHub/storage.rules` and `AquaHubCustomer/storage.rules` are byte-identical. Firebase Storage accepts authenticated owner-UID-scoped JPEG/PNG/WebP files up to 5 MiB in product/station/payment-QR categories.

## Cloud Functions

The active package is `AquaHubCustomer/functions/`; `aquahub-functions/` is an unused scaffold. Exported functions are listed in `04-functions-migration.md`. Functions are the trusted writer for customer order intake, cancellation, mirrors, public projections, staff account lifecycle, subscriptions/Play billing, account deletion, notifications, and trial claims.

## Risks and unknowns

1. Production Firebase export counts, Firebase Auth provider counts, password-hash export capability, deployed Functions versions, deployed rules/index versions, App Check/Play Integrity configuration, and Crashlytics/Performance console state are not derivable from source and remain `UNKNOWN`.
2. Firestore uses string document IDs while Room uses auto-generated `Long` IDs plus `sync_metadata`; the proposed schema therefore keeps `firebase_id` columns and does not assume Firebase IDs are UUIDs.
3. The current source has owner monetary fields represented as `Double` in Room but integer centavos remotely. New PostgreSQL fields use `numeric(12,2)` only where importing legacy peso values is unavoidable; migrated operational money uses integer-centavo `bigint` columns.
4. Public station/product projections are derived data, not independent business records. They should be replaced by secured views or server-maintained projections only after customer query behavior is tested.
5. Firebase Analytics, FCM, and Crashlytics/Performance must remain available after Supabase is introduced.
