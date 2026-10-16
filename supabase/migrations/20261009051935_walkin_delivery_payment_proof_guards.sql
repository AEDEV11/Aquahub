-- Owner-created delivery orders are fulfilled before the rider collects money.
-- Do not allow create_order's legacy PAID default to manufacture a receipt.
create or replace function app_private.keep_walk_in_delivery_unpaid()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  if new.order_source = 'OWNER_WALK_IN'
     and (new.delivery_mode = 'DELIVERY' or upper(new.payment_method) = 'CLIENT_ACCOUNT') then
    new.payment_status := 'PENDING';
  end if;
  return new;
end;
$$;

revoke all on function app_private.keep_walk_in_delivery_unpaid() from public, anon, authenticated, service_role;
grant execute on function app_private.keep_walk_in_delivery_unpaid() to postgres;

drop trigger if exists orders_keep_walk_in_delivery_unpaid on public.orders;
create trigger orders_keep_walk_in_delivery_unpaid
before insert on public.orders
for each row execute function app_private.keep_walk_in_delivery_unpaid();

-- Proof objects are private and scoped to one active assigned rider and order.
-- The same predicate is used for upload and read so Storage can return upload
-- metadata without granting riders broad access to station payment assets.
create or replace function app_private.storage_delivery_payment_proof_allowed(object_name text)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, auth
as $$
  select case
    when object_name ~ '^organizations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/stations/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/delivery-payment-proofs/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.(jpg|png|webp)$'
    then exists (
      select 1
      from public.stations s
      join public.orders o on o.station_id = s.id
      join public.riders r on r.id = o.rider_id
      where s.id = split_part(object_name, '/', 4)::uuid
        and s.organization_id = split_part(object_name, '/', 2)::uuid
        and o.id = split_part(object_name, '/', 6)::uuid
        and o.delivery_mode = 'DELIVERY'
        and o.status in ('RIDER_ASSIGNED', 'OUT_FOR_DELIVERY', 'DELIVERED', 'COMPLETED')
        and r.profile_id = (select auth.uid())
        and r.is_active
    )
    else false
  end
$$;

revoke all on function app_private.storage_delivery_payment_proof_allowed(text) from public, anon, authenticated, service_role;
grant execute on function app_private.storage_delivery_payment_proof_allowed(text) to authenticated, service_role;

drop policy if exists storage_rider_delivery_payment_proof_insert on storage.objects;
drop policy if exists storage_rider_delivery_payment_proof_select on storage.objects;

create policy storage_rider_delivery_payment_proof_insert on storage.objects
for insert to authenticated
with check (
  bucket_id = 'payment-assets'
  and (select app_private.storage_delivery_payment_proof_allowed(name))
);

create policy storage_rider_delivery_payment_proof_select on storage.objects
for select to authenticated
using (
  bucket_id = 'payment-assets'
  and (select app_private.storage_delivery_payment_proof_allowed(name))
);

-- A rider can only create a payment for their assigned delivery after it has
-- been delivered, and only when their own private proof object was uploaded.
create or replace function app_private.require_rider_delivery_payment_proof()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public, auth, storage
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  proof_path text;
begin
  if caller is null then
    return new;
  end if;

  select o.* into order_row
  from public.orders o
  where o.id = new.order_id;

  if order_row.id is null or not exists (
    select 1
    from public.riders r
    where r.id = order_row.rider_id
      and r.profile_id = caller
      and r.is_active
  ) then
    return new;
  end if;

  proof_path := new.metadata ->> 'delivery_payment_proof_object_path';
  if order_row.delivery_mode <> 'DELIVERY'
     or order_row.status not in ('DELIVERED', 'COMPLETED')
     or proof_path is null
     or not app_private.storage_delivery_payment_proof_allowed(proof_path)
     or not exists (
       select 1 from storage.objects object_row
       where object_row.bucket_id = 'payment-assets'
         and object_row.name = proof_path
         and object_row.owner_id = caller::text
     ) then
    raise exception using errcode = '42501', message = 'assigned riders must upload proof before recording delivery payment';
  end if;

  return new;
end;
$$;

revoke all on function app_private.require_rider_delivery_payment_proof() from public, anon, authenticated, service_role;
grant execute on function app_private.require_rider_delivery_payment_proof() to postgres;

drop trigger if exists payments_require_rider_delivery_proof on public.payments;
create trigger payments_require_rider_delivery_proof
before insert on public.payments
for each row execute function app_private.require_rider_delivery_payment_proof();

-- Payment state must be written through record_payment; owner-editable fields
-- retain their existing direct update path.
revoke update on public.orders from authenticated;
revoke update (payment_status) on public.orders from authenticated;
grant update (station_note, payment_method) on public.orders to authenticated;
revoke insert, update, delete on public.payments from authenticated;

-- Keep customer spending equal to receipts actually recorded as PAID. This is
-- incremental and transaction-safe; it does not rewrite existing totals.
create or replace function app_private.apply_paid_payment_to_customer_spend()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  old_paid bigint := 0;
  new_paid bigint := 0;
  spend_delta bigint;
