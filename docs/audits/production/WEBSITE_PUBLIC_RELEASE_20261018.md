# AquaHub public website release — 2026-10-18

## Outcome

**Website changes: PASS — deployed and checked.**

**Public app download: BLOCKED in the observed browser.** The exact Owner Play listing returned Not Found / HTTP 404. Correct website links cannot make an unavailable Play release downloadable. Play Console release status, eligible countries, and enrollment availability still need verification. This report does not grant Android production release approval.

## Scope and deployment

The user authorized website modifications and redeployment. Only Firebase Hosting site `aquahub-506411` was deployed; no Supabase configuration, database rows, backend functions, Android artifacts, or Play releases were changed in this task.

- Public website: https://aquahub.aesprt.com/
- Firebase origin: https://aquahub-506411.web.app/
- Owner listing: https://play.google.com/store/apps/details?id=com.aesprt.aquahub
- Test enrollment: https://play.google.com/apps/testing/com.aesprt.aquahub
- Final cache revision: `styles.css?v=20261018-public-r3`.
- Firebase CLI 15.34.0 reported successful Hosting deployment of 50 files.
- Deployment command: `firebase deploy --only hosting --project aquahub-506411 --non-interactive` using the locally cached official Firebase CLI.

## Changes verified

| Area | Result | Evidence / behavior |
| --- | --- | --- |
| Download links | PASS | Eight homepage anchors plus support-page links target the exact Owner package; functional without JavaScript; new-tab links use safe rel attributes. |
| Testing scope | PASS | Open-testing availability is explained; online checkout requires connectivity; offline sales are not advertised. Actual in-app enforcement is outside this website check. |
| Icons | PASS | Existing Play SVG and inline SVG icons replace decorative emoji/Unicode arrow controls. Accessible labels are preserved; decorative SVGs are hidden from assistive technology. |
| Pricing | PASS with follow-up | User confirmed Starter ₱199, Business ₱499, Pro ₱999 monthly. Final live DOM shows ₱0 / ₱199 / ₱499 / ₱999. Annual prices and unverified annual discount offers are not quoted; Google Play checkout is authoritative. |
| Privacy | PASS for identified content gaps | Covers guest/browser orders, customer details/location, payment proof, local cart/customer storage, station sharing, hosting/security/performance, notifications, SMS and opted-in marketing providers. |
| Terms | PASS for identified content gaps | Separates physical water purchases from app subscriptions; uploaded proof is not independent bank settlement verification; explains online service availability. |
| Subscription policy | PASS | Current monthly reference prices, real charges during open testing, license-test payment distinction, cancellation instructions and Google Play management link. |
| Account deletion page | PASS for website | Actionable email request with subject; guest-order requests, deletion/retention explanation and subscription cancellation guidance. Real deletion completion was NOT VERIFIED in this task. |
| Support | PASS | Working policy/help navigation, online requirement, download and enrollment links. |
| Policy navigation | PASS | Removed root base URL that broke policy contents links; clicked Privacy Scope successfully remained on `/privacy.html#scope`. |
| Missing page | PASS | Branded noindex 404 returns actual HTTP 404 and offers Home/Support/legal navigation. |
| Hosting | PASS | Ordering rewrites preserved; development/config files return 404; nosniff, referrer and frame headers present. |
| Fonts | PASS | Locally served licensed Plus Jakarta Sans; license included. |

All six public pages were reviewed: homepage, Privacy, Terms, Subscription Policy, Account Deletion and Support. No separate cookie-policy page was added: browser storage and provider processing are disclosed in Privacy. The stated retention is purpose based; a fixed business retention schedule was not independently established. These checks address identified website gaps and do not constitute certification of all legal obligations.

## Actual checks

- **PASS:** 21 marketing/legal JavaScript tests; 0 failures. Includes exact package links, preservation of accessible CTA content, monthly prices, legal navigation, local fragments/assets, icons, online-only copy and Hosting rules.
- **PASS:** 19 browser-ordering tests; 0 failures. Existing ordering source changes in the workspace predate this task; the ordering bundle was not rebuilt here.
- **PASS:** Local link audit: 7 HTML pages, 181 local links/assets; no missing targets, fragments or duplicate IDs.
- **PASS:** JavaScript syntax checks and `git diff --check`.
- **PASS:** Final live homepage browser inspection shows r3 stylesheet, confirmed monthly prices and exact-package Play URLs.
- **PASS:** Actual desktop and phone-size browser layout checks; policy/support pages had no horizontal overflow at the checked narrow viewport. Mobile navigation opened successfully.
- **PASS:** Newsletter form required email and explicit marketing consent before enabling submission. No form was submitted and no email/contact was created for this check.
- **PASS:** 46 URL checks across the custom domain and Firebase origin. One custom-domain robots.txt request timed out; its retry returned HTTP 200. All final expected responses passed. Origin responses matched local source wherever compared. Cloudflare modifies custom-domain HTML and robots responses, so their byte hashes are not expected to equal the origin.
- **PASS:** `/order/`, `/s/{code}`, `/order/s/{code}` still serve the ordering shell. This is routing evidence, not a completed customer order.
- **PASS:** Existing published ordering artifacts were compared byte-for-byte with the Firebase origin; App Links JSON also matched source.
- **PASS:** `/firebase.json`, `/.firebaserc`, `/order-app/package.json`, `/order-app/.env.production`, `/test/public-pages.test.js`, `/README.md` returned 404 on both hosts.
- **NOT VERIFIED:** Signed-in Play enrollment, app download/install, real subscription purchase/restore, a new live order, delivered marketing email, and completed account deletion.

Read-only inspection of deployed `delete-account` version 28 found marketing withdrawal/provider cleanup queue handling before Auth deletion. Source inspection does not prove successful real account deletion. No production user was deleted.

## Remaining follow-ups

1. **P1 / BLOCKED — public app availability.** The public Play listing is Not Found from the observed browser. Confirm open-testing release approval/rollout, countries and test enrollment in Play Console, then validate download with an eligible account. Exact Console cause is NOT VERIFIED.
2. **P2 / FAIL — backend catalog price metadata disagrees with confirmed Play prices.** Read-only production `subscription_plans` currently records ₱299 / ₱699 / ₱1,299 monthly and annual centavo values 287040 / 671040 / 1247040. User confirmed actual Play monthly prices ₱199 / ₱499 / ₱999. Website now uses the confirmed prices; backend billing/catalog consistency requires a separate scoped change and verification. No backend price was changed here. Published plan limits and 30-day paid-plan trial metadata matched the site.
3. **BLOCKED outside website scope — Android cancellation-return validation.** The previously prepared Owner crash fix still needs affected-device runtime verification before a broader launch approval; see the separate cancellation crash audit.
4. **NOT VERIFIED — complete operational/legal execution.** Mailbox handling/retention execution and end-to-end account deletion require actual operational validation. Existing Android, billing and security release gates retain their own status.

## Evidence

- [Hosted checks](evidence/website-20261018/hosted-checks.json)
- [Website tests](evidence/website-20261018/website-tests.txt)
- [Ordering tests](evidence/website-20261018/ordering-tests.txt)
- [Ordering artifact comparison](evidence/website-20261018/ordering-artifacts.json)
- [Source SHA-256 manifest](evidence/website-20261018/source-sha256.json)
- [Live homepage screenshot](evidence/website-20261018/live-homepage.png)

## Verdict

**Website update deployed successfully. Public acquisition journey: BLOCKED pending Google Play availability.** No blanket public app readiness or production GO is asserted.
