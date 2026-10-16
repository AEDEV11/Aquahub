# Google Play Billing and subscription audit

Audit snapshot: 2026-10-09, updated after approved local source changes. No Play Console access, licensed tester purchase, live payment, production function deployment, or subscription mutation was performed.

## Group 4 RTDN/reconciliation security update — 2026-10-09

Local RTDN and reconciliation functions now share a Google OIDC bearer verifier. It checks Google issuer, RS256 signature using Google's published certs, an exact configured audience string, expiry/not-before/issued-at bounds, and the expected verified service-account email. The reconciliation request no longer uses the Supabase service-role key as its incoming bearer. Seven synthetic signed-token tests pass, and the full local Deno suite is 34/34 with all 22 TypeScript files checked. This does not validate Pub/Sub's deployed OIDC configuration, Cloud Scheduler identity, API audience, secret rotation, Play API calls, deployed function revision, RTDN retries, or any licensed purchase. `reconcile-play-subscriptions` remains unscheduled and undeployed. For authenticated Pub/Sub push, follow Google's [authenticated push subscription guidance](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions); a Pub/Sub ID token must be authenticated against the configured audience/identity and is not interchangeable with an arbitrary webhook secret.

## Summary / release decision

Billing is **NOT VERIFIED and release-blocking**. The current checkout uses Google Play Billing Library 9.1.0 and sends tokens to Supabase `verify-play-purchase`. Group 3 local source now verifies through subscriptionsv2 and binds token ownership atomically; verified-contact trials and encrypted reconciliation tokens are also implemented locally. The latest full-tree Deno suite passes 23/23 and all 10 function entrypoints typecheck. No function was deployed; production backend association, Play Console configuration and every licensed lifecycle case remain unverified. Historical UID-only trial claims and hash-only purchase tokens remain recovery/privacy gaps.

## Architecture and configured contract

1. Android `GooglePlayBillingRepositoryImpl` builds BillingClient, handles connection/disconnection, discovers ProductDetails, launches the eligible plan/offer, queries active subscriptions for restore/plan replacement, then sends token/product to Supabase. The current subscription UI displays only the eligible Google-returned offer price/terms and does not substitute a local paid price when an offer is unavailable. This source change is not proof of Play Console configuration.
2. `SupabaseSubscriptionDataSource` calls `verify-play-purchase`; Edge Function identifies an active OWNER membership, calls shared Google Play subscriptionsv2 verifier, maps exact product/base plan, acknowledges if needed, and upserts organization subscription/history/token records.
3. `play-rtdn` receives notifications, deduplicates message IDs, verifies current provider state rather than trusting notification payload as entitlement, ignores older provider event times, and updates subscription state.
4. Owner app caches subscription data and resolves entitlements in use cases/UI. The new local Group 1 migration adds a server-side effective-plan resolver and write-time feature/limit checks, and the local SQL suite verifies Free-plan limits plus expired-plan fallback. That migration is not linked/deployed and the full plan × role × operation matrix is not run, so production entitlement enforcement remains NOT VERIFIED (see bug AH-P0-04).

Repository mapping (not Play Console evidence):

| Product ID | Base plan IDs in code | Internal plan | Cycle | Code/catalogue amount |
|---|---|---|---|---|
| aquahub_starter | monthly, yearly | STARTER | Monthly / annual | ₱199 monthly; ₱1,910.40 annual intended |
| aquahub_business | monthly, yearly | BUSINESS | Monthly / annual | ₱499 monthly; ₱4,790.40 annual intended |
| aquahub_pro | monthly, yearly | PRO | Monthly / annual | ₱999 monthly; ₱9,590.40 annual intended |

Play queries real ProductDetails and offer pricing, but product activation, market/currency/eligibility, base-plan IDs, offer tags, intro/free-trial eligibility, taxes, billing period, renewal disclosure, grace/account-hold configuration, and actual price were not inspected in Play Console. No value in this table is represented as verified Play pricing.

## Purchase security findings

