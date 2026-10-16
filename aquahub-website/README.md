# AquaHub Static Website

A clean, responsive, modern AquaHub marketing + legal website built with plain HTML, CSS, and JavaScript.

## Files

- `index.html` — landing page
- `privacy.html` — Privacy Policy
- `terms.html` — Terms of Service
- `account-deletion.html` — Google Play-friendly account/data deletion information
- `subscription-policy.html` — AquaHub Owner subscription/billing/refund policy
- `support.html` — support hub
- `styles.css` — shared responsive UI styles for the marketing, support, and legal pages
- `landing.css` — homepage visual system and responsive scene layouts
- `main.js` — support email links, Owner Play CTA configuration, monthly/annual pricing, mobile navigation, screenshot carousel, and page interactions
- `motion.js` — scroll-linked hero and station story, progressive reveals, and a lightweight water canvas
- `pricing-utils.js` — integer-centavo annual pricing calculations shared with focused Node tests
- `test/` — pricing and marketing-content regression checks
- `sitemap.xml` / `robots.txt` — public static-site crawl metadata
- `order-app/` — Supabase-backed browser ordering client for station QR links
- `assets/aquahub-logo.png` — official AquaHub logo asset
- `assets/favicon-64.png` — browser favicon generated from the official logo
- `assets/aquahub-icon-192.png` / `aquahub-icon-512.png` — optimized icon variants
- `assets/landing/` — original station and water imagery plus the supplied refill-station concept image
- `assets/ss-owners/*.webp` — optimized Owner screenshots; original PNGs remain available

## Design system

The marketing site follows the AquaHub theme: Plus Jakarta Sans,
Aqua blue (`#1264E6`), Aqua cyan (`#16C7E8`), deep navy (`#073B8C`), pale aqua
surfaces, slate text, and green success states. The homepage adds a dimensional
station and water story with scroll-linked visuals. The canvas pauses while offscreen
and is disabled on small screens, reduced-motion preferences, and data-saving connections. Pricing
copy on the landing page mirrors the current Owner subscription model, while
Google Play remains the source of truth for checkout and subscription status.

## Public calls to action and pricing

The landing page does not link to Customer app downloads or claim public Owner
app availability. The primary buttons show the exact label “Get Owner App on Google Play”
but stay disabled while `AQUAHUB_CONFIG.ownerPlayUrl` in `main.js` is empty. Once a
working HTTPS Play link for `com.aesprt.aquahub` and its intended audience is verified,
set that one value to the Owner listing or Owner testing URL. The script rejects other
packages and hosts, and turns both buttons into links. Until then, tester access and
plan-specific inquiries open email to the configured support contact. The closed-testing
status is based on the October 2026 release-environment verification and should be
checked again before publishing. The catalogue amounts in `index.html` reflect
the intended Owner plan model; annual totals are calculated as a 20% catalogue
discount from monthly amounts. Neither is a live Google Play checkout quote.
Google Play remains authoritative for plan availability, exact pricing, and
subscription terms when an offer is available.

Browser ordering is described as available only for participating stations with
an active ordering link. The landing page does not guarantee deployment,
checkout options, delivery availability, or order tracking for a specific
station.

## Important legal checks

The legal pages are strong starter drafts, not legal advice. Before publishing, verify them against the exact production behavior of the apps, including:

- Firebase products actually enabled
- Google Maps permissions and location behavior
- Crashlytics / Analytics SDKs
- Push notification behavior
- Google Play Billing products, base plans, offers, and trial behavior
- Payment providers used for physical water orders
- Data-retention implementation
- Real in-app account deletion flow
- Final plan names, prices, and entitlements

## Testimonials

The landing page uses anonymized early-owner feedback. Do not publish a person's name, photo, or direct quote without permission.

## Running locally

Serve the folder locally from this directory so the page's absolute paths resolve:

```bash
python3 -m http.server 8080
```

Then visit `http://localhost:8080`.

Run the marketing-site checks from this directory with:

```bash
node --test test/*.test.js
```

The browser ordering client has its own tests and build in `order-app/`; see its
README and the separate pre-deployment checklist before any hosted release.

## Web ordering build and deployment

The browser ordering client is a separate Vite application under `order-app/`.
Its frontend build, backend migration/function release, station configuration,
and Hosting routes are separate release steps. Local marketing-site work does
not verify or publish any of them. Do not infer that a station link or the
hosted ordering service is active from the page copy alone.

Build it from `order-app/` with `VITE_SUPABASE_URL`,
`VITE_SUPABASE_PUBLISHABLE_KEY`, and the restricted public
`VITE_GOOGLE_MAPS_BROWSER_KEY`. Every `VITE_` value is included in the browser
bundle. Never add the Maps server key or Supabase service-role key to this build.
The Firebase Hosting config excludes the Vite source directory, environment
files, tests, documentation, and dependencies while preserving the built
`order/` directory and `.well-known/assetlinks.json`.

The intended client architecture does not expose a service-role key or write
orders directly; the browser client reads public catalog projections and
submits guest orders through a server-side function. That architecture and its
production readiness must be verified against the current backend separately.
The configured default Firebase project is not assumed to be staging. See
`../docs/production-readiness/WEB_ORDERING_PREDEPLOYMENT.md` for separate
frontend/backend release checklists. Do not deploy either side without its own
explicit approval.
