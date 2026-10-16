-- Complete the Room outbox contract without replaying historical inventory
-- reservations, and keep customer cancellation notices transactionally queued.

create or replace function public.import_legacy_order(
  p_order_id uuid,
  p_station_id uuid,
  p_order_number text,
  p_customer_id uuid,
  p_customer_name text,
  p_customer_phone text,
  p_rider_id uuid,
  p_delivery_address_id uuid,
  p_delivery_address text,
  p_delivery_latitude double precision,
  p_delivery_longitude double precision,
  p_delivery_mode public.delivery_mode,
  p_subtotal_centavos bigint,
  p_delivery_fee_centavos bigint,
  p_discount_centavos bigint,
  p_total_centavos bigint,
  p_payment_method text,
  p_payment_status text,
  p_status public.order_status,
  p_scheduled_delivery_start bigint,
  p_scheduled_delivery_end bigint,
  p_customer_note text,
  p_station_note text,
  p_created_at bigint,
  p_accepted_at bigint,
  p_ready_at bigint,
  p_assigned_at bigint,
  p_out_for_delivery_at bigint,
  p_delivered_at bigint,
  p_completed_at bigint,
  p_cancelled_at bigint,
  p_cancellation_reason text,
  p_client_updated_at bigint,
  p_items jsonb
)
returns table (order_id uuid, created boolean)
language plpgsql
security definer
set search_path = pg_catalog, public, auth, app_private
as $$
declare
  caller uuid := auth.uid();
  station public.stations%rowtype;
  item jsonb;
  item_product uuid;
  order_exists boolean;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_order_id is null or p_station_id is null or nullif(btrim(p_order_number), '') is null then
    raise exception using errcode = '22023', message = 'order identity, station, and number are required';
  end if;
  if not app_private.can_operate_station(p_station_id) then
    raise exception using errcode = '42501', message = 'station access denied';
  end if;
  select * into station from public.stations
  where id = p_station_id and is_active and deleted_at is null;
  if station.id is null then
    raise exception using errcode = '22023', message = 'station is unavailable';
  end if;
  if p_subtotal_centavos < 0 or p_delivery_fee_centavos < 0 or p_discount_centavos < 0
     or p_total_centavos < 0 or p_created_at < 0 then
    raise exception using errcode = '22023', message = 'order amounts and timestamps are invalid';
  end if;
  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) > 100 then
    raise exception using errcode = '22023', message = 'legacy order items must be an array of at most 100 items';
  end if;
  if p_delivery_mode = 'DELIVERY' and nullif(btrim(p_delivery_address), '') is null then
    raise exception using errcode = '22023', message = 'delivery address is required';
  end if;
  if (p_delivery_latitude is null) <> (p_delivery_longitude is null)
     or (p_delivery_latitude is not null and (p_delivery_latitude not between -90 and 90 or p_delivery_longitude not between -180 and 180)) then
    raise exception using errcode = '22023', message = 'delivery coordinates are invalid';
  end if;
  if p_customer_id is not null and not exists (
    select 1 from public.customers c where c.id = p_customer_id and c.station_id = p_station_id
  ) then
    raise exception using errcode = '23503', message = 'customer does not belong to this station';
  end if;
  if p_rider_id is not null and not exists (
    select 1 from public.riders r where r.id = p_rider_id and r.station_id = p_station_id
  ) then
    raise exception using errcode = '23503', message = 'rider does not belong to this station';
  end if;

  select exists(select 1 from public.orders o where o.id = p_order_id) into order_exists;
  if order_exists then
    return query select p_order_id, false;
    return;
  end if;

  insert into public.orders (
    id, station_id, customer_id, order_number, customer_name, customer_phone,
    rider_id, delivery_address_id, delivery_address, delivery_latitude, delivery_longitude,
    delivery_mode, subtotal_centavos, delivery_fee_centavos, discount_centavos, total_centavos,
    payment_method, payment_status, status, scheduled_delivery_start, scheduled_delivery_end,
    customer_note, station_note, cancellation_reason, accepted_at, ready_at, assigned_at,
    out_for_delivery_at, delivered_at, completed_at, cancelled_at, requested_at,
    order_source, client_updated_at, created_at, updated_at
  ) values (
    p_order_id, p_station_id, p_customer_id, btrim(p_order_number), coalesce(p_customer_name, ''), coalesce(p_customer_phone, ''),
    p_rider_id, p_delivery_address_id, coalesce(p_delivery_address, ''), p_delivery_latitude, p_delivery_longitude,
    p_delivery_mode, p_subtotal_centavos, p_delivery_fee_centavos, p_discount_centavos, p_total_centavos,
    upper(btrim(coalesce(p_payment_method, 'CASH'))), upper(btrim(coalesce(p_payment_status, 'PENDING'))), p_status,
    p_scheduled_delivery_start, p_scheduled_delivery_end, p_customer_note, p_station_note,
    nullif(btrim(p_cancellation_reason), ''), p_accepted_at, p_ready_at, p_assigned_at,
    p_out_for_delivery_at, p_delivered_at, p_completed_at, p_cancelled_at, p_created_at,
    'LEGACY_IMPORT', p_client_updated_at,
    to_timestamp(p_created_at::numeric / 1000), now()
  );

  for item in select value from jsonb_array_elements(p_items)
  loop
    if coalesce((item->>'quantity')::integer, 0) not between 1 and 99
       or coalesce((item->>'unit_price_centavos')::bigint, -1) < 0
       or coalesce((item->>'subtotal_centavos')::bigint, -1) < 0
       or nullif(btrim(item->>'product_name'), '') is null then
      raise exception using errcode = '22023', message = 'legacy order item is invalid';
    end if;
    item_product := nullif(item->>'product_id', '')::uuid;
    if item_product is not null and not exists (
      select 1 from public.products p where p.id = item_product and p.station_id = p_station_id
    ) then
      raise exception using errcode = '23503', message = 'order item product does not belong to this station';
    end if;
    insert into public.order_items (
      order_id, product_id, product_name_snapshot, product_type_snapshot,
      size_label_snapshot, unit_price_centavos, quantity, subtotal_centavos
    ) values (
      p_order_id, item_product, btrim(item->>'product_name'), coalesce(nullif(btrim(item->>'product_type'), ''), 'OTHER'),
      coalesce(item->>'size_label', ''), (item->>'unit_price_centavos')::bigint,
      (item->>'quantity')::integer, (item->>'subtotal_centavos')::bigint
    );
  end loop;

  -- This is an archival snapshot import. Existing Room inventory and any
  -- historical order reservation are not replayed into the current ledger.
  return query select p_order_id, true;
