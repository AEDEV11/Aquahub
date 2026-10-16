-- Add explicit movement/source metadata without changing the deployed
-- six-argument RPC contract. New callers use the eight-argument overload.

alter table public.inventory_movements
  add column movement_type text not null default 'MANUAL_ADJUSTMENT',
  add column source_id text not null default '';

create index inventory_movements_source_idx
  on public.inventory_movements (movement_type, source_id);

create or replace function public.adjust_inventory(
  p_station_id uuid,
  p_product_id uuid,
  p_delta integer,
  p_operation_id text,
  p_reason text,
  p_order_id uuid,
  p_movement_type text,
  p_source_id text
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
  result record;
  normalized_type text := nullif(btrim(p_movement_type), '');
  normalized_source text := nullif(btrim(p_source_id), '');
begin
  if normalized_type is null or normalized_source is null then
    raise exception using errcode = '22023', message = 'inventory movement metadata is required';
  end if;

  select * into result
  from public.adjust_inventory(
    p_station_id, p_product_id, p_delta, p_operation_id,
    p_reason, p_order_id
  );

  update public.inventory_movements
  set movement_type = normalized_type,
      source_id = normalized_source
  where id = result.movement_id;

  return query select
    result.balance_id, result.station_id, result.product_id,
    result.quantity, result.version, result.movement_id, result.operation_id;
end;
$$;

revoke execute on function public.adjust_inventory(uuid, uuid, integer, text, text, uuid, text, text)
  from public, anon, authenticated, service_role;
grant execute on function public.adjust_inventory(uuid, uuid, integer, text, text, uuid, text, text)
  to authenticated, service_role;