- Positive: client callback alone is not the source of paid entitlement; server verifier is in the flow. The server calls Google subscriptionsv2 and checks provider state, package/product/base-plan mapping, owner membership, and acknowledgement. Service-account/API secrets are server-side in reviewed code.
- **P1 AH-P1-05:** the original local verifier had a token check-then-act race and did not bind Google identity. Current local source compares Google's verified `obfuscatedExternalAccountId` to the authenticated owner identifier and writes token ownership/subscription/history through a serializing service-role RPC before acknowledgement. Encryption, duplicate callback, replacement and cross-org cases have local Deno/SQL coverage. Latest totals: Deno 23/23, 10 entrypoints typecheck, 27 billing SQL assertions in the 81-assertion suite. No Edge Function deployment occurred; hosted and production behavior remains unverified.
- **P1 AH-P1-06:** provider emits `ACTIVE_UNTIL_PERIOD_END` for canceled-but-entitled subscriptions. Local Android source now represents and resolves that status through `currentPeriodEnd`; a JVM boundary test passes before and at expiry. This is not a Google licensed lifecycle test and the updated Android release artifact has not been built/installed.
- **P1 AH-P1-07:** local trial claims now use HMAC keys for normalized verified email/phone and atomically provision claim, trial and history. Historical UID-only claims cannot be linked to verified contacts; account deletion/recreation/contact changes still require privacy-reviewed handling and staging abuse tests.
- **P0 AH-P0-04:** local mutation guards use the shared effective-plan resolver and checked-in feature/limit catalog. Selected SQL checks pass, but the full plan × feature × operation × role/station matrix, direct REST bypass tests, linked deployment and production behavior remain unverified.
- Concurrent subscription RPC contention, signed RTDN delivery/deduplication/reordering, publisher API/service-account integration, acknowledgement failure recovery, missed-RTDN scheduling, and actual refund/revocation convergence have not been integration-tested. The endpoint list or local code is not evidence of deployed RTDN topic delivery, Google permissions, or Play Console setup.

## Lifecycle matrix

| Scenario | Source/evidence status | Release validation needed |
|---|---|---|
| New purchase | Group 3 local verifier binds token to authenticated Owner and Google obfuscated account identity; atomic token/subscription/history RPC before acknowledgement. SQL/Deno unit coverage passes. | Licensed purchase; wrong owner/org; wrong product/base plan; acknowledgement and DB failure recovery |
| Pending payment | Mapper and verifier reject pending states before entitlement; unit tests pass. | Delayed licensed payment completion/cancel; confirm no entitlement before provider reports purchased |
| Active monthly/annual | subscriptionsv2 mapper exists | Confirm plan, dates, auto-renew and actual returned price on tester accounts |
| Free trial | Local verified-contact HMAC claims and atomic provision/history RPC; Deno/SQL tests cover normalization, unverified contacts, idempotency and cross-org denial. Historical UID-only claims remain unmapped. | First/repeat trial, deleted/recreated account, changed contacts, cross-org race and interrupted provisioning on license/staging identities |
| Renewal | RTDN handler re-fetches provider state and guarded RPC applies changes; local SQL state/history tests pass. No periodic schedule exists. | Accelerated license renewal, actual duplicate delivery and recovery after missed RTDN |
| Cancel | Provider emits `ACTIVE_UNTIL_PERIOD_END`; local parser/state-boundary JVM test now passes; linked function/runtime unverified | Licensed purchase cancellation; retain access to exact provider expiry, then remove across restart/device |
| Grace period/account hold/pause | Local state mapper covers grace, suspended/hold/paused and expiry cutoffs; selected SQL entitlement cases pass. Plan/role allowlist consistency and Play states remain unverified. | Exercise every state with license tester and verify expiry/feature matrix across app/backend |
| Restore/outside-app purchase | `queryPurchasesAsync` restore code exists | Existing token on same owner, wrong owner/org, multiple active products, reinstall |
| Upgrade/downgrade | Replacement flow with proration configured in client | Google license plan change and provider event ordering/effective period |
| Refund/revoke/expire | RTDN path intended to reconcile authoritative state | Revoke/refund, RTDN loss/retry, server poll/reconcile and access revocation |
| Offline at purchase result | Client forwards token to server; no proof of durable queue/reconciliation after process death | Complete purchase offline, kill/reopen, query purchase, verify before entitlement |
| Duplicate/out-of-order callback | SQL verifies repeat purchase-state idempotency and stale provider-event rejection; RTDN claim RPC exists. Signed Pub/Sub duplicate/out-of-order handler delivery and concurrent sessions were not tested. | Replay signed duplicate/out-of-order RTDN and concurrent callbacks; verify one consistent entitlement/history transition |