end;
$$;

revoke all on function public.import_legacy_order(uuid, uuid, text, uuid, text, text, uuid, uuid, text, double precision, double precision, public.delivery_mode, bigint, bigint, bigint, bigint, text, text, public.order_status, bigint, bigint, text, text, bigint, bigint, bigint, bigint, bigint, bigint, bigint, bigint, text, bigint, jsonb)
  from public, anon, authenticated, service_role;
grant execute on function public.import_legacy_order(uuid, uuid, text, uuid, text, text, uuid, uuid, text, double precision, double precision, public.delivery_mode, bigint, bigint, bigint, bigint, text, text, public.order_status, bigint, bigint, text, text, bigint, bigint, bigint, bigint, bigint, bigint, bigint, bigint, text, bigint, jsonb)
  to authenticated, service_role;

create or replace function public.create_station(p_station_id uuid, p_settings jsonb)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, public, auth, app_private
as $$
declare
  caller uuid := auth.uid();
  target_organization_id uuid;
  organization_member_id uuid;
  existing_station public.stations%rowtype;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_station_id is null or p_settings is null or jsonb_typeof(p_settings) <> 'object'
     or nullif(btrim(p_settings->>'name'), '') is null then
    raise exception using errcode = '22023', message = 'station identity, name, and settings are required';
  end if;

  select p.preferred_business_id into target_organization_id
  from public.profiles p where p.id = caller;
  if target_organization_id is null or not app_private.can_manage_org(target_organization_id) then
    raise exception using errcode = '42501', message = 'organization management access denied';
  end if;
  select m.id into organization_member_id
  from public.organization_members m
  where m.organization_id = target_organization_id and m.profile_id = caller
    and m.is_active and m.role in ('OWNER', 'MANAGER')
  limit 1;
  if organization_member_id is null then
    raise exception using errcode = '42501', message = 'active owner or manager membership is required';
  end if;

  select * into existing_station from public.stations s where s.id = p_station_id for update;
  if existing_station.id is not null then
  if existing_station.organization_id <> target_organization_id then
      raise exception using errcode = '42501', message = 'station identity belongs to another organization';
    end if;
    perform public.update_station_settings(p_station_id, p_settings);
    return p_station_id;
  end if;

  insert into public.stations(id, organization_id, name, address, phone)
  values (
    p_station_id,
    target_organization_id,
    btrim(p_settings->>'name'),
    coalesce(p_settings->>'address', ''),
    coalesce(p_settings->>'phone', '')
  );
  insert into public.member_stations(member_id, station_id)
  values (organization_member_id, p_station_id) on conflict (member_id, station_id) do nothing;
  perform public.update_station_settings(p_station_id, p_settings);
  return p_station_id;
