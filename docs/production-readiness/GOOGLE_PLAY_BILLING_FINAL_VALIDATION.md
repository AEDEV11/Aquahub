# Google Play Billing — Group 3 validation

Snapshot: 2026-10-09. Local source and isolated test database only. **Decision: NO-GO for production.** No Play Console configuration was changed, no licensed or paid purchase was made, and no Supabase migration or Edge Function was deployed.

## Architecture and trust boundaries

The current checked-out Owner path is:

`Compose subscription screen → GooglePlayBillingRepositoryImpl/BillingClient → ProductDetails and eligible offer → Play purchase token → authenticated verify-play-purchase Edge Function → Google purchases.subscriptionsv2.get → atomic database RPC → organization subscription/history/token rows → server-derived effective plan → app subscription state`.

The Android client supplies a product ID and purchase token, but does not supply an authoritative paid status. The verifier authenticates the Supabase user, resolves an active Owner membership, fetches Google’s subscription state for the configured package, validates the recognized product/base-plan mapping, checks Google’s obfuscated account identifier for new token binding, and persists the verified state through a service-role-only RPC. Token ownership, replacement lineage, subscription state and history are serialized/updated transactionally. Acknowledgement occurs only after the verified entitlement commits; retries can safely reapply the same token.

RTDN verifies the Google push identity/signature and audience, deduplicates the Pub/Sub message, strips raw purchase tokens before storing event payloads, and re-fetches current state from Google before changing entitlements. It does not treat notification type as proof of payment. Reconciliation source now exists as a service-role-protected Edge Function; it decrypts and hashes stored tokens, queries Google, applies the same atomic RPC, and acknowledges verified purchases. **It is not scheduled or deployed**, so it does not yet guarantee recovery from missed RTDN.

The local server entitlement resolver grants a paid plan only while a verified subscription/trial/grace cutoff remains valid; expired, suspended, invalid and missing paid rows fall back to Free. Group 1 write-time feature/limit guards remain in local source. The plan feature/limit tables are the checked-out configuration source, but the full plan × operation × role matrix has not been exercised against PostgREST or staging.

## Product, base-plan and price mapping

| Play product ID | Base plan ID | Internal plan | Period | Latest intended price (not Play Console verified) |
|---|---|---|---|---:|
| `aquahub_starter` | `monthly` | Starter | 1 month | ₱199/month |
| `aquahub_starter` | `yearly` | Starter | 1 year | ₱1,910.40/year |
| `aquahub_business` | `monthly` | Business | 1 month | ₱499/month |
| `aquahub_business` | `yearly` | Business | 1 year | ₱4,790.40/year |
| `aquahub_pro` | `monthly` | Pro | 1 month | ₱999/month |
| `aquahub_pro` | `yearly` | Pro | 1 year | ₱9,590.40/year |

Annual amounts are the current intended 20% discount from twelve monthly payments. Their values are stored in centavos in the local migration. They are catalogue/business configuration only. The Owner UI no longer substitutes a local paid-plan amount when Google Play has not returned an eligible price; purchase selection is disabled/unavailable instead. Introductory phases are shown separately from the recurring charge, and the offer/base-plan passed to BillingClient is the same eligible offer whose price is displayed.

The source declares Google Play Billing Library 9.1.0. The checkout returned prices, currency and eligible offers are the runtime purchase UI authority. Play Console product activation, exact IDs/base plans, current market prices, offer/trial eligibility, tax and renewal copy remain **NOT VERIFIED**.

## Checked-out server entitlement catalog

The migration seed `supabase/migrations/20261004220000_seed_subscription_catalog.sql` is the current local source for these quantity limits. Group 1 mutation guards call the server-side limit assertions for monthly orders, stations, products, customers, staff and riders. The local catalog also has plan feature rows, but there is no evidence here that these values match the deployed/Play commercial configuration, and the full plan × feature × mutation × role matrix has not been exercised through PostgREST.

| Plan | Enabled feature rows in local catalog | Stations | Riders | Products | Monthly orders | Customers | Staff |
|---|---:|---:|---:|---:|---:|---:|---:|
| Free | 6 | 1 | not configured in the seed | 20 | 100 | 50 | not configured in the seed |
| Starter | 14 | 1 | 2 | 30 | 300 | 100 | 2 |
| Business | 22 | 3 | 10 | 500 | 10,000 | 2,000 | 10 |
| Pro | 24 | 10 | 100 | 10,000 | 100,000 | 50,000 | 100 |

“Not configured” is not proof that the limit is unlimited; the feature's intended Free behavior must be established and tested. Source presence and local SQL cases do not establish every entry point is guarded or that limits are enforced for all organization members/stations.

## Local lifecycle model

