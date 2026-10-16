-- Keep owner walk-in delivery orders consistent with the Android owner flow.
-- The create_order RPC historically returned PENDING for every delivery order,
-- while the owner walk-in flow treats the order as ready for rider assignment.

create or replace function app_private.normalize_owner_walk_in_delivery_order()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  if new.order_source = 'OWNER_WALK_IN'
     and new.delivery_mode = 'DELIVERY'
     and new.status = 'PENDING' then
    new.status := 'READY_FOR_PICKUP';
    new.accepted_at := coalesce(new.accepted_at, new.requested_at, now_ms);
    new.ready_at := coalesce(new.ready_at, new.requested_at, now_ms);
  end if;
  return new;
end;
$$;

drop trigger if exists normalize_owner_walk_in_delivery_order on public.orders;
create trigger normalize_owner_walk_in_delivery_order
before insert on public.orders
for each row
execute function app_private.normalize_owner_walk_in_delivery_order();

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

  -- Preparation transitions belong to station operators. Riders may only
  -- advance an already assigned delivery through its rider lifecycle.
  if p_target_status in ('ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP') and not is_operator then
    raise exception using errcode = '42501', message = 'only station operators can prepare deliveries';
  end if;
  if not (
    (order_row.status = 'PENDING' and p_target_status = 'ACCEPTED') or
    (order_row.status = 'ACCEPTED' and p_target_status = 'PREPARING') or
    (order_row.status = 'PREPARING' and p_target_status = 'READY_FOR_PICKUP') or
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
      accepted_at = case when p_target_status = 'ACCEPTED' then coalesce(o.accepted_at, now_ms) else o.accepted_at end,
      ready_at = case when p_target_status = 'READY_FOR_PICKUP' then coalesce(o.ready_at, now_ms) else o.ready_at end,
      out_for_delivery_at = case when p_target_status = 'OUT_FOR_DELIVERY' then now_ms else o.out_for_delivery_at end,
      delivered_at = case when p_target_status = 'DELIVERED' then now_ms else o.delivered_at end,
      completed_at = case when p_target_status = 'COMPLETED' then now_ms else o.completed_at end,
      updated_at = now(),
      order_version = o.order_version + 1
  where o.id = order_row.id;

  return query select order_row.id, p_target_status, true;
end;
$$;

revoke all on function public.transition_delivery_status(uuid, public.order_status, text, jsonb) from public, anon, authenticated, service_role;
grant execute on function public.transition_delivery_status(uuid, public.order_status, text, jsonb) to authenticated, service_role;
