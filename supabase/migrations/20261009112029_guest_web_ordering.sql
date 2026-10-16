-- Guest browser orders use the existing CUSTOMER_WEB order contract and the
-- same inventory/pricing transaction as authenticated orders. Anonymous clients
-- receive no table grants; only the service-role Edge Function can call these RPCs.

-- Guest inventory movements have no authenticated profile actor; null identifies a
-- customer web order. Authenticated and owner movements remain attributed.
alter table public.inventory_movements alter column created_by drop not null;
comment on column public.inventory_movements.created_by is 'Authenticated profile actor; null for guest web order inventory movements.';

create table app_private.guest_web_orders (
  operation_id text primary key,
  order_id uuid not null unique references public.orders(id) on delete cascade,
  payload_hash bytea not null,
  tracking_token_hash bytea not null,
  created_at timestamptz not null default now()
);
alter table app_private.guest_web_orders enable row level security;
revoke all on app_private.guest_web_orders from public, anon, authenticated, service_role;

create table app_private.guest_order_rate_limits (
  dimension text not null check (dimension in ('create_ip', 'create_phone', 'track_ip')),
  fingerprint text not null check (fingerprint ~ '^[0-9a-f]{64}$'),
  window_start timestamptz not null,
  request_count integer not null default 0 check (request_count > 0),
  primary key (dimension, fingerprint, window_start)
);
alter table app_private.guest_order_rate_limits enable row level security;
revoke all on app_private.guest_order_rate_limits from public, anon, authenticated, service_role;

create or replace function app_private.validate_order_insert_context()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  caller_profile public.profiles%rowtype;
  trusted_guest boolean := new.order_source = 'CUSTOMER_WEB'
    and new.customer_uid is null
    and coalesce(auth.jwt() ->> 'role', '') = 'service_role'
    and pg_catalog.current_setting('app.guest_order_request', true) = 'on';
begin
  if new.order_source in ('CUSTOMER_APP', 'CUSTOMER_WEB') then
    if trusted_guest then
      if nullif(pg_catalog.btrim(new.customer_name), '') is null or nullif(pg_catalog.btrim(new.customer_phone), '') is null then
        raise exception using errcode = '22023', message = 'guest contact details are required';
      end if;
    else
      if caller is null or new.customer_uid is distinct from caller then
        raise exception using errcode = '42501', message = 'customer order identity mismatch';
      end if;
      select * into caller_profile from public.profiles where id = caller;
      if caller_profile.id is null or caller_profile.account_state <> 'ACTIVE' then
        raise exception using errcode = '42501', message = 'customer profile is not active';
      end if;
      new.customer_name := coalesce(nullif(btrim(caller_profile.display_name), ''), 'Customer');
      new.customer_phone := coalesce(caller_profile.phone, '');
    end if;
  end if;

  if new.customer_id is not null and not exists (
    select 1 from public.customers c
    where c.id = new.customer_id and c.station_id = new.station_id and c.is_active
  ) then
    raise exception using errcode = '23503', message = 'customer does not belong to station';
  end if;
  return new;
end;
$$;

