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
- `main.js` — support email links, Owner Play CTA configuration, mobile navigation, screenshot carousel, and page interactions
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
and is disabled on small screens, reduced-motion preferences, and data-saving connections. Plan features and limits on the landing page match the verified production catalogue. Monthly reference prices follow the owner-confirmed Play offers; annual prices remain in Google Play. Google Play remains the source of truth for checkout and subscription status.

## Public calls to action and pricing

The public Owner download actions are real HTML links to
`https://play.google.com/store/apps/details?id=com.aesprt.aquahub` and work
without JavaScript. Header, hero, plan cards, final section, footer, and support
page share the Owner package. `AQUAHUB_CONFIG.ownerPlayUrl` validates the host
and package before updating these anchors in place. Open-testing enrollment
links point to `https://play.google.com/apps/testing/com.aesprt.aquahub`.

The website describes the public Owner release as online-only: internet is
required for checkout and offline sales are unavailable. Download availability
remains controlled by Google Play. On October 18, 2026, a public browser still
returned Not Found for the listing; publishing this website does not resolve
that external release gate. Paid subscriptions are not automatically free in
open testing. The owner confirmed the current Google Play monthly prices as ₱199 / ₱499 /
₱999 on October 18, 2026. Those monthly references appear on the homepage and
subscription policy. Annual quotes and the annual toggle are omitted until
actual annual offers are confirmed; the calculator remains covered by sample
input tests. A read-only production catalogue check still found ₱299 / ₱699 /
₱1,299 metadata. That discrepancy is recorded in the website audit; this
Hosting release does not alter the backend catalogue or Play billing settings.


Marketing and legal pages use SVG controls, self-hosted licensed fonts,
consistent policy/support navigation, and a custom 404 page. Legal contents
links resolve within each document (there is no root `<base>` on legal pages).
Privacy disclosures cover browser checkout, payment-proof uploads, local/session
storage, Cloudflare delivery/performance, SMS verification, and optional Reach
marketing. These public pages do not verify the actual deletion workflow,
business retention schedule, or the app's broader production gates.

Browser ordering is described as available only for participating stations with
an active ordering link. The landing page does not guarantee deployment,
checkout options, delivery availability, or order tracking for a specific
station.

## Important legal checks

The legal pages describe the current implementation and were reviewed for the public website on October 18, 2026. Keep them aligned with production behavior, including:

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

Do not add invented testimonials, ratings, user counts, or customer identities. Obtain permission before publishing a person's name, photo, or direct quote.

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