| Google/provider state | Local state and expected access | Evidence/remaining limit |
|---|---|---|
| Pending | No paid entitlement and no acknowledgement | Shared mapper rejects; unit tests pass. No licensed delayed-payment test. |
| Pending purchase canceled | No paid entitlement | Explicit error path; unit test passes. |
| Active | Plan remains until verified cutoff | subscriptionsv2 state applied by trusted RPC. No live purchase test. |
| Canceled but period still valid | `ACTIVE_UNTIL_PERIOD_END`; access until provider expiry | Android boundary tests and SQL RPC case pass. No license cancellation test. |
| Grace period | `GRACE_PERIOD`; expiry is the provider’s current `expiryTime` cutoff | Deno mapping + SQL entitlement case pass; actual Play grace test not run. |
| Account hold / paused / other non-entitled state | `SUSPENDED`; effective plan falls back to Free | Source mapping; Play state tests are unit simulations only. Exact Console configuration not verified. |
| Expired/revoked state represented by provider expiry | `EXPIRED`; paid plan is no longer effective | Deno mapping + SQL fallback case pass. No real refund/revocation test. |
| Replacement token | New token is bound; prior token is marked superseded; stale prior RTDN is rejected | Isolated database assertions pass; no Play plan-change purchase. |
| Renewal | Verified newer period applied; history appended only on state/period change | Transaction path exists; no accelerated renewal. |

Free-trial claims now use HMACs of normalized **verified** email/phone contacts. The claim, trial subscription, and history event are one service-role transaction protected by ordered advisory locks and unique constraints. A Free onboarding row does not consume a paid trial. Legacy UID-hash lookups remain for same-UID compatibility. Existing UID-only claims cannot be mapped to verified contacts without a safe, approved migration; account deletion/recreation and contact changes still need licensed/staging abuse tests. The HMAC secret is mandatory for paid trials and failure is closed.

## Security and schema changes

Local migration [`20261009124434_group3_subscription_security_and_catalog.sql`](../../supabase/migrations/20261009124434_group3_subscription_security_and_catalog.sql):

- updates intended catalog values in integer centavos;
- stores an AES-256-GCM encrypted token for reconciliation, while keeping only a prefixed SHA-256 hash for lookup;
- adds token replacement/superseded state and service-role-only atomic purchase/trial RPCs;
- restores only service-role access needed by the Edge Functions to token/event tables; client table access remains denied;
- scrubs raw purchase tokens from historical RTDN payloads while preserving a matching hash.

The migration and all function changes are local only. Old rows that have only `token_hash` and no encrypted token cannot be processed by the new reconciliation job; a user-initiated restore/reverification or separately reviewed migration path is needed. Do not invent/backfill tokens or claim those subscriptions have been reconciled. The synthetic SQL test exercises the scrub expression using a fake token and transaction rollback; no real RTDN rows were inspected.

Required server secret names are `GOOGLE_PLAY_PACKAGE_NAME`, `GOOGLE_PLAY_CLIENT_EMAIL`, `GOOGLE_PLAY_PRIVATE_KEY`, `PLAY_TOKEN_ENCRYPTION_KEY`, `OWNER_TRIAL_CLAIM_SECRET`, `PLAY_RTDN_PUSH_AUDIENCE`, and `PLAY_RTDN_PUSH_SERVICE_ACCOUNT`, plus the existing Supabase URL/service-role settings. Values must be provisioned only in the approved server secret store; this report contains no secret values. Package name defaults to `com.aesprt.aquahub` in source, but production package/project alignment is unverified.

## Refund and financial reporting policy

The Owner order detail exposes each refund’s amount and `PENDING`, `COMPLETED`, or `FAILED` status. Pending amounts reserve refundable cents and remain an operational obligation; they do **not** reduce sales until a cash handoff/external refund is confirmed and recorded as completed. Completed refunds reduce the selected period’s net collection/revenue using `completed_at`; failed attempts do not reduce sales and remain visible for operator resolution. Failed reservations are released so a corrected/retried refund can be requested without exceeding the still-collected receipt amount.

Cancelled/rejected orders are excluded from sales aggregates. Their original payment rows remain immutable, and pending/completed/failed refunds remain in the per-order ledger. Customer history receives masked status. Current customer/rider/station analytics use the same order-attributed net adjustment; the refund is attributed to the original order dimensions and the period of refund completion, so a refund for an older order can produce a negative adjustment for that dimension in the current period. This is a periodized net-sales view, **not** lifetime customer spend, bank cash-flow statement, or balance-sheet liability statement.

There is no dedicated aggregate dashboard/report of pending refund liabilities or failed obligations. The order-level ledger makes individual obligations visible, but aggregate liability reconciliation remains a reporting gap and a release blocker for accounting completeness. Do not use the sales report alone to reconcile cash on hand or outstanding refund obligations.

