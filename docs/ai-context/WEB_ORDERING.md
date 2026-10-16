# AquaHub Web Ordering

## Status

**IMPLEMENTED LOCALLY; HOSTED MIGRATIONS AND EDGE FUNCTION NOT DEPLOYED.** The
browser app is the fallback for the existing `/s/{publicStationCode}` QR route.
It is a separate browser client and does not create a Supabase Auth account.

## Hosting architecture

- Firebase Hosting serves the existing static AquaHub site and the Vite build
  from `aquahub-website/order/`. The Vite base path is `/order/`; Firebase
  rewrites `/s/{stationCode}` and `/order/s/{stationCode}` to that entry point.
- The `aquahub-website/.firebaserc` default project is
  `aquahub-506411`. This is a configured target, not evidence of a staging
  environment. A read-only provider lookup found one site (`aquahub-506411`)
  and only the live channel; no staging site or preview channel was found.
- The Firebase Hosting public directory remains `aquahub-website/`. Its ignore
  patterns exclude `order-app/` source, environment files, tests and docs while
  preserving the built `order/` output and `/.well-known/assetlinks.json`.
- Supabase remains the database, Storage and guest order API. Firebase Hosting
  is not a backend and the static site never writes privileged database rows.
- A read-only Supabase inventory found project `AquahubDB`
  (`pfkxrggexueltytxtqil`) as the only accessible project, no branches, and no
  deployed `guest-order` function. The remote migration history is nine
  versions behind the local migration directory; see
  `docs/production-readiness/AQUAHUB_STAGING_RUNBOOK.md` for the exact pending
  versions. No remote changes were made.

## Request path and Owner compatibility

1. `aquahub-website/order-app/src/main.js` reads `public_stations` and
   `public_products` for the station QR code. Images resolve from the existing
   `station-assets` and `product-images` Storage buckets.
   **IMPLEMENTED in local source; release pending:** It also checks the public
   read-time ordering eligibility RPC before showing checkout and again before
   submission. Missing or failed eligibility closes checkout. The RPC applies
   effective `MOBILE_APP_ORDERING` and oldest-active `STATION_COUNT` slots;
   the order insert trigger enforces the same slot rule for guest and Android
   customer orders. No private plan details are returned to either client.
2. The browser sends order details to `supabase/functions/guest-order` with the
   public client key. It sends product IDs and quantities, not prices, totals,
   fees, stock, or a client-computed delivery eligibility decision. For a
   selected delivery pin, it sends a Google Place ID; browser coordinates are
   not used as the trusted radius point.
3. The Edge Function resolves that Place ID with a server-only Google Places
   key, replaces client address/coordinates with provider details, and calls
   the service-role-only `create_guest_order_with_container_sources` database RPC. That RPC delegates product pricing,
   promotions, stock locks/decrements, fee calculation, item snapshots, and
   order creation to the existing `create_order_internal` transaction.
4. The stored order uses the existing `CUSTOMER_WEB` source, `PENDING` order
   status, `PENDING` payment status, integer-centavo totals, `orders` columns,
   and `order_items` snapshots. `customer_uid` remains null for a guest. In the
   same transaction, the server matches an active customer at that station by
   normalized Philippine mobile number or creates one when the plan permits,
   links `orders.customer_id`, and increments `total_orders`. If customer
   management is disabled or its count limit is reached, checkout still succeeds
   with the guest contact fields on the order and no customer link. The migration
   backfills eligible historical guest orders. Owner Customer Detail shows all
   linked orders; billing and Record Payment remain limited to `CLIENT_ACCOUNT`
   orders. Refill items carry nullable `container_source` values
   (`CUSTOMER_OWNED`, `STATION_EXCHANGE`, or `STATION_BORROW`); legacy callers
   default to station exchange. No new status or order-source value is introduced.
5. Inventory movements for guest sales have a null `created_by`; the additive
   migration makes that actor nullable instead of attributing a guest sale to a
   fabricated profile. Authenticated and Owner app movements retain their
   profile actor.

## Validation and privacy

