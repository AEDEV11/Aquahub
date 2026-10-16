# Notification outbox scheduler

Status: `OUTBOX_SCHEDULER_IMPLEMENTED_VALIDATION_PENDING`  
Last reviewed: 2026-10-04

## Boundary

Business transactions enqueue recipient-scoped rows in `public.notification_outbox`. Delivery-event triggers already do this without making the delivery transaction depend on FCM. The server-only chain is:

```text
delivery event / future business event
  -> notification_outbox
  -> trusted scheduler or worker
  -> dispatch-notification-outbox
  -> send-fcm-notification
  -> FCM HTTP v1
```

The database does not call FCM. The dispatcher claims rows with a database lease, and the sender applies bounded retry/backoff and terminal-failure state. Overlapping scheduler invocations are safe because claims are atomic and the sender is idempotent for `SENT` rows.

## Worker contract

Use `tools/supabase-migration/dispatch-notifications.mjs` from a trusted server-side scheduler. It requires these environment variables and never prints their values:

| Variable | Meaning |
|---|---|
| `SUPABASE_URL` | Linked project URL, unless an explicit function URL is supplied |
| `NOTIFICATION_DISPATCH_SECRET` | Same secret configured on both dispatcher and sender |
| `NOTIFICATION_DISPATCH_FUNCTION_URL` | Optional full dispatcher URL |
| `NOTIFICATION_DISPATCH_BATCH_SIZE` | Optional integer from 1 to 100; default 20 |
| `NOTIFICATION_DISPATCH_TIMEOUT_MS` | Optional 1–120 second timeout; default 30 seconds |

The worker is intentionally one-shot and mutating. A scheduler should invoke it approximately once per minute, with a request timeout shorter than the ten-minute stale-claim lease. Retries are safe; a timed-out run may leave a claim that is recovered by the next invocation.

Example trusted invocation:

```bash
node tools/supabase-migration/dispatch-notifications.mjs
```

The output contains only a run ID and numeric counters (`claimed`, `sent`, `retried`, `terminal`, `skipped`, and duration). A non-2xx response, malformed response, missing configuration, or timeout exits non-zero so the external scheduler can alert/retry.

## Scheduler options

Use one approved server-side mechanism: a hosted scheduler that can store the secret, a private CI/worker job, or an operator-controlled service with secret injection. Do not put the dispatch secret in Android, a public repository, a URL query string, or a client-callable Supabase function.

The current linked project has no usable `pg_cron`, `pg_net`, or Vault configuration, so no database-owned schedule is claimed. The trusted worker credentials are now configured and a hosted dispatcher smoke run passed with zero claimed rows. A recurring external schedule and a controlled device token are still required for live delivery evidence.

`MANUAL_ACTION_REQUIRED: CONFIGURE_OUTBOX_SCHEDULER`

Recommended Google Cloud Scheduler fields (configure from Google Cloud Console or Cloud Shell; do not install `gcloud` locally just for this):

| Field | Value |
|---|---|
| Name | `aquahub-notification-dispatch` |
| Region | `asia-southeast1` or the project’s existing scheduler region |
| Frequency | `*/1 * * * *` |
| Time zone | `Asia/Manila` |
| Target type | HTTP, or a private Cloud Run/worker wrapper that invokes the trusted worker |
| URL | `https://<supabase-project-ref>.supabase.co/functions/v1/dispatch-notification-outbox` for direct invocation, or the wrapper URL |
| Method | `POST` |
| Header | `x-notification-dispatch-secret: <value from Secret Manager; never put the value in source, Android, or the URL>` |
| Body | `{ "limit": 20 }` |
| Timeout | 30 seconds |
| Retry | Exponential backoff; maximum 3 attempts per run |

If the Cloud Scheduler UI cannot protect a custom secret header with the project’s secret-management policy, use a private Cloud Run wrapper with a Secret Manager-injected `NOTIFICATION_DISPATCH_SECRET`; the wrapper runs `dispatch-notifications.mjs` and is invoked by Scheduler with OIDC. Never make the Supabase dispatcher public and never place the dispatch secret in Android.

## Observability and failure policy

- The dispatcher creates a per-run ID and returns bounded counters; it never returns notification payloads, recipient data, tokens, or secrets.
- `409` sender responses count as terminal failures and are not immediately retried.
- `202` responses count as skipped because another claim won the race or the lease was no longer present.
- Other sender failures count as retryable; the outbox row controls backoff and becomes terminal after the configured attempt limit.
- `UNREGISTERED` removes only the specific device-token row. Payload errors do not remove tokens.
- FCM failure never changes the business delivery/payment/order state.

Live delivery remains `FCM_OUTBOX_IMPLEMENTED_LIVE_VALIDATION_PENDING` until an approved real device token, recurring scheduler, and success/retry/invalid-token checks are demonstrated.

## Configuration checklist

- [x] Store `NOTIFICATION_DISPATCH_SECRET` in the Edge Function and trusted worker secret stores.
- [x] Configure FCM HTTP v1 server credentials only in the sender's server secret store.
- [ ] Configure a one-minute external schedule for the worker.
- [ ] Register/verify a Supabase-owned device token on a controlled device.
- [ ] Exercise success, duplicate, transient failure, terminal failure, and invalid-token paths.
- [ ] Confirm no Android client can invoke the dispatcher or sender.