end;
$$;

revoke all on function public.create_station(uuid, jsonb) from public, anon, authenticated, service_role;
grant execute on function public.create_station(uuid, jsonb) to authenticated, service_role;

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
  select * into prior from public.delivery_events where operation_id = btrim(p_operation_id);
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
  if order_row.status = 'RIDER_ASSIGNED' and order_row.rider_id = p_rider_id then
    return query select order_row.id, order_row.rider_id, order_row.status, false;
    return;
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

revoke all on function public.assign_delivery(uuid, uuid, text) from public, anon, authenticated, service_role;
grant execute on function public.assign_delivery(uuid, uuid, text) to authenticated, service_role;

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
  valid_transition boolean := false;
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_order_id is null or p_target_status is null or nullif(btrim(p_operation_id), '') is null then
    raise exception using errcode = '22023', message = 'order, target status, and operation are required';
  end if;
  select * into prior from public.delivery_events where operation_id = btrim(p_operation_id);
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
  if order_row.delivery_mode = 'PICKUP' then
    if not is_operator then
      raise exception using errcode = '42501', message = 'only station operators can transition pickup orders';
    end if;
    if order_row.status = p_target_status then
      return query select order_row.id, order_row.status, false;
      return;
    end if;
    valid_transition :=
      (order_row.status = 'PENDING' and p_target_status = 'ACCEPTED') or
      (order_row.status = 'ACCEPTED' and p_target_status = 'PREPARING') or
      (order_row.status = 'PREPARING' and p_target_status = 'READY_FOR_PICKUP') or
      (order_row.status = 'READY_FOR_PICKUP' and p_target_status = 'COMPLETED');
  else
    if not is_operator and not is_assigned_rider then
      raise exception using errcode = '42501', message = 'delivery transition access denied';
    end if;
    if p_target_status in ('ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP') and not is_operator then
      raise exception using errcode = '42501', message = 'only station operators can prepare deliveries';
    end if;
    if order_row.status = p_target_status then
      return query select order_row.id, order_row.status, false;
      return;
    end if;
    valid_transition :=
      (order_row.status = 'PENDING' and p_target_status = 'ACCEPTED') or
      (order_row.status = 'ACCEPTED' and p_target_status = 'PREPARING') or
      (order_row.status = 'PREPARING' and p_target_status = 'READY_FOR_PICKUP') or
      (order_row.status = 'RIDER_ASSIGNED' and p_target_status = 'OUT_FOR_DELIVERY') or
      (order_row.status = 'OUT_FOR_DELIVERY' and p_target_status = 'DELIVERED') or
      (order_row.status = 'DELIVERED' and p_target_status = 'COMPLETED');
  end if;
  if not valid_transition then
    raise exception using errcode = '55000', message = 'invalid order status transition';
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
      updated_at = now(), order_version = o.order_version + 1
  where o.id = order_row.id;
  return query select order_row.id, p_target_status, true;
end;
$$;

revoke all on function public.transition_delivery_status(uuid, public.order_status, text, jsonb) from public, anon, authenticated, service_role;
grant execute on function public.transition_delivery_status(uuid, public.order_status, text, jsonb) to authenticated, service_role;

