-- Local-only public catalog availability checks; fixtures roll back.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(15);

do $$
declare
  v_organization_id uuid := gen_random_uuid();
  v_station_id uuid := gen_random_uuid();
begin
  insert into public.organizations (id, name)
  values (v_organization_id, 'Synthetic public ordering availability');
  insert into public.stations (id, organization_id, name, address, public_station_code)
  values (v_station_id, v_organization_id, 'Availability fixture station', 'Test address', 'ELIGIBLE01');
  perform pg_catalog.set_config('test.public_ordering.organization_id', v_organization_id::text, true);
  perform pg_catalog.set_config('test.public_ordering.station_id', v_station_id::text, true);
end;
$$;

select extensions.ok(
  has_function_privilege('anon', 'public.get_public_station_ordering_eligibility(uuid[])', 'execute')
  and has_function_privilege('authenticated', 'public.get_public_station_ordering_eligibility(uuid[])', 'execute'),
  'anonymous and signed-in customers can read only public ordering eligibility'
);

set local role anon;
select extensions.ok(
  (select is_ordering_available is false
   from public.get_public_station_ordering_eligibility(
     array[pg_catalog.current_setting('test.public_ordering.station_id')::uuid])),
  'a station switch alone does not enable customer ordering on the Free plan'
);
reset role;

