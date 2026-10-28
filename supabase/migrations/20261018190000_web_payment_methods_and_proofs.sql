-- Customer web checkout discounts, account billing, and private online proof receipts.
begin;

CREATE OR REPLACE FUNCTION public.create_order_internal(p_station_id uuid, p_client_operation_id text, p_source text, p_items jsonb, p_customer_id uuid DEFAULT NULL::uuid, p_customer_name text DEFAULT ''::text, p_customer_phone text DEFAULT ''::text, p_delivery_mode delivery_mode DEFAULT 'PICKUP'::delivery_mode, p_delivery_address text DEFAULT ''::text, p_delivery_place_id text DEFAULT NULL::text, p_delivery_latitude double precision DEFAULT NULL::double precision, p_delivery_longitude double precision DEFAULT NULL::double precision, p_delivery_fee_centavos bigint DEFAULT NULL::bigint, p_payment_method text DEFAULT 'CASH'::text, p_customer_note text DEFAULT NULL::text)
 RETURNS TABLE(order_id uuid, order_number text, total_centavos bigint, created boolean, status order_status)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
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
    if upper(coalesce(p_payment_method, '')) not in ('CASH', 'COD', 'GCASH', 'MAYA', 'CLIENT_ACCOUNT') then
      raise exception using errcode = '22023', message = 'select a payment method supported by this station';
    end if;
    if upper(p_payment_method) = 'CLIENT_ACCOUNT'
       and (p_source <> 'CUSTOMER_WEB' or p_delivery_mode <> 'PICKUP') then
      raise exception using errcode = '22023', message = 'account balance is only available for pickup orders';
    elsif upper(p_payment_method) = 'CLIENT_ACCOUNT' then
      perform app_private.require_plan_feature(station.organization_id, 'ACCOUNTS_RECEIVABLE');
    elsif upper(p_payment_method) = 'CASH' and p_delivery_mode <> 'PICKUP' then
      raise exception using errcode = '22023', message = 'cash is only available for pickup orders';
    elsif upper(p_payment_method) = 'CASH' and not station.cash_payment_enabled then
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
      if product.promotion_type = 'FIXED_PRICE' and product.promotional_price_centavos is not null then
        line_unit_price := least(line_regular_price, product.promotional_price_centavos);
      elsif product.promotion_type in ('PERCENTAGE', 'QUANTITY_BREAK') and product.promotion_percent_bps is not null then
        line_unit_price := floor(line_regular_price * (10000 - product.promotion_percent_bps)::numeric / 10000)::bigint;
      elsif product.promotion_type = 'QUANTITY_BREAK' and product.promotional_price_centavos is not null then
        line_unit_price := least(line_regular_price, product.promotional_price_centavos);
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
      if product.promotion_type = 'FIXED_PRICE' and product.promotional_price_centavos is not null then line_unit_price := least(line_regular_price, product.promotional_price_centavos);
      elsif product.promotion_type in ('PERCENTAGE', 'QUANTITY_BREAK') and product.promotion_percent_bps is not null then line_unit_price := floor(line_regular_price * (10000 - product.promotion_percent_bps)::numeric / 10000)::bigint;
      elsif product.promotion_type = 'QUANTITY_BREAK' and product.promotional_price_centavos is not null then line_unit_price := least(line_regular_price, product.promotional_price_centavos);
      end if;
    end if;
    line_subtotal := line_unit_price * input_line.quantity;
    insert into public.order_items (order_id, product_id, product_name_snapshot, product_type_snapshot, size_label_snapshot, unit_price_centavos, quantity, subtotal_centavos) values (order_uuid, product.id, product.name, product.product_type, product.size_label, line_unit_price, input_line.quantity, line_subtotal);
    update public.inventory_balances set quantity = quantity - input_line.quantity, version = version + 1, updated_at = now() where id = (select b.id from public.inventory_balances b where b.station_id = p_station_id and b.product_id = product.id);
    movement_operation := 'order:' || order_uuid::text || ':deduct:' || product.id::text;
    insert into public.inventory_movements (station_id, product_id, operation_id, delta, quantity_after, reason, order_id, created_by, movement_type, source_id) values (p_station_id, product.id, movement_operation, -input_line.quantity, (select quantity from public.inventory_balances b where b.station_id = p_station_id and b.product_id = product.id), 'Order sale', order_uuid, caller, 'ORDER_DEDUCTION', order_uuid::text);
  end loop;
  return query select order_uuid, generated_order_number, total, true, (select o.status from public.orders o where o.id = order_uuid);
end;
$function$;

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
  if payment_method_value = 'CLIENT_ACCOUNT' and delivery_mode_value <> 'PICKUP' then
    raise exception using errcode = '22023', message = 'account balance is only available for pickup orders';
  end if;
  if payment_method_value not in ('CASH', 'COD', 'GCASH', 'MAYA', 'CLIENT_ACCOUNT') then
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

