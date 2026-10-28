# AquaHub Web Ordering Pre-Deployment Checklist

## Release decision

**Production release completed 2026-10-18 by direct owner authorization.** Firebase Hosting site `aquahub-506411` now serves the production ordering build. Supabase project `pfkxrggexueltytxtqil` has the quantity-break percentage fix and subscription-history read policy applied. The release skipped isolated staging because no staging environment was provisioned; the end-to-end checks below remain outstanding and must be completed before treating the entire web-ordering flow as fully validated.

For the read-only environment inventory, isolated staging preparation, exact
CLI commands, gateway-IP test, Owner App test build method, and complete
end-to-end runbook, see
[`AQUAHUB_STAGING_RUNBOOK.md`](AQUAHUB_STAGING_RUNBOOK.md).

The Firebase project recorded in aquahub-website/.firebaserc is `aquahub-506411`, the production site used for this explicitly authorized release. It is not a staging project.

## Architecture and local build

The release path is:

Customer browser → Firebase Hosting static Vite app → Supabase guest-order Edge Function → Supabase PostgreSQL transaction.

Firebase Hosting serves the existing website and the built Vite output under /order/. The Vite base is /order/, the build directory is aquahub-website/order/, and station QR links remain /s/{stationCode}. Firebase rewrites /s/** and /order/s/** to the ordering entry point. Hosting public remains the website root to preserve marketing pages and /.well-known/assetlinks.json; order-app source, environment files, tests, docs, and dependencies are excluded.

Before release, confirm Firebase CLI resolves the intended hosting site, inspect the generated Hosting file list, and verify that source files, local environment files, test fixtures, and credentials are not in the deploy set. The local Hosting emulator serves files directly from its public directory and does not apply the Hosting deploy ignore list; a local emulator request for an ignored source file is not evidence that it will be uploaded. Use a sanitized output tree for UI preview checks and inspect the actual deployment file list before any preview upload. Keep the Android App Links association file available. Verify clean URL behavior and that the root marketing routes remain intact.

The Hosting config sets HTML to no-cache, hashed /order/assets files to one-year immutable caching, local font assets to seven-day caching, and the logo to one-day caching. Confirm actual response headers in a preview channel and use a new content hash whenever a cached asset changes.

## Frontend release checklist — Firebase Hosting

1. Obtain explicit approval for the Firebase Hosting site and environment. Treat frontend deployment as a separate release from Supabase.
2. Build from aquahub-website/order-app with production Vite variables:
   - VITE_SUPABASE_URL for the approved backend.
   - VITE_SUPABASE_PUBLISHABLE_KEY, or the existing anon-key fallback.
   - VITE_GOOGLE_MAPS_BROWSER_KEY for Google Maps JavaScript, Places, and Geocoding.
3. Treat every VITE_ value as public browser configuration. Restrict the Maps browser key by the exact Hosting domain and required APIs. Keep the Google Maps server key and Supabase service-role key out of the Vite environment and build output.
4. Inspect output for correct /order/ asset paths, logo, Plus Jakarta Sans and Material Symbols font files, no missing resources, no source files, and no test or environment data.
5. Start a Firebase Hosting preview channel only after authorization and only for the confirmed frontend staging site. Record the exact preview URL. Add that exact origin to the backend CORS allowlist before browser testing; do not use wildcard preview origins.
6. Verify:
   - /s/{stationCode}, /order/s/{stationCode}, root marketing pages, and /.well-known/assetlinks.json.
   - Hashed assets, logo, WOFF2 fonts, image fallbacks, and the cache headers above.
   - Product and station Storage URLs against the isolated staging backend.
   - Supabase requests and preflight from the preview origin.
   - Browser permission denial, unavailable location, address search, pin selection, invalid address, slow network, and failed map provider behavior.
   - Checkout and tracking at supported phone, tablet, and desktop viewport sizes; keyboard access, focus, reduced motion, inline errors, and field retention after recoverable errors.
7. After the preview acceptance gate, prepare a production Vite build against the production Supabase public endpoint. Publish to Firebase Hosting only after separate production approval.

Do not deploy from the configured default project as an assumed staging target. Do not replace Firebase Hosting or change the Vite/vanilla-JavaScript application architecture.

## Backend release checklist — Supabase

### Database migration

The additive migration is supabase/migrations/20261009112029_guest_web_ordering.sql. Before any hosted application:

1. Confirm the approved staging project ref, Postgres major version, migration history, prerequisites, backup, and recovery owner.
2. Verify the target schema contains the existing app_private schema, digest extension, order identity trigger, canonical order RPCs, station delivery/payment settings, inventory tables, and CUSTOMER_WEB source contract.
3. Apply the full migration chain to an isolated local database matching the approved staging Postgres version. Run the complete SQL test suite, including refund workflow and guest-order tests.
4. Review the migration and grants. Guest tables are private; privileged functions use an empty search_path and qualified names; browser roles receive no direct grants. Authenticated order identity checks and historical orders must remain unchanged.
5. Apply the migration to hosted staging only after explicit approval. Never use a linked migration command until the project ref is explicitly confirmed.

### Edge Function and secrets

The guest-order function is an API endpoint; it does not host the website. It is configured with verify_jwt=false for guest access, validates every request, and uses a server-side Supabase service-role client to call restricted RPCs.

Configure only in the approved isolated backend:

- SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY from the Supabase runtime.
- GOOGLE_MAPS_SERVER_API_KEY, restricted to the required Places API and any required server-side geocoding API.
- GUEST_ORDER_ALLOWED_ORIGINS containing exact approved web origins, including the exact Firebase Hosting preview-channel URL for the test window.

Keep the server key and service-role key in backend secrets only. The Vite browser Maps key is public and must be separately restricted by HTTP referrer and API. Never use a preview wildcard in CORS. Remove temporary preview origins after testing.

Confirm the browser uses the guest-order URL from the approved Supabase project and the publishable/anon key. No direct privileged database write may be added to the website.

### Security, integrity, and staging checks

Before accepting backend staging:

- Verify the Supabase gateway supplies the trusted cf-connecting-ip value and overwrites any client-supplied value. The Edge Function fails closed when the value is absent or malformed outside local development. If gateway provenance cannot be proven, do not count IP limits as an abuse control; add an approved WAF/bot control.
- Confirm exact-origin CORS preflight and rejection of an unapproved Origin.
- Test valid/invalid Philippine phones, station-specific products, current rules version, disabled payment methods, current online-order acceptance, authoritative price/promotion, stock locks, fee/radius, and rollback after a transaction failure.
- Test concurrent distinct operations against one remaining unit and confirm stock cannot oversell.
- Simulate a lost successful response and retry with the same operation ID, canonical payload, and tracking token. Confirm one order and one stock decrement, including recovery when Places lookup is unavailable after commit. Reusing the operation ID with a different payload or token must be rejected.
- Test invalid or mismatched Place IDs, provider timeouts, and a forged browser coordinate. The Edge Function must resolve the selected Place ID server-side and pass provider-derived address and coordinates to the transaction.
- Test wrong/random tracking tokens and rate limits. A valid tracking response must contain only order number, canonical status, total, station name, and update time; it must not expose customer phone, address, notes, or items.
- Confirm no sensitive request bodies, tokens, names, phone numbers, or delivery addresses enter logs.
- Confirm Owner App order queue and details show guest name, phone, address, mode, product snapshots, unit prices, fees, discount, total, payment, PENDING canonical status, and fulfillment fields. Confirm no customer UID is required and Owner updates work.

## Cross-system staging end-to-end plan

This plan requires explicit authorization to use a confirmed isolated Supabase staging backend and a Firebase Hosting preview site. Do not use the configured default Firebase project or any production system by assumption.

1. Prepare disposable station/product fixtures, one-unit inventory race fixtures, and synthetic customer details. Use non-collecting payment methods and do not use real customer data.
2. Apply the reviewed migration to isolated Supabase staging, deploy the Edge Function there with temporary exact CORS origins and approved secrets, then publish the static app to a Firebase Hosting preview channel.
3. Load the preview URL from mobile, tablet, and desktop browsers. Confirm image and font requests, product/category/search behavior, cart calculations, checkout errors, guest submission, and Owner queue/detail visibility.
4. Deny location permission and separately test unavailable device location. Search for a Philippine address, move the map pin, and submit. Confirm address and radius use the provider-verified server result.
5. Test malformed and out-of-radius addresses, disabled payment, stale rules, unavailable product, insufficient inventory, request rollback, lost-response retry, operation-key mismatch, wrong tracking token, and rate limits.
6. Keep preview and staging logs free of personal data and credentials. Remove temporary CORS origins and test fixtures according to the approved retention plan.

## Rollback and monitoring

Frontend rollback: restore the previously accepted Firebase Hosting version. Backend rollback: restore the prior Edge Function version or disable the guest endpoint while keeping Owner App processing available. Preserve the additive schema, guest order mapping, orders, and inventory history; do not drop guest tables or delete guest orders during rollback. Reconcile orders, items, balances, and inventory movements before any manual retry.

Monitor Edge Function latency and 5xx/4xx/429 rates, rate-limit RPC failures, provider lookup errors, guest order counts by station and canonical status, and order/item/inventory movement consistency. Track safe operation identifiers and error classes only. Alert on absent trusted IP values and repeated retries. Assign a responder before public QR traffic is enabled.

## GO / NO-GO criteria

Production is **GO** only after separate explicit approvals for Firebase Hosting release and Supabase migration/function release, and when all of these are true:

- A confirmed isolated staging backend and Firebase Hosting preview site have passed the cross-system checks above.
- The full database suite passes on the migration state that will be released.
- Gateway IP provenance, CORS, secrets, Maps key restrictions, Storage image reads, and rate limits have been verified.
- Duplicate prevention, concurrent inventory behavior, transaction rollback, and protected tracking pass.
- The actual Owner App queue and details accept and update guest orders without a customer UID.
- Responsive checkout, address search/map pin, permission-denial fallback, and accessibility checks pass on supported browsers.
- Monitoring, backup/recovery, rollback owners, and release sequencing are ready.

The owner explicitly authorized the 2026-10-18 direct production release. Firebase Hosting publication and the two Supabase migrations are complete. The staging, Maps-provider, gateway-IP, and Owner-device checks above remain incomplete, so full cross-system acceptance is still **PARTIAL**. No additional release should be inferred from this deployment record.
