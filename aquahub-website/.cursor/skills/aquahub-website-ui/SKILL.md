---
name: aquahub-website-ui
description: Design, review, and verify responsive AquaHub marketing and mobile-ordering web pages. Use for website content, pricing, accessibility, SEO, or responsive layout changes.
---

# AquaHub Website UI

Use these project conventions when editing the static marketing site or its browser ordering client.

## Before changing a page

1. Read the workspace instructions and current source for the affected page.
2. Trace claims to current Owner plan entitlements or the implemented browser-ordering flow. Mark availability as conditional when hosted services are not verified.
3. Keep the static marketing site and the Vite ordering client distinct. Do not change backend or hosting configuration as part of a content update.
4. Preserve legal descriptions of existing services when removing promotional app-download content.

## Brand and visual system

- Keep the AquaHub blue, cyan, navy, pale-aqua, and slate palette in `styles.css`.
- Reuse the existing typography, spacing, radius, and shadow tokens before adding new values.
- Keep sections concise, scannable, and aligned to the existing content width.
- Use actual product screenshots when available. Mark component mockups as illustrations; do not invent order, tracking, payment, or fulfillment states.

## Responsive layout

- Design for narrow phones first, then verify tablet portrait, tablet landscape, laptop, and wide desktop widths.
- Use content-driven breakpoints; avoid fixed widths that create horizontal scrolling.
- Keep pricing cards readable and CTAs comfortably tappable on phones. Use balanced columns on tablets and desktops.
- Check the mobile navigation, long headings, translated/mixed-language text, images, and footers at narrow widths.

## Content, pricing, and SEO

- Treat current source and verified entitlement documents as authoritative; distinguish intended catalogue pricing from Google Play checkout pricing.
- Keep each displayed price in one source of truth. Calculate annual totals and savings with integer centavos.
- Do not link a plan CTA to an unverified purchase offer or describe closed testing as public availability.
- Describe browser ordering as available only for stations with ordering enabled when hosted readiness is unknown.
- Update title, description, canonical, social metadata, heading hierarchy, alt text, and sitemap only with accurate claims.

## Accessibility and interaction

- Prefer semantic links, buttons, headings, form labels, and native radio controls.
- Provide visible keyboard focus, understandable labels, keyboard-operable menus and toggles, and sufficient contrast.
- Respect `prefers-reduced-motion`; animation must not be required to understand or operate the page.
- Keep anchor targets and all CTAs functional. Never leave placeholder `href="#"` actions without a real destination.

## Verification

1. Run focused Node tests for pricing and content invariants.
2. Run syntax checks and the existing ordering-client tests/build when shared files could affect it.
3. Serve the static site locally and inspect phone, tablet, and desktop screenshots when a browser is available.
4. Check keyboard focus, reduced motion, broken links, price formatting, and horizontal overflow.
5. Report the actual widths, commands, and results tested. Do not equate a build with visual verification or a local preview with production availability.
