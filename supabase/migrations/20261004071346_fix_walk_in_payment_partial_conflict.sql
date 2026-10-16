-- The operation_id uniqueness index is partial because legacy payment rows may
-- have a null operation_id. The conflict target must include the same
-- predicate or PostgreSQL raises 42P10 for walk-in orders.

create or replace function app_private.create_walk_in_payment()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  org_id uuid;
begin
  if new.order_source = 'OWNER_WALK_IN'
     and new.payment_status = 'PAID'
     and upper(new.payment_method) <> 'CLIENT_ACCOUNT' then
    select organization_id into org_id
    from public.stations
    where id = new.station_id;

    insert into public.payments (
      firebase_id, organization_id, station_id, customer_id, order_id,
      payment_method, amount_centavos, status, operation_id, created_at,
      paid_at, updated_at
    ) values (
      null, org_id, new.station_id, new.customer_id, new.id,
      upper(new.payment_method), new.total_centavos, 'PAID',
      'payment:' || new.id::text || ':walk-in', now(), now(), now()
    ) on conflict (operation_id) where operation_id is not null do nothing;
  end if;
  return new;
end;
$$;

revoke execute on function app_private.create_walk_in_payment() from public, anon, authenticated, service_role;
grant execute on function app_private.create_walk_in_payment() to postgres;
