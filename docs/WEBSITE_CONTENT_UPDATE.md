# AquaHub Website Content, Pricing, and Mobile Ordering Update

Updated October 10, 2026. This records local source changes and local
verification only. Nothing in this update publishes the site or changes the
ordering backend.

## Public site changes

- Rewrote the landing page around AquaHub Owner and the station-enabled browser
  ordering path. Browser ordering is clearly conditional on a participating
  station having an active ordering link and setup.
- Removed Customer app download promotion and Owner Play Store install links.
  Existing legal references to the Android Customer app and Android App Links
  remain where they describe app behavior.
- Removed the unsupported trial claim from marketing. The subscription policy
  retains its trial terms, while the paid checkout amount remains explicitly
  subject to Google Play.
- Replaced the hero's customer-app mockup with a clearly marked illustrative
  Owner dashboard and browser-ordering flow. No sample dashboard values are
  represented as live data.
- Added focused SEO metadata, canonical/social metadata, JSON-LD, `sitemap.xml`,
  and `robots.txt`.
- Added working email CTAs with plan-specific subject lines. They still work
  when JavaScript is unavailable.

## Intended Owner catalogue shown on the site

Amounts below mirror the current local Owner plan model. Google Play Console
activation, exact market prices, offers, taxes, and annual base plans remain
unverified. These are reference amounts, not checkout quotes or proof that a
paid plan is currently offered.

| Plan | Monthly reference | Annual catalogue total | Annual savings | Main published limits |
| --- | ---: | ---: | ---: | --- |
| Free | ₱0 | No annual billing | — | 1 station, 20 products, 100 monthly orders, 50 customers |
| Starter | ₱199 | ₱1,910.40 | ₱477.60 | 1 station, 30 products, 300 monthly orders, 100 customers, 2 riders, 2 staff |
| Business | ₱499 | ₱4,790.40 | ₱1,197.60 | 3 stations, 500 products, 10,000 monthly orders, 2,000 customers, 10 riders, 10 staff |
| Pro | ₱999 | ₱9,590.40 | ₱2,397.60 | 10 stations, 10,000 products, 100,000 monthly orders, 50,000 customers, 100 riders, 100 staff |

Annual values are calculated from monthly integer-centavo values with the
current intended 20% catalogue discount. Free does not receive an annual
charge. The page directs users to Google Play for current availability and
paid checkout details.

The Free card identifies mobile ordering, rider management, and delivery
dispatch as excluded. Eligible paid plans describe delivery tools and mobile
ordering conditionally, subject to station setup. The copy does not promise live
rider tracking, route planning, online payment availability, or a specific
station's hosted ordering status.

Plan names, entitlements, limits, monthly amounts, and annual calculation were
checked against the current local Owner model at
`AquaHub/app/src/main/java/com/aesprt/aquahub/domain/model/SubscriptionModels.kt`.
The separate billing audit records why checkout availability and exact Play
prices remain unverified.

## Responsive and interaction work

- Added a native, keyboard-operable Monthly/Annual radio control. Paid annual
  totals and savings are calculated with integer centavos in
  `aquahub-website/pricing-utils.js`.
- Kept one-column pricing on phones, added two-column feature/workflow/pricing
  grids at 601–820 CSS pixels, and retained the existing two-column pricing
  layout through medium desktop widths and four columns on wide screens.
- Changed the hero preview to a vertical grid so its illustrative dashboard and
  browser flow remain proportionate at desktop widths.
- Added sticky-header anchor spacing and accessible touch-sized billing
  controls.
- Existing mobile navigation, experience tabs, FAQ accordion, screenshot
  carousel, and reduced-motion behavior remain in place.

## Local verification

| Check | Result |
| --- | --- |
| `node --check main.js` and `node --check pricing-utils.js` | PASS |
| `node --test test/pricing-config.test.js` | PASS, 8 tests |
| `npm test` in `order-app/` | PASS, 7 tests |
| `npm run build -- --outDir /tmp/aquahub-web-order-build --emptyOutDir` in `order-app/` | PASS; build emitted non-fatal notices for public font URLs, and the font files were present in the generated output |
| Local HTML link/fragment audit | PASS, 58 local links across 6 HTML pages |
| `sitemap.xml` parse | PASS, valid XML with 6 URLs |
| Browser render and responsive checks | PASS at 390×844, 768×1024, and 1440×960 CSS pixels; no horizontal overflow observed |
| Annual pricing interaction | PASS; annual totals and savings matched the catalogue calculations |
| Persona tabs and keyboard arrow navigation | PASS |
| Local browser console warnings/errors | None observed |

The local browser screenshots were visually inspected in this task. The
marketing site was served from localhost; the production website was not
visited or changed.

## Remaining release checks

- Verify Google Play Console products, prices, trials, annual base plans, and
  eligible offers before representing any of them as purchasable.
- Verify current hosted browser-ordering routes and server function behavior
  independently. This task did not query or mutate the hosted backend.
- Review and approve legal language before publication.
- Publish the static site only through a separate, explicitly approved release
  step.

This local website update does not change the separate Android Google Play
production decision. Billing and hosted release behavior remain NOT VERIFIED in
the existing production-readiness records.
