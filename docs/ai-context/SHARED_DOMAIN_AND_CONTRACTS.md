# Shared Domain and Cross-App Contracts

## Canonical vocabulary

| Canonical term | Meaning and confirmed aliases |
|---|---|
| Station | A water-refilling business location. UI/requirements may say shop, store, water station, or branch. No separate Branch entity exists. |
| Owner | Firebase user/business member operating one or more stations through AquaHub. |
| Customer | Buyer profile. Station customer rows use Supabase UUIDs and map to numeric Room IDs in AquaHub; authenticated customer-app users use account identities and remote string IDs. Guest web orders can link to a station customer by normalized Philippine mobile number without creating an auth user. |
| Customer acquisition | Ordering requires one linked business/station pair established by a station QR/link/referral. The customer app does not expose station discovery before linking and shows only that exact station afterward. Scanning a different valid QR replaces the link. Functions re-check the pair when creating an order. |
| Product | Station catalog item with product type, a station-saved size label, price, stock, and availability. “Water type” and “container size” are attributes/UI concepts, not separate aggregates. The owner app still writes the existing string field to preserve compatibility. |
| Product promotion | A Business/Pro-only product offer embedded with its product for offline ownership and sync. Supported types are percentage discount, fixed promotional price, and quantity-break percentage. Checkout recalculates the effective unit price from trusted private product data and snapshots promotion/discount fields on the order. |
| Order / booking | A sale containing order items. Customer bookings and walk-in sales share the owner Order model but follow different creation paths. |
| Delivery | Fulfilment represented mainly by order status, address, rider, and timestamps; there is no separate Delivery entity. |
| Inventory movement | Product stock updates plus the separate reusable-container ledger/aggregate in AquaHub. |
| Payment | Customer checkout exposes only station-enabled methods compatible with pickup/delivery; guest web pickup also supports eligible customer-account billing. A receipt is the authority for collected sales and customer spend; unpaid order totals do not count. Owner walk-in delivery orders start pending, and rider-collected delivery payments require a private proof image plus server confirmation before becoming paid. Guest web GCash/Maya submissions create a paid receipt after the customer submits a reference and image; this does not verify provider settlement. Card and provider-side settlement are absent. |
| Expense | A private station operating-cost record owned by the owner app. Amounts are integer centavos; Business/Pro may create, update, and archive them. Expenses are not exposed to customer clients. |
| Subscription / plan | Owner-side feature/limit entitlement model. Remote subscription is intended to be authoritative. |

## Stable enums and value formats

Shared product types are `REFILL`, `NEW_CONTAINER`, `EMPTY_CONTAINER`, and `OTHER`.

Shared order statuses are:

```text
PENDING
ACCEPTED
PREPARING
READY_FOR_PICKUP
RIDER_ASSIGNED
OUT_FOR_DELIVERY
DELIVERED
COMPLETED
REJECTED
CANCELLED
```

Do not rename or reorder contract values without updating both domain models, owner DTO/mappers, Functions validation/trigger logic, customer mapping/UI, existing documents, rules tests, and stored-data compatibility.

Firestore prices, subtotal, fees, totals, and expenses are integer centavos. Customer `Money` and owner expense amounts use `Long`; Functions require integer values. Older AquaHub order/payment fields remain `Double` pesos in Room and round `value * 100` at the sync boundary. Avoid adding new floating-point remote fields.

Promotion percentages use integer basis points (`100` = 1%, maximum `9000` = 90%). Promotional fixed prices use integer centavos. Promotions never stack in the implemented product-level model; quantity breaks apply only when the line quantity reaches the configured threshold. Expired, disabled, malformed, or non-Business/Pro promotions are ignored by server pricing.

Timestamps are epoch milliseconds in owner local/domain objects and Firestore numeric fields in the current mapping. Functions also writes Firestore server timestamps for audit/request metadata. Verify an individual field before changing its representation.

