-- Enforce server-owned customer context even if a future privileged caller
-- invokes the order RPC with stale presentation fields.

create or replace function app_private.validate_order_insert_context()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  caller uuid := auth.uid();
  caller_profile public.profiles%rowtype;
begin
  if new.order_source = 'CUSTOMER_APP' then
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

  if new.customer_id is not null and not exists (
    select 1 from public.customers c
    where c.id = new.customer_id and c.station_id = new.station_id and c.is_active
  ) then
    raise exception using errcode = '23503', message = 'customer does not belong to station';
  end if;
  return new;
end;
$$;

create trigger orders_validate_insert_context
before insert on public.orders
for each row execute function app_private.validate_order_insert_context();

revoke execute on function app_private.validate_order_insert_context() from public, anon, authenticated, service_role;
grant execute on function app_private.validate_order_insert_context() to postgres, authenticated, service_role;
