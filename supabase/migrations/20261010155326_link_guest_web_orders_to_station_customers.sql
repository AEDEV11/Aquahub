-- Associate guest browser orders with the station's customer record by
-- station-scoped Philippine mobile number. A full customer list must not
-- prevent an otherwise valid guest order from being placed.

create or replace function app_private.normalize_philippine_customer_phone(p_phone text)
returns text
language sql
immutable
set search_path = ''
as $$
  with digits as (
    select pg_catalog.regexp_replace(coalesce(p_phone, ''), '\D', '', 'g') as value
  ),
  normalized as (
    select case
      when value ~ '^0[0-9]{10}$' then '63' || pg_catalog.substr(value, 2)
      when value ~ '^9[0-9]{9}$' then '63' || value
      when value ~ '^63[0-9]{10}$' then value
      else null
    end as value
    from digits
  )
  select case when value ~ '^639[0-9]{9}$' then value else null end
  from normalized;
$$;

revoke all on function app_private.normalize_philippine_customer_phone(text)
  from public, anon, authenticated, service_role;

create or replace function app_private.get_or_create_guest_station_customer(
  p_station_id uuid,
  p_customer_name text,
  p_customer_phone text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  normalized_phone text;
  customer_id_value uuid;
  error_message text;
begin
  normalized_phone := app_private.normalize_philippine_customer_phone(p_customer_phone);
  if p_station_id is null or normalized_phone is null then
    return null;
  end if;

  -- Serialize different guest orders for the same station and phone so that
  -- simultaneous checkouts cannot create duplicate station-customer rows.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_station_id::text || ':' || normalized_phone, 0)
  );

  select c.id
    into customer_id_value
    from public.customers as c
   where c.station_id = p_station_id
     and c.is_active
     and c.deleted_at is null
     and app_private.normalize_philippine_customer_phone(c.phone) = normalized_phone
   order by c.created_at, c.id
   limit 1
   for update;

  if customer_id_value is not null then
    return customer_id_value;
  end if;

  begin
    insert into public.customers (station_id, name, phone)
    values (
      p_station_id,
      coalesce(nullif(pg_catalog.btrim(p_customer_name), ''), 'Web Customer'),
      '+' || normalized_phone
    )
    returning id into customer_id_value;
  exception
    when check_violation or insufficient_privilege then
      get stacked diagnostics error_message = message_text;
      if error_message in ('subscription limit reached', 'subscription feature is not available') then
        -- Preserve checkout. The order keeps its contact details but remains
        -- unlinked until the station has room for another customer.
        return null;
      end if;
      raise;
  end;

  return customer_id_value;
end;
$$;

revoke all on function app_private.get_or_create_guest_station_customer(uuid, text, text)
  from public, anon, authenticated, service_role;
grant execute on function app_private.get_or_create_guest_station_customer(uuid, text, text)
  to service_role;

-- Keep the existing validated, idempotent order transaction. Linking a
-- customer and updating its order count happen in this same transaction.
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

-- Older browser orders already contain a validated phone but were never
-- connected to station customers. Keep this helper idempotent so the
-- migration backfill can also be regression-tested on legacy-shaped rows.
create or replace function app_private.backfill_guest_web_order_customers()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  guest_group record;
  customer_id_value uuid;
  linked_orders integer;
begin
  for guest_group in
    select normalized.station_id,
           normalized.phone_digits,
           (pg_catalog.array_agg(normalized.customer_name order by normalized.created_at desc, normalized.id desc))[1] as customer_name,
           (pg_catalog.array_agg(normalized.customer_phone order by normalized.created_at desc, normalized.id desc))[1] as customer_phone
      from (
        select o.id,
               o.station_id,
               o.customer_name,
               o.customer_phone,
               o.created_at,
               app_private.normalize_philippine_customer_phone(o.customer_phone) as phone_digits
          from public.orders as o
         where o.order_source = 'CUSTOMER_WEB'
           and o.customer_id is null
      ) as normalized
     where normalized.phone_digits is not null
     group by normalized.station_id, normalized.phone_digits
     order by normalized.station_id, normalized.phone_digits
  loop
    customer_id_value := app_private.get_or_create_guest_station_customer(
      guest_group.station_id,
      guest_group.customer_name,
      guest_group.customer_phone
    );

    if customer_id_value is null then
      continue;
    end if;

    update public.orders as o
       set customer_id = customer_id_value,
           updated_at = pg_catalog.now()
     where o.station_id = guest_group.station_id
       and o.order_source = 'CUSTOMER_WEB'
       and o.customer_id is null
       and app_private.normalize_philippine_customer_phone(o.customer_phone) = guest_group.phone_digits;
    get diagnostics linked_orders = row_count;

    if linked_orders > 0 then
      update public.customers
         set total_orders = total_orders + linked_orders,
             updated_at = pg_catalog.now()
       where id = customer_id_value
         and station_id = guest_group.station_id;
    end if;
  end loop;
end;
$$;

revoke all on function app_private.backfill_guest_web_order_customers()
  from public, anon, authenticated, service_role;

select app_private.backfill_guest_web_order_customers();