## Verification executed

| Check | Result |
|---|---|
| Clean isolated schema replay | PASS through `20261009124434_group3_subscription_security_and_catalog`; last recorded migrations are `09124434`, `09112029`, `09104237`. Target was the localhost disposable Supabase DB, never the linked project. |
| PostgreSQL/pgTAP | PASS: 11 SQL files / 81 assertions, including 27 billing-specific assertions, transactional purchase/trial fixtures, token ownership/replacement/staleness, state cutoffs, and synthetic historical token redaction. All fixtures roll back. |
| Deno tests | PASS: fresh full-tree Deno 2.9.7 run, 23 passed / 0 failed, including five guest-order security tests. |
| Deno typecheck | PASS: all 10 `supabase/functions/*/index.ts` entrypoints. Remote type dependencies resolved in this run. |
| Supabase DB lint | Exit 0 with one warning: unused `p_payment_status` parameter in `public.import_legacy_order`. |
| Owner JVM | PASS: `:app:testDebugUnitTest`, 120 tests / 0 failures / 0 errors. |
| Customer JVM | PASS: `:app:testDebugUnitTest`, 21 tests / 0 failures / 0 errors. No Customer billing source was changed. |
| Owner connected instrumentation | PASS: 13 tests / 0 failures / 0 errors on Samsung SM-A366B, Android 16/API 36. Coverage includes Room refund aggregation, sync persistence/recovery, inventory rollback and selected payment/customer UI checks. This is not a complete online workflow, subscription UI, responsive screenshot, or Customer smoke test. Details: `TEST_MATRIX.md`. |
| Linked/production API and Play | NOT RUN: no hosted direct API, deployed function, Console review, license purchase, or production mutation. |

## Manual licensed test matrix required before release

Use Play license testers and an isolated staging organization only. Capture redacted evidence of provider state, database subscription/history, acknowledgement, and app entitlement on two devices. Verify all of the following against actual Play Billing Lab/license test behavior: Starter/Business/Pro monthly; each annual plan; introductory/free trial eligibility and expiry; delayed/pending completion and cancellation; successful and declined renewal; grace period; account hold; pause/resume if configured; user cancellation through paid expiry; expiration; refund/revocation; upgrade/downgrade with replacement token; restore after reinstall/on another device; purchase while app is backgrounded; network loss after checkout; duplicate callback/RTDN; missed RTDN recovered by scheduled reconciliation; retry after database/acknowledgement failure; and wrong owner/org token binding.

Do not initiate real purchases automatically. Do not mark these scenarios PASS based on mocked provider responses or local Deno/SQL tests.

## Release blockers

1. Identify the production-of-record and exact Owner/Customer release targets; linked Supabase is not proof.
2. Reconcile each of the nine local-only migration versions against the confirmed live target schema; the 54-file local replay does not resolve linked history or schema drift.
3. Configure and deploy Edge Functions and the atomic RPC migration only after a separate deployment authorization; schedule reconciliation, provision secrets, and decide how old unencrypted-token rows are safely reverified.
4. Validate the complete server entitlement/role/operation matrix and backward compatibility with already released binaries; do not restore unsafe table grants.
5. Run the licensed lifecycle matrix, verify Play Console products/prices/offer terms and RTDN configuration, and verify exact production package/service-account permissions.
6. Close verified-contact trial abuse cases, validate the locally implemented aggregate pending/failed refund-obligation report against the deployed API, execute device UI/regression tests, build a fresh release candidate, and complete external Play policy checks.

Official references: [Billing subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions), [subscriptionsv2 resource](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2), [Billing integration](https://developer.android.com/google/play/billing/integrate), and [RTDN reference](https://developer.android.com/google/play/billing/rtdn-reference).

## Group 4 direct-to-production evidence refresh — 2026-10-09

Read-only Play Console inspection confirms both applications are on Closed testing and Owner Production is inactive. Owner Alpha shows release label `11 (aq-1.0.6)`; the locally available signed Owner AAB is `aq-1.0.7` and embeds the Supabase ref used by current local builds, but is not hash/provenance-matched to the Play artifact. Customer's exact served artifact/version and endpoint were not obtained. Thus neither the deployed backend verifier nor the purchase package/project linkage is established from these records.

The current checked-out verifier, subscription RPCs and OIDC RTDN/reconciliation code remain local only. Fresh local replay now includes all 54 migrations and 12 SQL test files / 84 assertions, but this is not a Google API, Pub/Sub, Play Console or live entitlement test. No licensed or real purchase, subscription change, Play setting change, production function deployment, or live data mutation was performed. Billing remains **NOT VERIFIED / RELEASE-BLOCKING**; the lifecycle and product/base-plan matrix below remains outstanding.