insert into public.subscriptions (
  organization_id, plan_id, status, billing_cycle, current_period_start, current_period_end
)
select pg_catalog.current_setting('test.public_ordering.organization_id')::uuid,
       p.id, 'ACTIVE', 'MONTHLY',
       (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint,
       (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint + 86400000
from public.subscription_plans p where p.code = 'STARTER';

-- A higher plan can create two active stations; a later downgrade retains
-- both rows but only the oldest station receives the Starter slot.
update public.subscriptions
set plan_id = (select id from public.subscription_plans where code = 'BUSINESS')
where organization_id = pg_catalog.current_setting('test.public_ordering.organization_id')::uuid;

do $$
declare
  second_station_id uuid := pg_catalog.gen_random_uuid();
begin
  insert into public.stations (
    id, organization_id, name, address, public_station_code, created_at
  ) values (
    second_station_id,
    pg_catalog.current_setting('test.public_ordering.organization_id')::uuid,
    'Later availability fixture station', 'Test address', 'ELIGIBLE02',
    pg_catalog.now() + interval '1 minute'
  );
  perform pg_catalog.set_config('test.public_ordering.second_station_id', second_station_id::text, true);
end;
$$;

set local role anon;
select extensions.ok(
  (select count(*) = 2
   from public.get_public_station_ordering_eligibility(array[
     pg_catalog.current_setting('test.public_ordering.station_id')::uuid,
     pg_catalog.current_setting('test.public_ordering.second_station_id')::uuid
   ]) where is_ordering_available),
  'both active stations are orderable on the Business plan'
);
reset role;

update public.subscriptions
set plan_id = (select id from public.subscription_plans where code = 'STARTER')
where organization_id = pg_catalog.current_setting('test.public_ordering.organization_id')::uuid;

set local role anon;
select extensions.ok(
  (select is_ordering_available
   from public.get_public_station_ordering_eligibility(array[
     pg_catalog.current_setting('test.public_ordering.station_id')::uuid
   ])),
  'the oldest active station retains the Starter ordering slot after downgrade'
);
select extensions.ok(
  (select is_ordering_available is false
   from public.get_public_station_ordering_eligibility(array[
     pg_catalog.current_setting('test.public_ordering.second_station_id')::uuid
   ])),
  'the later station loses ordering immediately after downgrade'
);
reset role;

do $$
declare
  source_name text;
begin
  foreach source_name in array array['CUSTOMER_APP', 'CUSTOMER_WEB'] loop
    begin
      insert into public.orders (
        station_id, order_number, customer_name, delivery_mode,
        subtotal_centavos, total_centavos, payment_method, order_source
      ) values (
        pg_catalog.current_setting('test.public_ordering.second_station_id')::uuid,
        'DOWNGRADE-' || source_name, 'Local test customer', 'PICKUP',
        100, 100, 'CASH', source_name
      );
    exception when sqlstate '42501' then
      if sqlerrm = 'station exceeds subscription limit' then
        perform pg_catalog.set_config('test.public_ordering.guard_' || lower(source_name), 'true', true);
      end if;
    end;
  end loop;
end;
$$;
select extensions.ok(
  pg_catalog.current_setting('test.public_ordering.guard_customer_app', true) = 'true',
  'the server rejects a customer app order for a station outside the effective slot'
);
select extensions.ok(
  pg_catalog.current_setting('test.public_ordering.guard_customer_web', true) = 'true',
  'the server rejects a guest web order for a station outside the effective slot'
);

set local role anon;
select extensions.ok(
  (select is_ordering_available
   from public.get_public_station_ordering_eligibility(
     array[pg_catalog.current_setting('test.public_ordering.station_id')::uuid])),
  'anonymous customer sees active paid ordering without a projection rewrite'
);
reset role;

set local role authenticated;
select extensions.ok(
  (select is_ordering_available
   from public.get_public_station_ordering_eligibility(
     array[pg_catalog.current_setting('test.public_ordering.station_id')::uuid])),
  'signed-in customer sees the same paid ordering eligibility'
);
reset role;

update public.subscriptions
set current_period_end = (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint - 1
where organization_id = pg_catalog.current_setting('test.public_ordering.organization_id')::uuid;

set local role anon;
select extensions.ok(
  (select is_ordering_available is false
   from public.get_public_station_ordering_eligibility(
     array[pg_catalog.current_setting('test.public_ordering.station_id')::uuid]))
  and (select is_accepting_orders from public.public_stations
       where station_id = pg_catalog.current_setting('test.public_ordering.station_id')::uuid),
  'expired paid access immediately disables ordering even while the station projection is unchanged'
);
reset role;

update public.subscriptions
set current_period_end = (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint + 86400000
where organization_id = pg_catalog.current_setting('test.public_ordering.organization_id')::uuid;
update public.stations set is_accepting_online_orders = false
where id = pg_catalog.current_setting('test.public_ordering.station_id')::uuid;

set local role anon;
select extensions.ok(
  (select is_ordering_available is false
   from public.get_public_station_ordering_eligibility(
     array[pg_catalog.current_setting('test.public_ordering.station_id')::uuid])),
  'the station ordering switch still disables ordering on a paid plan'
);
reset role;

update public.stations set is_accepting_online_orders = true, is_open = false
where id = pg_catalog.current_setting('test.public_ordering.station_id')::uuid;

set local role anon;
select extensions.ok(
  (select is_ordering_available is false
   from public.get_public_station_ordering_eligibility(
     array[pg_catalog.current_setting('test.public_ordering.station_id')::uuid])),
  'a closed station cannot advertise ordering'
);
select extensions.ok(
  (select count(*) = 1
   from public.get_public_station_ordering_eligibility(
     array[pg_catalog.current_setting('test.public_ordering.station_id')::uuid,
           pg_catalog.gen_random_uuid()])),
  'batch lookup returns projected stations only and reveals no private row for an unknown ID'
);
reset role;

update public.stations set is_active = false
where id = pg_catalog.current_setting('test.public_ordering.station_id')::uuid;

set local role anon;
select extensions.ok(
  (select is_ordering_available
   from public.get_public_station_ordering_eligibility(array[
     pg_catalog.current_setting('test.public_ordering.second_station_id')::uuid
   ])),
  'the next oldest active station receives the available slot when the first is deactivated'
);
reset role;

do $$
begin
  begin
    perform * from public.get_public_station_ordering_eligibility(
      array(select pg_catalog.gen_random_uuid() from pg_catalog.generate_series(1, 101))
    );
  exception when sqlstate '22023' then
    perform pg_catalog.set_config('test.public_ordering.limit_rejected', 'true', true);
  end;
end;
$$;
select extensions.ok(
  pg_catalog.current_setting('test.public_ordering.limit_rejected', true) = 'true',
  'public lookup rejects oversized station batches'
);

select * from extensions.finish();
rollback;
