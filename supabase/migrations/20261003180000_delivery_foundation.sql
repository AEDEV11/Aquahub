-- Delivery foundation. Delivery remains part of orders; this table is only the
-- durable operation/audit ledger needed for idempotent assignment and transitions.
create table if not exists public.delivery_events (
  id uuid primary key default gen_random_uuid(),
  operation_id text not null unique,
  order_id uuid not null references public.orders(id) on delete cascade,
  event_type text not null check (event_type in ('ASSIGNMENT', 'STATUS_TRANSITION')),
  from_status public.order_status,
  to_status public.order_status not null,
  rider_id uuid references public.riders(id) on delete set null,
  actor_profile_id uuid references public.profiles(id) on delete set null,
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index if not exists delivery_events_order_created_idx
  on public.delivery_events(order_id, created_at desc);

alter table public.delivery_events enable row level security;
revoke all on public.delivery_events from public, anon, authenticated, service_role;

create or replace function public.assign_delivery(
  p_order_id uuid,
  p_rider_id uuid,
  p_operation_id text
)
returns table (order_id uuid, rider_id uuid, status public.order_status, created boolean)
language plpgsql
security definer
set search_path = pg_catalog, public, auth, app_private
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  rider_row public.riders%rowtype;
  prior public.delivery_events%rowtype;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_order_id is null or p_rider_id is null or nullif(btrim(p_operation_id), '') is null then
    raise exception using errcode = '22023', message = 'order, rider, and operation are required';
  end if;

  select * into prior from public.delivery_events
  where operation_id = btrim(p_operation_id);
  if prior.id is not null then
    select o.id, o.rider_id, o.status into order_id, rider_id, status
    from public.orders o where o.id = prior.order_id;
    created := false;
    return next;
    return;
  end if;

  select * into order_row from public.orders where id = p_order_id for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  if not app_private.can_operate_station(order_row.station_id) then
    raise exception using errcode = '42501', message = 'delivery assignment access denied';
  end if;
  if order_row.delivery_mode <> 'DELIVERY' then
    raise exception using errcode = '22023', message = 'only delivery orders can be assigned';
  end if;
  if order_row.status <> 'READY_FOR_PICKUP' then
    raise exception using errcode = '55000', message = 'order is not ready for delivery assignment';
  end if;

  select * into rider_row from public.riders where id = p_rider_id for update;
  if rider_row.id is null or rider_row.station_id <> order_row.station_id
     or not rider_row.is_active or not rider_row.is_available then
    raise exception using errcode = '22023', message = 'rider is unavailable for this station';
  end if;

  insert into public.delivery_events(
    operation_id, order_id, event_type, from_status, to_status,
    rider_id, actor_profile_id
  ) values (
    btrim(p_operation_id), order_row.id, 'ASSIGNMENT', order_row.status,
    'RIDER_ASSIGNED', rider_row.id, caller
  );

  update public.orders o
  set rider_id = rider_row.id,
      status = 'RIDER_ASSIGNED',
      assigned_at = floor(extract(epoch from clock_timestamp()) * 1000)::bigint,
      updated_at = now(),
      order_version = o.order_version + 1
  where o.id = order_row.id;

  return query select order_row.id, rider_row.id, 'RIDER_ASSIGNED'::public.order_status, true;
end;
$$;

create or replace function public.transition_delivery_status(
  p_order_id uuid,
  p_target_status public.order_status,
  p_operation_id text,
  p_metadata jsonb default '{}'::jsonb
)
returns table (order_id uuid, status public.order_status, created boolean)
language plpgsql
security definer
set search_path = pg_catalog, public, auth, app_private
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  prior public.delivery_events%rowtype;
  is_operator boolean;
  is_assigned_rider boolean;
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_order_id is null or p_target_status is null or nullif(btrim(p_operation_id), '') is null then
    raise exception using errcode = '22023', message = 'order, target status, and operation are required';
  end if;

  select * into prior from public.delivery_events
  where operation_id = btrim(p_operation_id);
  if prior.id is not null then
    select o.id, o.status into order_id, status from public.orders o where o.id = prior.order_id;
    created := false;
    return next;
    return;
  end if;

  select * into order_row from public.orders where id = p_order_id for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  is_operator := app_private.can_operate_station(order_row.station_id);
  select exists (
    select 1 from public.riders r
    where r.id = order_row.rider_id and r.profile_id = caller and r.is_active
  ) into is_assigned_rider;
  if not is_operator and not is_assigned_rider then
    raise exception using errcode = '42501', message = 'delivery transition access denied';
  end if;
  if order_row.delivery_mode <> 'DELIVERY' then
    raise exception using errcode = '22023', message = 'only delivery orders can transition here';
  end if;
  if not (
    (order_row.status = 'RIDER_ASSIGNED' and p_target_status = 'OUT_FOR_DELIVERY') or
    (order_row.status = 'OUT_FOR_DELIVERY' and p_target_status = 'DELIVERED') or
    (order_row.status = 'DELIVERED' and p_target_status = 'COMPLETED')
  ) then
    raise exception using errcode = '55000', message = 'invalid delivery status transition';
  end if;

  insert into public.delivery_events(
    operation_id, order_id, event_type, from_status, to_status,
    rider_id, actor_profile_id, metadata
  ) values (
    btrim(p_operation_id), order_row.id, 'STATUS_TRANSITION', order_row.status,
    p_target_status, order_row.rider_id, caller, coalesce(p_metadata, '{}'::jsonb)
  );

  update public.orders o
  set status = p_target_status,
      out_for_delivery_at = case when p_target_status = 'OUT_FOR_DELIVERY' then now_ms else o.out_for_delivery_at end,
      delivered_at = case when p_target_status = 'DELIVERED' then now_ms else o.delivered_at end,
      completed_at = case when p_target_status = 'COMPLETED' then now_ms else o.completed_at end,
      updated_at = now(),
      order_version = o.order_version + 1
  where o.id = order_row.id;

  return query select order_row.id, p_target_status, true;
end;
$$;

revoke all on function public.assign_delivery(uuid, uuid, text) from public, anon, authenticated, service_role;
revoke all on function public.transition_delivery_status(uuid, public.order_status, text, jsonb) from public, anon, authenticated, service_role;
grant execute on function public.assign_delivery(uuid, uuid, text) to authenticated, service_role;
grant execute on function public.transition_delivery_status(uuid, public.order_status, text, jsonb) to authenticated, service_role;
