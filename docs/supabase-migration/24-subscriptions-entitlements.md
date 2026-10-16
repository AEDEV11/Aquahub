# Subscriptions and entitlements migration

Status: `SUBSCRIPTIONS_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`

This is an implementation checkpoint, not a claim that a real Google Play purchase or authenticated hosted fixture has been validated. No Firebase source data, purchase token, or Storage object has been deleted.

## Authority and discovered billing contract

The owner app uses Google Play Billing Library subscription products:

| Play product | Base plans | Supabase plan | Billing cycle |
|---|---|---|---|
| `aquahub_starter` | `monthly`, `yearly` | `STARTER` | `MONTHLY`, `ANNUAL` |
| `aquahub_business` | `monthly`, `yearly` | `BUSINESS` | `MONTHLY`, `ANNUAL` |
| `aquahub_pro` | `monthly`, `yearly` | `PRO` | `MONTHLY`, `ANNUAL` |

The local model also contains `FREE`. No one-time purchase product was found. The existing owner trial flow supports the current plan choices and is now backed by the Supabase `subscription-trial` Edge Function.

Migration `20261004220000_seed_subscription_catalog.sql` seeds the canonical `FREE`, `STARTER`, `BUSINESS`, and `PRO` rows plus their feature/limit metadata in Supabase. This is required because the Android Room seeder is only an offline cache; `subscription-trial` rejects an unconfigured plan and cannot activate a trial from local plan data alone. The `BUSINESS` trial is persisted as `subscriptions.status = TRIALING` with `trial_started_at` and `trial_ends_at` set by the server.

The authority chain is:

```text
Google Play purchase
  -> Android BillingClient purchase token
  -> verify-play-purchase Edge Function
  -> public.subscriptions / subscription_history
  -> get_effective_entitlements()
  -> Room cache for offline display and feature-gate continuity
```

Android never grants a plan, paid status, expiry, or entitlement. The server verifies the product/base plan/package, purchase status, acknowledgement state, expiry, and token ownership before updating Supabase.

## Supabase schema

The existing normalized tables remain authoritative:

- `subscription_plans`, `plan_features`, and `plan_limits` describe the catalog.
- `subscriptions` stores one current organization subscription and retains `firebase_id` only for historical mapping.
- `subscription_history` records state changes.
- `google_play_subscription_tokens` stores a SHA-256 token hash and organization binding; the raw token is not persisted.
- `google_play_billing_events` remains available for idempotent provider-event handling.
- `owner_trial_claims` stores the hashed Supabase owner identity for one-time trial eligibility.

Migration `20261004040946_subscriptions_entitlements_foundation.sql` adds the missing `GRACE_PERIOD` and `SUSPENDED` enum values, `last_verified_at`, and the server-derived `get_effective_entitlements()` resolver. It is `SECURITY INVOKER`; only authenticated callers can execute it, and subscription writes remain server-only.

The resolver derives the organization from the caller's active membership, selects the preferred/current subscription, and returns the selected plan's features and limits. `BASIC_INVENTORY` is therefore sourced from `plan_features` through Supabase, never from a client setting or debug grant. The Android Room feature/limit rows are refreshed from this resolver after a successful online subscription refresh and remain a cache only.

The owner domain model currently names these feature entitlements: `STATION_MANAGEMENT`, `DASHBOARD`, `ORDER_MANAGEMENT`, `CUSTOMER_MANAGEMENT`, `PRODUCT_MANAGEMENT`, `BASIC_INVENTORY`, `ADVANCED_INVENTORY`, `BASIC_CONTAINER_TRACKING`, `ADVANCED_CONTAINER_TRACKING`, `RIDER_MANAGEMENT`, `DELIVERY_MANAGEMENT`, `MOBILE_APP_ORDERING` (including the legacy `QR_ORDERING` alias), `QR_ANALYTICS`, `BASIC_REPORTS`, `ADVANCED_REPORTS`, `ADVANCED_ANALYTICS`, `CUSTOMER_INSIGHTS`, `ACCOUNTS_RECEIVABLE`, `PROMOTIONS`, `EXPENSE_TRACKING`, `SCHEDULED_DELIVERIES`, `STAFF_ACCOUNTS`, `MULTI_BRANCH`, `MULTI_STATION`, `DATA_EXPORT`, `EXPORT_CSV`, `EXPORT_EXCEL`, `EXPORT_PDF`, `CONSOLIDATED_REPORTS`, `STATION_COMPARISON`, `DELIVERY_ANALYTICS`, and `PRIORITY_SUPPORT`. Limits currently named by the app are `BRANCH_COUNT`, `STATION_COUNT`, `RIDER_COUNT`, `STAFF_COUNT`, `MONTHLY_ORDER_COUNT`, `CUSTOMER_COUNT`, `PRODUCT_COUNT`, and `QR_CODE_COUNT`. No separate entitlement source was found.

## Android billing cutover

`GooglePlayBillingRepositoryImpl` now:

