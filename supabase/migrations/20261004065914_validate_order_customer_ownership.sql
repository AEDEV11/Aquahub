-- Customer references are station-scoped business data. Do not allow a
-- caller to attach a customer from another tenant/station to an order.
create or replace function app_private.validate_order_customer_station()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  if new.customer_id is not null and not exists (
    select 1
    from public.customers c
    where c.id = new.customer_id
      and c.station_id = new.station_id
      and c.is_active
      and c.deleted_at is null
  ) then
    raise exception using errcode = '42501', message = 'customer access denied';
  end if;
  return new;
end;
$$;

drop trigger if exists orders_customer_station_guard on public.orders;
create constraint trigger orders_customer_station_guard
after insert or update of station_id, customer_id on public.orders
for each row execute function app_private.validate_order_customer_station();

revoke all on function app_private.validate_order_customer_station() from public, anon, authenticated;