Delivery orders may carry `deliveryPlaceId`, `deliveryLatitude`, and `deliveryLongitude` alongside the human-readable `deliveryAddress`. Coordinates are nullable for backward compatibility, use decimal degrees, and must be written as a complete latitude/longitude pair. The customer callable validates geographic ranges and persists the selected Places/map point; AquaHub stores the point in Room so order detail can open the delivery pin for rider reference. Pickup orders use null delivery coordinates.

Owner-created customer delivery addresses may also carry nullable `latitude` and `longitude` fields. AquaHub's add-customer flow obtains them from the shared Places/map picker, persists them as a complete coordinate pair in `customerAddresses`, and syncs the formatted address and administrative fields with the point.

## Supabase web QR ordering

**IMPLEMENTED in local source; release pending:** Customer Android and web
clients read current checkout availability through the batched public
`get_public_station_ordering_eligibility(uuid[])` RPC. It returns only station
IDs and booleans. Availability requires the station to be open, accepting
orders, entitled to `MOBILE_APP_ORDERING` under its effective time-bounded
plan, and within the plan's `STATION_COUNT` oldest active stations (server
`created_at`, UUID tie-break). Lookup failures close checkout; the browser
checks again before submission. A server order insert trigger applies the same
slot rule to `CUSTOMER_APP` and `CUSTOMER_WEB`, in addition to the existing
feature and quota guards. The migration has not been released to production.

The browser fallback for `/s/{publicStationCode}` is a guest flow and does not
require or create a Supabase Auth user. It reads the public catalog projections,
then submits to the `guest-order` Edge Function. The function uses the existing
`CUSTOMER_WEB` order source and delegates to the same server pricing, promotion,
stock, and order transaction used by the current Supabase contract. Web orders
keep the canonical `PENDING` status and integer-centavo order fields;
`customer_uid` remains null for an unauthenticated guest. The transaction
matches an active Customer at the same station by normalized Philippine mobile
number or creates one when the plan permits, then links `orders.customer_id`
and increments `customers.total_orders` atomically. If customer management is
disabled or its count limit is reached, checkout still commits with guest
contact fields on the order and a null `customer_id`. The migration backfills
eligible historical guest orders. Owner Customer Detail shows all linked
orders, while billing and receivables remain limited to `CLIENT_ACCOUNT` orders.
Web refill items may record `container_source` as `CUSTOMER_OWNED`,
`STATION_EXCHANGE`, or `STATION_BORROW`; the nullable field is displayed in
Owner order details and leaves historical/app-created rows compatible.

Guest access is isolated in a private mapping keyed by operation ID and a hash
of a high-entropy tracking token. Anonymous clients receive no order-table or
private-schema grants. Tracking returns only order number, status, total,
station name, and update time. The Owner app reads the same `orders` and
`order_items` columns; guest inventory movements use null `created_by` rather
than impersonating a profile. Up to ten recent guest tracking capabilities are
kept in the browser tab session, and customers may opt to save contact and
delivery details on that device after an order. The customer-link migration is
local until separately deployed; the Owner app history update also needs an
Android release. See
[`WEB_ORDERING.md`](WEB_ORDERING.md) for the request and release details.

## Firestore ownership model

Core paths used by the code are:

```text
users/{uid}
users/{uid}/devices/{tokenId}
users/{uid}/orders/{orderId}                 customer mirror
businesses/{businessId}
businesses/{businessId}/members/{uid}
businesses/{businessId}/stations/{stationId}
businesses/{businessId}/stations/{stationId}/products/{productId}
businesses/{businessId}/stations/{stationId}/customers/{customerId}
businesses/{businessId}/stations/{stationId}/customerAddresses/{addressId}
businesses/{businessId}/stations/{stationId}/riders/{riderId}
businesses/{businessId}/stations/{stationId}/orders/{orderId}
businesses/{businessId}/stations/{stationId}/containerInventory/{id}
businesses/{businessId}/stations/{stationId}/containerMovements/{id}
businesses/{businessId}/stations/{stationId}/payments/{id}
businesses/{businessId}/stations/{stationId}/expenses/{expenseId}
businesses/{businessId}/subscription/current
ownerTrialClaims/{identityHash}
subscriptionPlans/{planId}
customerOrderRequests/{uid}_{idempotencyKey}
auditEvents/{eventId}
```

