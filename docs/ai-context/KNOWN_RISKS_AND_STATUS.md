# Known Risks and Requirements Status

These are findings, not authorization to fix unrelated behavior. Re-verify source before acting.

## Critical and high risks

| Severity | Finding | Consequence / affected sources |
|---|---|---|
| HIGH | The local guest-ordering flow now links orders to station customers, but its migration, `guest-order` Edge Function, and Owner customer-history update are not deployed. Hosted `CUSTOMER_WEB` rows exist, but their creating path was not confirmed in the latest read-only inventory. | This code will not affect hosted checkout until the matching RPC migration is applied; existing eligible rows are backfilled then, and the Owner app must be released to display all linked payment types. `supabase/migrations/20261010155326_link_guest_web_orders_to_station_customers.sql`, `supabase/functions/guest-order/`, AquaHub Customer Detail. |
| MEDIUM | Customer order creation now validates integer `stockQuantity`, decrements stock transactionally, and cancellation/rejection restores it; Owner offline absolute-stock pushes can still race with remote orders. | Concurrent offline-owner updates can overwrite newer remote inventory. `AquaHubCustomer/functions/src/index.ts`, AquaHub sync. |
| HIGH | Owner Station data does not manage all fields expected by customer/backend: online acceptance, delivery radius, fee, preparation minutes. | Delivery commonly defaults to zero radius/fee and can be rejected unexpectedly. |
| MEDIUM | Customer cancellation now validates the customer mirror identity, then reads the station-side order status inside the transaction; the newest source hardening is pending redeployment after Firebase CLI re-authentication. | Until redeployed, the prior callable remains active. |
| HIGH | Google Play billing is server-backed, and the verification/acknowledgement Function was deployed on 2026-09-16. Eligible first-time paid onboarding now starts the server-backed AquaHub-managed trial without Play checkout; expired trials and trial-ineligible owners launch verified Play Billing, but the dedicated runtime account still requires confirmed Google Cloud `roles/datastore.user` plus App Check IAM, Play Console permissions, RTDN topic wiring, the Play-signed client release, and end-to-end license-tester validation. | Paid entitlement behavior cannot be validated until the remaining external setup is completed. |
| MEDIUM | Owner trial eligibility is now persisted as a hashed phone/recovery-email claim when a trial-used account is deleted. | Re-registration with either same identity is denied a new paid-plan trial; production billing/provider eligibility still requires a separate authoritative integration. |

## Medium risks

- Some owner settings/profile/subscription state uses `owner.stationId` instead of `ActiveStationContext`; station switching can show/edit the wrong station.
- Indefinite nested Flow collectors in owner ViewModels can prevent outer account/station updates from being observed.
- Owner registration phone verification is optional in the data source and not wired into `RegisterScreen`, despite stronger documentation claims.
- Customer onboarding and saved addresses are in-memory; checkout schedule selections are not transmitted.
- Customer station discovery is an unbounded collection-group listener with client-side filtering/distance sorting; no geospatial query/pagination exists.
- Customer Firestore mapping can silently drop malformed orders, and some listener errors do not reach UI state.
- FCM support is incomplete in both apps: permissions, current-token registration, foreground presentation, and deep-link routing are not end-to-end.
- Owner remote intake loses customer-specific fields such as source, UID/profile ID, idempotency key, delivery mode, and cancellation/rejection reasons.
- Owner reporting includes fixed/heuristic analytics and in-memory aggregation; do not treat all metrics as audited business truth.
- Room uses explicit migrations but schema export is disabled; many cross-aggregate relationships are logical rather than foreign-key enforced.
- Owner local currency is `Double`, creating rounding risk at the centavo boundary.
- Customer order IDs use unique document IDs, but the human order-number suffix is millisecond-based and can collide.
- Release signing/flavor state is not established by the repository. The latest billing callable logging/error-handling update was deployed on 2026-09-16; runtime IAM and Play-signed client validation remain external.
- The synchronized Firestore rules and empty custom-index configuration were deployed to the named `aquahub` database in Firebase project `aquahub-506411` on 2026-09-05. Current queries rely only on automatic single-field indexes. Functions, App Check, signing, and monitoring remain independently unverified unless noted elsewhere.

## Lower-level debt

- `RoomAuthRepository` is unused legacy code and contains a plaintext-password pattern if rebound.
- `AquaHubCustomer/aquahub-functions/` is a confusing unused scaffold.
- Reusable UI/theme concepts are duplicated across projects rather than shared (appropriate for independent builds, but changes may drift).
- No deep links are declared. Notification taps cannot currently route reliably to an order.
- No canonical public Station projection exists; customer rules read station documents that also serve owner sync.

## Requirement versus implementation

