-- Durable notification handoff. Business transactions enqueue work here;
-- external FCM delivery is deliberately processed outside the transaction.
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

create index if not exists notification_outbox_pending_idx
  on public.notification_outbox(status, created_at)
  where status in ('PENDING', 'PROCESSING');

alter table public.notification_outbox enable row level security;
revoke all on public.notification_outbox from public, anon, authenticated, service_role;

-- Delivery RPCs already commit delivery_events atomically. This trigger only
-- records recipients; FCM availability can never roll back delivery state.
create or replace function app_private.enqueue_delivery_notification()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, app_private
as $$
declare
  order_row public.orders%rowtype;
  recipient uuid;
begin
  select * into order_row from public.orders where id = new.order_id;
  if order_row.id is null then return new; end if;

  for recipient in
    select distinct profile_id from (
      select order_row.customer_uid as profile_id
      union all
      select r.profile_id from public.riders r where r.id = new.rider_id
    ) recipients
    where profile_id is not null
  loop
    insert into public.notification_outbox(
      dedupe_key, event_type, recipient_profile_id, entity_type, entity_id, payload
    ) values (
      'delivery:' || new.operation_id || ':' || recipient::text,
      'DELIVERY_' || new.event_type,
      recipient,
      'ORDER',
      order_row.id,
      jsonb_build_object(
        'order_id', order_row.id,
        'order_number', order_row.order_number,
        'status', new.to_status,
        'event_type', new.event_type
      )
    ) on conflict (dedupe_key) do nothing;
  end loop;
  return new;
end;
$$;

revoke all on function app_private.enqueue_delivery_notification() from public, anon, authenticated;
drop trigger if exists delivery_events_notification_outbox on public.delivery_events;
create trigger delivery_events_notification_outbox
after insert on public.delivery_events
for each row execute function app_private.enqueue_delivery_notification();
