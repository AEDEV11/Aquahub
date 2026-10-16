-- Container counts are a station/size aggregate. Client-generated row IDs
-- must never be the conflict key for that aggregate.
alter table public.container_movements
  add column if not exists inventory_effect_applied boolean not null default false;

create or replace function public.ensure_container_inventory(
  p_station_id uuid,
  p_size_label text,
  p_total_owned integer,
  p_available integer,
  p_with_customers integer,
  p_damaged integer,
  p_lost integer,
  p_deposit_price_centavos bigint,
  p_seed_snapshot boolean
)
returns table (synced_container_id uuid)
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_container_id uuid;
  v_total_owned integer := 0;
  v_available integer := 0;
  v_with_customers integer := 0;
  v_damaged integer := 0;
  v_lost integer := 0;
  v_size_label text := pg_catalog.btrim(p_size_label);
begin
  if auth.uid() is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_station_id is null or v_size_label = '' then
    raise exception using errcode = '22023', message = 'station and container size are required';
  end if;
  if p_deposit_price_centavos is null or p_deposit_price_centavos < 0 then
    raise exception using errcode = '22023', message = 'deposit price cannot be negative';
  end if;

  -- A pending live movement will apply its own delta. Only seed a legacy
  -- snapshot when no unapplied live movement is waiting for this size.
  if p_seed_snapshot then
    if p_total_owned is null or p_available is null or p_with_customers is null
       or p_damaged is null or p_lost is null
       or p_total_owned < 0 or p_available < 0 or p_with_customers < 0
       or p_damaged < 0 or p_lost < 0
       or p_total_owned::bigint <> p_available::bigint + p_with_customers::bigint
         + p_damaged::bigint + p_lost::bigint then
      raise exception using errcode = '22023', message = 'container snapshot is invalid';
    end if;
    v_total_owned := p_total_owned;
    v_available := p_available;
    v_with_customers := p_with_customers;
    v_damaged := p_damaged;
    v_lost := p_lost;
  end if;

  -- Insert-only on the canonical station/size key: a delayed local snapshot
  -- must not overwrite a newer server balance from another device.
  insert into public.containers (
    station_id, size_label, total_owned, available, with_customers,
    damaged, lost, deposit_price_centavos
  ) values (
    p_station_id, v_size_label, v_total_owned, v_available, v_with_customers,
    v_damaged, v_lost, p_deposit_price_centavos
  )
  on conflict (station_id, size_label) do nothing
  returning id into v_container_id;

  if v_container_id is null then
    select c.id into v_container_id
    from public.containers c
    where c.station_id = p_station_id and c.size_label = v_size_label;
  end if;
  if v_container_id is null then
    raise exception using errcode = '42501', message = 'station access denied';
  end if;

  return query select v_container_id;
end;
$$;

create or replace function public.apply_container_movement(
  p_firebase_id text,
  p_operation_id text,
  p_station_id uuid,
  p_container_size text,
  p_movement_type text,
  p_quantity integer,
  p_customer_id uuid,
  p_order_id uuid,
  p_customer_name text,
  p_notes text,
  p_created_at timestamptz,
  p_apply_inventory boolean,
  p_tracks_customer_balance boolean,
  p_deposit_price_centavos bigint
)
returns table (synced_movement_id uuid)
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_firebase_id text := pg_catalog.btrim(p_firebase_id);
  v_operation_id text := pg_catalog.btrim(p_operation_id);
  v_size_label text := pg_catalog.btrim(p_container_size);
  v_movement_type text := pg_catalog.upper(pg_catalog.btrim(p_movement_type));
  v_lock_key text;
  v_match_count bigint;
  v_movement public.container_movements%rowtype;
  v_movement_id uuid;
  v_container_id uuid;
  v_container public.containers%rowtype;
  v_delta_available bigint := 0;
  v_delta_customers bigint := 0;
  v_delta_damaged bigint := 0;
  v_delta_lost bigint := 0;
  v_available bigint;
  v_with_customers bigint;
  v_damaged bigint;
  v_lost bigint;
  v_total_owned bigint;
  v_apply_effect boolean := false;