All rows above are **NOT RUN** with a Play licensed tester unless specifically called out as source evidence. No production payment was initiated.

## Manual licensed test procedure

After P0/P1 code fixes and a separate staging/closed-track approval:

1. Confirm Play Console app identity, tester list, subscription products/base plans/offers, eligible markets, price/renewal disclosures, Play App Signing, RTDN Pub/Sub topic, Developer API access, and staging backend are configured. Record sanitized configuration evidence.
2. Use only designated license-tester accounts and a non-production/staging organization. Exercise fresh monthly and annual subscription, cancel checkout, pending/delayed payment, approval/completion, duplicate callback, app kill/relaunch, offline completion/reconnect, reinstall/restore, and purchase made outside the app.
3. Verify backend subscription row/events and app entitlement agree with subscriptionsv2; do not use client UI alone as evidence. Check acknowledgment deadline, deduplicated token binding, idempotent event replay, exact period dates and actual locale/currency price.
4. Test renewal, cancellation before expiry, expiration, grace period, account hold, pause if configured, upgrade, downgrade, refund and revoke using Play license-test controls/accelerated periods. Capture redacted traces and compare UI/server state across two devices.
5. Confirm unauthorized second organization cannot restore/bind the same token and paid UI is not unlocked by pending state or forged client payload. Remove only test data under staging's approved cleanup process.

Do not buy with a personal production payment method or change live subscriptions as part of this audit.

## Required before release

- Latest local Deno tests pass 23/23 and all 10 entrypoints typecheck. Add signed-RTDN handler, Google publisher HTTP, concurrent subscription/trial and hosted cross-organization API tests in an isolated staging environment; deploy only with separate explicit authorization.
- Run licensed cancel/restore tests to validate the local `ACTIVE_UNTIL_PERIOD_END` fix against Google and persisted database state.
- Resolve account-recreation/contact-change handling for historical UID-only trial claims under a privacy-reviewed durable identity policy; local atomic verified-contact claims are implemented but are not deployed.
- Verify all plan gates and limits on server mutations; local guards exist, but the full plan/role/RPC matrix and hosted direct-REST denial remain unverified.
- Run the full licensed test matrix above; verify Console product IDs, base plans, prices, offers, acknowledgment, Play App Signing and RTDN configuration with actual Console evidence.
- Provide auditable reconciliation/retry for missed or delayed RTDN and server failures, and test duplicate/out-of-order/refund events.

## Group 3 local remediation addendum — 2026-10-09

This addendum supersedes earlier statements in this report that server plan/limit enforcement, purchase-token ownership, and trial claim atomicity are wholly absent from the checked-out source. The latest code now performs subscriptionsv2 verification, authenticated Owner membership resolution, Google obfuscated-account binding for new claims, token encryption, atomic token/subscription/history writes, purchase acknowledgement after commit, RTDN deduplication plus authoritative provider re-fetch, and HMAC claims for verified contacts. Owner purchase UI now displays Google-returned eligible offer pricing and terms without a fabricated paid-price fallback. A service-role reconciliation endpoint exists but is **not scheduled or deployed**.

Local evidence: 27 billing-specific pgTAP assertions are included in the fresh 11-file/81-assertion replay; the fresh full-tree Deno 2.9.7 run passes 23/23 and all 10 Edge Function entrypoints typecheck. This still does not verify Play Console product/base-plan configuration, actual prices, service account/API permissions, RTDN delivery, a licensed lifecycle, deployed functions, or the production binary. Catalog amounts in source/migration (Starter ₱199, Business ₱499, Pro ₱999 monthly and 20%-discounted annual amounts) are intended local configuration only; the earlier ₱299/₱699/₱1,299 values in this historical audit section are superseded, not Play Console-verified.

Open release issues remain: reconciliation has no monitored schedule; old hash-only token rows require user re-verification or a privacy/security-reviewed alternative; legacy UID-only trial claims cannot be converted to verified contacts without a safe policy; the full operation/role/plan entitlement matrix is not hosted-tested; and all Play licensed scenarios in the manual test matrix remain NOT RUN. No real purchase was initiated and no Edge Function was deployed.