- No anonymous table grants or RLS policies are added. The guest order mapping
  and rate-limit tables live in the unexposed `app_private` schema, have RLS
  enabled, and have no client grants.
- Public order and tracking RPCs are executable only by `service_role`. The
  Edge Function is configured with `verify_jwt = false` because a guest has no
  user JWT; the handler validates the request and uses only a server-side
  service-role client. The browser never receives that secret.
- Hosted CORS now accepts only origins listed in
  `GUEST_ORDER_ALLOWED_ORIGINS`; localhost defaults are enabled only when the
  Edge Function is running against a local Supabase URL. Staging and production
  must each configure their exact origins independently.
- Guest request IDs are locked and stored uniquely. The same operation ID,
  stable request fingerprint, and tracking token return the original committed
  order. The retry-recovery RPC can return a committed order before another
  Google lookup; a reused ID with a different payload or token is rejected.
- Creation limits are 20 attempts per trusted edge IP and 4 per normalized
  phone number in a 15-minute bucket. Tracking is capped at 120 requests per IP
  in the same bucket. IPv4 peers use a canonical address and IPv6 peers are
  grouped by /64 to limit privacy-address rotation. Fingerprints are HMACs and
  source addresses are not stored in the rate table. The function accepts only
  the platform `cf-connecting-ip` header and fails closed if it is absent or
  malformed outside local development; staging must verify that the gateway
  overwrites client-supplied values.
- Tracking uses a random 256-bit browser capability token. Only its SHA-256
  hash is stored with the guest order mapping. The tracking RPC returns the
  order number, status, total, station name, and update time; it never returns
  the customer's phone, address, note, or item history. The browser keeps the
  current capability and up to ten recent order capabilities in `sessionStorage`,
  not in a URL; starting another order does not discard earlier tracking links.
- After a successful order, the browser can remember contact and delivery
  details in station-scoped `localStorage`. Checkout shows a checked-by-default
  remember option and a clear action; payment choices, notes, and rule acceptance
  are not saved with the profile.
- Philippine numbers are normalized to `+639XXXXXXXXX`. A station's enabled
  payment methods and applicable order rules are displayed and checked again
  on the server. Cash is never invented when disabled.
- Coordinates are hidden from the form. Customers can search a Philippine
  address, choose a map point, or use device location in the Google Maps picker.
  A selected address is read-only in checkout; house/unit and landmark details
  remain separate. The Edge Function verifies the Place ID and replaces the
  browser address and coordinates with server-resolved values before the
  database computes delivery radius. If location permission is denied, search
  or map selection remains available. Stations without a positive radius can
  still accept a typed address when map lookup is unavailable.
- Fees come from the station row. Stock, promotions, product availability,
  payment flags, online acceptance/open status, rule version, and radius are
  checked by the database transaction.

## UI behavior

The storefront follows the AquaHub Customer blue/cyan palette. It has a
responsive product catalog, category filters and search, promotion-aware prices,
desktop cart summary, mobile sticky cart bar, and cart/product bottom sheets.
Supabase image references in `supabase://bucket/object` form resolve to public
Storage URLs; direct URLs and raw object paths remain supported. Refill product
details let the customer choose an exchange, their own container, or a borrowed
station container. Standalone `NEW_CONTAINER` products remain separate catalog
items. Tracking shows pickup/delivery milestones and animates its check marker
only when the server reports a new status; reduced-motion preferences are
respected. Checkout retains fields after recoverable errors and uses keyboard
labels, visible focus states, and live status messages.

Branding follows the AquaHub Owner App:

- The web logo is an unchanged copy of `AquaHub/app/src/main/res/drawable/aquahub_new_logo.png`,
  used in the header, error empty state, favicon, and Apple touch icon.
- The Owner App typography is Plus Jakarta Sans. The website serves locally
  hosted variable WOFF2 subsets under `aquahub-website/order-app/public/fonts/`
  with the upstream SIL Open Font License included.
- Icons use a local subset of Material Symbols Rounded; the Apache 2.0 license
  is included beside the font.