Station documents may publish a non-sensitive `publicStationCode`; the Functions-managed `publicStations` projection exposes that code for station ordering links such as `https://aquahub.aesprt.com/s/{code}`. Preferred customer-station fields are written by the validated `setCustomerPreferredStation` callable, not by the customer client.

The same station link supports both customer clients: Android App Links open the installed customer app, while the browser fallback at `aquahub-website/order-app/` resolves the station code and submits guest checkout through the `guest-order` Edge Function and `CUSTOMER_WEB` order RPC. The installed customer app uses Supabase Auth for its `CUSTOMER_APP` flow. Both flows share server-side station, pricing, delivery-fee, inventory, and idempotency checks.

Private station documents also carry owner payment activation (`cashPaymentEnabled`, `codPaymentEnabled`, `gcashPaymentEnabled`, `mayaPaymentEnabled`) and optional GCash/Maya account name, number, and QR image URL fields. A sanitized copy is published for linked-customer checkout. The guest web checkout also reads a boolean-only account-receivable plan eligibility result; it does not expose customer records. These values are payment instructions, not proof of provider settlement.

Customer-order `paymentMethod` is the customer's selected station-enabled method and starts with `paymentStatus=PENDING`. Guest web GCash/Maya orders become paid when the capability-token-protected endpoint records a customer-submitted reference and private image in a full-value `PAID` receipt. The Owner app displays that reference and proof path. The receipt is not a provider-confirmed settlement; the station must compare it with its GCash/Maya account. Only an explicit `CLIENT_ACCOUNT` charge belongs in receivables; an order does not become debt merely because it is delivered, completed, or unpaid. Guest web account billing is pickup-only, requires an active saved station customer matching the entered phone, and is plan-gated by `ACCOUNTS_RECEIVABLE`. For owner walk-in account orders, `orders.payment_method` remains `CLIENT_ACCOUNT` after collection; each receipt stores its actual collection method and is applied only to its own `order_id`. Revenue is derived from paid receipt rows, not order completion.

Confirm exact collection constants in source before adding paths. Customer clients may read approved station/catalog data and their own profile/devices/order mirrors; they must not write station orders directly. Owner access is based on business membership. Functions is the trusted writer for customer order intake and audit records.

Owner trial entitlement is identity-level rather than Firebase-UID-level. The authenticated Owner app checks eligibility after account/station setup and before final plan selection. When a paid trial starts, and again when an owner account with a started or expired trial is deleted, Functions stores a server-only SHA-256 claim keyed from the normalized phone and recovery email in `ownerTrialClaims/{identityHash}`. A later registration using either identity cannot start another paid-plan free trial. The raw identifiers are not stored in the claim document.

Owner-app team membership roles are `OWNER`, `MANAGER`, `STAFF`, and `RIDER`. Each non-owner membership has one or more assigned station IDs; the owner can view and update these assignments from Staff Management, and station-scoped staff pulls use them. Staff accounts can operate Home, Orders, and Walk-in Sales; their Home FAB opens Walk-in POS directly. Rider accounts can access Home and assigned delivery orders, update delivery status, cannot use Walk-in Sales, and do not receive a Home FAB. Owners/managers retain the expanded Home FAB shortcuts. A Delivery Fleet Rider is created only after an active Rider team account exists, linked by normalized phone number. Fleet details remain on the station Rider record. Changing/removing a Rider account deactivates the linked fleet record after active assignments are cleared. Assignment changes trigger an FCM notification to the Rider account's registered devices.

## Customer order lifecycle