create or replace function public.reject_order(p_order_id uuid, p_operation_id text)
returns table (order_id uuid, status public.order_status, created boolean)
language plpgsql
security definer
set search_path = pg_catalog, public, auth, app_private
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  prior public.delivery_events%rowtype;
  item record;
  balance public.inventory_balances%rowtype;
  operation text;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_order_id is null or nullif(btrim(p_operation_id), '') is null then
    raise exception using errcode = '22023', message = 'order and operation are required';
  end if;
  select * into prior from public.delivery_events where operation_id = btrim(p_operation_id);
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
  if not app_private.can_operate_station(order_row.station_id) then
    raise exception using errcode = '42501', message = 'order access denied';
  end if;
  if order_row.status = 'REJECTED' then
    return query select order_row.id, order_row.status, false;
    return;
  end if;
  if order_row.status <> 'PENDING' then
    raise exception using errcode = '55000', message = 'only pending orders can be rejected';
  end if;

  if order_row.order_source <> 'LEGACY_IMPORT' then
    for item in
      select oi.product_id, oi.quantity from public.order_items oi
      where oi.order_id = order_row.id and oi.product_id is not null order by oi.product_id
    loop
      insert into public.inventory_balances (station_id, product_id)
      values (order_row.station_id, item.product_id)
      on conflict (station_id, product_id) do nothing;
      select * into balance from public.inventory_balances
      where station_id = order_row.station_id and product_id = item.product_id for update;
      update public.inventory_balances
      set quantity = balance.quantity + item.quantity, version = balance.version + 1, updated_at = now()
      where id = balance.id;
      operation := 'order:' || order_row.id::text || ':reject:' || item.product_id::text;
      insert into public.inventory_movements (
        station_id, product_id, operation_id, delta, quantity_after, reason,
        order_id, created_by, movement_type, source_id
      ) values (
        order_row.station_id, item.product_id, operation, item.quantity,
        balance.quantity + item.quantity, 'Order rejected by station',
        order_row.id, caller, 'ORDER_REVERSAL', order_row.id::text
      ) on conflict (operation_id) do nothing;
    end loop;
  end if;

  insert into public.delivery_events(
    operation_id, order_id, event_type, from_status, to_status, rider_id, actor_profile_id,
    metadata
  ) values (
    btrim(p_operation_id), order_row.id, 'STATUS_TRANSITION', order_row.status,
    'REJECTED', order_row.rider_id, caller, jsonb_build_object('reason', 'Rejected by station')
  );
  update public.orders
  set status = 'REJECTED', rejection_reason = 'Rejected by station',
      updated_at = now(), order_version = order_version + 1
  where id = order_row.id;
  return query select order_row.id, 'REJECTED'::public.order_status, true;
end;
$$;

revoke all on function public.reject_order(uuid, text) from public, anon, authenticated, service_role;
grant execute on function public.reject_order(uuid, text) to authenticated, service_role;

create or replace function app_private.enqueue_customer_cancellation_notification()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  if old.status is distinct from new.status
     and new.status = 'CANCELLED'
     and new.order_source in ('CUSTOMER_APP', 'CUSTOMER_WEB')
     and new.customer_uid is not null then
    insert into public.notification_outbox(
      dedupe_key, event_type, recipient_profile_id, entity_type, entity_id, payload
    ) values (
      'order-cancel:' || new.id::text || ':' || new.order_version::text,
      'ORDER_CANCELLED', new.customer_uid, 'ORDER', new.id,
      jsonb_build_object(
        'order_id', new.id,
        'order_number', new.order_number,
        'status', new.status,
        'cancellation_reason', coalesce(new.cancellation_reason, 'Cancelled by the station')
      )
    ) on conflict (dedupe_key) do nothing;
  end if;
  return new;
end;
$$;

revoke all on function app_private.enqueue_customer_cancellation_notification() from public, anon, authenticated;
drop trigger if exists orders_customer_cancellation_notification on public.orders;
create trigger orders_customer_cancellation_notification
after update of status on public.orders
for each row execute function app_private.enqueue_customer_cancellation_notification();

create or replace function app_private.refresh_rider_active_order_count()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  if tg_op = 'UPDATE' and old.rider_id is not null
     and (old.rider_id is distinct from new.rider_id or old.status is distinct from new.status) then
    update public.riders r
    set active_order_count = (
      select count(*)::integer from public.orders o
      where o.rider_id = old.rider_id and o.status in ('RIDER_ASSIGNED', 'OUT_FOR_DELIVERY')
    ), updated_at = now()
    where r.id = old.rider_id;
  end if;
  if new.rider_id is not null then
    update public.riders r
    set active_order_count = (
      select count(*)::integer from public.orders o
      where o.rider_id = new.rider_id and o.status in ('RIDER_ASSIGNED', 'OUT_FOR_DELIVERY')
    ), updated_at = now()
    where r.id = new.rider_id;
  end if;
  return new;
