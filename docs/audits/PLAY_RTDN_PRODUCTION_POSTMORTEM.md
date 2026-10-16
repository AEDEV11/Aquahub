# AquaHub Google Play RTDN Production Postmortem

**Audit captured:** 2026-10-11 07:11 UTC  
**Scope:** Read-only production review of Supabase project `pfkxrggexueltytxtqil`, Google Cloud project `aquahub-506411`, Edge Function `play-rtdn`, and subscription/billing implementation. No production records, Pub/Sub settings, or deployed code were changed.

## Executive assessment

The retry storm has stopped in the latest observed window. `play-rtdn` v26 is ACTIVE; all 39 function requests in the most recent 15 minutes returned HTTP 204, and the claim RPC returned HTTP 200 in its bounded log sample. The Pub/Sub subscription is in authenticated Push mode and currently reports zero unacknowledged messages. The database contains 44 durably completed RTDN event rows with no active or expired leases. Completion does not mean every row applied an entitlement: the token-binding aggregate below identifies unbound and superseded cases that were safely not applied.

The integration is **operationally healthy and ready for continued production processing**. This audit does not prove every subscription lifecycle state from live production examples: the current dataset has one annual PRO subscription in EXPIRED state, and no current monthly, active, grace-period, or suspended record. Exact current Supabase quota balances and the effective PostgREST exposed-schema allowlist were not available through the connected read-only interfaces; both are follow-ups below.

## Root cause and repair

The original handler called `POST /rest/v1/rpc/claim_play_rtdn_event`, while the implementation existed only in `app_private`. PostgREST could not resolve that RPC from the exposed `public` schema, so claims failed with HTTP 404. The Edge Function returned 503, causing Pub/Sub to retry and generating a high volume of function and log activity.

The previous claim implementation also set `processing_at` during the initial insert and then tried to claim only rows with an empty or expired lease, preventing the first delivery from acquiring its claim.

The deployed repair provides a narrow `public.claim_play_rtdn_event` wrapper, grants its execution only to `service_role`, and keeps the implementation in `app_private`. The claim operation uses a row lock and a unique lease token. Finalize/release operations are conditioned on that token so an expired worker cannot complete or clear a newer worker's lease. The handler applies verified subscription state, retries Google Play acknowledgement if needed, and durably finalizes the event before returning 204. Busy and recoverable failures return non-2xx for Pub/Sub retry.

## Production health evidence

| Evidence | Observation |
|---|---|
| Function deployment | `play-rtdn` is ACTIVE, version 26, with `verify_jwt=false`; custom Google OIDC verification is enforced by the handler. |
| Latest function window | 2026-10-11 06:53:31–07:08:31 UTC: 39 invocations, all v26 HTTP 204; 0 HTTP 4xx and 0 HTTP 5xx. |
| Latest execution time | p95 approximately 3.70 seconds; maximum approximately 3.93 seconds. This sample does not indicate a timeout concern. |
| Claim RPC | 44 HTTP 200 requests in the corresponding narrow log sample. Function and RPC counts are not directly correlated and should not be treated as one-to-one message counts. |
| Earlier incident window | In the six-hour filtered view, v25 produced 18,382 HTTP 503 responses and the claim RPC produced 18,390 HTTP 404 responses across the 01:00–04:30 UTC buckets. These are pre-fix failures. No claim RPC 404s appeared after the final 04:30 UTC bucket in that query. |
| Post-fix errors | No v26 503s or claim RPC 404s in the latest 15-minute sample. This confirms the observed retry storm stopped; it cannot guarantee future errors will never occur. |
| Duplicate evidence | The database has one row per message ID and zero duplicate message IDs. Pub/Sub is at-least-once; the available logs do not include a safe message-ID correlation field, so the number of duplicate delivery attempts cannot be measured from this sample. |

Log queries were limited to exact function/RPC paths and bounded 15-minute and six-hour UTC windows. They were not broad project log scans. Supabase's field reference describes `function_edge_logs` status and execution-time fields used for this review: [Supabase log field reference](https://supabase.com/docs/guides/observability/log-field-reference).

## Pub/Sub delivery health

The Google Cloud Console showed `aquahub-play-rtdn-push` active in **Push** mode. Its endpoint is `https://pfkxrggexueltytxtqil.supabase.co/functions/v1/play-rtdn`; authenticated OIDC is enabled, and the configured audience matches that endpoint. The service-account identity was checked against the recorded configuration and is redacted here. The deployed function verifies the Google signature, issuer, expiry/time claims, verified email, configured audience, and expected service-account email. The observed successful 204 deliveries are consistent with authentication passing. Supabase secret values were not read or exposed.

Current delivery metrics showed **zero unacknowledged messages** and current oldest-unacknowledged age of zero. The one-hour chart included a prior maximum age of approximately 6.948 days; this was historical backlog, not the current age. The subscription currently has 14-day message retention, a 60-second acknowledgement deadline, no dead-letter policy, and its existing retry setting is “retry immediately.” The chart's current acknowledgement counter was zero and publish-to-ack delta briefly went negative, so its sampled metrics do not support a precise acknowledgement percentage. The current zero backlog and successful handler responses are stronger health signals than that delayed counter.

