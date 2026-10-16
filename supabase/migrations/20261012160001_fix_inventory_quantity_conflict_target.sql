-- The RETURNS TABLE output names overlap inventory_balances columns. Use the
-- named unique constraint in ON CONFLICT so PL/pgSQL never resolves the
-- conflict target against the output variables.
begin;

create or replace function public.set_inventory_quantity(
  p_station_id uuid,
  p_product_id uuid,
  p_quantity integer,
  p_expected_version bigint,
  p_operation_id text,
  p_reason text default 'Owner stock count'
)
returns table (
  balance_id uuid,
  station_id uuid,
  product_id uuid,
  quantity integer,
  version bigint,
  operation_id text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  balance public.inventory_balances%rowtype;
  movement public.inventory_movements%rowtype;
  delta integer;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_quantity < 0 or p_expected_version < 0
     or nullif(btrim(p_operation_id), '') is null
     or nullif(btrim(p_reason), '') is null then
    raise exception using errcode = '22023', message = 'stock target, revision and operation are required';
  end if;
  if not app_private.can_operate_station(p_station_id) then
    raise exception using errcode = '42501', message = 'station access denied';
  end if;

  perform app_private.require_plan_feature(
    (select s.organization_id from public.stations s where s.id = p_station_id),
    'BASIC_INVENTORY'
  );

  select * into movement
  from public.inventory_movements m
  where m.operation_id = btrim(p_operation_id)
  for update;
  if movement.id is not null then
    select * into balance
    from public.inventory_balances b
    where b.station_id = p_station_id and b.product_id = p_product_id;
    if movement.station_id <> p_station_id or movement.product_id <> p_product_id
       or movement.quantity_after <> p_quantity then
      raise exception using errcode = '23505', message = 'inventory operation id conflict';
    end if;
    return query select balance.id, balance.station_id, balance.product_id,
      balance.quantity, balance.version, movement.operation_id;
    return;
  end if;

  insert into public.inventory_balances (station_id, product_id)
  values (p_station_id, p_product_id)
  on conflict on constraint inventory_balances_station_id_product_id_key do nothing;

  select * into balance
  from public.inventory_balances b
  where b.station_id = p_station_id and b.product_id = p_product_id
  for update;
  if balance.version <> p_expected_version then
    raise exception using errcode = '40001', message = 'inventory revision conflict';
  end if;
  if p_quantity < balance.reserved_quantity then
    raise exception using errcode = '23514', message = 'stock target is below reserved inventory';
  end if;

  delta := p_quantity - balance.quantity;
  if delta = 0 then
    return query select balance.id, balance.station_id, balance.product_id,
      balance.quantity, balance.version, btrim(p_operation_id);
    return;
  end if;

  update public.inventory_balances b
  set quantity = p_quantity, version = b.version + 1, updated_at = now()
  where b.id = balance.id
  returning * into balance;

  insert into public.inventory_movements (
    station_id, product_id, operation_id, delta, quantity_after, reason, created_by
  ) values (
    p_station_id, p_product_id, btrim(p_operation_id), delta,
    balance.quantity, btrim(p_reason), caller
  ) returning * into movement;

  return query select balance.id, balance.station_id, balance.product_id,
    balance.quantity, balance.version, movement.operation_id;
end;
$$;

revoke all on function public.set_inventory_quantity(uuid, uuid, integer, bigint, text, text)
  from public, anon, authenticated, service_role;
grant execute on function public.set_inventory_quantity(uuid, uuid, integer, bigint, text, text)
  to authenticated, service_role;

commit;
