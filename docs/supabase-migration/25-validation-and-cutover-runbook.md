# Supabase validation and cutover runbook

Status: `IMPLEMENTED — LIVE VALIDATION PENDING`  
Last reviewed: 2026-10-04

## Configuration presence

Run without printing values:

```bash
node tools/supabase-migration/check-validation-config.mjs
```

Required names:

| Area | Names | Status in this environment |
|---|---|---|
| Supabase fixtures | `SUPABASE_URL`, `SUPABASE_SECRET_KEY`/`SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_PUBLISHABLE_KEY`/`SUPABASE_ANON_KEY` | Available; authenticated disposable matrix passed |
| Google Play verification | `GOOGLE_PLAY_CLIENT_EMAIL`, `GOOGLE_PLAY_PRIVATE_KEY`, `GOOGLE_PLAY_PACKAGE_NAME` | Available locally and synchronized to hosted functions; real purchase token pending |
| FCM HTTP v1 | `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`, `NOTIFICATION_DISPATCH_SECRET` | Available and synchronized; live device delivery pending |
| Play RTDN OIDC | `PLAY_RTDN_PUSH_AUDIENCE`, `PLAY_RTDN_PUSH_SERVICE_ACCOUNT` | Available and synchronized; live Pub/Sub delivery pending |
| Outbox scheduler auth | `SUPABASE_URL`, `NOTIFICATION_DISPATCH_SECRET` | Available; hosted dispatcher smoke passed |

Never commit service-account JSON, service-role keys, OAuth tokens, FCM access tokens, or purchase tokens.

Order validation additionally requires `SUPABASE_ORDER_FIXTURES` pointing to an approved disposable fixture JSON. If it is absent, `validate-backend.mjs` reports `ORDER_AUTHENTICATED_VALIDATION_BLOCKED_BY_TEST_SETUP` instead of invoking the harness with an invalid `file://` or empty path.

## Authenticated validation sequence

Run the consolidated sequence with disposable credentials and clean up fixtures after every run:

```bash
node tools/supabase-migration/validate-backend.mjs
```

The sequence invokes `validate-orders.mjs`, `validate-staff-settings.mjs`, `validate-payments.mjs` when that harness exists, and `validate-subscriptions.mjs`. A missing domain harness is reported as `SKIPPED_NOT_PRESENT`, not as a pass.

The subscription harness uses service-role only to create/delete disposable fixtures. Entitlement and subscription requests use a normal user access token. Missing fixtures are reported as `SUBSCRIPTIONS_AUTHENTICATED_VALIDATION_BLOCKED_BY_TEST_SETUP`, never as a pass.

Google Play cases requiring a real or controlled purchase token remain `BLOCKED_BY_GOOGLE_PLAY_TEST_CONFIGURATION`. FCM device delivery remains `FCM_LIVE_VALIDATION_BLOCKED_BY_CONFIGURATION` until an approved device token and server configuration exist.

## Secret matrix

| Function | Required names | Purpose | Current status |
|---|---|---|---|
| `verify-play-purchase` | `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `GOOGLE_PLAY_CLIENT_EMAIL`, `GOOGLE_PLAY_PRIVATE_KEY`, `GOOGLE_PLAY_PACKAGE_NAME` | Authenticated purchase verification and acknowledgement | Deployed/configured; real purchase token validation pending |
| `subscription-trial` | `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` | Authenticated trial provisioning | Deployed |
| `play-rtdn` | `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `GOOGLE_PLAY_*`, `PLAY_RTDN_PUSH_AUDIENCE`, `PLAY_RTDN_PUSH_SERVICE_ACCOUNT` | Pub/Sub OIDC validation and Google re-verification | Deployed/configured; live Pub/Sub delivery pending |
| `send-fcm-notification` | `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`, `NOTIFICATION_DISPATCH_SECRET` | Server-only FCM HTTP v1 delivery | Deployed/configured; real device-token delivery pending |
| `dispatch-notification-outbox` | `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, `NOTIFICATION_DISPATCH_SECRET` | Claims due outbox rows and invokes FCM sender | Deployed/configured; recurring scheduler and device delivery pending |
| `staff-admin` | `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` | Trusted Auth Admin staff operations | Deployed |

Every function fails closed when required configuration is absent and never logs secret values.

## Play RTDN cutover

Firebase currently receives Pub/Sub topic `play-subscription-notifications`. The legacy function validates the package, extracts `subscriptionId` and `purchaseToken`, requires a previously bound token, re-queries Android Publisher, then writes Firestore subscription state and a message-id event record.

The Supabase replacement is `play-rtdn`. It expects a Pub/Sub push envelope and verifies the Google-issued OIDC bearer token using Google JWKS. The configured audience and service account email must match the Pub/Sub push subscription. The payload is only a change signal: the function re-verifies the token through Android Publisher before updating Supabase.

RTDN processing is idempotent by Pub/Sub `messageId`, ignores unknown package/token records, records meaningful subscription history, and rejects stale event timestamps so older messages cannot regress newer state. Firebase RTDN remains `RTDN_CUTOVER_PENDING_LIVE_VALIDATION` until Play Console/Pub/Sub delivery, authentication, retry, duplicate, and state-update tests pass.

## Notification outbox

Delivery events enqueue `notification_outbox` rows. `dispatch-notification-outbox` uses row claims and leases, while `send-fcm-notification` classifies invalid tokens separately from transient failures. Only the specific invalid token row is removed. FCM success/failure cannot roll back delivery state.

Production scheduling still needs a supported external Cron/worker invocation; the linked project has no `pg_cron`, `pg_net`, or Vault extension available for a database-owned scheduler. The hosted dispatcher smoke has passed with zero queued rows, but the pipeline remains `FCM_OUTBOX_IMPLEMENTED_LIVE_VALIDATION_PENDING` until a recurring scheduler and controlled device token are exercised.

The implementation-side scheduler is now `tools/supabase-migration/dispatch-notifications.mjs`. It is a one-shot external worker with a bounded timeout and no dry-run mode because invocation may claim and deliver rows. It calls only the secret-protected dispatcher, never the sender directly, and exits non-zero on configuration, HTTP, timeout, or malformed-response failures. See `26-notification-scheduler.md` for the operational contract and safe overlap behavior.

## Firebase bridge removal prerequisites

Remove the compatibility bridge only after:

1. authenticated order validation passes;
2. order Functions and legacy order listeners are retired;
3. remaining Firestore business runtime is migrated or imported;
4. staff legacy Functions have passed authenticated observation and are retired;
5. Storage no longer needs Firebase Auth-backed access;
6. no business Function requires Firebase Auth tokens;
7. Play RTDN is live on Supabase;
8. FCM outbox delivery is configured and device-tested.

Billing identity, new storage writes, Supabase device-token ownership, and active staff writes are not valid reasons to retain Firebase Auth.
