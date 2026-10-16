-- Guest refill orders capture the same per-line choice offered by the customer
-- app. The nullable field preserves older order rows and existing callers.
alter table public.order_items
  add column container_source text;

alter table public.order_items
  add constraint order_items_container_source_check
  check (
    container_source is null
    or (
      product_type_snapshot = 'REFILL'
      and container_source in ('CUSTOMER_OWNED', 'STATION_EXCHANGE', 'STATION_BORROW')
    )
  );

comment on column public.order_items.container_source is
  'Guest refill container choice: CUSTOMER_OWNED, STATION_EXCHANGE, or STATION_BORROW. Null for legacy and non-refill items.';

-- Keep the existing server-authoritative order transaction intact, then add
-- validated item metadata in the same RPC transaction. SECURITY INVOKER means
-- the Edge Function service role supplies its own table privileges; no new
-- SECURITY DEFINER endpoint is introduced.
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
  product_id_value uuid;
  product_type_value text;
  container_source_value text;
  updated_items integer;
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

  return result;
end;
$$;

revoke all on function public.create_guest_order_with_container_sources(text, jsonb, text)
  from public, anon, authenticated, service_role;
grant execute on function public.create_guest_order_with_container_sources(text, jsonb, text)
  to service_role;