1. Queries/restores owned Google Play purchases through BillingClient.
2. Sends the product ID and purchase token to `verify-play-purchase`.
3. Refreshes the Supabase-authoritative subscription and entitlement cache.
4. Keeps the existing acknowledgement behavior only after the server verification result is successful.

The BillingClient obfuscated account ID uses the Supabase Auth UUID hash. It no longer uses Firebase Auth as the billing identity. The old Firebase subscription data source remains source-only compatibility code and is no longer registered in the owner runtime dependency graph.

Expiry is provider-derived. The client may use the refreshed Room copy for offline UX, but protected Supabase operations continue to enforce server-side membership and entitlement rules. No periodic Google Play re-verification scheduler was found; the current reconciliation points are purchase processing, restore, sign-in refresh, and the retained provider-notification path.

## Trusted verification

Deployed Edge Functions:

- `verify-play-purchase`: validates the caller JWT, resolves the caller's OWNER organization, verifies Google Play with server-only Publisher API credentials, prevents token reuse across organizations, acknowledges the verified purchase, and upserts one subscription idempotently.
- `subscription-trial`: replaces the owner trial/eligibility callable using a hashed Supabase Auth identity and server-side organization membership.

Required secrets are intentionally not committed or logged: `GOOGLE_PLAY_CLIENT_EMAIL`, `GOOGLE_PLAY_PRIVATE_KEY`, and the package configuration. The deployed verifier fails closed until those server secrets are configured. No authenticated purchase validation is claimed in this environment.

Google Play RTDN is currently still handled by the retained Firebase Pub/Sub function because no approved Supabase Pub/Sub/webhook receiver is deployed. It remains a compatibility dependency and does not change the Android authority path. A future RTDN replacement must re-verify with Google and write the same Supabase state idempotently.

## Historical import

`tools/supabase-migration/migrate-subscriptions.mjs` is dry-run by default and requires both `--apply` and `--confirm-hosted-production` to write. It validates legacy IDs, organization/profile mappings, plan codes, statuses, and expiry values; preserves timestamps and `firebase_id`; records imported rows as `LEGACY_IMPORT`; reports conflicts/unresolved mappings; and never treats a historical purchase token as Google verification. It never mutates Firebase.

## Notification outbox

`notification_outbox` remains server-only. Delivery-event triggers enqueue recipient-scoped work without coupling delivery state to FCM availability. Migration `20261004052000_notification_outbox_retry.sql` adds server-only claim functions, stale-claim recovery, backoff metadata, and terminal-failure handling. Deployed functions are:

- `dispatch-notification-outbox`: claims due work, recovers stale claims, and invokes the sender.
- `send-fcm-notification`: sends through FCM HTTP v1, removes only permanently invalid device tokens, and retries transient failures with bounded exponential backoff.

The dispatcher requires `NOTIFICATION_DISPATCH_SECRET`; FCM requires server-side project/service credentials. Those secrets are not configured here, so deployment is complete but live delivery is validation-pending. No client can enqueue arbitrary cross-tenant notifications or invoke the sender without the server dispatch secret.

## Remaining Firebase billing/runtime dependencies

Retained Firebase billing-related source/functions:

- `verifyPlaySubscription`, `ensureOwnerTrial`, and `checkOwnerTrialEligibility`: legacy source/compatibility only; Android no longer calls them.
- `playSubscriptionNotifications`: retained for RTDN until a Supabase receiver exists and is validated.
- Firebase Auth compatibility sessions: still needed by blocked order/legacy paths, not by the new billing verifier.

The subscription gate remains `SUBSCRIPTIONS_MIGRATION_IMPLEMENTED_VALIDATION_PENDING` until server secrets, a real Google Play purchase, restore/replay, token-reuse denial, entitlement resolution, and authenticated hosted checks are evidenced.

## Play RTDN replacement

The legacy Firebase RTDN behavior was audited from `AquaHubCustomer/functions/src/index.ts`: Pub/Sub supplies a package/product/token change signal; the token must already be bound to a business; Android Publisher is queried again; then Firestore subscription state and a message-id event are written. No subscription notification side effect was present in the legacy handler, so no new billing notification event was invented.

`supabase/functions/play-rtdn/index.ts` is the Supabase replacement. It authenticates Google Pub/Sub push with an OIDC JWT (`PLAY_RTDN_PUSH_AUDIENCE` and `PLAY_RTDN_PUSH_SERVICE_ACCOUNT`), rejects malformed/unexpected packages, re-verifies with Android Publisher, and updates only the owning organization. `subscriptions.provider_event_time` and `provider_event_version` protect against out-of-order regressions; `google_play_billing_events.message_id` provides replay idempotency. Status is `PLAY_RTDN_IMPLEMENTED_LIVE_VALIDATION_PENDING` until the topic and live delivery are validated.

Validation and secret setup are documented in `docs/supabase-migration/25-validation-and-cutover-runbook.md`. The owner billing runtime no longer requires FirebaseAuth, Firestore writes, or Firebase callable billing functions. Those Functions remain source/rollback compatibility until authenticated validation and RTDN cutover are complete.
