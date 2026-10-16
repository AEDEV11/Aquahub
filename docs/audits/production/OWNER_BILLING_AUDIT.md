# Owner Billing Audit

## Phase 3 reconciliation disposition — 2026-10-12

**BILL-01 remains BLOCKED and NO-GO.** The prior targeted, read-only production aggregate remains the latest billing evidence: three unbound token-hash groups and 15 related RTDN events; all 44/44 events had been processed and RTDN v26 was active. Processing status does not verify purchase ownership. No raw token/hash is included here; no Google Play Developer API receipt query or new token binding was done in Phase 3. No subscription, billing record or entitlement changed.

For each hash, owner-provided evidence must include a current Google Play Developer API response for the correct package, product/base plan, state/expiry/acknowledgement and linked-token chain; independent authenticated AquaHub user and organization membership proof; duplicate/reuse check; and a two-person reviewed, redacted decision. If ownership is ambiguous, retain the unbound state. Any production repair requires a separate change record and explicit target-specific authorization.

Subscription restore, pending/renewal/grace/hold/refund/revocation behavior still requires Play license-tester evidence. No real purchase or live entitlement test occurred. Do not assign a token based on RTDN history, app email, timing or plausible organization. The full secure reconciliation procedure below remains unchanged.


## Remediation update — 2026-10-11

**BILL-01 remains BLOCKED.** No purchase token, token hash, subscription, organization binding or entitlement was changed. The historical evidence remains three unbound purchase-token hashes (15 completed RTDN events); the 44/44 processed-event aggregate and RTDN v26 only show queue handling, not entitlement ownership. No broad logs query or Pub/Sub change was made.

A fresh, targeted read-only production SQL aggregate joined RTDN event hashes to the token table without returning any hash or token values: **3 unbound hash groups, 15 related events, 0 unprocessed events, and all 3 hashes still lacking a token row**. This confirms they remain unresolved; it does not establish whether any organization is entitled. The SQL was SELECT-only.

### Secure reconciliation procedure (read-only first)

1. Retrieve each candidate by a redacted token hash through an owner-authorized, narrowly scoped read-only workflow. Never expose or log the raw token; do not use broad log queries.
2. Query Google Play Developer API for current state, package/product/base plan, purchase time, expiry, acknowledgement, order ID and linked purchase token. Confirm the token is valid and current for the correct package. Capture a redacted, dated evidence record.
3. Independently verify the claimant's authenticated AquaHub profile and active organization membership. Require a unique proof linking the Play purchase to that owner/organization (such as a verified prior purchase binding or validated linked-predecessor chain); do not infer ownership from RTDN payload, email, receipt description, phone or timing alone.
4. Cross-check that the token hash is not already bound to another organization and review duplicate/out-of-order RTDN history for the same token chain. Two-person review records the evidence and decision for each hash.
5. If any identity, purchase state, or chain is ambiguous, leave it unbound and escalate to the owner/Google Play support. Do not assign an organization as a best guess.
6. If a verified repair is still required, prepare a separate change record with target organization, before/after entitlement, idempotency key, compensating action and reviewer. Apply only after separate explicit production approval, then verify server entitlement and cross-device restoration read-only.

No reconciliation step above was executed against production in this pass. Console product/base-plan, license-test purchase lifecycle and current entitlement cross-check remain manual release gates.

## Current evidence

Supabase live plan catalog reports: Free ₱0; Starter ₱299 monthly / ₱2,870.40 annual; Business ₱699 monthly / ₱6,710.40 annual; Pro ₱1,299 monthly / ₱12,470.40 annual. Paid plans show a 30-day trial. Amounts are integer centavos in backend catalog. Source product IDs are `aquahub_starter`, `aquahub_business`, `aquahub_pro`, with `monthly` and `yearly` base plans. These backend values do not prove Play Console price/offers are synchronized.

Billing Library **9.1.0** is in source and is current against the Play deadline reviewed for this audit. Server purchase verification and RTDN processing exist. Actual Play Console product/base-plan/offer setup, license testers, subscription restore, pending purchase and grace/account-hold flows were not accessible or exercised.

## RTDN health and entitlement concern

Read-only production metadata: `play-rtdn` is active v26; live migration `20261011140000_play_rtdn_atomic_claims` exists. RTDN table aggregate: 44 total, 44 processed, 0 pending, 0 claimed-not-processed, 0 stale claims. No broad logs were queried. This aggregate does not prove current Pub/Sub backlog, endpoint availability, or entitlement state.

Historical postmortem `docs/audits/PLAY_RTDN_PRODUCTION_POSTMORTEM.md:64-81,114-116` identified three completed unbound purchase-token hashes. Their ownership-safe reconciliation is not evidenced. **BILL-01 is a P1 release gate** until each is resolved against current Play receipt and organization identity without reusing or transferring tokens.

## Lifecycle coverage matrix

| Requirement | Status | Evidence/gap |
|---|---|---|
| Free plan/basic saleable inventory | IMPLEMENTED | Migration `20261010100000_free_plan_basic_inventory.sql`; production catalog Free ₱0. |
| Product IDs and base plans | PARTIAL | Present in source; Play Console mapping inaccessible. |
| Local billing state trusted? | PASS at source-design level | Client invokes server verification; still require runtime proof. |
| Upgrade/downgrade | NOT VERIFIED | No license-tester transaction. |
| Pending/cancelled purchase | NOT VERIFIED | No Play flow run. |
| Acknowledge purchase | NOT VERIFIED | Source path exists; end-to-end acknowledgement not evidenced. |
| Grace period/account hold/recovery | NOT VERIFIED | Console settings and Play events inaccessible. |
| Renewal/expiry/refund/revocation | PARTIAL | RTDN path exists; no Play test event or entitlement regression. |
| Restore/reinstall/device change | NOT VERIFIED | No Play restore test. |
| Token reuse/cross-organization binding | NOT VERIFIED | Requires disposable/test Play identities and backend authorization tests. |
| Duplicate/out-of-order RTDN | PARTIAL | v26 atomic claim migration and backend tests; no live replay test. |
| Play pricing/trials/offers | NOT VERIFIED | Supabase catalog only; no Play Console evidence. |

## Security and release requirements

Never grant entitlement solely from local BillingClient state. Verify purchase server-side, bind token to immutable Play account/organization, reject token reuse, process duplicate/out-of-order events idempotently, acknowledge according to verified state, and recover all subscription states across devices. Do not run real purchase tests or mutate live entitlement during this audit. Use Play license testers and internal test track for final lifecycle validation.

Before rollout, provide screenshots/exports of Play Console product IDs, base plans, price regions, offer/trial eligibility, purchase tester accounts, RTDN topic/subscription delivery health, App Integrity settings, and current entitlement reconciliation. Keep tokens and personal data redacted.