| Capability | Status | Evidence-based difference |
|---|---|---|
| Separate owner/customer APKs | IMPLEMENTED | Independent builds and package IDs. |
| Firebase customer order loop | IMPLEMENTED; latest hardening pending deploy | Callable creates station order/mirror; trigger mirrors status. The deployed baseline is verified; the newest cancellation guard and synchronized rules hardening require Firebase CLI re-authentication before redeploy. |
| Server-authoritative totals/idempotency | IMPLEMENTED | Functions recalculates catalog prices and records request key. |
| Inventory-safe customer ordering | PARTIAL | Current Functions check integer stock and decrement/restore it, but Owner offline absolute-stock pushes can still race with remote orders; concurrency tests remain. |
| Owner online-order controls | PARTIAL | Required public delivery/acceptance fields are not fully modeled in owner UI/domain. |
| GPS and manual location | IMPLEMENTED | Customer supports Places autocomplete, GPS reverse geocoding and map-pin selection; discovery remains client-side rather than a geospatial query. |
| Saved delivery addresses | PARTIAL | Room persists UID-scoped saved addresses with map points; cart and checkout scheduling remain process-memory, and cloud sync of address history is not implemented. |
| Scheduled booking | MOCK | Date/time UI state is not sent to order creation. |
| Live delivery tracking | MOCK/PARTIAL | Status timeline exists and owner order detail can open the customer's delivery pin for rider reference, but rider identity/live coordinates and a driver app are absent. |
| Owner in-person payment ledger | IMPLEMENTED | Station-activated Cash, delivery-only COD, GCash, and Maya; unpaid orders do not count as collected sales. Assigned riders must upload private proof before a delivered-order receipt is server-confirmed. Business/Pro customer accounts support partial/full receipt history and PDF statements. |
| PayMongo/online payment | PLANNED/NOT IMPLEMENTED | No provider integration found. |
| Notifications | PARTIAL | Token registration, high-priority Owner wake-up, and Customer foreground notifications exist; runtime permission handling and order deep-link routing remain incomplete. |
| Owner offline-first sync | IMPLEMENTED with caveats | Room/outbox/WorkManager/cursors exist; fresh wake-ups use expedited replacement work, repeated account writes were removed, and incremental reads are parallelized; remote arrival is still periodic/notification-triggered rather than live. |
| Subscription billing | PARTIAL | App-managed 30-day trial onboarding, Google Play checkout for payment activation/plan changes, server verification, token ownership binding, acknowledgement retries, Play subscription management handoff, plan replacement parameters, RTDN handler, and explicit Play/Firestore failure logging exist in source; the verification Function is deployed, while runtime IAM, the Play-signed client release, and external Play setup remain to be validated. |
| Station QR ordering | PARTIAL; guest web source implemented locally | Owner can generate a station public code and Customer resolves it through the public projection. The responsive guest web fallback and secure order tracking are implemented in source, but the migration, Edge Function, and static client are not deployed or hosted-verified. |
| Ratings/favorites/reviews | NOT IMPLEMENTED | No customer feature/data path found. |
| Supabase web ordering | IMPLEMENTED LOCALLY; HOSTED STATE PENDING | Guest orders use the existing `CUSTOMER_WEB` order contract and transaction. The additive guest mapping/rate-limit migration and `guest-order` Edge Function await separate deployment approval. See `docs/ai-context/WEB_ORDERING.md`. |

## Prioritized future work (do not bundle into unrelated tasks)

1. Design transactional stock reservation/decrement and cancellation compensation.
2. Define a versioned public Station contract and owner controls/default migration.
3. Add emulator/callable concurrency tests for cancellation, stock, and account deletion.
4. Complete Play Console/service-account setup and grant/confirm the billing runtime account's Firestore/App Check IAM, then validate subscription authority including RTDN delivery and Play-signed license-tester lifecycle cases.
5. Complete scheduling, saved addresses, notifications/deep links, and truthful tracking UI.
6. Add backend concurrency tests, app contract tests, and Room migration/schema export coverage.

## Subscription and inventory verification (2026-09-06)

Owner app gating is PARTIAL overall: local runtime destination/use-case, content/export, and station slot checks are implemented. Newly created stations in multi-branch plans now immediately replicate the business's active subscription in Room, preventing false expiration on new branches. Manage Stations displays slot allocation and locks stations exceeding downgraded limits, preventing active context switching to locked branches. Google Play verification, acknowledgement retries, lifecycle handlers, and explicit backend failure logging are present in source; the verification Function is deployed, while billing runtime IAM, Play-signed client rollout, and lifecycle validation remain outstanding. App-side gating is not a server authorization boundary.

Customer online order stock integrity is PARTIAL: Cloud Functions `createCustomerOrder` checks `stockQuantity >= quantity` and decrements stock; `cancelCustomerOrder` and owner rejection/cancellation via `mirrorCustomerOrderStatus` restore reserved stock. However, offline owner walk-in synchronization uses absolute stock pushes, which requires delta-based reconciliation to avoid race conditions against concurrent remote orders. Water-refilling container inventory tracks available, customer-held, damaged, and lost buckets, but still lacks intermediate sanitizing/cleaning/ready stages, rider custody, and production consumables. The latest local stock/cancellation hardening is not yet deployed because the Firebase CLI session expired.
