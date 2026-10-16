# Subscription reconciliation plan

Status: Local verification code only. No production schedule, hosted invocation, real Play purchase, or Play Console mutation is authorized or verified.

## RTDN trust boundary

The local `play-rtdn` handler now verifies an OIDC bearer using Google-published RS256 certificates and checks issuer, exact configured audience membership, expiry/not-before/issued-at bounds, subject presence, and the configured verified service-account email before using the service-role client. Cert-fetch outage is reported as retryable 503; invalid identity/signature is 401. Unknown `kid` triggers a key refresh and then 401 if absent. The function endpoints set `verify_jwt=false` because they validate Google OIDC identity themselves; this is safe only if the handler's verifier and exact environment configuration remain present.

For Pub/Sub authenticated push, configure the subscription to attach an OIDC token for a dedicated service account and the exact HTTPS endpoint audience. Grant the Pub/Sub service agent only the token-creator capability needed to mint that identity. Keep the push audience and allowed service-account email in secret/config management; do not reuse Supabase service-role credentials as the HTTP bearer. Validate Google's token signature/issuer/audience/identity per the official [Pub/Sub push authentication](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions) guidance. RTDN payload content is only a signal: the handler re-queries Google Play before applying entitlement state.

Local `reconcile-play-subscriptions` uses the same Google OIDC validation with dedicated `PLAY_RECONCILIATION_PUSH_AUDIENCE` and `PLAY_RECONCILIATION_PUSH_SERVICE_ACCOUNT`; the privileged Supabase key remains server-side. It is not deployed or scheduled. Do not expose a generic unauthenticated/manual scheduler endpoint.

## Scheduled reconciliation proposal

After staging exists, use a least-privilege Cloud Scheduler job with OIDC identity restricted to the reconciliation endpoint, exact audience, bounded timeout, modest frequency (initially daily), and a single concurrent invocation. The local handler caps one run at 100 rows and processes idempotently; measure Google API quota/cost and backlog in staging before choosing higher frequency. Alert on non-2xx, repeated per-token failures, partial runs/backlog, and stale `updated_at`; retry with bounded exponential backoff and jitter. Do not log token plaintext or provider response bodies. Schedule creation and any production frequency/secret configuration require separate approval.

## Lifecycle and recovery matrix

| Condition | Reconciliation policy |
|---|---|
| Active, canceled-but-entitled, grace, account hold, expired, revoked | Re-fetch authoritative `subscriptionsv2` state; persist the provider state and entitlement cutoff atomically; never infer from RTDN alone. |
| Pending | Do not activate. Keep auditable event; retry only after provider state can change. |
| Duplicate or out-of-order RTDN | Deduplicate message IDs and enforce provider event ordering/version in the DB; stale events cannot overwrite newer verified state. |
| Purchase token replaced | Verify linked token lineage and atomically supersede old binding; reject cross-organization reuse. |
| Reconciliation API outage | Retain last verified entitlement only until stored provider cutoff; bounded retry and alert. Do not extend expiry locally. |
| Historical nonrecoverable token hash | It cannot be used to re-query Google. Preserve current recorded entitlement only to its existing verified cutoff; request user restoration with current Play purchase token/account identity. Do not fabricate a token or grant an indefinite plan. |
| Legacy UID-only trial claim | Do not automatically grant another trial or revoke a valid account. Use a reviewed identity-link process backed by verified account evidence; preserve claim audit and deny duplicate claims until resolved. |
| Reinstall/device switch | Restore purchases through Play account query, re-submit current token to trusted verifier, and match the verified obfuscated Owner account ID/organization binding. No local preference grants access. |

## Required staging acceptance

Run signed synthetic OIDC requests for valid/wrong issuer/audience/email/signature, expired/future claims, unknown `kid`, cert outage, unauthenticated calls, duplicate Pub/Sub message, delayed/out-of-order event, provider pending/error, token reuse across orgs, and reconciliation retry. Assert both HTTP response and persisted event/subscription/entitlement state. Then perform Google Play license tester purchase, pending payment, cancellation, renewal, refund/revocation, account hold/grace and restore scenarios. Local verifier tests: 7 passed; licensed tests and hosted RTDN transport: **BLOCKED**.
