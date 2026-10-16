-- Payment foundation. This migration is additive and does not rewrite legacy
-- payment rows or invoke any external provider.
alter table public.payments
  add column if not exists organization_id uuid references public.organizations(id) on delete restrict,
  add column if not exists customer_id uuid references public.customers(id) on delete set null,
  add column if not exists provider text,
  add column if not exists provider_transaction_id text,
  add column if not exists metadata jsonb not null default '{}'::jsonb,
  add column if not exists operation_id text,
  add column if not exists updated_at timestamptz not null default now();

update public.payments p
set organization_id = s.organization_id,
    customer_id = o.customer_id
from public.orders o
join public.stations s on s.id = o.station_id
where p.order_id = o.id
  and (p.organization_id is null or p.customer_id is distinct from o.customer_id);

alter table public.payments
  add constraint payments_status_check
  check (status in ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'REFUNDED', 'PARTIALLY_REFUNDED'));

create unique index if not exists payments_operation_id_uidx
  on public.payments(operation_id)
  where operation_id is not null;

create unique index if not exists payments_provider_transaction_uidx
  on public.payments(provider, provider_transaction_id)
  where provider is not null and provider_transaction_id is not null;

create index if not exists payments_order_status_idx on public.payments(order_id, status);

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
    select organization_id into org_id from public.stations where id = new.station_id;
    insert into public.payments (
      firebase_id, organization_id, station_id, customer_id, order_id,
      payment_method, amount_centavos, status, operation_id, created_at, paid_at, updated_at
    ) values (
      null, org_id, new.station_id, new.customer_id, new.id,
      upper(new.payment_method), new.total_centavos, 'PAID',
      'payment:' || new.id::text || ':walk-in', now(), now(), now()
    ) on conflict (operation_id) do nothing;
  end if;
  return new;
end;
$$;

drop trigger if exists orders_create_walk_in_payment on public.orders;
create trigger orders_create_walk_in_payment
after insert on public.orders
for each row execute function app_private.create_walk_in_payment();

revoke execute on function app_private.create_walk_in_payment() from public, anon, authenticated, service_role;
grant execute on function app_private.create_walk_in_payment() to postgres;

create or replace function public.record_payment(
  p_order_id uuid,
  p_operation_id text,
  p_amount_centavos bigint,
  p_payment_method text,
  p_reference_number text default null,
  p_provider text default null,
  p_provider_transaction_id text default null,
  p_metadata jsonb default '{}'::jsonb
)
returns table (payment_id uuid, order_id uuid, status text, amount_centavos bigint, created boolean)
language plpgsql
security definer
set search_path = pg_catalog, public, auth, app_private
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  existing public.payments%rowtype;
  paid_total bigint;
  org_id uuid;
begin
  if caller is null then raise exception using errcode = '28000', message = 'authenticated user is required'; end if;
  if nullif(btrim(p_operation_id), '') is null or p_amount_centavos <= 0 then
    raise exception using errcode = '22023', message = 'payment operation and positive amount are required';
  end if;
  select * into order_row from public.orders where id = p_order_id for update;
  if order_row.id is null then raise exception using errcode = 'P0002', message = 'order not found'; end if;
  if not app_private.can_operate_station(order_row.station_id)
     and order_row.customer_uid is distinct from caller then
    raise exception using errcode = '42501', message = 'payment access denied';
  end if;
  if order_row.status in ('CANCELLED', 'REJECTED') then
    raise exception using errcode = '55000', message = 'payment is not allowed for this order';
  end if;
  select * into existing from public.payments where operation_id = btrim(p_operation_id);
  if existing.id is not null then
    return query select existing.id, existing.order_id, existing.status, existing.amount_centavos, false;
    return;
  end if;
  if p_provider is not null and p_provider_transaction_id is not null and exists (
    select 1 from public.payments where provider = p_provider and provider_transaction_id = p_provider_transaction_id
  ) then
    raise exception using errcode = '23505', message = 'provider transaction was already recorded';
  end if;
  select coalesce(sum(amount_centavos), 0) into paid_total
  from public.payments where order_id = order_row.id and status = 'PAID';
  if p_amount_centavos > greatest(order_row.total_centavos - paid_total, 0) then
    raise exception using errcode = '22023', message = 'payment exceeds the remaining order balance';
  end if;
  select organization_id into org_id from public.stations where id = order_row.station_id;
  insert into public.payments (
    organization_id, station_id, customer_id, order_id, payment_method,
    amount_centavos, status, reference_number, provider, provider_transaction_id,
    metadata, operation_id, created_at, paid_at, updated_at
  ) values (
    org_id, order_row.station_id, order_row.customer_id, order_row.id,
    upper(btrim(p_payment_method)), p_amount_centavos, 'PAID',
    nullif(btrim(p_reference_number), ''), nullif(btrim(p_provider), ''),
    nullif(btrim(p_provider_transaction_id), ''), coalesce(p_metadata, '{}'::jsonb),
    btrim(p_operation_id), now(), now(), now()
  ) returning id into existing.id;
  paid_total := paid_total + p_amount_centavos;
  update public.orders
  set payment_status = case when paid_total >= total_centavos then 'PAID' else 'PENDING' end,
      payment_method = upper(btrim(p_payment_method)), updated_at = now()
  where id = order_row.id;
  return query select existing.id, order_row.id, 'PAID'::text, p_amount_centavos, true;
end;
$$;

revoke all on function public.record_payment(uuid, text, bigint, text, text, text, text, jsonb) from public, anon, authenticated, service_role;
grant execute on function public.record_payment(uuid, text, bigint, text, text, text, text, jsonb) to authenticated, service_role;
