-- Apply Owner-defined percentage discounts for eligible quantity-break promotions
-- in both the order-total and order-item snapshot calculations. Preserve legacy
-- fixed-price quantity-break rows when they have no percentage value.
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
$function$

