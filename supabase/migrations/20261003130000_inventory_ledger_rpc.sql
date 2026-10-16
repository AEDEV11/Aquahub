-- Canonical inventory foundation. Product stock remains dual-read until the
-- Android repository and order RPCs are cut over to this ledger.

create table public.inventory_balances (
  id uuid primary key default gen_random_uuid(),
  station_id uuid not null references public.stations(id) on delete restrict,
  product_id uuid not null references public.products(id) on delete restrict,
  quantity integer not null default 0 check (quantity >= 0),
  version bigint not null default 0 check (version >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (station_id, product_id)
);

create table public.inventory_movements (
  id uuid primary key default gen_random_uuid(),
  station_id uuid not null references public.stations(id) on delete restrict,
  product_id uuid not null references public.products(id) on delete restrict,
  operation_id text not null unique,
  delta integer not null check (delta <> 0),
  quantity_after integer not null check (quantity_after >= 0),
  reason text not null,
  order_id uuid references public.orders(id) on delete set null,
  created_by uuid not null default auth.uid() references public.profiles(id) on delete restrict,
  created_at timestamptz not null default now()
);

create index inventory_balances_station_idx
  on public.inventory_balances (station_id, product_id);

create index inventory_movements_station_product_created_idx
  on public.inventory_movements (station_id, product_id, created_at desc);

alter table public.inventory_balances enable row level security;
alter table public.inventory_movements enable row level security;

revoke all on public.inventory_balances, public.inventory_movements from anon, authenticated;
grant select on public.inventory_balances, public.inventory_movements to authenticated;

create policy inventory_balances_station_read
  on public.inventory_balances for select to authenticated
  using (app_private.can_operate_station(station_id));

create policy inventory_movements_station_read
  on public.inventory_movements for select to authenticated
  using (app_private.can_operate_station(station_id));

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
  on conflict (station_id, product_id) do nothing;

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