No messages were inspected individually, acknowledged, replayed, sought, purged, or deleted.

## Billing and subscription integrity

### Read-only database checks

| Check | Before rollout baseline | Current observation | Result |
|---|---:|---:|---|
| Subscription rows | 1 | 1 | Stable |
| Subscription history rows | 11 | 12 | One new history entry; no unexpected count growth |
| Google Play token rows | 3 | 3 | Stable |
| Encrypted token rows | — | 3 of 3 | All present; no invalid ciphertext format/version detected |
| Billing event rows | 0 | 44 | 44 processed; 0 incomplete, 0 active leases, 0 expired leases, 0 unclaimed |
| Plan feature rows | 89 | 89 | Stable |
| Plan limit rows | 22 | 22 | Stable |
| Raw `purchaseToken` in event payloads | — | 0 | No raw token field found |
| Invalid token-hash format | — | 0 | Token hashes meet the expected 64-hex-character format |

### Event-to-subscription binding

A second aggregate joined event token hashes to the token-binding table without returning any token values:

| Event/token relationship | Events | Distinct token hashes | Interpretation |
|---|---:|---:|---|
| Event has organization and matching current token binding | 9 | 1 | Bound events were eligible for verified subscription application. |
| Event hash maps to a superseded token | 18 | 2 | Handler records these as stale and does not reapply old subscription state. |
| Event hash has no token row and no organization | 15 | 3 | Durable notifications were acknowledged without organization assignment or entitlement application. Event times span 2026-10-04 through 2026-10-10. |

The last group is intentionally fail-closed: RTDN alone does not establish ownership for a first/unbound purchase. The data does not show whether those three hashes were removed historical tokens or purchases that never completed authenticated owner verification. No raw token was read. This is an entitlement-completeness follow-up, not evidence that an entitlement was corrupted.

The one current Google Play subscription is **PRO / ANNUAL / EXPIRED**. The integrity query found no missing plan or organization, invalid billing cycle, inconsistent grace-period cutoff, or inverted current-period range. These are structural consistency checks; the current data is not a representative live sample of every lifecycle state.

### Flow review

- **Purchases and restore:** `verify-play-purchase` authenticates the owner, verifies the purchase against Google Play, resolves organization ownership from verified membership/token bindings, hashes token identifiers, and stores encrypted purchase tokens. The owner app refreshes its Supabase entitlement snapshot after server verification.
- **Monthly and annual plans:** server product/base-plan mapping covers monthly and yearly Starter, Business, and Pro products. The current production subscription sample is annual; monthly processing is supported by implementation but was not demonstrated by a current row.
- **Renewal, cancellation, expiration, grace, and suspension:** the server derives state from Google Play's current subscription response and applies the supported states through the privileged subscription RPC. The database application rejects invalid state/plan mappings and invalid grace cutoffs. Production currently has no active, grace, or suspended row to confirm those states end-to-end.
- **Out-of-order notifications:** the handler re-verifies current state with Google Play; the SQL application guards provider event time/version, current organization subscription, and linked-token ownership, and rejects stale or unbound token transitions.
- **Acknowledgement and partial failure:** subscription state is applied before Google Play acknowledgement. If acknowledgement or event finalization fails, the handler returns a retryable response. The application RPC is idempotent, so retries after partial completion do not reapply a duplicate entitlement transition.
- **Duplicates and concurrency:** the database uses message-ID uniqueness, row-locked claims, lease tokens, and token/organization locks. The regression suite includes first delivery, processed duplicate, concurrent duplicate, expired lease/stale worker, transient verification/database failures, acknowledgement failure after apply, event-finalization failure, and persistence failure cases. Tests were inspected but not executed during this read-only production audit.
- **Organization isolation:** a first RTDN for an unbound token does not guess an organization. Existing or linked tokens must match the current organization's verified binding; cross-organization token conflicts fail closed.
- **Unbound notification recovery:** unbound notifications remain durably recorded, but do not create an entitlement until authenticated owner verification/restore establishes a valid organization binding. The 15 events above need reconciliation against authenticated purchase/linked-token evidence before concluding that no subscription state is missing. No live Play lookup or entitlement mutation was performed in this audit.

The AquaHub Owner app reads the refreshed entitlement state and resolves access locally from its Room-backed subscription/plan repositories. The visible one-second subscription-gate recheck uses those local repositories; the gate refreshes the remote subscription once when the active station/owner context changes. This local check is not evidence of a one-second Supabase polling loop.

## Security findings

- Google OIDC is enforced in the function despite Supabase `verify_jwt=false`. That setting is necessary for Pub/Sub's Google-issued identity token and is protected by application-level signature and claim checks.
- Production privilege checks show `public.claim_play_rtdn_event` and `public.apply_verified_play_subscription` are executable by `service_role`, but not by `anon` or `authenticated`. The private claim implementation itself is not executable by those client roles.
- RLS is enabled on subscriptions, subscription history, Google Play token/event tables, plan features, and plan limits. Client roles have no direct SELECT privilege on `google_play_billing_events` or `google_play_subscription_tokens`; billing event, token, and history tables have no client policies, which denies access under RLS.
- Purchase tokens are encrypted in the token table; event payloads retain a hash rather than the raw token. Handler code avoids logging tokens/provider response bodies.
- No security weakening or production database change was observed in this audit.

