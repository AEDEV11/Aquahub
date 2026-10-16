# AquaHub Web Ordering staging runbook

**Status: staging is not provisioned; no hosted changes were made.** This runbook
separates Firebase Hosting preview work from Supabase database and Edge Function
work. Every command that changes a hosted resource is marked for execution only
after approval for that target and release operation. The current deployment
choice is local disposable testing followed by direct-to-production only after
an exact preflight; hosted staging is intentionally not selected. The earlier
environment labels below are historical leads and do not establish the mobile
apps' production-of-record (see `RELEASE_ENVIRONMENT_VERIFICATION.md`).

## 1. Environment inventory (2026-10-09)

| Service | Observed configuration | Assessment |
|---|---|---|
| Firebase project | `aquahub-506411` (`AquaHub`); both Firebase alias files use it as `default`. The read-only project list returned this as the only accessible AquaHub project. | Production project per workspace deployment documentation; do not use it as staging. |
| Firebase Hosting site | `aquahub-506411`, default URL `https://aquahub-506411.web.app`; the site ID is the project ID. The read-only site list returned one site. | Current live site is configured here. The CLI site lookup did not report custom-domain bindings; `aquahub.aesprt.com` is the documented customer URL, but its Firebase domain association was not independently confirmed. |
| Firebase Hosting channels | The site channel listing returned `live` only. | No existing preview channel was found. A preview channel in a production Firebase project is still a hosted resource and is not an isolated Firebase project. |
| Supabase project | `AquahubDB`, ref `pfkxrggexueltytxtqil`, region `ap-southeast-2`, status `ACTIVE_HEALTHY`. The project list, local CLI link, local properties and locally available signed AABs point to this ref. | Current-build candidate only. Play-served artifact/backend association is NOT VERIFIED; do not use it for mutation or test records until the release target is established. |
| Supabase branches | Read-only branch list returned no branches. | No existing branch-based staging environment was found. Prefer a new empty staging project; do not branch or clone production data containing customer/order records. |
| Supabase database | Hosted project reports PostgreSQL `17.11.0.002`; local `supabase/config.toml` requests major version 17. | Use PostgreSQL 17 for staging, then confirm extensions and migration compatibility. |
| Migration history | Local tree has 54 migrations through `20261009135735`; the linked project's read-only migration list has 45 through `20261009043510`. No remote-only versions were reported. | Nine local migrations are absent from linked history; the linked project's production role is NOT VERIFIED. The full local chain replays in a disposable database, but do not apply it to the linked project without per-migration reconciliation. |
| Deployed Edge Functions | Latest read-only list: `delete-account` v1, `dispatch-notification-outbox` v3, `play-rtdn` v4, `send-fcm-notification` v5, `send-sms` v8, `staff-admin` v5, `subscription-trial` v2 and `verify-play-purchase` v3. | Local `guest-order` and `reconcile-play-subscriptions` are not present in that observed deployment list; source revision match is NOT VERIFIED. |
| Website endpoint | Browser source reads `VITE_SUPABASE_URL` plus a publishable/anon key and derives `https://<project-ref>.supabase.co/functions/v1/guest-order` from the URL. | No Vite `.env` files were found. The root `.env.validation.local` has `SUPABASE_URL`, publishable-key, and secret-key variables plus other service credentials; values were not printed or copied. It points at the configured production ref and must not be copied into a web or staging build. |
| Storage | Web source uses public URLs from `station-assets` and `product-images`; Owner source also references `payment-assets`. | Bucket names are confirmed in source. Existence, public-read policy, and object access on a future staging project remain unverified. |
| Maps | Browser code expects `VITE_GOOGLE_MAPS_BROWSER_KEY`; the Edge Function reads `GOOGLE_MAPS_SERVER_API_KEY`. | No local web Maps keys were found. Use distinct keys for development, staging, and production, with different restrictions. |
| Owner App | `AquaHub/app/build.gradle.kts` reads `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and `MAPS_API_KEY` from its local properties/environment. The checked-in app ID is `com.aesprt.aquahub`; Google Services config is for `aquahub-506411`. | No staging flavor exists. A separate copy and dedicated emulator with staging Supabase values and a staging Google Services file are required; never install a staging-configured build over the production app. |

The production-only migration gap after `20261009043510` is:

```text
20261009030621_owner_sync_cancellation_notifications.sql
20261009051935_walkin_delivery_payment_proof_guards.sql
20261009060109_preserve_client_account_payment_method.sql
20261009080803_restrict_order_payment_table_writes.sql
20261009093931_group1_backend_security_payment_entitlements.sql
20261009104237_group2_refunds_and_order_integrity.sql
20261009112029_guest_web_ordering.sql
20261009124434_group3_subscription_security_and_catalog.sql
20261009135735_group4_refund_obligation_report.sql
```

Do not cherry-pick only the guest migration onto a database that lacks its
predecessors. The current clean replay was performed on a fresh local disposable
project. It is not evidence that any migration is safe to apply over the linked
schema or production data.

## 2. Local configuration now prepared

- `aquahub-website/firebase.staging.json` mirrors the current Hosting paths,
  headers, ignore patterns, and `/s/**` routes, with a named `staging` target.
  `firebase.json` and the staging config both exclude `firebase.staging.json`
  from public files. `.firebaserc` remains unchanged and still defaults to
  production; commands must pass the explicit staging project ID.
- Vite templates are present at
  `aquahub-website/order-app/.env.development.example`,
  `.env.staging.example`, and `.env.production.example`. Actual `.env.*` files
  are ignored by Git and Hosting. `npm run build:staging` loads Vite's staging
  mode and refuses missing/placeholder configuration or the production
  Supabase project ref. It writes to `order/` relative to the site copy. The
  staging procedure builds in a disposable copy so production build artifacts
  in the active website directory are not overwritten.
- `supabase/functions/.env.development.example` and
  `.env.staging.example` list only user-managed Maps server keys and origin
  allowlists. Development localhost origins are explicit; hosted staging must
  use its exact preview origin. Production values belong only in its remote
  secret store. Supabase supplies `SUPABASE_URL` and
  `SUPABASE_SERVICE_ROLE_KEY` to the Edge Function runtime; never place either
  in the browser or a committed template.
- Guest-function CORS now has no implicit production origins. Hosted runtimes
  allow only exact origins from `GUEST_ORDER_ALLOWED_ORIGINS`; localhost
  defaults apply only when `SUPABASE_URL` is a local Supabase URL. This keeps a
  staging deployment from inheriting production and localhost origins. CORS
  is a browser policy, not API authentication.
- No staging project IDs, target mappings, secrets, or Firebase aliases were
  invented or added. The staging Firebase Hosting target mapping is created
  locally only after the exact approved site ID is known.

Firebase Hosting's public directory remains the website root, Vite base remains
`/order/`, and `/s/{stationCode}` plus `/order/s/{stationCode}` continue to
rewrite to `/order/index.html`. Keep the `.well-known/assetlinks.json` file in
the deploy set. Firebase Hosting ignore rules filter deployment files; the
local emulator does not prove the uploaded file set. Firebase CLI dry-run is
available, but its own help warns it may enable APIs on the target project.

## 3. Manual staging setup prerequisites

Complete these tasks in the provider consoles or approved tooling. None has
been performed in this preparation:

1. Create an empty Supabase staging project, separate from `AquahubDB`, with
   PostgreSQL 17. Use a distinct project ref and synthetic records only. Do
   not import production orders, customers, auth users, or inventory.
2. Create a separate Firebase staging project and Hosting site, or obtain an
   explicitly approved staging site. A preview channel on the production
   project does not isolate Firebase configuration. Register the staging
   Owner Android app and download its staging `google-services.json`.
3. Create a disposable staging station and public station code, products,
   promotion, enabled/disabled payment settings, delivery fee/radius, and
   synthetic inventory. Disable real SMS/push dispatch and any real payment
   collection. Add synthetic station/product Storage images and verify the
   two web buckets are public-read only where intended.
4. Create a restricted browser Maps key for the exact Firebase preview
   hostname and required Maps JavaScript, Places, and Geocoding APIs. Create a
   separate server key restricted to the required Places API. Never reuse a
   production key or put the server key in Vite.
5. After an approved preview channel is created, record the exact URL returned
   by Firebase CLI. Add that exact origin (scheme and host, no path or
   wildcard) to the staging `GUEST_ORDER_ALLOWED_ORIGINS` value. Do not allow
   the production site or localhost in hosted staging CORS.
6. Store the staging server key and CORS value in the staging Supabase secret
   store. Confirm the runtime has its injected `SUPABASE_SERVICE_ROLE_KEY`.
   The service-role key must never be copied to a browser, Android build, or
   source file.

Local development uses `supabase/functions/.env.development.local` with the
local Supabase stack and localhost origins. Production has no committed env
file; configure its separate exact Firebase production origins and Google
server key in the production Supabase secret store only after backend release
approval.

Firebase preview URLs are publicly reachable by anyone who knows the URL and
use the configured backend. Point the staging Vite build only at staging
Supabase before uploading preview content. [Firebase preview channel guide](https://firebase.google.com/docs/hosting/test-preview-deploy)

## 4. Staging commands (do not run until the relevant staging target and
approval are confirmed)

Run these commands from the `WaterStationApp` workspace root unless a command
uses a subshell to enter another directory.

### Frontend — Firebase Hosting

```bash
export AQUAHUB_WORKSPACE_ROOT="$PWD"
export FIREBASE_STAGING_PROJECT_ID='approved-staging-project-id'
export FIREBASE_STAGING_SITE_ID='approved-staging-site-id'
export FIREBASE_STAGING_CHANNEL_ID='web-ordering-qa'
export WEB_STAGING_DIR="$(mktemp -d /tmp/aquahub-web-ordering-staging.XXXXXX)"

# Copy source/config only; omit production local aliases, build output,
# dependencies, and any local Vite env files.
rsync -a --exclude '/.firebaserc' --exclude '/.firebase/' --exclude '/.git/' \
  --exclude '/order/' --exclude '/order-app/node_modules/' \
  --exclude '/order-app/.env*' \
  "$AQUAHUB_WORKSPACE_ROOT/aquahub-website/" "$WEB_STAGING_DIR/"

# Local target mapping in the disposable copy; no hosted resource is created.
(cd "$WEB_STAGING_DIR" && \
  firebase target:apply hosting staging "$FIREBASE_STAGING_SITE_ID" \
    --project "$FIREBASE_STAGING_PROJECT_ID")

# Hosted preview-channel creation; requires frontend approval. Capture the
# exact URL printed by Firebase CLI before configuring CORS or Maps referrers.
(cd "$WEB_STAGING_DIR" && \
  firebase hosting:channel:create "$FIREBASE_STAGING_CHANNEL_ID" \
    --site "$FIREBASE_STAGING_SITE_ID" --project "$FIREBASE_STAGING_PROJECT_ID" \
    --expires 7d)
```

The `firebase target:apply` command edits only the `.firebaserc` in the
disposable copy. Channel creation and deployment create/update hosted preview
resources and are not authorized by this runbook. The dry-run is not guaranteed
to be side-effect free because Firebase CLI may enable APIs. Never omit
`--project` on a Firebase command. [Firebase deploy targets](https://firebase.google.com/docs/hosting/multisites)

After the exact channel URL is known and backend staging is approved and ready,
build and upload the UI preview:

```bash
# Copy the inert template in the disposable site and fill it with the staging
# endpoint/key plus the restricted browser Maps key. Do not edit the source
# checkout's local files.
cp "$WEB_STAGING_DIR/order-app/.env.staging.example" \
  "$WEB_STAGING_DIR/order-app/.env.staging.local"
(cd "$WEB_STAGING_DIR/order-app" && npm ci && npm test && npm run build:staging)

# Inspect static output and configuration before any upload. The dry-run can
# enable Firebase APIs, so use it only against the approved staging project.
(cd "$WEB_STAGING_DIR" && \
  firebase deploy --config firebase.staging.json \
    --project "$FIREBASE_STAGING_PROJECT_ID" \
    --only hosting:staging --dry-run)

# Hosted content upload; requires separate frontend approval.
(cd "$WEB_STAGING_DIR" && \
  firebase hosting:channel:deploy "$FIREBASE_STAGING_CHANNEL_ID" \
    --project "$FIREBASE_STAGING_PROJECT_ID" --config firebase.staging.json \
    --only staging --expires 7d)
```

Before upload, verify the file list contains only the website, built `order/`
assets and `.well-known/assetlinks.json` in `$WEB_STAGING_DIR`; it must not contain `order-app/`, any
`.env*`, the staging Firebase config, tests, docs, `.git`, dependencies, or
credential files. Check the exact preview URL and response headers after
deployment, then confirm logo, WOFF2 fonts, hashed assets, station route refresh,
and root marketing routes. The production Hosting project and live channel
remain unchanged.

### Backend — isolated Supabase staging

```bash
export AQUAHUB_WORKSPACE_ROOT="${AQUAHUB_WORKSPACE_ROOT:-$PWD}"
export SUPABASE_STAGING_PROJECT_REF='approved-staging-project-ref'

# Read-only comparison; inspect all local/remote differences first.
supabase migration list --project-ref "$SUPABASE_STAGING_PROJECT_REF" \
  --workdir "$AQUAHUB_WORKSPACE_ROOT"

# Preview pending migrations. This does not apply them.
supabase db push --project-ref "$SUPABASE_STAGING_PROJECT_REF" \
  --workdir "$AQUAHUB_WORKSPACE_ROOT" --dry-run

# After separate backend approval, apply the reviewed migration chain.
supabase db push --project-ref "$SUPABASE_STAGING_PROJECT_REF" \
  --workdir "$AQUAHUB_WORKSPACE_ROOT"

# Copy the inert template to supabase/functions/.env.staging.local and fill it.
cp "$AQUAHUB_WORKSPACE_ROOT/supabase/functions/.env.staging.example" \
  "$AQUAHUB_WORKSPACE_ROOT/supabase/functions/.env.staging.local"

# After backend approval, send only user-managed secrets to staging.
supabase secrets set --project-ref "$SUPABASE_STAGING_PROJECT_REF" \
  --workdir "$AQUAHUB_WORKSPACE_ROOT" \
  --env-file "$AQUAHUB_WORKSPACE_ROOT/supabase/functions/.env.staging.local"

# Deploy this function only; do not use --prune or deploy all functions.
supabase functions deploy guest-order \
  --project-ref "$SUPABASE_STAGING_PROJECT_REF" \
  --workdir "$AQUAHUB_WORKSPACE_ROOT" \
  --no-verify-jwt
```

Never use `--linked` here: the current CLI link is production. Keep
`--project-ref` explicit on every remote Supabase command. `--no-verify-jwt` is
needed because guests have no user JWT; request validation, exact CORS, trusted
IP limits, capability tokens, and restricted database RPC grants remain
mandatory.

## 5. Owner App staging build without touching the installed production app

The Owner app currently has no staging flavor and reads Supabase values from
`AquaHub/local.properties` or the process environment. Do not change that file
in the active checkout and do not install a stage-configured APK on the
production phone. Use a disposable source copy and a dedicated emulator:

1. Create a disposable source copy outside the active checkout. Exclude
   production local configuration, Firebase app config, VCS metadata, and build
   outputs:

   ```bash
   export OWNER_STAGING_DIR="$(mktemp -d /tmp/aquahub-owner-staging.XXXXXX)"
   rsync -a --exclude '.git/' --exclude '.gradle/' --exclude 'build/' \
     --exclude 'app/build/' --exclude 'local.properties' \
     --exclude 'app/google-services.json' AquaHub/ "$OWNER_STAGING_DIR/"
   ```

2. Put the staging project's `google-services.json` in that copy and create a
   local properties file there containing only staging `SUPABASE_URL`,
   `SUPABASE_ANON_KEY`, and restricted `MAPS_API_KEY` values. The Firebase
   staging project must register the current app ID. These client keys are
   scoped to staging; no service-role value belongs in the file.
3. Build with the copied wrapper and install only on a dedicated disposable
   emulator with no production Owner app data:

   ```bash
   cd "$OWNER_STAGING_DIR"
   ./gradlew :app:assembleDebug
   adb -s "$STAGING_EMULATOR_ID" install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   The package ID is currently the same as production, so installing on any
   device that already has the Owner app would replace it. Keep the normal
   installed app and its configuration untouched.
4. Sign into a synthetic staging account, select the staging station, and
   verify the complete order lifecycle in section 6. Capture only synthetic
   data in screenshots/logs.

If a dedicated staging Firebase app/config cannot be provisioned, stop before
installing the test build. No Android source files or production app settings
were changed as part of this preparation.

## 6. End-to-end test procedure

Use only the exact Hosting preview URL, staging Supabase project, disposable
station/products, and synthetic customer data. Do not use a real payment method
or production station code.

1. **Public station route:** open `/s/{stagingStationCode}` in a fresh private
   browser session and refresh it directly. It must return the storefront and
   load only the staging Supabase URL. Repeat `/order/s/{stagingStationCode}`.
2. **Storage images:** verify station and product images from staging
   `station-assets` and `product-images` return success. Confirm missing or
   invalid objects show the fallback and no production bucket URL is used.
3. **Places search:** search for a controlled Philippine test address. Choose
   a result and verify the UI shows the selected address. Confirm the request
   sends its Place ID and the Edge Function derives the address/coordinates
   server-side. Test denied/unavailable geolocation separately.
4. **Map pin:** move the pin and confirm the selected point/address changes.
   Verify server-resolved coordinates, not browser-supplied coordinates, are
   used for the radius check. Test invalid Place ID/provider timeout.
5. **Radius and fee:** submit a point inside the configured radius and one
   outside it. Verify the fee comes from the staging station, equals the
   server-calculated centavo amount, and an out-of-radius order creates no
   order or inventory movement.
6. **Guest checkout:** complete order in a private session with no login or
   registration. Confirm the order has null `customer_uid`, guest name/phone,
   delivery address/coordinates, canonical `CUSTOMER_WEB` source, and `PENDING`
   status and payment status.
7. **Payments:** test each enabled method, then a disabled method. The disabled
   method must be rejected by the server. Confirm no silent Cash fallback.
8. **Promotions:** compare the product display price with the stored order and
   item snapshot. Tamper with client price/total fields; the server must ignore
   them and recompute current promotion pricing.
9. **Inventory atomicity:** after a successful order, verify one deduction per
   item and exactly one `ORDER_DEDUCTION` inventory movement. Cause a
   transaction failure in a disposable fixture and verify order, item, and
   inventory changes roll back together.
10. **Idempotent retry:** replay the same operation ID, canonical request, and
    tracking token after a simulated lost response. It must return the same
    order ID and not deduct stock again. Reusing the operation ID with changed
    payload or token must fail.
11. **Concurrent stock:** set one unit in staging and submit two distinct
    operation IDs simultaneously. Exactly one order may succeed; inventory
    must never become negative and exactly one movement should exist.
12. **Tracking capability:** poll with the correct token, then a random/wrong
    token. Only the correct token should return status. Response bodies and
    browser URLs/logs must not contain customer phone, address, notes, or item
    history; the token must remain in session storage.
13. **CORS allowlist:** send `OPTIONS` to the staging function with the exact
    preview `Origin`; require the matching `Access-Control-Allow-Origin` and
    expected methods/headers. Repeat from an unapproved origin; it must receive
    no allow-origin header and must not be able to submit through a browser.
    Remember that non-browser callers can spoof `Origin`; CORS is not auth.
14. **Trusted gateway IP and rate limit:** use one controlled runner with a
    stable egress and the intentionally nonexistent staging station code. Send
    20 syntactically valid create attempts with unique operation IDs and
    synthetic, non-contacted test phones. Include varying documentation-only
    values in `CF-Connecting-IP`, `X-Forwarded-For`, `X-Real-IP`, and
    `Forwarded`. Invalid-station attempts should return the normal validation
    response and create no orders. The next attempt should return `429` for
    the same actual egress. Repeat one attempt from a second controlled egress
    within the same 15-minute bucket; it should not inherit the first egress's
    IP bucket. Also send a request without client-supplied forwarding headers;
    the function should not fail with its missing-IP `503` if the gateway
    supplies its trusted value. If changing a claimed IP bypasses the 20/IP
    limit, or the no-header request returns `503`, **fail the gate** and add a
    verified gateway/WAF control before release. Do not log or return the IP.
    Confirm the order count is unchanged. Rate counters expire naturally;
    do not delete production or staging records manually.
15. **Owner queue and details:** build the isolated Owner test app in section
    5. Select the correct station and confirm the guest order appears with
    guest contact, address and pin, products/quantities, unit prices, discount,
    delivery fee, total, payment method/status, and canonical status.
16. **Owner status update:** change `PENDING` to `ACCEPTED`, then through a
    valid next transition. Confirm the Supabase order status updates and the
    queue/detail reflect it.
17. **Guest tracking after update:** refresh tracking with the valid token and
    verify the new canonical status is shown while private customer data stays
    unavailable. Repeat a wrong-token request after status changes.
18. **Responsive and recovery behavior:** repeat storefront, cart, and checkout
    at phone, tablet, and desktop sizes. Check keyboard/focus navigation,
    touch scrolling, empty/loading states, inline errors, slow/offline network,
    and that customer fields remain after a recoverable submission failure.
    Verify station ordering rules are displayed and stale rule versions are
    rejected server-side.

### Read-only database checks

Run these against the approved staging database only, replacing placeholders
with staging fixture identifiers:

```sql
-- Migration state. Compare with local migration files and the reviewed dry run.
select version
from supabase_migrations.schema_migrations
order by version desc
limit 15;

-- Guest order contract and customer identity.
select id, station_id, client_operation_id, customer_uid, customer_name,
       customer_phone, order_source, status, payment_status, delivery_mode,
       delivery_address, delivery_latitude, delivery_longitude,
       subtotal_centavos, delivery_fee_centavos, discount_centavos,
       total_centavos, payment_method
from public.orders
where station_id = '<STAGING_STATION_UUID>'
  and order_source = 'CUSTOMER_WEB'
order by requested_at desc;

-- One exact order should have the expected snapshot lines.
select order_id, product_name_snapshot, quantity, unit_price_centavos,
       subtotal_centavos
from public.order_items
where order_id = '<STAGING_ORDER_UUID>'
order by id;

-- One inventory movement per order item, including after retries.
select order_id, product_id, delta, quantity_after, movement_type, created_by
from public.inventory_movements
where order_id = '<STAGING_ORDER_UUID>'
order by product_id;

-- Retry test: expect exactly one row for the operation ID.
select count(*) as order_count
from public.orders
where station_id = '<STAGING_STATION_UUID>'
  and client_operation_id = '<STAGING_OPERATION_ID>';
```

Do not query or export production customer/order rows for this validation.
Tracking token verification is performed through the API with known synthetic
test tokens; plaintext tokens are not stored in the database.

## 7. Recovery and rollback

- **Preview frontend:** stop using the preview URL, then let the short-lived
  channel expire or delete that staging channel after retaining the QA report.
  Production Hosting live content is not involved. Rebuild from the intended
  environment before another preview upload.
- **Edge Function:** on stage failure, restore the last known-good staging
  function version or disable guest intake only in the staging project. Do not
  prune unrelated functions. Preserve test order and inventory history until
  reconciled.
- **Database:** do not run `db reset`, `migration down`, or table deletion on a
  hosted target. The reviewed migrations are additive but the local tree is
  ahead of production by nine files. Prefer a forward corrective migration;
  restore a staging backup only if the isolated staging database is disposable
  and recovery is approved. Reconcile orders, order items, inventory balances,
  and movements before replaying any operation.
- **Evidence:** retain migration list, deployment version IDs, function
  version, exact preview URL, synthetic test operation IDs, and pass/fail
  results. Do not retain customer PII, auth tokens, secret values, or raw IPs.

## 8. Production release checklist and approvals

### Frontend approval — Firebase Hosting

- Confirm production project/site/domain and current live version.
- Build with reviewed production Vite values and the production-restricted
  browser Maps key; inspect the build for secrets and the Hosting file list.
- Pass staging routes, asset, CORS, browser, accessibility, and checkout tests.
- Approve and execute only `firebase deploy --only hosting` for the confirmed
  production project/site. Do not combine it with Firebase backend products.
- Verify the new live version and cache headers, with the prior live version
  ready for Hosting rollback.

### Backend approval — Supabase

- Confirm target ref, database major version, backup/recovery owner, existing
  migration history, service secrets, trusted gateway IP behavior, and exact
  CORS origins.
- Review all nine pending migrations and their dependencies; apply the complete
  approved sequence, not only guest ordering. Run the full database suite on
  staging first.
- Deploy only `guest-order`, with `verify_jwt = false` only after reviewing
  the unauthenticated request controls and tracking capability flow.
- Confirm Owner queue/status and guest tracking after status changes.
- Approve migration and Edge Function deployment separately from Hosting.
- Monitor 4xx/5xx/429, Places errors, transaction failures, order/item/movement
  consistency, and safe operation identifiers. Do not log PII, tokens, or raw
  addresses/IPs.

**Production GO requires both approvals and all staging gates above.** This
preparation does not authorize or perform any hosted action.