end;
$$;

revoke all on function app_private.refresh_rider_active_order_count() from public, anon, authenticated;
drop trigger if exists orders_refresh_rider_active_order_count on public.orders;
create trigger orders_refresh_rider_active_order_count
after insert or update of rider_id, status on public.orders
for each row execute function app_private.refresh_rider_active_order_count();

-- Keep the established cancellation contract, but do not restore stock for
-- legacy Room snapshots whose inventory was never reserved in Supabase.
create or replace function public.cancel_order(
  p_order_id uuid,
  p_operation_id text default null,
  p_reason text default 'Cancelled'
)
returns table (
  order_id uuid,
  status public.order_status,
  already_cancelled boolean,
  restored_items integer
)
language plpgsql
security definer
set search_path = pg_catalog, public, auth, app_private
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  item record;
  balance public.inventory_balances%rowtype;
  operation text;
  restored integer := 0;
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
  is_operator boolean;
  reason text := coalesce(nullif(btrim(p_reason), ''), 'Cancelled');
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_order_id is null then
    raise exception using errcode = '22023', message = 'order is required';
  end if;
  if p_operation_id is not null
     and btrim(p_operation_id) <> 'order:cancel:' || p_order_id::text then
    raise exception using errcode = '22023', message = 'invalid cancellation operation id';
  end if;
  if char_length(reason) > 300 then
    raise exception using errcode = '22023', message = 'cancellation reason is too long';
  end if;

  select * into order_row from public.orders where id = p_order_id for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  is_operator := app_private.can_operate_station(order_row.station_id);
  if not is_operator and order_row.customer_uid is distinct from caller then
    raise exception using errcode = '42501', message = 'order access denied';
  end if;
  if order_row.status = 'CANCELLED' then
    return query select order_row.id, order_row.status, true, 0;
    return;
  end if;
  if order_row.status in ('DELIVERED', 'COMPLETED', 'REJECTED') then
    raise exception using errcode = '55000', message = 'order cannot be cancelled in its current state';
  end if;
  if not is_operator and order_row.status not in ('PENDING', 'ACCEPTED') then
    raise exception using errcode = '55000', message = 'customer cancellation window has closed';
  end if;

  if order_row.order_source <> 'LEGACY_IMPORT' then
    for item in
      select oi.product_id, oi.quantity
      from public.order_items oi
      where oi.order_id = order_row.id and oi.product_id is not null
      order by oi.product_id
    loop
      insert into public.inventory_balances (station_id, product_id)
      values (order_row.station_id, item.product_id)
      on conflict (station_id, product_id) do nothing;
      select * into balance from public.inventory_balances
      where station_id = order_row.station_id and product_id = item.product_id for update;
      update public.inventory_balances
      set quantity = balance.quantity + item.quantity,
          version = balance.version + 1,
          updated_at = now()
      where id = balance.id;
      operation := 'order:' || order_row.id::text || ':cancel:' || item.product_id::text;
      insert into public.inventory_movements (
        station_id, product_id, operation_id, delta, quantity_after, reason,
        order_id, created_by, movement_type, source_id
      ) values (
        order_row.station_id, item.product_id, operation, item.quantity,
        balance.quantity + item.quantity, reason,
        order_row.id, caller, 'ORDER_REVERSAL', order_row.id::text
      ) on conflict (operation_id) do nothing;
      restored := restored + item.quantity;
    end loop;
  end if;

  update public.orders
  set status = 'CANCELLED', cancellation_reason = reason,
      cancelled_by = caller, cancelled_at = now_ms, updated_at = now(),
      order_version = order_version + 1
  where id = order_row.id;
  return query select order_row.id, 'CANCELLED'::public.order_status, false, restored;
end;
$$;

revoke all on function public.cancel_order(uuid, text, text) from public, anon, authenticated, service_role;
grant execute on function public.cancel_order(uuid, text, text) to authenticated, service_role;
