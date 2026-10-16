-- Google Play RTDN is only a change signal. The Edge Function re-verifies
-- the purchase against Android Publisher before applying any entitlement.
alter table public.subscriptions
  add column if not exists provider_event_time bigint,
  add column if not exists provider_event_version bigint;

create index if not exists subscriptions_provider_event_idx
  on public.subscriptions(organization_id, provider_event_time desc nulls last);

-- Keep RTDN delivery records server-only and make replay idempotent by the
-- Pub/Sub message ID. The payload is retained for audit/debugging only; it is
-- never used as entitlement authority.
alter table public.google_play_billing_events
  add column if not exists notification_type integer,
  add column if not exists event_time bigint,
  add column if not exists purchase_token_hash text,
  add column if not exists product_id text,
  add column if not exists organization_id uuid references public.organizations(id) on delete set null;

create index if not exists google_play_billing_events_token_idx
  on public.google_play_billing_events(purchase_token_hash, event_time desc nulls last);

revoke all on public.google_play_billing_events from public, anon, authenticated, service_role;