begin
  if tg_op <> 'INSERT' and old.customer_id is not null then
    old_paid := case when old.status = 'PAID' then old.amount_centavos else 0 end;
  end if;
  if tg_op <> 'DELETE' and new.customer_id is not null then
    new_paid := case when new.status = 'PAID' then new.amount_centavos else 0 end;
  end if;

  if tg_op = 'UPDATE' and old.customer_id is distinct from new.customer_id then
    if old.customer_id is not null and old_paid <> 0 then
      update public.customers
      set total_spent_centavos = greatest(total_spent_centavos - old_paid, 0)
      where id = old.customer_id;
    end if;
    if new.customer_id is not null and new_paid <> 0 then
      update public.customers
      set total_spent_centavos = total_spent_centavos + new_paid
      where id = new.customer_id;
    end if;
  else
    spend_delta := new_paid - old_paid;
    if coalesce(new.customer_id, old.customer_id) is not null and spend_delta <> 0 then
      update public.customers
      set total_spent_centavos = greatest(total_spent_centavos + spend_delta, 0)
      where id = coalesce(new.customer_id, old.customer_id);
    end if;
  end if;

  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;

revoke all on function app_private.apply_paid_payment_to_customer_spend() from public, anon, authenticated, service_role;
grant execute on function app_private.apply_paid_payment_to_customer_spend() to postgres;

drop trigger if exists payments_apply_customer_paid_spend on public.payments;
create trigger payments_apply_customer_paid_spend
after insert or update or delete on public.payments
for each row execute function app_private.apply_paid_payment_to_customer_spend();

-- Riders are not station operators, but an active rider assigned to this
-- delivered order may record its collection. The payments trigger above still
-- requires a private proof object owned by that rider before the insert.
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
  assigned_rider boolean := false;
  normalized_method text := upper(btrim(p_payment_method));
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if nullif(btrim(p_operation_id), '') is null or p_amount_centavos <= 0 then
    raise exception using errcode = '22023', message = 'payment operation and positive amount are required';
  end if;
  if normalized_method is null or normalized_method not in ('CASH', 'CASH_ON_DELIVERY', 'GCASH', 'MAYA') then
    raise exception using errcode = '22023', message = 'unsupported payment method';
  end if;

  select * into order_row
  from public.orders
  where id = p_order_id
  for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  if order_row.status in ('CANCELLED', 'REJECTED') then
    raise exception using errcode = '55000', message = 'payment is not allowed for this order';
  end if;

  select exists (
    select 1
    from public.riders r
    where r.id = order_row.rider_id
      and r.profile_id = caller
      and r.is_active
      and order_row.delivery_mode = 'DELIVERY'
      and order_row.status in ('DELIVERED', 'COMPLETED')
  ) into assigned_rider;

  if not app_private.can_operate_station(order_row.station_id)
     and order_row.customer_uid is distinct from caller
     and not assigned_rider then
    raise exception using errcode = '42501', message = 'payment access denied';
  end if;

  select * into existing
  from public.payments p
  where p.operation_id = btrim(p_operation_id);
  if existing.id is not null then
    if existing.order_id <> p_order_id
       or existing.amount_centavos <> p_amount_centavos
       or existing.payment_method <> normalized_method then
      raise exception using errcode = '22023', message = 'payment operation was already used for a different receipt';
    end if;
    return query select existing.id, existing.order_id, existing.status, existing.amount_centavos, false;
    return;
  end if;

  if p_provider is not null and p_provider_transaction_id is not null and exists (
    select 1
    from public.payments p
    where p.provider = p_provider
      and p.provider_transaction_id = p_provider_transaction_id
  ) then
    raise exception using errcode = '23505', message = 'provider transaction was already recorded';
  end if;

  select coalesce(sum(p.amount_centavos), 0) into paid_total
  from public.payments p
  where p.order_id = order_row.id
    and p.status = 'PAID';
  if p_amount_centavos > greatest(order_row.total_centavos - paid_total, 0) then
    raise exception using errcode = '22023', message = 'payment exceeds the remaining order balance';
  end if;

  select s.organization_id into org_id
  from public.stations s
  where s.id = order_row.station_id;
  insert into public.payments (
    organization_id, station_id, customer_id, order_id, payment_method,
    amount_centavos, status, reference_number, provider, provider_transaction_id,
    metadata, operation_id, created_at, paid_at, updated_at
  ) values (
    org_id, order_row.station_id, order_row.customer_id, order_row.id,
    normalized_method, p_amount_centavos, 'PAID',
    nullif(btrim(p_reference_number), ''), nullif(btrim(p_provider), ''),
    nullif(btrim(p_provider_transaction_id), ''), coalesce(p_metadata, '{}'::jsonb),
    btrim(p_operation_id), now(), now(), now()
  ) returning id into existing.id;

  paid_total := paid_total + p_amount_centavos;
  update public.orders o
  set payment_status = case when paid_total >= o.total_centavos then 'PAID' else 'PENDING' end,
      payment_method = normalized_method,
      updated_at = now()
  where o.id = order_row.id;

  return query select existing.id, order_row.id, 'PAID'::text, p_amount_centavos, true;
end;
$$;

revoke all on function public.record_payment(uuid, text, bigint, text, text, text, text, jsonb)
  from public, anon, authenticated, service_role;
grant execute on function public.record_payment(uuid, text, bigint, text, text, text, text, jsonb)
  to authenticated, service_role;