begin
  if auth.uid() is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if v_firebase_id = '' or v_operation_id = '' or p_station_id is null or v_size_label = '' then
    raise exception using errcode = '22023', message = 'movement identity, station, and size are required';
  end if;
  if p_quantity is null or p_quantity <= 0 then
    raise exception using errcode = '22023', message = 'movement quantity must be positive';
  end if;
  if v_movement_type not in ('ADJUSTMENT', 'DELIVERED', 'RETURNED', 'DAMAGED', 'LOST') then
    raise exception using errcode = '22023', message = 'unsupported container movement type';
  end if;
  if p_apply_inventory is null or p_tracks_customer_balance is null
     or p_deposit_price_centavos is null or p_deposit_price_centavos < 0 then
    raise exception using errcode = '22023', message = 'movement sync options are invalid';
  end if;

  -- Serialize retries that share either stable client key. Sorting the keys
  -- gives concurrent requests a consistent advisory-lock order.
  for v_lock_key in
    select keys.lock_key
    from pg_catalog.unnest(array[
      'operation:' || v_operation_id,
      'legacy:' || v_firebase_id
    ]) as keys(lock_key)
    order by keys.lock_key
  loop
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_lock_key, 0));
  end loop;

  select pg_catalog.count(*) into v_match_count
  from public.container_movements m
  where m.client_operation_id = v_operation_id or m.firebase_id = v_firebase_id;
  if v_match_count > 1 then
    raise exception using errcode = '23505', message = 'container movement identity conflict';
  elsif v_match_count = 1 then
    select m.* into v_movement
    from public.container_movements m
    where m.client_operation_id = v_operation_id or m.firebase_id = v_firebase_id
    limit 1
    for update;

    if v_movement.station_id <> p_station_id
       or v_movement.container_size <> v_size_label
       or v_movement.movement_type <> v_movement_type
       or v_movement.quantity <> p_quantity then
      raise exception using errcode = '23505', message = 'container movement identity conflict';
    end if;
    v_movement_id := v_movement.id;
    v_apply_effect := p_apply_inventory and not v_movement.inventory_effect_applied;
  else
    v_apply_effect := p_apply_inventory;
  end if;

  if v_apply_effect then
    select e.synced_container_id into v_container_id
    from public.ensure_container_inventory(
      p_station_id, v_size_label, 0, 0, 0, 0, 0,
      p_deposit_price_centavos, false
    ) as e;

    select c.* into v_container
    from public.containers c
    where c.id = v_container_id
    for update;
    if v_container.id is null then
      raise exception using errcode = '42501', message = 'station access denied';
    end if;

    case v_movement_type
      when 'ADJUSTMENT' then
        v_delta_available := p_quantity::bigint;
      when 'DELIVERED' then
        v_delta_available := -p_quantity::bigint;
        v_delta_customers := p_quantity::bigint;
      when 'RETURNED' then
        v_delta_available := p_quantity::bigint;
        if p_tracks_customer_balance then
          v_delta_customers := -p_quantity::bigint;
        end if;
      when 'DAMAGED' then
        v_delta_available := -p_quantity::bigint;
        v_delta_damaged := p_quantity::bigint;
      when 'LOST' then
        v_delta_available := -p_quantity::bigint;
        v_delta_lost := p_quantity::bigint;
    end case;

    v_available := v_container.available::bigint + v_delta_available;
    v_with_customers := v_container.with_customers::bigint + v_delta_customers;
    v_damaged := v_container.damaged::bigint + v_delta_damaged;
    v_lost := v_container.lost::bigint + v_delta_lost;
    v_total_owned := v_available + v_with_customers + v_damaged + v_lost;
    if v_available < 0 or v_available > 2147483647
       or v_with_customers < 0 or v_with_customers > 2147483647
       or v_damaged < 0 or v_damaged > 2147483647
       or v_lost < 0 or v_lost > 2147483647
       or v_total_owned > 2147483647 then
      raise exception using errcode = '23514', message = 'container inventory quantity is out of range';
    end if;

    update public.containers c
    set total_owned = v_total_owned::integer,
        available = v_available::integer,
        with_customers = v_with_customers::integer,
        damaged = v_damaged::integer,
        lost = v_lost::integer,
        updated_at = pg_catalog.now()
    where c.id = v_container_id;
  else
    select c.id into v_container_id
    from public.containers c
    where c.station_id = p_station_id and c.size_label = v_size_label;
  end if;

  if v_movement_id is null then
    insert into public.container_movements (
      firebase_id, client_operation_id, station_id, container_id,
      customer_id, order_id, customer_name, movement_type, quantity,
      container_size, notes, created_at, inventory_effect_applied
    ) values (
      v_firebase_id, v_operation_id, p_station_id, v_container_id,
      p_customer_id, p_order_id, p_customer_name, v_movement_type, p_quantity,
      v_size_label, p_notes, coalesce(p_created_at, pg_catalog.now()), true
    )
    returning id into v_movement_id;
  else
    update public.container_movements m
    set client_operation_id = v_operation_id,
        firebase_id = coalesce(m.firebase_id, v_firebase_id),
        container_id = coalesce(v_container_id, m.container_id),
        customer_id = coalesce(p_customer_id, m.customer_id),
        order_id = coalesce(p_order_id, m.order_id),
        customer_name = coalesce(p_customer_name, m.customer_name),
        notes = coalesce(p_notes, m.notes),
        inventory_effect_applied = true
    where m.id = v_movement_id;
  end if;

  return query select v_movement_id;
end;
$$;

revoke all on function public.ensure_container_inventory(uuid, text, integer, integer, integer, integer, integer, bigint, boolean)
  from public, anon, authenticated, service_role;
grant execute on function public.ensure_container_inventory(uuid, text, integer, integer, integer, integer, integer, bigint, boolean)
  to authenticated;

revoke all on function public.apply_container_movement(text, text, uuid, text, text, integer, uuid, uuid, text, text, timestamptz, boolean, boolean, bigint)
  from public, anon, authenticated, service_role;
grant execute on function public.apply_container_movement(text, text, uuid, text, text, integer, uuid, uuid, text, text, timestamptz, boolean, boolean, bigint)
  to authenticated;