create or replace function app_private.consume_guest_rate(
  p_dimension text,
  p_fingerprint text
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  bucket timestamptz := date_trunc('hour', pg_catalog.now())
    + pg_catalog.make_interval(mins => (extract(minute from pg_catalog.now())::integer / 15) * 15);
  hits integer;
begin
  insert into app_private.guest_order_rate_limits(dimension, fingerprint, window_start, request_count)
  values (p_dimension, p_fingerprint, bucket, 1)
  on conflict (dimension, fingerprint, window_start)
  do update set request_count = app_private.guest_order_rate_limits.request_count + 1
  returning request_count into hits;
  return hits;
end;
$$;
revoke all on function app_private.consume_guest_rate(text, text) from public, anon, authenticated, service_role;
grant execute on function app_private.consume_guest_rate(text, text) to postgres, service_role;

create or replace function public.consume_guest_order_rate_limit(
  p_ip_fingerprint text,
  p_phone_fingerprint text
)
returns table (allowed boolean, retry_after_seconds integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
  ip_hits integer;
  phone_hits integer;
  bucket timestamptz := date_trunc('hour', pg_catalog.now())
    + pg_catalog.make_interval(mins => (extract(minute from pg_catalog.now())::integer / 15) * 15);
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role'
     or p_ip_fingerprint is null or p_ip_fingerprint !~ '^[0-9a-f]{64}$'
     or p_phone_fingerprint is null or p_phone_fingerprint !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '42501', message = 'rate limit request is invalid';
  end if;
  delete from app_private.guest_order_rate_limits
  where window_start < pg_catalog.now() - interval '2 days';
  ip_hits := app_private.consume_guest_rate('create_ip', p_ip_fingerprint);
  phone_hits := app_private.consume_guest_rate('create_phone', p_phone_fingerprint);
  return query select ip_hits <= 20 and phone_hits <= 4,
    greatest(1, ceil(extract(epoch from bucket + interval '15 minutes' - pg_catalog.now()))::integer);
end;
$$;
revoke all on function public.consume_guest_order_rate_limit(text, text) from public, anon, authenticated, service_role;
grant execute on function public.consume_guest_order_rate_limit(text, text) to service_role;

create or replace function public.consume_guest_tracking_rate_limit(p_ip_fingerprint text)
returns table (allowed boolean, retry_after_seconds integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
  ip_hits integer;
  bucket timestamptz := date_trunc('hour', pg_catalog.now())
    + pg_catalog.make_interval(mins => (extract(minute from pg_catalog.now())::integer / 15) * 15);
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role'
     or p_ip_fingerprint is null or p_ip_fingerprint !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '42501', message = 'rate limit request is invalid';
  end if;
  delete from app_private.guest_order_rate_limits
  where window_start < pg_catalog.now() - interval '2 days';
  ip_hits := app_private.consume_guest_rate('track_ip', p_ip_fingerprint);
  return query select ip_hits <= 120,
    greatest(1, ceil(extract(epoch from bucket + interval '15 minutes' - pg_catalog.now()))::integer);
end;
$$;
revoke all on function public.consume_guest_tracking_rate_limit(text) from public, anon, authenticated, service_role;
grant execute on function public.consume_guest_tracking_rate_limit(text) to service_role;

create or replace function public.create_order_internal(
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
returns table (order_id uuid, order_number text, total_centavos bigint, created boolean, status public.order_status)
language plpgsql security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  guest_request boolean := coalesce(pg_catalog.current_setting('app.guest_order_request', true), '') = 'on';
  distance_km double precision;
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
  if caller is null and not guest_request then raise exception using errcode = '28000', message = 'authenticated user is required'; end if;
  if guest_request and (caller is not null or coalesce(auth.jwt() ->> 'role', '') <> 'service_role') then
    raise exception using errcode = '42501', message = 'guest order context is invalid';
  end if;
  if guest_request and p_source <> 'CUSTOMER_WEB' then
    raise exception using errcode = '42501', message = 'guest order source is invalid';
  end if;
  if nullif(btrim(p_client_operation_id), '') is null then raise exception using errcode = '22023', message = 'order operation id is required'; end if;
  if p_source not in ('CUSTOMER_APP', 'CUSTOMER_WEB', 'OWNER_WALK_IN') then raise exception using errcode = '22023', message = 'unsupported order source'; end if;
  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) < 1 or jsonb_array_length(p_items) > 20 then raise exception using errcode = '22023', message = 'order must contain 1 to 20 items'; end if;

  select * into existing from public.orders where client_operation_id = btrim(p_client_operation_id) for update;
  if existing.id is not null then return query select existing.id, existing.order_number, existing.total_centavos, false, existing.status; return; end if;
  select * into station from public.stations where id = p_station_id for share;
  if station.id is null or not station.is_active or station.deleted_at is not null then raise exception using errcode = '22023', message = 'station is unavailable'; end if;
  if caller is not null then
    select * into profile from public.profiles where id = caller;
    if profile.id is null or profile.account_state <> 'ACTIVE' then raise exception using errcode = '42501', message = 'account is not active'; end if;
  end if;
  if guest_request then
    if not station.is_accepting_online_orders or not station.is_open then
      raise exception using errcode = '42501', message = 'station is not accepting online orders';
    end if;
  elsif p_source = 'OWNER_WALK_IN' then
    if not app_private.can_operate_station(p_station_id) then raise exception using errcode = '42501', message = 'station access denied'; end if;
  elsif profile.role <> 'CUSTOMER' or not profile.setup_complete or (not profile.email_verified and not profile.phone_verified) or profile.preferred_station_id is distinct from p_station_id or not station.is_accepting_online_orders or not station.is_open then
    raise exception using errcode = '42501', message = 'customer order access denied';
  end if;
  if p_delivery_mode = 'DELIVERY' and nullif(btrim(p_delivery_address), '') is null then raise exception using errcode = '22023', message = 'delivery address is required'; end if;
  if (p_delivery_latitude is null) <> (p_delivery_longitude is null) then raise exception using errcode = '22023', message = 'delivery coordinates must be supplied together'; end if;
  if p_delivery_latitude is not null and (p_delivery_latitude not between -90 and 90 or p_delivery_longitude not between -180 and 180) then raise exception using errcode = '22023', message = 'delivery coordinates are invalid'; end if;
  if guest_request then
    if upper(coalesce(p_payment_method, '')) not in ('CASH', 'COD', 'GCASH', 'MAYA') then
      raise exception using errcode = '22023', message = 'select a payment method supported by this station';
    end if;
    if upper(p_payment_method) = 'CASH' and not station.cash_payment_enabled then
      raise exception using errcode = '22023', message = 'cash payment is not available at this station';
    elsif upper(p_payment_method) = 'COD' and (not station.cod_payment_enabled or p_delivery_mode <> 'DELIVERY') then
      raise exception using errcode = '22023', message = 'cash on delivery is not available for this order';
    elsif upper(p_payment_method) = 'GCASH' and not station.gcash_payment_enabled then
      raise exception using errcode = '22023', message = 'GCash is not available at this station';
    elsif upper(p_payment_method) = 'MAYA' and not station.maya_payment_enabled then
      raise exception using errcode = '22023', message = 'Maya is not available at this station';
    end if;
    if p_delivery_mode = 'DELIVERY'
       and (station.delivery_radius_km::text = 'NaN' or station.delivery_radius_km < 0) then
      raise exception using errcode = '22023', message = 'station delivery settings are unavailable';
    end if;
    if p_delivery_mode = 'DELIVERY' and coalesce(station.delivery_radius_km, 0) > 0 then
      if station.latitude is null or station.longitude is null
         or station.latitude not between -90 and 90
         or station.longitude not between -180 and 180 then
        raise exception using errcode = '22023', message = 'station delivery location is unavailable';
      end if;
      if p_delivery_latitude is null or p_delivery_longitude is null then
        raise exception using errcode = '22023', message = 'share your delivery location so the station can confirm its delivery area';
      end if;
      distance_km := 6371.0088 * acos(least(1.0, greatest(-1.0,
        sin(radians(station.latitude)) * sin(radians(p_delivery_latitude))
        + cos(radians(station.latitude)) * cos(radians(p_delivery_latitude))
        * cos(radians(p_delivery_longitude) - radians(station.longitude))
      )));
      if distance_km > station.delivery_radius_km then
        raise exception using errcode = '22023', message = 'delivery address is outside this station’s service area';
      end if;
    end if;
  end if;
  select count(*) into item_count from (select (value->>'product_id')::uuid as product_id from jsonb_array_elements(p_items) value) lines;
  if item_count <> (select count(distinct (value->>'product_id')::uuid) from jsonb_array_elements(p_items) value) then raise exception using errcode = '22023', message = 'duplicate products are not allowed'; end if;

  for input_line in select (value->>'product_id')::uuid as product_id, (value->>'quantity')::integer as quantity from jsonb_array_elements(p_items) value order by (value->>'product_id')::uuid loop
    if input_line.quantity is null or input_line.quantity < 1 or input_line.quantity > 99 then raise exception using errcode = '22023', message = 'invalid order quantity'; end if;
    select * into product from public.products where id = input_line.product_id and station_id = p_station_id and is_active and is_available for share;
    if product.id is null then raise exception using errcode = '23503', message = 'product is unavailable'; end if;
    insert into public.inventory_balances (station_id, product_id) values (p_station_id, product.id) on conflict (station_id, product_id) do nothing;
    select * into balance from public.inventory_balances where station_id = p_station_id and product_id = product.id for update;
    if balance.quantity < input_line.quantity then raise exception using errcode = '23514', message = 'insufficient inventory'; end if;
    line_regular_price := product.price_centavos; line_unit_price := line_regular_price;
    if product.promotion_is_active and (product.promotion_starts_at is null or product.promotion_starts_at <= now_ms) and (product.promotion_ends_at is null or product.promotion_ends_at >= now_ms) and (product.promotion_minimum_quantity is null or input_line.quantity >= product.promotion_minimum_quantity) then
      if product.promotion_type in ('FIXED_PRICE', 'QUANTITY_BREAK') and product.promotional_price_centavos is not null then line_unit_price := least(line_regular_price, product.promotional_price_centavos);
      elsif product.promotion_type = 'PERCENTAGE' and product.promotion_percent_bps is not null then line_unit_price := floor(line_regular_price * (10000 - product.promotion_percent_bps)::numeric / 10000)::bigint;
      end if;
    end if;
    line_subtotal := line_unit_price * input_line.quantity; line_discount := (line_regular_price - line_unit_price) * input_line.quantity;
    subtotal := subtotal + line_regular_price * input_line.quantity; discount := discount + line_discount;
  end loop;
  delivery_fee := case when p_delivery_mode = 'DELIVERY' and p_source in ('CUSTOMER_APP', 'CUSTOMER_WEB') then station.delivery_fee_centavos when p_delivery_mode = 'DELIVERY' then coalesce(p_delivery_fee_centavos, 0) else 0 end;
  if delivery_fee < 0 then raise exception using errcode = '22023', message = 'delivery fee is invalid'; end if;
  total := subtotal - discount + delivery_fee; generated_order_number := app_private.next_order_number(p_station_id); order_uuid := gen_random_uuid();

  insert into public.orders (id, client_operation_id, station_id, customer_id, customer_uid, order_number, customer_name, customer_phone, delivery_mode, delivery_address, delivery_place_id, delivery_latitude, delivery_longitude, subtotal_centavos, delivery_fee_centavos, discount_centavos, tax_centavos, total_centavos, payment_method, payment_status, status, customer_note, requested_at, accepted_at, ready_at, completed_at, order_source)
  values (order_uuid, btrim(p_client_operation_id), p_station_id, p_customer_id, case when p_source in ('CUSTOMER_APP', 'CUSTOMER_WEB') and not guest_request then caller else null end, generated_order_number, coalesce(nullif(btrim(p_customer_name), ''), profile.display_name, 'Walk-in Customer'), coalesce(p_customer_phone, profile.phone, ''), p_delivery_mode, case when p_delivery_mode = 'PICKUP' then 'Station pickup' else btrim(p_delivery_address) end, nullif(btrim(p_delivery_place_id), ''), p_delivery_latitude, p_delivery_longitude, subtotal, delivery_fee, discount, 0, total, upper(coalesce(nullif(btrim(p_payment_method), ''), 'CASH')), case when p_source = 'OWNER_WALK_IN' then 'PAID' else 'PENDING' end, case when p_source = 'OWNER_WALK_IN' and p_delivery_mode = 'PICKUP' then 'COMPLETED'::public.order_status else 'PENDING'::public.order_status end, nullif(btrim(p_customer_note), ''), now_ms, case when p_source = 'OWNER_WALK_IN' then now_ms else null end, case when p_source = 'OWNER_WALK_IN' then now_ms else null end, case when p_source = 'OWNER_WALK_IN' and p_delivery_mode = 'PICKUP' then now_ms else null end, p_source);

  for input_line in select (value->>'product_id')::uuid as product_id, (value->>'quantity')::integer as quantity from jsonb_array_elements(p_items) value order by (value->>'product_id')::uuid loop
    select * into product from public.products where id = input_line.product_id for share; line_regular_price := product.price_centavos; line_unit_price := line_regular_price;
    if product.promotion_is_active and (product.promotion_starts_at is null or product.promotion_starts_at <= now_ms) and (product.promotion_ends_at is null or product.promotion_ends_at >= now_ms) and (product.promotion_minimum_quantity is null or input_line.quantity >= product.promotion_minimum_quantity) then
      if product.promotion_type in ('FIXED_PRICE', 'QUANTITY_BREAK') and product.promotional_price_centavos is not null then line_unit_price := least(line_regular_price, product.promotional_price_centavos); elsif product.promotion_type = 'PERCENTAGE' and product.promotion_percent_bps is not null then line_unit_price := floor(line_regular_price * (10000 - product.promotion_percent_bps)::numeric / 10000)::bigint; end if;
    end if;
    line_subtotal := line_unit_price * input_line.quantity;
    insert into public.order_items (order_id, product_id, product_name_snapshot, product_type_snapshot, size_label_snapshot, unit_price_centavos, quantity, subtotal_centavos) values (order_uuid, product.id, product.name, product.product_type, product.size_label, line_unit_price, input_line.quantity, line_subtotal);
    update public.inventory_balances set quantity = quantity - input_line.quantity, version = version + 1, updated_at = now() where id = (select b.id from public.inventory_balances b where b.station_id = p_station_id and b.product_id = product.id);
    movement_operation := 'order:' || order_uuid::text || ':deduct:' || product.id::text;
    insert into public.inventory_movements (station_id, product_id, operation_id, delta, quantity_after, reason, order_id, created_by, movement_type, source_id) values (p_station_id, product.id, movement_operation, -input_line.quantity, (select quantity from public.inventory_balances b where b.station_id = p_station_id and b.product_id = product.id), 'Order sale', order_uuid, caller, 'ORDER_DEDUCTION', order_uuid::text);
  end loop;
  return query select order_uuid, generated_order_number, total, true, (select o.status from public.orders o where o.id = order_uuid);
end;
$$;

create or replace function public.create_guest_order(
  p_station_code text,
  p_request jsonb,
  p_tracking_token text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  station public.stations%rowtype;
  existing app_private.guest_web_orders%rowtype;
  normalized_phone text;
  v_operation_id text;
  delivery_mode_value public.delivery_mode;
  payment_method_value text;
  delivery_address_value text;
  latitude_value double precision;
  longitude_value double precision;
  request_fingerprint_value text;
  items_value jsonb;
  payload_hash_value bytea;
  token_hash_value bytea;
  ordered record;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role' then
    raise exception using errcode = '42501', message = 'guest order access denied';
  end if;
  if p_request is null or jsonb_typeof(p_request) <> 'object'
     or p_station_code is null or p_station_code !~ '^[A-Za-z0-9_-]{6,64}$'
     or p_tracking_token is null or p_tracking_token !~ '^[A-Za-z0-9_-]{40,60}$' then
    raise exception using errcode = '22023', message = 'order request is invalid';
  end if;
  if exists (
    select 1 from jsonb_object_keys(p_request) as keys(key)
    where key not in ('operation_id','customer_name','customer_phone','delivery_mode',
      'delivery_address','delivery_place_id','latitude','longitude','payment_method',
      'customer_note','accepted_rules_version','items','request_fingerprint')
  ) then
    raise exception using errcode = '22023', message = 'order request contains unsupported fields';
  end if;
  v_operation_id := p_request ->> 'operation_id';
  if v_operation_id is null or v_operation_id !~ '^guest:[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' then
    raise exception using errcode = '22023', message = 'order retry key is invalid';
  end if;
  request_fingerprint_value := p_request ->> 'request_fingerprint';
  if request_fingerprint_value is not null and request_fingerprint_value !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'order request fingerprint is invalid';
  end if;
  if length(btrim(coalesce(p_request ->> 'customer_name', ''))) not between 2 and 100 then
    raise exception using errcode = '22023', message = 'enter your name';
  end if;
  normalized_phone := p_request ->> 'customer_phone';
  if normalized_phone is null or normalized_phone !~ '^\+639[0-9]{9}$' then
    raise exception using errcode = '22023', message = 'enter a valid Philippine mobile number';
  end if;
  if upper(coalesce(p_request ->> 'delivery_mode', '')) not in ('PICKUP', 'DELIVERY') then
    raise exception using errcode = '22023', message = 'choose pickup or delivery';
  end if;
  delivery_mode_value := upper(p_request ->> 'delivery_mode')::public.delivery_mode;
  payment_method_value := upper(coalesce(p_request ->> 'payment_method', ''));
  if payment_method_value not in ('CASH', 'COD', 'GCASH', 'MAYA') then
    raise exception using errcode = '22023', message = 'choose an available payment method';
  end if;
  delivery_address_value := btrim(coalesce(p_request ->> 'delivery_address', ''));
  if delivery_mode_value = 'DELIVERY' and length(delivery_address_value) < 8 then
    raise exception using errcode = '22023', message = 'enter your delivery address';
  end if;
  if length(coalesce(p_request ->> 'customer_note', '')) > 500 then
    raise exception using errcode = '22023', message = 'order note is too long';
  end if;
  if jsonb_typeof(p_request -> 'items') <> 'array'
     or jsonb_array_length(p_request -> 'items') < 1
     or jsonb_array_length(p_request -> 'items') > 20 then
    raise exception using errcode = '22023', message = 'your cart must contain 1 to 20 products';
  end if;
  items_value := p_request -> 'items';
  latitude_value := nullif(p_request ->> 'latitude', '')::double precision;
  longitude_value := nullif(p_request ->> 'longitude', '')::double precision;
  payload_hash_value := case
    when request_fingerprint_value is not null then extensions.digest(request_fingerprint_value, 'sha256')
    else extensions.digest(jsonb_build_object('station_code', p_station_code, 'request', p_request)::text, 'sha256')
  end;
  token_hash_value := extensions.digest(p_tracking_token, 'sha256');

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_operation_id, 0));
  select * into existing from app_private.guest_web_orders
  where app_private.guest_web_orders.operation_id = v_operation_id
  for update;
  if existing.operation_id is not null then
    if existing.payload_hash is distinct from payload_hash_value
       or existing.tracking_token_hash is distinct from token_hash_value then
      raise exception using errcode = '22023', message = 'this retry key was already used for a different order request';
    end if;
    return (
      select jsonb_build_object('order_id', o.id, 'order_number', o.order_number,
        'status', o.status::text, 'total_centavos', o.total_centavos, 'created', false)
      from public.orders o where o.id = existing.order_id
    );
  end if;

  select * into station from public.stations s
  where s.public_station_code = p_station_code
    and s.is_active and s.deleted_at is null
  for share;
  if station.id is null then
    raise exception using errcode = '22023', message = 'this station link is unavailable';
  end if;
  if nullif(btrim(station.business_rules_text), '') is not null
     and p_request ->> 'accepted_rules_version' is distinct from station.business_rules_version::text then
    raise exception using errcode = '22023', message = 'station ordering rules changed; review and accept them before placing your order';
  end if;

  perform pg_catalog.set_config('app.guest_order_request', 'on', true);
  select * into ordered from public.create_order_internal(
    station.id, v_operation_id, 'CUSTOMER_WEB', items_value, null,
    btrim(p_request ->> 'customer_name'), normalized_phone,
    delivery_mode_value, delivery_address_value,
    nullif(btrim(p_request ->> 'delivery_place_id'), ''),
    latitude_value, longitude_value, null, payment_method_value,
    nullif(btrim(coalesce(p_request ->> 'customer_note', '')), '')
  );
  insert into app_private.guest_web_orders (
    operation_id, order_id, payload_hash, tracking_token_hash
  ) values (
    v_operation_id, ordered.order_id, payload_hash_value, token_hash_value
  );
  return jsonb_build_object('order_id', ordered.order_id,
    'order_number', ordered.order_number, 'status', ordered.status::text,
    'total_centavos', ordered.total_centavos, 'created', ordered.created);
end;
$$;
revoke all on function public.create_guest_order(text, jsonb, text) from public, anon, authenticated, service_role;
grant execute on function public.create_guest_order(text, jsonb, text) to service_role;

create or replace function public.resume_guest_order(
  p_operation_id text,
  p_request_fingerprint text,
  p_tracking_token text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  existing app_private.guest_web_orders%rowtype;
  payload_hash_value bytea;
  token_hash_value bytea;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role'
     or p_operation_id is null or p_operation_id !~ '^guest:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
     or p_request_fingerprint is null or p_request_fingerprint !~ '^[0-9a-f]{64}$'
     or p_tracking_token is null or p_tracking_token !~ '^[A-Za-z0-9_-]{40,60}$' then
    raise exception using errcode = '42501', message = 'guest order retry is unavailable';
  end if;
  payload_hash_value := extensions.digest(p_request_fingerprint, 'sha256');
  token_hash_value := extensions.digest(p_tracking_token, 'sha256');
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_operation_id, 0));
  select * into existing from app_private.guest_web_orders g
  where g.operation_id = p_operation_id
  for update;
  if existing.operation_id is null then return null; end if;
  if existing.payload_hash is distinct from payload_hash_value
     or existing.tracking_token_hash is distinct from token_hash_value then
    raise exception using errcode = '22023', message = 'this retry key was already used for a different order request';
  end if;
  return (
    select jsonb_build_object('order_id', o.id, 'order_number', o.order_number,
      'status', o.status::text, 'total_centavos', o.total_centavos, 'created', false)
    from public.orders o where o.id = existing.order_id
  );
end;
$$;
revoke all on function public.resume_guest_order(text, text, text) from public, anon, authenticated, service_role;
grant execute on function public.resume_guest_order(text, text, text) to service_role;

create or replace function public.get_guest_order_status(p_order_id uuid, p_tracking_token text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  order_row public.orders%rowtype;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role'
     or p_tracking_token is null or p_tracking_token !~ '^[A-Za-z0-9_-]{40,60}$' then
    raise exception using errcode = '42501', message = 'order tracking is unavailable';
  end if;
  select o.* into order_row
  from app_private.guest_web_orders g
  join public.orders o on o.id = g.order_id
  where g.order_id = p_order_id
    and g.tracking_token_hash = extensions.digest(p_tracking_token, 'sha256');
  if order_row.id is null then
    raise exception using errcode = '42501', message = 'order tracking is unavailable';
  end if;
  return jsonb_build_object(
    'order_id', order_row.id,
    'order_number', order_row.order_number,
    'status', order_row.status::text,
    'total_centavos', order_row.total_centavos,
    'station_name', (select s.name from public.stations s where s.id = order_row.station_id),
    'updated_at', order_row.updated_at
  );
end;
$$;
revoke all on function public.get_guest_order_status(uuid, text) from public, anon, authenticated, service_role;
grant execute on function public.get_guest_order_status(uuid, text) to service_role;

revoke execute on function app_private.validate_order_insert_context() from public, anon, authenticated, service_role;
grant execute on function app_private.validate_order_insert_context() to postgres, authenticated, service_role;