## Supabase usage and cost audit

The organization is on the **Free** tier and the project reports `ACTIVE_HEALTHY`. The observed retry storm explains substantial avoidable function and log activity: about 18.4 thousand failing function calls and 18.4 thousand failing claim-RPC requests appeared in the filtered incident window. In the latest 15-minute window, function traffic is 39 calls (about 2.6/minute) with no failures. The Pub/Sub backlog is now zero, so there is no current evidence of an ongoing retry loop.

Exact current invocation, log-ingestion, and Log Query quota balances were not available through the Supabase project/log APIs used for this audit. The Supabase dashboard browser redirected to sign-in, so current quota headroom and the original quota-exceeded counters could not be verified. No cost upgrade was made or recommended as a prerequisite.

No recurring Supabase polling path was found in the reviewed RTDN handler. The reconciliation function uses bounded batches and authenticated OIDC, but its production schedule/trigger and invocation history were not available in this read-only view, so its contribution to function quota cannot be quantified here.

## Remaining issues by severity

### Critical

- None found in the reviewed RTDN path or current production evidence.

### High

- None observed in the latest production health window.

### Medium

1. **Exact quota headroom is unverified.** The Free-tier project has a demonstrated history of invocation/log quota exhaustion, but current quota counters could not be obtained. Continue short-window monitoring and review the Usage page when authenticated dashboard access is available.
2. **PostgREST schema exposure needs confirmation.** The database grants `authenticated` `USAGE` on `app_private`; the RTDN privileged functions remain non-executable by client roles, and this audit did not establish that `app_private` is exposed in PostgREST's effective schema allowlist. Verify in Supabase API settings that `app_private` remains excluded. Do not grant broader access to resolve this.
3. **No dead-letter policy and immediate retry remain.** A future poison message or persistent downstream failure could retry until retention and consume quota. Consider a bounded exponential retry policy and a dead-letter queue with an approved, monitored recovery process; do not change Pub/Sub settings without separate authorization.
4. **Unbound billing events need an ownership-safe reconciliation review.** Fifteen completed events across three token hashes have no token row or organization binding; the handler correctly avoided granting access without ownership evidence. Reconcile only against a current Google Play verification plus authenticated owner/linked-predecessor proof. Do not assign an organization or change entitlement state from the RTDN payload alone; any live state repair requires separate approval.

### Low

1. **Lifecycle coverage is mostly code/test evidence.** Current production data contains one annual expired subscription, not live examples of monthly, active, grace, suspended, cancellation, or payment-failure transitions. Do not infer all-state production behavior from the current row counts.
2. **Duplicate delivery rate is not directly observable.** Message IDs are not logged for correlation and the unique event table intentionally stores only one row per ID. Add aggregate/sampled redelivery telemetry only if it can avoid logging tokens or other sensitive data.
3. **Pub/Sub acknowledgement-rate metric is delayed/inconsistent.** Current backlog is zero, but the sampled chart counters do not provide a reliable exact ack rate. Use bounded metric windows and correlate with successful function responses.

## Recommended optimizations

1. Keep monitoring on exact `play-rtdn` and claim-RPC paths with short windows; alert on any sustained 401/403/5xx or renewed RPC 404, and include p95/max execution time.
2. Add a bounded retry/backoff and dead-letter design after separate approval, preserving pending messages and documenting controlled replay/reconciliation before enabling it.
3. Review current Supabase Usage counters after dashboard access is restored. Confirm whether the prior quota incident has cleared and establish alerts before further usage changes.
4. Confirm the effective API schema allowlist excludes `app_private`; leave all service-role and RLS permissions unchanged unless a separately reviewed change is approved.
5. Preserve aggregate integrity checks for incomplete events, expired leases, duplicate IDs, token ciphertext/hash formats, subscription status/period consistency, and plan/organization references.
6. If event observability is extended, log only a non-sensitive outcome/category and bounded timing; never log purchase tokens, authorization headers, full Pub/Sub bodies, or provider response bodies.

## Readiness conclusion

The production RTDN integration is **stable in the observed window**: the v26 handler is active, recent pushes completed with HTTP 204, the claim RPC is succeeding, RTDN events are durably recorded, and the Pub/Sub backlog is empty. There is no evidence that the previous retry storm continues. Entitlement completeness for three currently unbound token hashes remains unresolved and requires ownership-safe reconciliation.

The RTDN-specific authentication, RPC permissions, token confidentiality, idempotency, and organization checks passed the available read-only checks. Overall readiness is **ready for continued delivery with billing follow-up**: exact Supabase quota balances and effective PostgREST schema exposure were unavailable, the current production sample cannot validate every billing lifecycle state, and three unbound token hashes need an ownership-safe reconciliation review. No production modifications were made.
