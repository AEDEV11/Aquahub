-- Local-only regression coverage. All synthetic fixtures are rolled back.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(4);

do $$
declare
  v_owner_id uuid := gen_random_uuid();
  v_organization_id uuid := gen_random_uuid();
  v_station_id uuid := gen_random_uuid();
  v_product_id uuid := gen_random_uuid();
begin
  insert into auth.users (id) values (v_owner_id);
  insert into public.organizations (id, name)
  values (v_organization_id, 'Synthetic Free inventory test');
  insert into public.profiles (id, display_name, role, preferred_business_id)
  values (v_owner_id, 'Synthetic owner', 'OWNER', v_organization_id);
  insert into public.stations (id, organization_id, name, address)
  values (v_station_id, v_organization_id, 'Synthetic Free station', 'Local fixture');
  insert into public.organization_members (id, organization_id, profile_id, role)
  values (gen_random_uuid(), v_organization_id, v_owner_id, 'OWNER');
  insert into public.member_stations (member_id, station_id)
  select m.id, v_station_id
  from public.organization_members m
  where m.organization_id = v_organization_id and m.profile_id = v_owner_id;
  insert into public.products (
    id, station_id, name, product_type, size_label, price_centavos, stock_quantity
  ) values (
    v_product_id, v_station_id, 'Synthetic purified refill', 'REFILL', '5 Gallon', 3000, 0
  );
  perform pg_catalog.set_config('request.jwt.claim.sub', v_owner_id::text, true);
  perform pg_catalog.set_config('test.free_inventory.organization_id', v_organization_id::text, true);
  perform pg_catalog.set_config('test.free_inventory.station_id', v_station_id::text, true);
  perform pg_catalog.set_config('test.free_inventory.product_id', v_product_id::text, true);
end;
$$;

select extensions.ok(
  (select pf.enabled
   from public.plan_features pf
   join public.subscription_plans p on p.id = pf.plan_id
   where p.code = 'FREE' and pf.feature = 'BASIC_INVENTORY')
  and not (select pf.enabled
   from public.plan_features pf
   join public.subscription_plans p on p.id = pf.plan_id
   where p.code = 'FREE' and pf.feature = 'BASIC_CONTAINER_TRACKING'),
  'Free includes saleable product inventory while station-owned container tracking stays excluded'
);

select extensions.ok(
  (select max_value = 20 from public.plan_limits pl
   join public.subscription_plans p on p.id = pl.plan_id
   where p.code = 'FREE' and pl.limit_type = 'PRODUCT_COUNT')
  and (select max_value = 100 from public.plan_limits pl
   join public.subscription_plans p on p.id = pl.plan_id
   where p.code = 'FREE' and pl.limit_type = 'MONTHLY_ORDER_COUNT')
  and (select max_value = 1 from public.plan_limits pl
   join public.subscription_plans p on p.id = pl.plan_id
   where p.code = 'FREE' and pl.limit_type = 'STATION_COUNT')
  and (select max_value = 50 from public.plan_limits pl
   join public.subscription_plans p on p.id = pl.plan_id
   where p.code = 'FREE' and pl.limit_type = 'CUSTOMER_COUNT'),
  'Free product, order, station, and customer limits remain unchanged'
);

set local role authenticated;
do $$
declare
  v_station_id uuid := current_setting('test.free_inventory.station_id')::uuid;
  v_product_id uuid := current_setting('test.free_inventory.product_id')::uuid;
  first_result record;
  retry_result record;
begin
  select * into first_result
  from public.adjust_inventory(
    v_station_id, v_product_id, 50, 'free-plan-opening-stock-test',
    'Synthetic opening stock', null, 'MANUAL_ADJUSTMENT', 'free-plan-test'
  );
  select * into retry_result
  from public.adjust_inventory(
    v_station_id, v_product_id, 50, 'free-plan-opening-stock-test',
    'Synthetic opening stock', null, 'MANUAL_ADJUSTMENT', 'free-plan-test'
  );
  if first_result.quantity <> 50 or retry_result.quantity <> 50
     or first_result.movement_id <> retry_result.movement_id then
    raise exception 'Free stock adjustment or idempotent retry did not preserve one movement';
  end if;
  perform pg_catalog.set_config('test.free_inventory.movement_id', first_result.movement_id::text, true);
end;
$$;
reset role;

select extensions.ok(
  (select b.quantity = 50
   from public.inventory_balances b
   where b.station_id = current_setting('test.free_inventory.station_id')::uuid
     and b.product_id = current_setting('test.free_inventory.product_id')::uuid)
  and (select count(*) = 1
   from public.inventory_movements m
   where m.id = current_setting('test.free_inventory.movement_id')::uuid),
  'Free stock adjustment persists once and retries do not duplicate stock movement'
);

do $$
begin
  begin
    insert into public.containers (station_id, size_label)
    values (current_setting('test.free_inventory.station_id')::uuid, '5 Gallon');
    raise exception 'Free plan unexpectedly created station-owned container tracking';
  exception when insufficient_privilege then
    perform pg_catalog.set_config('test.free_inventory.container_denied', 'true', true);
  end;
end;
$$;

select extensions.ok(
  current_setting('test.free_inventory.container_denied', true) = 'true'
  and not exists (
    select 1 from public.containers c
    where c.station_id = current_setting('test.free_inventory.station_id')::uuid
  ),
  'Free plan server guard rejects station-owned container tracking writes'
);

select * from extensions.finish();
rollback;
