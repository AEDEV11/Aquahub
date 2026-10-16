-- Supabase-primary order transactions.
-- This migration intentionally does not delete or rewrite Firebase order data.

alter table public.orders
  add column if not exists order_source text not null default 'LEGACY_IMPORT',
  add column if not exists tax_centavos bigint not null default 0,
  add column if not exists cancellation_reason text,
  add column if not exists cancelled_by uuid references public.profiles(id) on delete set null;

alter table public.orders
  add constraint orders_order_source_check
  check (order_source in ('CUSTOMER_APP', 'OWNER_WALK_IN', 'LEGACY_IMPORT'));

alter table public.orders
  add constraint orders_tax_centavos_check check (tax_centavos >= 0);

create table if not exists public.order_number_counters (
  station_id uuid not null references public.stations(id) on delete cascade,
  order_date date not null,
  next_value integer not null check (next_value between 1 and 100000000),
  primary key (station_id, order_date)
);

revoke all on public.order_number_counters from public, anon, authenticated;

create or replace function app_private.next_order_number(p_station_id uuid)
returns text
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  sequence_value integer;
begin
  insert into public.order_number_counters (station_id, order_date, next_value)
  values (p_station_id, current_date, 2)
  on conflict (station_id, order_date) do update
    set next_value = public.order_number_counters.next_value + 1
  returning next_value - 1 into sequence_value;

  return 'AQH-' || to_char(current_date, 'YYYYMMDD') || '-' || lpad(sequence_value::text, 4, '0');
end;
$$;

revoke all on function app_private.next_order_number(uuid) from public, anon, authenticated, service_role;
grant execute on function app_private.next_order_number(uuid) to postgres, authenticated, service_role;