```text
Customer checkout
  → createCustomerOrder callable (auth + App Check + validation + server totals)
  → station order + customer mirror + request/audit documents
  → owner WorkManager pull maps remote string IDs into Room numeric IDs
  → owner transition queues an outbox mutation
  → Firestore order update
  → mirrorCustomerOrderStatus trigger updates customer mirror and sends FCM
```

This is implemented in source, and the deployed baseline was verified in Firebase project `aquahub-506411` on 2026-09-11. Staff account callables (`createStaffAccount`, `listStaffAccounts`) were deployed to `asia-southeast1` on 2026-09-12. New owner orders are not delivered through a live listener; their visibility depends on sync scheduling/notification-triggered sync.

## Order state rules

Owner transition validation currently permits:

```text
PENDING → ACCEPTED | REJECTED | CANCELLED
ACCEPTED → PREPARING | CANCELLED
PREPARING → READY_FOR_PICKUP | CANCELLED
READY_FOR_PICKUP → RIDER_ASSIGNED | COMPLETED
RIDER_ASSIGNED → OUT_FOR_DELIVERY
OUT_FOR_DELIVERY → DELIVERED
DELIVERED → COMPLETED
```

Terminal states have no outgoing transitions. Customer cancellation permits only `PENDING` or `ACCEPTED`, creating a deliberate/accidental mismatch with owner-side cancellation from `PREPARING`.

Walk-in creation validates unique product lines, local availability/stock and station/customer ownership, decrements product sale units and updates customer order counts in a Room transaction. Paid pickup sales create a `PAID` receipt row; walk-in delivery orders start `PENDING` and cannot count as collected until a receipt is confirmed. Assigned riders collecting payment on a delivered order must upload a private proof image, and the server validates the assignment, delivered status, proof ownership, and receipt before the app marks it paid. Business/Pro owners may charge a saved client account and later add multiple receipt rows. The aggregate order remains `PENDING` until paid receipt centavos reach the full order total. Station-owned bottle loans and returns are explicit, customer-attributed movements; customer-owned refills do not move station containers. A container sale does not create a customer loan. Customer-created orders are priced on the server but currently do not reserve or decrement stock.

## Station public contract gap

The customer/backend expects station fields including online-order acceptance, delivery radius, delivery fee in centavos, preparation minutes, and the owner-controlled `manualOpenOverride` flag. When that flag is true, `isOpen` is authoritative even outside the configured hours; otherwise the configured schedule applies. The field defaults to false for older documents and is persisted in the owner Room schema (migration 8→9). Keep the public projection, Functions order validation, owner mapper, customer mapper, and defaults aligned across both apps.

## Change impact matrix

| Change | AquaHub | Customer app | Functions | Rules/indexes |
|---|:---:|:---:|:---:|:---:|
| Station identity/public fields | ✓ | ✓ | ✓ | ✓ |
| Product fields/type/price/availability | ✓ | ✓ | ✓ | ✓ |
| Order/order-item schema | ✓ | ✓ | ✓ | ✓ |
| Order status or transition | ✓ | ✓ | ✓ | Maybe |
| Customer/address identity | ✓ | ✓ | ✓ | ✓ |
| Delivery/rider fields | ✓ | ✓ | ✓ | Maybe |
| Payment method/status | ✓ | ✓ | ✓ | Maybe |
| Subscription authority/entitlements | ✓ | Maybe | Maybe | ✓ |
| Owner-only container movement | ✓ | No current reader | Maybe | ✓ |
| Customer cart presentation | No | ✓ | Validate request if shape changes | No |
| Notification payload/deep link | ✓ | ✓ | ✓ | No |

“Maybe” means inspect the target before assuming no effect.

## Compatibility protocol

For a cross-app contract change, define backward-compatible reads/defaults, update writers before relying on new fields, retain old enum values until stored data is migrated, test authorization and idempotency, and verify both apps against the same emulator/deployed schema. Keep the two rules files byte-identical and deploy the tested copy through the canonical `AquaHubCustomer/firebase.json` configuration.