- Owner App colors are reflected in the website's primary blue `#1264E6`, cyan
  `#16C7E8`, navy `#073B8C`, and light canvas `#F5FAFF`.

## Local verification

From `aquahub-website/order-app/`:

```bash
npm test
npm run build -- --outDir /tmp/aquahub-web-order-build --emptyOutDir
```

Against an isolated local Supabase database with all workspace migrations:

```bash
supabase test db --local
```

`supabase/tests/guest_web_ordering.sql` creates a rollback-only organization,
station, subscription, product, and inventory fixture. It checks Owner order
compatibility, customer linking/reuse and idempotent counts, historical-order
backfill, station isolation, checkout when customer limits or entitlements
block profile creation, refill container choices, centavo promotion/fee
calculations, idempotent retries and payload mismatch, payment/rules/radius
validation, rate limiting, protected tracking, unauthorized reads, and rollback
after a forced inventory-movement failure. A separate disposable two-session test
confirmed that two distinct orders competing for one remaining unit create
exactly one order and one inventory movement.

The refund workflow test now runs under the matching fixture customer JWT. It
was failing before the guest-order migration because its multi-row fixture
inserted a `CUSTOMER_APP` order without setting `request.jwt.claim.sub`; the
existing identity trigger correctly rejected it. No identity check was
weakened.

An earlier guest-ordering local verification run passed all 11 database test files (81 pgTAP
assertions), all seven web unit tests, all five guest function helper tests,
and Deno type checking for the Edge Function. Database lint reported one
existing warning: the legacy import RPC has an unused payment-status
parameter. The Vite production build completed into the Firebase Hosting
output directory. A separate sanitized Hosting-emulator fixture used a
synthetic local API to inspect station routing, logo and font requests,
promotion totals, missing product-image fallbacks, mobile/tablet/desktop
catalog and checkout layouts, guest checkout validation, preserved form
values, and the tracking screen. It did not call a hosted project or create a
real order.

The root Hosting emulator serves every file from its configured public
directory, including paths listed in Hosting deploy ignores; this was
confirmed by requesting ignored source files locally. Firebase's ignore list
is a deploy filter, so that emulator result is not a deployment file list. A
sanitized fixture was used for UI preview checks. Inspect the Firebase CLI
deployment file list before any authorized preview or production publish.

Local tests do not verify hosted Edge IP header provenance, deployed Supabase secrets,
Storage access, Firebase Hosting preview channels, Android sync arrival, or an
actual Owner device's presentation. Local production builds have a browser Maps
key configured, but the hosted bundle, allowed referrers, enabled Maps/Places
APIs, billing, and the Edge Function's server-only Places key are not verified.
The picker reports common key, API, billing, quota, and network failures.
The local checkout fixture intentionally omitted the browser Maps key and
verified its missing-provider fallback. Real browser permission denial, Place
Autocomplete, map pin movement, and
provider-backed address resolution require staging verification with approved
keys. Address details appended by the customer remain delivery instructions;
the provider-verified base address and coordinates are what the database uses
for radius checks.

The ordering-eligibility follow-up passed a clean local migration replay and
all 17 database test files (149 pgTAP assertions), eight web unit tests, and
Customer debug unit tests plus `assembleDebug`. These checks do not establish
a hosted release.

## Release state

The local guest-ordering path is not deployed: the latest read-only inventory
did not find a hosted `guest-order` Edge Function. Hosted `CUSTOMER_WEB` rows
were present, but their creating client/function was not confirmed. The new
customer-link migration
`supabase/migrations/20261010155326_link_guest_web_orders_to_station_customers.sql`
and the Owner customer-history changes remain local source only. Deploy the
database migration and publish the Owner app as separate release operations.
Before enabling the local guest flow, reconcile the deployed web-order path
because the function and browser client are also not deployed. See
`docs/production-readiness/WEB_ORDERING_PREDEPLOYMENT.md` for separate frontend
and backend checklists, preview-channel CORS setup, key restrictions, recovery,
monitoring, and release criteria. Current recommendation: NO-GO until the
location provider, trusted IP behavior, and actual Owner workflow pass against
an approved isolated staging backend.