create or replace function public.create_order(
  p_station_id uuid,
  p_client_operation_id text,
  p_source text,
  p_items jsonb,
  p_customer_id uuid default null,
  p_customer_name text default '',
  p_customer_phone text default '',
  p_delivery_mode public.delivery_mode default 'PICKUP',
  p_delivery_address text default '',
  p_delivery_place_id text default null,
  p_delivery_latitude double precision default null,
  p_delivery_longitude double precision default null,
  p_delivery_fee_centavos bigint default null,
  p_payment_method text default 'CASH',
  p_customer_note text default null
)
returns table (
  order_id uuid,
  order_number text,
  total_centavos bigint,
  created boolean,
  status public.order_status
)
language plpgsql
security definer
set search_path = pg_catalog, public, auth, app_private
as $$
declare
  caller uuid := auth.uid();
  station public.stations%rowtype;
  profile public.profiles%rowtype;
  existing public.orders%rowtype;
  product public.products%rowtype;
  balance public.inventory_balances%rowtype;
  input_line record;
  line_unit_price bigint;
  line_regular_price bigint;
  line_subtotal bigint;
  line_discount bigint;
  subtotal bigint := 0;
  discount bigint := 0;
  delivery_fee bigint;
  total bigint;
  order_uuid uuid;
  generated_order_number text;
  movement_operation text;
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
  item_count integer;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if nullif(btrim(p_client_operation_id), '') is null then
    raise exception using errcode = '22023', message = 'order operation id is required';
  end if;
  if p_source not in ('CUSTOMER_APP', 'OWNER_WALK_IN') then
    raise exception using errcode = '22023', message = 'unsupported order source';
  end if;
  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) < 1 or jsonb_array_length(p_items) > 20 then
    raise exception using errcode = '22023', message = 'order must contain 1 to 20 items';
  end if;

  -- A committed retry returns the canonical result and does not touch stock.
  select * into existing
  from public.orders
  where client_operation_id = btrim(p_client_operation_id)
  for update;
  if existing.id is not null then
    return query select existing.id, existing.order_number, existing.total_centavos,
      false, existing.status;
    return;
  end if;

  select * into station from public.stations where id = p_station_id for share;
  if station.id is null or not station.is_active or station.deleted_at is not null then
    raise exception using errcode = '22023', message = 'station is unavailable';
  end if;

  select * into profile from public.profiles where id = caller;
  if profile.id is null or profile.account_state <> 'ACTIVE' then
    raise exception using errcode = '42501', message = 'account is not active';
  end if;
  if p_source = 'OWNER_WALK_IN' then
    if not app_private.can_operate_station(p_station_id) then
      raise exception using errcode = '42501', message = 'station access denied';
    end if;
  else
    if profile.role <> 'CUSTOMER' or not profile.setup_complete
       or (not profile.email_verified and not profile.phone_verified)
       or profile.preferred_station_id is distinct from p_station_id
       or not station.is_accepting_online_orders or not station.is_open then
      raise exception using errcode = '42501', message = 'customer order access denied';
    end if;
  end if;


  if p_delivery_mode = 'DELIVERY' and nullif(btrim(p_delivery_address), '') is null then
    raise exception using errcode = '22023', message = 'delivery address is required';
  end if;
  if (p_delivery_latitude is null) <> (p_delivery_longitude is null) then
    raise exception using errcode = '22023', message = 'delivery coordinates must be supplied together';
  end if;
  if p_delivery_latitude is not null and (p_delivery_latitude not between -90 and 90 or p_delivery_longitude not between -180 and 180) then
    raise exception using errcode = '22023', message = 'delivery coordinates are invalid';
  end if;

  select count(*) into item_count
  from (select (value->>'product_id')::uuid as product_id from jsonb_array_elements(p_items) value) lines;
  if item_count <> (select count(distinct (value->>'product_id')::uuid) from jsonb_array_elements(p_items) value) then
    raise exception using errcode = '22023', message = 'duplicate products are not allowed';
  end if;

  -- Product and balance locks are acquired in product UUID order. This avoids
  -- deadlocks when two orders contain overlapping products in different input order.
  for input_line in
    select (value->>'product_id')::uuid as product_id, (value->>'quantity')::integer as quantity
    from jsonb_array_elements(p_items) value
    order by (value->>'product_id')::uuid
  loop
    if input_line.quantity is null or input_line.quantity < 1 or input_line.quantity > 99 then
      raise exception using errcode = '22023', message = 'invalid order quantity';
    end if;

    select * into product
    from public.products
    where id = input_line.product_id and station_id = p_station_id
      and is_active and is_available
    for share;
    if product.id is null then
      raise exception using errcode = '23503', message = 'product is unavailable';
    end if;

    insert into public.inventory_balances (station_id, product_id)
    values (p_station_id, product.id)
    on conflict (station_id, product_id) do nothing;
    select * into balance from public.inventory_balances
    where station_id = p_station_id and product_id = product.id for update;
    if balance.quantity < input_line.quantity then
      raise exception using errcode = '23514', message = 'insufficient inventory';
    end if;

    line_regular_price := product.price_centavos;
    line_unit_price := line_regular_price;
    if product.promotion_is_active
       and (product.promotion_starts_at is null or product.promotion_starts_at <= now_ms)
       and (product.promotion_ends_at is null or product.promotion_ends_at >= now_ms)
       and (product.promotion_minimum_quantity is null or input_line.quantity >= product.promotion_minimum_quantity) then
      if product.promotion_type = 'FIXED_PRICE' and product.promotional_price_centavos is not null then
        line_unit_price := least(line_regular_price, product.promotional_price_centavos);
      elsif product.promotion_type = 'PERCENTAGE' and product.promotion_percent_bps is not null then
        line_unit_price := floor(line_regular_price * (10000 - product.promotion_percent_bps)::numeric / 10000)::bigint;
      elsif product.promotion_type = 'QUANTITY_BREAK' and product.promotional_price_centavos is not null then
        line_unit_price := least(line_regular_price, product.promotional_price_centavos);
      end if;
    end if;
    line_subtotal := line_unit_price * input_line.quantity;
    line_discount := (line_regular_price - line_unit_price) * input_line.quantity;
    subtotal := subtotal + line_regular_price * input_line.quantity;
    discount := discount + line_discount;
  end loop;

  delivery_fee := case
    when p_delivery_mode = 'DELIVERY' and p_source = 'CUSTOMER_APP' then station.delivery_fee_centavos
    when p_delivery_mode = 'DELIVERY' then coalesce(p_delivery_fee_centavos, 0)
    else 0
  end;
  if delivery_fee < 0 then
    raise exception using errcode = '22023', message = 'delivery fee is invalid';
  end if;
  total := subtotal - discount + delivery_fee;
  generated_order_number := app_private.next_order_number(p_station_id);
  order_uuid := gen_random_uuid();

  insert into public.orders (
    id, client_operation_id, station_id, customer_id, customer_uid,
    order_number, customer_name, customer_phone, delivery_mode,
    delivery_address, delivery_place_id, delivery_latitude, delivery_longitude,
    subtotal_centavos, delivery_fee_centavos, discount_centavos, tax_centavos,
    total_centavos, payment_method, payment_status, status, customer_note,
    requested_at, accepted_at, ready_at, completed_at, order_source
  ) values (
    order_uuid, btrim(p_client_operation_id), p_station_id, p_customer_id,
    case when p_source = 'CUSTOMER_APP' then caller else null end,
    generated_order_number,
    coalesce(nullif(btrim(p_customer_name), ''), profile.display_name, 'Walk-in Customer'),
    coalesce(p_customer_phone, profile.phone, ''), p_delivery_mode,
    case when p_delivery_mode = 'PICKUP' then 'Station pickup' else btrim(p_delivery_address) end,
    nullif(btrim(p_delivery_place_id), ''), p_delivery_latitude, p_delivery_longitude,
    subtotal, delivery_fee, discount, 0, total,
    upper(coalesce(nullif(btrim(p_payment_method), ''), 'CASH')),
    case when p_source = 'OWNER_WALK_IN' then 'PAID' else 'PENDING' end,
    case when p_source = 'OWNER_WALK_IN' and p_delivery_mode = 'PICKUP' then 'COMPLETED' else 'PENDING' end,
    nullif(btrim(p_customer_note), ''), now_ms,
    case when p_source = 'OWNER_WALK_IN' then now_ms else null end,
    case when p_source = 'OWNER_WALK_IN' then now_ms else null end,
    case when p_source = 'OWNER_WALK_IN' and p_delivery_mode = 'PICKUP' then now_ms else null end,
    p_source
  );

  for input_line in
    select (value->>'product_id')::uuid as product_id, (value->>'quantity')::integer as quantity
    from jsonb_array_elements(p_items) value
    order by (value->>'product_id')::uuid
  loop
    select * into product from public.products where id = input_line.product_id for share;
    line_regular_price := product.price_centavos;
    line_unit_price := line_regular_price;
    if product.promotion_is_active
       and (product.promotion_starts_at is null or product.promotion_starts_at <= now_ms)
       and (product.promotion_ends_at is null or product.promotion_ends_at >= now_ms)
       and (product.promotion_minimum_quantity is null or input_line.quantity >= product.promotion_minimum_quantity) then
      if product.promotion_type in ('FIXED_PRICE', 'QUANTITY_BREAK') and product.promotional_price_centavos is not null then
        line_unit_price := least(line_regular_price, product.promotional_price_centavos);
      elsif product.promotion_type = 'PERCENTAGE' and product.promotion_percent_bps is not null then
        line_unit_price := floor(line_regular_price * (10000 - product.promotion_percent_bps)::numeric / 10000)::bigint;
      end if;
    end if;
    line_subtotal := line_unit_price * input_line.quantity;
    insert into public.order_items (
      order_id, product_id, product_name_snapshot, product_type_snapshot,
      size_label_snapshot, unit_price_centavos, quantity, subtotal_centavos
    ) values (
      order_uuid, product.id, product.name, product.product_type,
      product.size_label, line_unit_price, input_line.quantity, line_subtotal
    );

    update public.inventory_balances
    set quantity = quantity - input_line.quantity,
        version = version + 1,
        updated_at = now()
    where id = (select b.id from public.inventory_balances b
                where b.station_id = p_station_id and b.product_id = product.id);
    movement_operation := 'order:' || order_uuid::text || ':deduct:' || product.id::text;
    insert into public.inventory_movements (
      station_id, product_id, operation_id, delta, quantity_after, reason,
      order_id, created_by, movement_type, source_id
    ) values (
      p_station_id, product.id, movement_operation, -input_line.quantity,
      (select quantity from public.inventory_balances b where b.station_id = p_station_id and b.product_id = product.id),
      'Order sale', order_uuid, caller, 'ORDER_DEDUCTION', order_uuid::text
    );
  end loop;

  return query select order_uuid, generated_order_number, total, true, (
    select o.status from public.orders o where o.id = order_uuid
  );
