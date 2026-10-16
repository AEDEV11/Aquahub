-- Fix the PL/pgSQL ambiguity between the RPC return columns and the
-- inventory_balances unique-key columns. This is a function-only repair.

create or replace function public.adjust_inventory(
  p_station_id uuid,
  p_product_id uuid,
  p_delta integer,
  p_operation_id text,
  p_reason text,
  p_order_id uuid default null
)
returns table (
  balance_id uuid,
  station_id uuid,
  product_id uuid,
  quantity integer,
  version bigint,
  movement_id uuid,
  operation_id text
)
language plpgsql
security definer
set search_path = public, app_private
as $$
declare
  caller uuid := auth.uid();
  balance public.inventory_balances%rowtype;
  movement public.inventory_movements%rowtype;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_delta = 0 then
    raise exception using errcode = '22023', message = 'inventory delta must not be zero';
  end if;
  if nullif(btrim(p_operation_id), '') is null then
    raise exception using errcode = '22023', message = 'inventory operation id is required';
  end if;
  if nullif(btrim(p_reason), '') is null then
    raise exception using errcode = '22023', message = 'inventory reason is required';
  end if;
  if not app_private.can_operate_station(p_station_id) then
    raise exception using errcode = '42501', message = 'station access denied';
  end if;
  if not exists (
    select 1
    from public.products p
    where p.id = p_product_id
      and p.station_id = p_station_id
      and p.is_active
  ) then
    raise exception using errcode = '23503', message = 'product does not belong to station';
  end if;

  select * into movement
  from public.inventory_movements m
  where m.operation_id = btrim(p_operation_id)
  for update;
  if movement.id is not null then
    if movement.station_id <> p_station_id or movement.product_id <> p_product_id
       or movement.delta <> p_delta then
      raise exception using errcode = '23505', message = 'inventory operation id conflict';
    end if;
    select * into balance
    from public.inventory_balances b
    where b.station_id = movement.station_id and b.product_id = movement.product_id;
    return query select balance.id, balance.station_id, balance.product_id,
      balance.quantity, balance.version, movement.id, movement.operation_id;
    return;
  end if;

  insert into public.inventory_balances (station_id, product_id)
  values (p_station_id, p_product_id)
  on conflict on constraint inventory_balances_station_id_product_id_key do nothing;

  select * into balance
  from public.inventory_balances b
  where b.station_id = p_station_id and b.product_id = p_product_id
  for update;

  if balance.quantity + p_delta < 0 then
    raise exception using errcode = '23514', message = 'inventory quantity cannot be negative';
  end if;

  update public.inventory_balances b
  set quantity = balance.quantity + p_delta,
      version = balance.version + 1,
      updated_at = now()
  where b.id = balance.id
  returning * into balance;

  insert into public.inventory_movements (
    station_id, product_id, operation_id, delta, quantity_after,
    reason, order_id, created_by
  ) values (
    p_station_id, p_product_id, btrim(p_operation_id), p_delta,
    balance.quantity, btrim(p_reason), p_order_id, caller
  ) returning * into movement;

  return query select balance.id, balance.station_id, balance.product_id,
    balance.quantity, balance.version, movement.id, movement.operation_id;
end;
$$;

revoke execute on function public.adjust_inventory(uuid, uuid, integer, text, text, uuid)
  from public, anon, authenticated, service_role;
grant execute on function public.adjust_inventory(uuid, uuid, integer, text, text, uuid)
  to authenticated, service_role;
