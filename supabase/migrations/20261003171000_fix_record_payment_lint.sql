-- Repair the deployed function without editing the already-applied foundation
-- migration. The return column amount_centavos otherwise shadows the table
-- column inside the PL/pgSQL function.
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
  select * into existing from public.payments p where p.operation_id = btrim(p_operation_id);
  if existing.id is not null then
    return query select existing.id, existing.order_id, existing.status, existing.amount_centavos, false;
    return;
  end if;
  if p_provider is not null and p_provider_transaction_id is not null and exists (
    select 1 from public.payments p
    where p.provider = p_provider and p.provider_transaction_id = p_provider_transaction_id
  ) then
    raise exception using errcode = '23505', message = 'provider transaction was already recorded';
  end if;
  select coalesce(sum(p.amount_centavos), 0) into paid_total
  from public.payments p where p.order_id = order_row.id and p.status = 'PAID';
  if p_amount_centavos > greatest(order_row.total_centavos - paid_total, 0) then
    raise exception using errcode = '22023', message = 'payment exceeds the remaining order balance';
  end if;
  select s.organization_id into org_id from public.stations s where s.id = order_row.station_id;
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
  update public.orders o
  set payment_status = case when paid_total >= o.total_centavos then 'PAID' else 'PENDING' end,
      payment_method = upper(btrim(p_payment_method)), updated_at = now()
  where o.id = order_row.id;
  return query select existing.id, order_row.id, 'PAID'::text, p_amount_centavos, true;
end;
$$;

revoke all on function public.record_payment(uuid, text, bigint, text, text, text, text, jsonb) from public, anon, authenticated, service_role;
grant execute on function public.record_payment(uuid, text, bigint, text, text, text, text, jsonb) to authenticated, service_role;