create or replace function public.create_guest_order_with_container_sources(
  p_station_code text,
  p_request jsonb,
  p_tracking_token text
)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  result jsonb;
  item jsonb;
  order_id_value uuid;
  station_id_value uuid;
  customer_id_value uuid;
  customer_name_value text;
  customer_phone_value text;
  product_id_value uuid;
  product_type_value text;
  container_source_value text;
  updated_items integer;
  linked_orders integer;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role' then
    raise exception using errcode = '42501', message = 'guest order access denied';
  end if;

  if upper(coalesce(p_request ->> 'payment_method', '')) = 'CLIENT_ACCOUNT' then
    if upper(coalesce(p_request ->> 'delivery_mode', '')) <> 'PICKUP' then
      raise exception using errcode = '22023', message = 'account balance is only available for pickup orders';
    end if;
    if not exists (
      select 1 from public.stations s
      join public.customers c on c.station_id = s.id
      where s.public_station_code = p_station_code
        and s.is_active and s.deleted_at is null
        and c.is_active and c.deleted_at is null
        and app_private.normalize_philippine_customer_phone(c.phone)
          = app_private.normalize_philippine_customer_phone(p_request ->> 'customer_phone')
    ) then
      raise exception using errcode = '22023', message = 'account balance is available only to existing station customers';
    end if;
  end if;

  result := public.create_guest_order(p_station_code, p_request, p_tracking_token);
  order_id_value := (result ->> 'order_id')::uuid;

  for item in
    select value
    from pg_catalog.jsonb_array_elements(p_request -> 'items') as item_rows(value)
  loop
    product_id_value := (item ->> 'product_id')::uuid;
    select order_item.product_type_snapshot
      into product_type_value
      from public.order_items as order_item
     where order_item.order_id = order_id_value
       and order_item.product_id = product_id_value
     for update;

    if not found then
      raise exception using errcode = '22023', message = 'order item is unavailable';
    end if;

    container_source_value := nullif(pg_catalog.upper(pg_catalog.btrim(item ->> 'container_source')), '');
    if product_type_value = 'REFILL' then
      container_source_value := coalesce(container_source_value, 'STATION_EXCHANGE');
      if container_source_value not in ('CUSTOMER_OWNED', 'STATION_EXCHANGE', 'STATION_BORROW') then
        raise exception using errcode = '22023', message = 'container option is invalid';
      end if;
      update public.order_items
         set container_source = container_source_value
       where order_id = order_id_value
         and product_id = product_id_value;
      get diagnostics updated_items = row_count;
      if updated_items <> 1 then
        raise exception using errcode = '22023', message = 'order item is unavailable';
      end if;
    elsif container_source_value is not null then
      raise exception using errcode = '22023', message = 'container option is only available for refills';
    end if;
  end loop;

  select o.station_id, o.customer_id, o.customer_name, o.customer_phone
    into station_id_value, customer_id_value, customer_name_value, customer_phone_value
    from public.orders as o
   where o.id = order_id_value
     and o.order_source = 'CUSTOMER_WEB'
   for update;

  if not found then
    raise exception using errcode = '22023', message = 'guest order is unavailable';
  end if;

  if customer_id_value is null then
    customer_id_value := app_private.get_or_create_guest_station_customer(
      station_id_value,
      customer_name_value,
      customer_phone_value
    );

    if customer_id_value is not null then
      update public.orders
         set customer_id = customer_id_value,
             updated_at = pg_catalog.now()
       where id = order_id_value
         and customer_id is null;
      get diagnostics linked_orders = row_count;

      if linked_orders = 1 then
        update public.customers
           set total_orders = total_orders + 1,
               updated_at = pg_catalog.now()
         where id = customer_id_value
           and station_id = station_id_value;
      end if;
    end if;
  end if;

  return result;
end;
$$;

create or replace function public.get_public_station_account_receivable_eligibility(p_station_ids uuid[])
returns table (station_id uuid, is_account_balance_available boolean)
language plpgsql stable security definer set search_path = ''
as $$
begin
  if p_station_ids is null or pg_catalog.cardinality(p_station_ids) > 100 then
    raise exception using errcode = '22023', message = 'invalid station id list';
  end if;
  return query
  select ps.station_id, coalesce(pf.enabled, false)
    from public.public_stations ps
    left join public.plan_features pf
      on pf.plan_id = app_private.effective_plan_id(ps.organization_id)
     and pf.feature = 'ACCOUNTS_RECEIVABLE'
   where ps.station_id = any(p_station_ids)
   order by ps.station_id;
end;
$$;
revoke all on function public.get_public_station_account_receivable_eligibility(uuid[])
  from public, anon, authenticated, service_role;
