-- Local-only regression test. All fixture rows are rolled back at the end.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(1);

do $$
declare
  v_user_id uuid := gen_random_uuid();
  v_organization_id uuid := gen_random_uuid();
  v_station_id uuid := gen_random_uuid();
  v_now_ms bigint := floor(extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint;
  v_container_id uuid;
  v_movement_id uuid;
  v_container public.containers%rowtype;
  v_count bigint;
begin
  perform pg_catalog.set_config('request.jwt.claim.sub', v_user_id::text, true);

  insert into public.organizations (id, name)
  values (v_organization_id, 'Container sync regression fixture');
  insert into public.stations (id, organization_id, name, address)
  values (v_station_id, v_organization_id, 'Container sync fixture', 'Local test only');
  insert into public.subscriptions (
    organization_id, plan_id, status, billing_cycle,
    current_period_start, current_period_end, updated_at
  )
  select v_organization_id, p.id, 'ACTIVE', 'MONTHLY', v_now_ms, v_now_ms + 2592000000, pg_catalog.now()
  from public.subscription_plans p where p.code = 'BUSINESS';
  perform pg_catalog.set_config('test.container_sync.station_id', v_station_id::text, true);

  if not has_function_privilege(
       'authenticated',
       'public.ensure_container_inventory(uuid,text,integer,integer,integer,integer,integer,bigint,boolean)',
       'execute'
     )
     or has_function_privilege(
       'anon',
       'public.ensure_container_inventory(uuid,text,integer,integer,integer,integer,integer,bigint,boolean)',
       'execute'
     )
     or not has_function_privilege(
       'authenticated',
       'public.apply_container_movement(text,text,uuid,text,text,integer,uuid,uuid,text,text,timestamp with time zone,boolean,boolean,bigint)',
       'execute'
     )
     or has_function_privilege(
       'anon',
       'public.apply_container_movement(text,text,uuid,text,text,integer,uuid,uuid,text,text,timestamp with time zone,boolean,boolean,bigint)',
       'execute'
     ) then
    raise exception 'container RPC grants are incorrect';
  end if;

  select e.synced_container_id into v_container_id
  from public.ensure_container_inventory(v_station_id, '5 Gallon', 0, 0, 0, 0, 0, 20000, true) e;

  select m.synced_movement_id into v_movement_id
  from public.apply_container_movement(
    'container-sync-add', 'container-sync-add-op', v_station_id, '5 Gallon',
    'ADJUSTMENT', 5, null, null, null, 'stock received', pg_catalog.now(), true, false, 20000
  ) m;
  if v_movement_id is null then raise exception 'add movement returned no id'; end if;

  -- The same operation replay must return the same ledger row and not add stock twice.
  if (select m.synced_movement_id
      from public.apply_container_movement(
        'container-sync-add', 'container-sync-add-op', v_station_id, '5 Gallon',
        'ADJUSTMENT', 5, null, null, null, 'stock received', pg_catalog.now(), true, false, 20000
      ) m) <> v_movement_id then
    raise exception 'duplicate movement replay returned a different id';
  end if;

  -- A stale snapshot for the same size cannot replace the server aggregate.
  if (select e.synced_container_id
      from public.ensure_container_inventory(v_station_id, '5 Gallon', 99, 99, 0, 0, 0, 99999, true) e)
      <> v_container_id then
    raise exception 'container size did not resolve to the canonical row';
  end if;

  perform * from public.apply_container_movement(
    'container-sync-delivered', 'container-sync-delivered-op', v_station_id, '5 Gallon',
    'DELIVERED', 2, null, null, null, null, pg_catalog.now(), true, false, 20000
  );
  perform * from public.apply_container_movement(
    'container-sync-returned', 'container-sync-returned-op', v_station_id, '5 Gallon',
    'RETURNED', 1, null, null, null, null, pg_catalog.now(), true, true, 20000
  );
  perform * from public.apply_container_movement(
    'container-sync-damaged', 'container-sync-damaged-op', v_station_id, '5 Gallon',
    'DAMAGED', 1, null, null, null, null, pg_catalog.now(), true, false, 20000
  );
  perform * from public.apply_container_movement(
    'container-sync-lost', 'container-sync-lost-op', v_station_id, '5 Gallon',
    'LOST', 1, null, null, null, null, pg_catalog.now(), true, false, 20000
  );

  -- Historical imports are recorded but must not alter a seeded balance.
  perform * from public.apply_container_movement(
    'container-sync-import', 'container-sync-import-op', v_station_id, '5 Gallon',
    'ADJUSTMENT', 7, null, null, null, 'legacy import', pg_catalog.now(), false, false, 20000
  );

  begin
    perform * from public.apply_container_movement(
      'container-sync-overdraw', 'container-sync-overdraw-op', v_station_id, '5 Gallon',
      'DELIVERED', 3, null, null, null, null, pg_catalog.now(), true, false, 20000
    );
    raise exception 'overdraw unexpectedly succeeded';
  exception when check_violation then
    null;
  end;

  select c.* into v_container
  from public.containers c
  where c.id = v_container_id;
  if v_container.total_owned <> 5 or v_container.available <> 2
     or v_container.with_customers <> 1 or v_container.damaged <> 1 or v_container.lost <> 1
     or v_container.deposit_price_centavos <> 20000 then
    raise exception 'container aggregate mismatch after movement/replay checks';
  end if;

  select pg_catalog.count(*) into v_count
  from public.container_movements m
  where m.station_id = v_station_id;
  if v_count <> 6 then
    raise exception 'expected six unique movements after failed overdraw, found %', v_count;
  end if;

  if not exists (
    select 1 from public.container_movements m
    where m.station_id = v_station_id
      and m.client_operation_id = 'container-sync-import-op'
      and m.inventory_effect_applied
  ) then
    raise exception 'import movement was not marked as intentionally processed';
  end if;
end;
$$;

-- Verify ordinary authenticated callers cannot seed a container in a station
-- they do not operate. The preceding fixture exists only inside this transaction.
set local role authenticated;
set local request.jwt.claim.sub = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
do $$
declare
  v_station_id uuid := pg_catalog.current_setting('test.container_sync.station_id')::uuid;
begin
  begin
    perform * from public.ensure_container_inventory(v_station_id, 'Unauthorized size', 0, 0, 0, 0, 0, 0, false);
    raise exception 'unauthorized container seed unexpectedly succeeded';
  exception when insufficient_privilege then
    null;
  end;
  if exists (
    select 1 from public.containers c
    where c.station_id = v_station_id and c.size_label = 'Unauthorized size'
  ) then
    raise exception 'unauthorized container seed persisted';
  end if;
end;
$$;
reset role;

select extensions.ok(true, 'container movement replay, balances, limits, and tenant authorization hold');
select * from extensions.finish();

rollback;
