# AquaHub web ordering client

This is the browser fallback for the existing station QR link. The Android
Customer app can open the same link when installed; otherwise the browser loads
this responsive storefront. Checkout is available to guests and does not create
or require an account.

## Local development

Use a local, uncommitted environment file or pass variables to the build:

```bash
VITE_SUPABASE_URL=https://your-project.supabase.co \
VITE_SUPABASE_PUBLISHABLE_KEY=your-publishable-key \
VITE_GOOGLE_MAPS_BROWSER_KEY=your-restricted-browser-key \
npm run dev
```

Never use a Supabase service-role key in this client. The browser reads the
public station and product projections, then submits and tracks orders through
the `guest-order` Edge Function. That function uses a server-only service-role
client to call narrowly granted database RPCs; the browser cannot write order,
inventory, or tracking tables directly. See `../../docs/ai-context/WEB_ORDERING.md`
for the request contract, migration state, and local verification steps.

The Edge Function is configured with JWT gateway verification disabled because
guest requests have no user JWT. It validates each request itself, requires the
trusted Supabase edge IP header for rate limiting, and applies exact origin
checks. Preview-channel origins must be explicitly configured for a staging
window. The `SUPABASE_SERVICE_ROLE_KEY` and
`GOOGLE_MAPS_SERVER_API_KEY` must only exist in the Edge Function runtime.
The browser Maps key is public; restrict it to approved Firebase Hosting
domains and the required Maps JavaScript, Places, and Geocoding APIs.

The production build is written to `../order/` and is served by Firebase
Hosting under `/order/`. Station routes `/s/**` and `/order/s/**` rewrite to
the ordering entry point. Firebase Hosting is the UI host; Supabase remains
the guest order API and database. The local Edge Function and database
migration are not deployed by the web build.

Environment templates for local development, staging, and production are
provided as `.env.*.example` files. Copy only the matching template to an
ignored `.env.<mode>.local` file. `npm run build:staging` rejects missing,
placeholder, or production Supabase configuration and writes to the same
`../order/` directory as the production build. Rebuild with the intended mode
before each release target. See
`../../docs/production-readiness/AQUAHUB_STAGING_RUNBOOK.md` for the separate
Firebase preview and Supabase staging steps.

## Checks

```bash
npm test
npm run build -- --outDir /tmp/aquahub-web-order-build --emptyOutDir
```

Run Supabase integration tests only against a local database with the current
migrations applied: `supabase test db --local`. Do not use `--linked` for local
verification.