grant execute on function public.get_public_station_account_receivable_eligibility(uuid[])
  to anon, authenticated, service_role;
comment on function public.get_public_station_account_receivable_eligibility(uuid[]) is
  'Returns only station IDs and whether the effective plan enables customer account billing.';

create or replace function public.record_guest_online_payment(
  p_order_id uuid, p_tracking_token text, p_payment_method text,
  p_reference_number text, p_proof_object_path text
)
returns table (payment_id uuid, order_id uuid, status text, amount_centavos bigint, created boolean)
language plpgsql security definer set search_path = ''
as $$
declare
  order_row public.orders%rowtype;
  existing public.payments%rowtype;
  organization_id_value uuid;
  normalized_method text := pg_catalog.upper(pg_catalog.btrim(p_payment_method));
  normalized_reference text := pg_catalog.btrim(coalesce(p_reference_number, ''));
  expected_prefix text;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role' then
    raise exception using errcode = '42501', message = 'guest payment access denied';
  end if;
  if p_order_id is null or p_tracking_token is null
     or p_tracking_token !~ '^[A-Za-z0-9_-]{40,60}$'
     or normalized_method not in ('GCASH', 'MAYA')
     or normalized_reference = '' or pg_catalog.length(normalized_reference) > 100
     or normalized_reference ~ '[[:cntrl:]]' or p_proof_object_path is null then
    raise exception using errcode = '22023', message = 'payment reference and proof are required';
  end if;
  if not exists (
    select 1 from app_private.guest_web_orders g
     where g.order_id = p_order_id
       and g.tracking_token_hash = extensions.digest(p_tracking_token, 'sha256')
  ) then
    raise exception using errcode = '42501', message = 'guest order is unavailable';
  end if;

  select * into order_row from public.orders o where o.id = p_order_id for update;
  if order_row.id is null or order_row.order_source <> 'CUSTOMER_WEB'
     or order_row.payment_method <> normalized_method
     or order_row.status in ('CANCELLED', 'REJECTED') then
    raise exception using errcode = '22023', message = 'online payment is unavailable for this order';
  end if;
  select s.organization_id into organization_id_value
    from public.stations s where s.id = order_row.station_id;
  expected_prefix := 'organizations/' || organization_id_value::text
    || '/stations/' || order_row.station_id::text
    || '/customer-payment-proofs/' || order_row.id::text || '/';
  if pg_catalog.left(p_proof_object_path, pg_catalog.length(expected_prefix)) <> expected_prefix
     or pg_catalog.substr(p_proof_object_path, pg_catalog.length(expected_prefix) + 1)
       !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.(jpg|png|webp)$'
     or not exists (
       select 1 from storage.objects so
        where so.bucket_id = 'payment-assets' and so.name = p_proof_object_path
     ) then
    raise exception using errcode = '22023', message = 'online payment proof is unavailable';
  end if;

  select * into existing from public.payments p
   where p.operation_id = 'guest-payment:' || p_order_id::text;
  if existing.id is not null then
    if existing.order_id <> p_order_id or existing.payment_method <> normalized_method
       or existing.amount_centavos <> order_row.total_centavos
       or existing.reference_number is distinct from normalized_reference then
      raise exception using errcode = '22023', message = 'a different payment proof was already submitted for this order';
    end if;
    return query select existing.id, existing.order_id, existing.status, existing.amount_centavos, false;
    return;
  end if;
  if order_row.payment_status = 'PAID' or order_row.total_centavos <= 0
     or exists (select 1 from public.payments p where p.order_id = p_order_id and p.status = 'PAID') then
    raise exception using errcode = '55000', message = 'order has no outstanding online payment';
  end if;

  insert into public.payments (
    organization_id, station_id, customer_id, order_id, payment_method,
    amount_centavos, status, reference_number, provider, provider_transaction_id,
    metadata, operation_id, created_at, paid_at, updated_at
  ) values (
    organization_id_value, order_row.station_id, order_row.customer_id, order_row.id,
    normalized_method, order_row.total_centavos, 'PAID', normalized_reference,
    null, null,
    pg_catalog.jsonb_build_object(
      'payment_proof_object_path', p_proof_object_path,
      'payment_source', 'CUSTOMER_WEB_SUBMITTED_PROOF'
    ),
    'guest-payment:' || p_order_id::text, pg_catalog.now(), pg_catalog.now(), pg_catalog.now()
  ) returning * into existing;
  update public.orders o set payment_status = 'PAID', updated_at = pg_catalog.now()
   where o.id = order_row.id;
  return query select existing.id, existing.order_id, existing.status, existing.amount_centavos, true;
end;
$$;
revoke all on function public.record_guest_online_payment(uuid, text, text, text, text)
  from public, anon, authenticated, service_role;
grant execute on function public.record_guest_online_payment(uuid, text, text, text, text)
  to service_role;

commit;