end;
$$;

revoke all on function public.create_order(uuid, text, text, jsonb, uuid, text, text, public.delivery_mode, text, text, double precision, double precision, bigint, text, text)
  from public, anon, authenticated, service_role;
grant execute on function public.create_order(uuid, text, text, jsonb, uuid, text, text, public.delivery_mode, text, text, double precision, double precision, bigint, text, text)
  to authenticated, service_role;

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
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
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

  for item in select oi.product_id, oi.quantity from public.order_items oi
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
      balance.quantity + item.quantity, coalesce(nullif(btrim(p_reason), ''), 'Cancelled'),
      order_row.id, caller, 'ORDER_REVERSAL', order_row.id::text
    ) on conflict (operation_id) do nothing;
    restored := restored + item.quantity;
  end loop;

  update public.orders
  set status = 'CANCELLED', cancellation_reason = coalesce(nullif(btrim(p_reason), ''), 'Cancelled'),
      cancelled_by = caller, cancelled_at = now_ms, updated_at = now(), order_version = order_version + 1
  where id = order_row.id;
  return query select order_row.id, 'CANCELLED'::public.order_status, false, restored;
end;
$$;

revoke all on function public.cancel_order(uuid, text, text) from public, anon, authenticated, service_role;
grant execute on function public.cancel_order(uuid, text, text) to authenticated, service_role;
