-- Retry metadata and server-only claiming for the notification outbox.
-- FCM delivery is intentionally outside the business transaction.
-- This migration sorts before the original foundation migration in a fresh
-- database, so ensure the base table exists before adding retry metadata.
create table if not exists public.notification_outbox (
  id uuid primary key default gen_random_uuid(),
  dedupe_key text not null unique,
  event_type text not null,
  recipient_profile_id uuid not null references public.profiles(id) on delete cascade,
  entity_type text not null,
  entity_id uuid not null,
  payload jsonb not null default '{}'::jsonb,
  status text not null default 'PENDING' check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED')),
  attempt_count integer not null default 0 check (attempt_count >= 0),
  last_error text,
  created_at timestamptz not null default now(),
  processed_at timestamptz
);

alter table public.notification_outbox enable row level security;
revoke all on public.notification_outbox from public, anon, authenticated, service_role;

alter table public.notification_outbox
  add column if not exists next_attempt_at timestamptz,
  add column if not exists claimed_at timestamptz,
  add column if not exists terminal_failure boolean not null default false;

create index if not exists notification_outbox_retry_idx
  on public.notification_outbox(status, next_attempt_at, created_at)
  where status = 'PENDING' and terminal_failure = false;

create or replace function app_private.claim_notification_outbox(p_limit integer default 20)
returns table(
  id uuid,
  recipient_profile_id uuid,
  event_type text,
  entity_type text,
  entity_id uuid,
  payload jsonb,
  attempt_count integer
)
language plpgsql
security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  if p_limit is null or p_limit < 1 or p_limit > 100 then
    raise exception using errcode = '22023', message = 'invalid notification batch size';
  end if;

  return query
  with candidates as (
    select n.id
    from public.notification_outbox n
    where n.status = 'PENDING'
      and not n.terminal_failure
      and (n.next_attempt_at is null or n.next_attempt_at <= now())
    order by n.created_at
    for update skip locked
    limit p_limit
  ), claimed as (
    update public.notification_outbox n
    set status = 'PROCESSING',
        attempt_count = n.attempt_count + 1,
        claimed_at = now(),
        last_error = null
    from candidates c
    where n.id = c.id
    returning n.*
  )
  select c.id, c.recipient_profile_id, c.event_type, c.entity_type,
         c.entity_id, c.payload, c.attempt_count
  from claimed c;
end;
$$;

create or replace function app_private.claim_notification_outbox_item(p_outbox_id uuid)
returns table(
  id uuid,
  recipient_profile_id uuid,
  event_type text,
  entity_type text,
  entity_id uuid,
  payload jsonb,
  attempt_count integer
)
language plpgsql
security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  return query
  update public.notification_outbox n
  set status = 'PROCESSING',
      attempt_count = n.attempt_count + 1,
      claimed_at = now(),
      last_error = null
  where n.id = p_outbox_id
    and n.status = 'PENDING'
    and not n.terminal_failure
    and (n.next_attempt_at is null or n.next_attempt_at <= now())
  returning n.id, n.recipient_profile_id, n.event_type, n.entity_type,
            n.entity_id, n.payload, n.attempt_count;
end;
$$;

create or replace function app_private.requeue_stale_notification_outbox(
  p_stale_after interval default interval '10 minutes'
)
returns integer
language plpgsql
security definer
set search_path = pg_catalog, public, app_private
as $$
declare
  changed integer;
begin
  if p_stale_after is null or p_stale_after < interval '1 minute' then
    raise exception using errcode = '22023', message = 'invalid stale interval';
  end if;

  update public.notification_outbox
  set status = case when attempt_count >= 5 then 'FAILED' else 'PENDING' end,
      terminal_failure = attempt_count >= 5,
      next_attempt_at = case
        when attempt_count >= 5 then null
        else now() + least(interval '15 minutes', interval '15 seconds' * power(2, greatest(attempt_count - 1, 0)))
      end,
      last_error = coalesce(last_error, 'STALE_PROCESSING_CLAIM')
  where status = 'PROCESSING'
    and claimed_at < now() - p_stale_after
    and processed_at is null;

  get diagnostics changed = row_count;
  return changed;
end;
$$;

revoke all on function app_private.claim_notification_outbox(integer) from public, anon, authenticated;
revoke all on function app_private.claim_notification_outbox_item(uuid) from public, anon, authenticated;
revoke all on function app_private.requeue_stale_notification_outbox(interval) from public, anon, authenticated;
grant execute on function app_private.claim_notification_outbox(integer) to service_role;
grant execute on function app_private.claim_notification_outbox_item(uuid) to service_role;
grant execute on function app_private.requeue_stale_notification_outbox(interval) to service_role;
