-- Group 2: additive, forward-only refund and cancellation integrity layer.
-- Original payment rows remain the immutable record of collections. Refunds
-- are separate ledger events. Replay only in an isolated database first.
begin;

alter table public.orders
  add column if not exists cancelled_from_status public.order_status,
  add column if not exists inventory_return_completed_at timestamptz;

create table public.payment_refunds (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete restrict,
  station_id uuid not null references public.stations(id) on delete restrict,
  order_id uuid not null references public.orders(id) on delete restrict,
  original_payment_id uuid not null references public.payments(id) on delete restrict,
  operation_id text not null unique,
  completion_operation_id text unique,
  failure_operation_id text unique,
  amount_centavos bigint not null check (amount_centavos > 0),
  payment_method text not null,
  status text not null default 'PENDING' check (status in ('PENDING', 'COMPLETED', 'FAILED')),
  reason text not null check (length(btrim(reason)) between 1 and 300),
  external_reference text,
  failure_reason text,
  requested_by uuid not null references public.profiles(id) on delete restrict,
  confirmed_by uuid references public.profiles(id) on delete restrict,
  failed_by uuid references public.profiles(id) on delete restrict,
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  failed_at timestamptz,
  updated_at timestamptz not null default now(),
  check ((status = 'COMPLETED') = (completed_at is not null)),
  check ((status = 'FAILED') = (failed_at is not null)),
  check ((status = 'COMPLETED') = (confirmed_by is not null)),
  check ((status = 'FAILED') = (failed_by is not null))
);
create index payment_refunds_order_created_idx on public.payment_refunds(order_id, created_at desc);
create index payment_refunds_payment_status_idx on public.payment_refunds(original_payment_id, status);
create index payment_refunds_station_status_idx on public.payment_refunds(station_id, status, created_at desc);

create table public.payment_refund_events (
  id uuid primary key default gen_random_uuid(),
  refund_id uuid not null references public.payment_refunds(id) on delete restrict,
  event_type text not null check (event_type in ('REQUESTED', 'COMPLETED', 'FAILED')),
  actor_profile_id uuid not null references public.profiles(id) on delete restrict,
  operation_id text not null,
  amount_centavos bigint not null check (amount_centavos > 0),
  reason text not null,
  external_reference text,
  metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(metadata) = 'object'),
  created_at timestamptz not null default now(),
  unique (event_type, operation_id)
);
create index payment_refund_events_refund_created_idx
  on public.payment_refund_events(refund_id, created_at);

create table public.order_cancellation_events (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete restrict,
  station_id uuid not null references public.stations(id) on delete restrict,
  order_id uuid not null references public.orders(id) on delete restrict,
  operation_id text not null unique,
  actor_profile_id uuid not null references public.profiles(id) on delete restrict,
  reason text not null check (length(btrim(reason)) between 1 and 300),
  from_status public.order_status not null,
  refund_requested_centavos bigint not null default 0 check (refund_requested_centavos >= 0),
  inventory_return_required boolean not null default false,
  created_at timestamptz not null default now()
);
create index order_cancellation_events_order_idx
  on public.order_cancellation_events(order_id, created_at desc);

create table public.order_return_events (
  id uuid primary key default gen_random_uuid(),
  station_id uuid not null references public.stations(id) on delete restrict,
  order_id uuid not null references public.orders(id) on delete restrict,
  operation_id text not null unique,
  request_items jsonb not null check (jsonb_typeof(request_items) = 'array'),
  reason text not null check (length(btrim(reason)) between 1 and 300),
  actor_profile_id uuid not null references public.profiles(id) on delete restrict,
  created_at timestamptz not null default now()
);
create index order_return_events_order_idx on public.order_return_events(order_id, created_at);

create table public.order_return_items (
  id uuid primary key default gen_random_uuid(),
  return_event_id uuid not null references public.order_return_events(id) on delete restrict,
  order_id uuid not null references public.orders(id) on delete restrict,
  station_id uuid not null references public.stations(id) on delete restrict,
  product_id uuid not null references public.products(id) on delete restrict,
  quantity integer not null check (quantity > 0),
  inventory_movement_id uuid not null references public.inventory_movements(id) on delete restrict,
  created_at timestamptz not null default now(),
  unique (return_event_id, product_id)
);
create index order_return_items_order_product_idx on public.order_return_items(order_id, product_id);

alter table public.payment_refunds enable row level security;
alter table public.payment_refund_events enable row level security;
alter table public.order_cancellation_events enable row level security;
alter table public.order_return_events enable row level security;
alter table public.order_return_items enable row level security;
revoke all on public.payment_refunds, public.payment_refund_events,
  public.order_cancellation_events, public.order_return_events,
  public.order_return_items from public, anon, authenticated, service_role;
revoke truncate on public.payments, public.payment_refund_events,
  public.order_cancellation_events, public.order_return_events,
  public.order_return_items from public, anon, authenticated, service_role;

-- Receipts and append-only audit rows are never edited to represent reversals.
-- Refund state changes live in payment_refunds; financial history is appended.
create or replace function app_private.prevent_immutable_payment_history_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if current_user <> 'postgres' then
    raise exception using errcode = '55000',
      message = 'financial history is immutable; record a compensating event instead';
  end if;
  return old;
end;
$$;
revoke all on function app_private.prevent_immutable_payment_history_mutation()
  from public, anon, authenticated, service_role;
grant execute on function app_private.prevent_immutable_payment_history_mutation() to postgres;

drop trigger if exists payment_receipts_immutable on public.payments;
create trigger payment_receipts_immutable
before update or delete on public.payments
for each row execute function app_private.prevent_immutable_payment_history_mutation();
drop trigger if exists refund_events_immutable on public.payment_refund_events;
create trigger refund_events_immutable
before update or delete on public.payment_refund_events
for each row execute function app_private.prevent_immutable_payment_history_mutation();
drop trigger if exists cancellation_events_immutable on public.order_cancellation_events;
create trigger cancellation_events_immutable
before update or delete on public.order_cancellation_events
for each row execute function app_private.prevent_immutable_payment_history_mutation();
drop trigger if exists return_events_immutable on public.order_return_events;
create trigger return_events_immutable
before update or delete on public.order_return_events
for each row execute function app_private.prevent_immutable_payment_history_mutation();
drop trigger if exists return_items_immutable on public.order_return_items;
create trigger return_items_immutable
before update or delete on public.order_return_items
for each row execute function app_private.prevent_immutable_payment_history_mutation();

create or replace function app_private.refresh_order_payment_status(p_order_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  order_row public.orders%rowtype;
  collected bigint;
  completed_refunds bigint;
  next_status text;
begin
  select * into order_row from public.orders o where o.id = p_order_id for update;
  if order_row.id is null then return; end if;
  select coalesce(sum(p.amount_centavos), 0) into collected
  from public.payments p where p.order_id = p_order_id and p.status = 'PAID';
  select coalesce(sum(r.amount_centavos) filter (where r.status = 'COMPLETED'), 0)
    into completed_refunds
  from public.payment_refunds r where r.order_id = p_order_id;

  if collected = 0 then
    next_status := 'PENDING';
  elsif collected >= order_row.total_centavos then
    if completed_refunds >= collected then
      next_status := 'REFUNDED';
    else
      -- Keep the legacy aggregate state PAID after a partial refund. Refund
      -- details and exact net amounts are represented by the refund ledger.
      next_status := 'PAID';
    end if;
  else
    next_status := 'PENDING';
  end if;
  update public.orders set payment_status = next_status, updated_at = now() where id = p_order_id;
end;
$$;
revoke all on function app_private.refresh_order_payment_status(uuid)
  from public, anon, authenticated, service_role;
grant execute on function app_private.refresh_order_payment_status(uuid) to postgres;

-- Paid cancellations are allowed only after all collected value is refunded
-- or reserved by a pending refund. Rejections still need explicit refunds.
create or replace function app_private.prevent_terminal_order_with_paid_receipt()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  collected bigint;
  reserved_or_refunded bigint;
begin
  if old.status is distinct from new.status and new.status in ('CANCELLED', 'REJECTED') then
    select coalesce(sum(p.amount_centavos), 0) into collected
    from public.payments p where p.order_id = old.id and p.status = 'PAID';
    if collected = 0 and old.payment_status = 'PAID' then
      raise exception using errcode = '55000',
        message = 'paid order has no immutable receipt; reconcile its financial history before changing terminal state';
    end if;
    select coalesce(sum(r.amount_centavos), 0) into reserved_or_refunded
    from public.payment_refunds r
    where r.order_id = old.id and r.status in ('PENDING', 'COMPLETED');
    if collected > reserved_or_refunded then
      raise exception using errcode = '55000',
        message = 'request refunds for all collected receipts before cancelling or rejecting this order';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function app_private.prevent_terminal_order_with_paid_receipt()
  from public, anon, authenticated, service_role;
grant execute on function app_private.prevent_terminal_order_with_paid_receipt() to postgres;

create or replace function public.request_payment_refund(
  p_payment_id uuid,
  p_operation_id text,
  p_amount_centavos bigint,
  p_reason text
)
returns table (refund_id uuid, status text, amount_centavos bigint, created boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  payment_row public.payments%rowtype;
  order_row public.orders%rowtype;
  existing public.payment_refunds%rowtype;
  caller_role public.member_role;
  reserved bigint;
  normalized_operation text := pg_catalog.btrim(p_operation_id);
  normalized_reason text := pg_catalog.left(pg_catalog.btrim(p_reason), 300);
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if normalized_operation is null or normalized_operation = '' or length(normalized_operation) > 180
     or p_amount_centavos is null or p_amount_centavos <= 0
     or normalized_reason is null or normalized_reason = '' then
    raise exception using errcode = '22023', message = 'refund operation, positive amount, and reason are required';
  end if;
  select * into payment_row from public.payments p where p.id = p_payment_id;
  if payment_row.id is null then
    raise exception using errcode = 'P0002', message = 'payment not found';
  end if;
  select * into order_row from public.orders o where o.id = payment_row.order_id for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'payment not found';
  end if;
  select * into payment_row from public.payments p where p.id = p_payment_id for update;

  select m.role into caller_role
  from public.stations s
  join public.organization_members m on m.organization_id = s.organization_id
  left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
  where s.id = order_row.station_id and m.profile_id = caller and m.is_active
    and m.role in ('OWNER', 'MANAGER')
    and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
  limit 1;
  if caller_role is null then
    raise exception using errcode = '42501', message = 'refund authorization denied';
  end if;
  if payment_row.status <> 'PAID' or payment_row.order_id <> order_row.id then
    raise exception using errcode = '55000', message = 'only a collected payment can be refunded';
  end if;

  select * into existing from public.payment_refunds r where r.operation_id = normalized_operation;
  if existing.id is not null then
    if existing.original_payment_id <> payment_row.id
       or existing.amount_centavos <> p_amount_centavos or existing.reason <> normalized_reason then
      raise exception using errcode = '22023', message = 'refund operation was already used for a different request';
    end if;
    return query select existing.id, existing.status, existing.amount_centavos, false;
    return;
  end if;

  select coalesce(sum(r.amount_centavos), 0) into reserved
  from public.payment_refunds r
  where r.original_payment_id = payment_row.id and r.status in ('PENDING', 'COMPLETED');
  if p_amount_centavos > payment_row.amount_centavos - reserved then
    raise exception using errcode = '22023', message = 'refund exceeds the unrefunded collected amount';
  end if;

  insert into public.payment_refunds (
    organization_id, station_id, order_id, original_payment_id, operation_id,
    amount_centavos, payment_method, status, reason, requested_by
  ) values (
    payment_row.organization_id, payment_row.station_id, payment_row.order_id,
    payment_row.id, normalized_operation, p_amount_centavos,
    payment_row.payment_method, 'PENDING', normalized_reason, caller
  ) returning * into existing;
  insert into public.payment_refund_events (
    refund_id, event_type, actor_profile_id, operation_id, amount_centavos, reason
  ) values (
    existing.id, 'REQUESTED', caller, normalized_operation, p_amount_centavos, normalized_reason
  );
  perform app_private.refresh_order_payment_status(order_row.id);
  return query select existing.id, 'PENDING'::text, existing.amount_centavos, true;
end;
$$;
revoke all on function public.request_payment_refund(uuid, text, bigint, text)
  from public, anon, authenticated, service_role;
grant execute on function public.request_payment_refund(uuid, text, bigint, text)
  to authenticated, service_role;

create or replace function public.complete_payment_refund(
  p_refund_id uuid,
  p_operation_id text,
  p_actual_refund_confirmed boolean,
  p_external_reference text default null
)
returns table (refund_id uuid, status text, completed_at timestamptz, created boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  refund_row public.payment_refunds%rowtype;
  caller_role public.member_role;
  normalized_operation text := pg_catalog.btrim(p_operation_id);
  normalized_reference text := nullif(pg_catalog.btrim(p_external_reference), '');
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  select * into refund_row from public.payment_refunds r where r.id = p_refund_id;
  if refund_row.id is null then
    raise exception using errcode = 'P0002', message = 'refund not found';
  end if;
  perform 1 from public.orders o where o.id = refund_row.order_id for update;
  select * into refund_row from public.payment_refunds r where r.id = p_refund_id for update;
  select m.role into caller_role
  from public.stations s
  join public.organization_members m on m.organization_id = s.organization_id
  left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
  where s.id = refund_row.station_id and m.profile_id = caller and m.is_active
    and m.role in ('OWNER', 'MANAGER')
    and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
  limit 1;
  if caller_role is null then
    raise exception using errcode = '42501', message = 'refund authorization denied';
  end if;
  if normalized_operation is null or normalized_operation = '' or length(normalized_operation) > 180
     or p_actual_refund_confirmed is distinct from true then
    raise exception using errcode = '22023', message = 'confirm that the refund was actually returned before completing it';
  end if;
  if refund_row.payment_method not in ('CASH', 'CASH_ON_DELIVERY') and normalized_reference is null then
    raise exception using errcode = '22023', message = 'external refund confirmation reference is required';
  end if;
  if refund_row.status = 'COMPLETED' then
    if refund_row.completion_operation_id <> normalized_operation
       or refund_row.external_reference is distinct from normalized_reference then
      raise exception using errcode = '22023', message = 'refund was already completed by a different operation';
    end if;
    return query select refund_row.id, refund_row.status, refund_row.completed_at, false;
    return;
  end if;
  if refund_row.status <> 'PENDING' then
    raise exception using errcode = '55000', message = 'only a pending refund can be completed';
  end if;

  update public.payment_refunds r
  set status = 'COMPLETED', completion_operation_id = normalized_operation,
      external_reference = normalized_reference, confirmed_by = caller,
      completed_at = now(), updated_at = now()
  where r.id = refund_row.id returning r.* into refund_row;
  insert into public.payment_refund_events (
    refund_id, event_type, actor_profile_id, operation_id, amount_centavos,
    reason, external_reference
  ) values (
    refund_row.id, 'COMPLETED', caller, normalized_operation,
    refund_row.amount_centavos, refund_row.reason, normalized_reference
  );
  perform app_private.refresh_order_payment_status(refund_row.order_id);
  return query select refund_row.id, refund_row.status, refund_row.completed_at, true;
end;
$$;
revoke all on function public.complete_payment_refund(uuid, text, boolean, text)
  from public, anon, authenticated, service_role;
grant execute on function public.complete_payment_refund(uuid, text, boolean, text)
  to authenticated, service_role;

create or replace function public.fail_payment_refund(
  p_refund_id uuid,
  p_operation_id text,
  p_failure_reason text
)
returns table (refund_id uuid, status text, failed_at timestamptz, created boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  refund_row public.payment_refunds%rowtype;
  caller_role public.member_role;
  normalized_operation text := pg_catalog.btrim(p_operation_id);
  normalized_reason text := pg_catalog.left(pg_catalog.btrim(p_failure_reason), 300);
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  select * into refund_row from public.payment_refunds r where r.id = p_refund_id;
  if refund_row.id is null then
    raise exception using errcode = 'P0002', message = 'refund not found';
  end if;
  perform 1 from public.orders o where o.id = refund_row.order_id for update;
  select * into refund_row from public.payment_refunds r where r.id = p_refund_id for update;
  select m.role into caller_role
  from public.stations s
  join public.organization_members m on m.organization_id = s.organization_id
  left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
  where s.id = refund_row.station_id and m.profile_id = caller and m.is_active
    and m.role in ('OWNER', 'MANAGER')
    and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
  limit 1;
  if caller_role is null then
    raise exception using errcode = '42501', message = 'refund authorization denied';
  end if;
  if normalized_operation is null or normalized_operation = '' or length(normalized_operation) > 180
     or normalized_reason is null or normalized_reason = '' then
    raise exception using errcode = '22023', message = 'failure operation and reason are required';
  end if;
  if refund_row.status = 'FAILED' then
    if refund_row.failure_operation_id <> normalized_operation or refund_row.failure_reason <> normalized_reason then
      raise exception using errcode = '22023', message = 'refund failure was already recorded by a different operation';
    end if;
    return query select refund_row.id, refund_row.status, refund_row.failed_at, false;
    return;
  end if;
  if refund_row.status <> 'PENDING' then
    raise exception using errcode = '55000', message = 'only a pending refund can be marked failed';
  end if;

  update public.payment_refunds r
  set status = 'FAILED', failure_operation_id = normalized_operation,
      failure_reason = normalized_reason, failed_by = caller, failed_at = now(), updated_at = now()
  where r.id = refund_row.id returning r.* into refund_row;
  insert into public.payment_refund_events (
    refund_id, event_type, actor_profile_id, operation_id, amount_centavos, reason
  ) values (
    refund_row.id, 'FAILED', caller, normalized_operation,
    refund_row.amount_centavos, normalized_reason
  );
  perform app_private.refresh_order_payment_status(refund_row.order_id);
  return query select refund_row.id, refund_row.status, refund_row.failed_at, true;
end;
$$;
revoke all on function public.fail_payment_refund(uuid, text, text)
  from public, anon, authenticated, service_role;
grant execute on function public.fail_payment_refund(uuid, text, text)
  to authenticated, service_role;

create or replace function public.list_order_refunds(p_order_id uuid)
returns table (
  refund_id uuid, original_payment_id uuid, operation_id text,
  amount_centavos bigint, status text, payment_method text,
  reason text, external_reference text, failure_reason text,
  created_at timestamptz, completed_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  is_customer boolean;
  is_station_reader boolean;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  select * into order_row from public.orders o where o.id = p_order_id;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  is_customer := order_row.customer_uid = caller;
  select exists (
    select 1 from public.stations s
    join public.organization_members m on m.organization_id = s.organization_id
    left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
    where s.id = order_row.station_id and m.profile_id = caller and m.is_active
      and m.role in ('OWNER', 'MANAGER', 'STAFF')
      and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
  ) into is_station_reader;
  if not is_customer and not is_station_reader then
    raise exception using errcode = '42501', message = 'refund access denied';
  end if;
  return query
  select r.id,
         case when is_customer then null else r.original_payment_id end,
         case when is_customer then null else r.operation_id end,
         r.amount_centavos, r.status, r.payment_method, r.reason,
         case when is_customer then null else r.external_reference end,
         case when is_customer then null else r.failure_reason end,
         r.created_at, r.completed_at
  from public.payment_refunds r where r.order_id = p_order_id
  order by r.created_at, r.id;
end;
$$;
revoke all on function public.list_order_refunds(uuid) from public, anon, authenticated, service_role;
grant execute on function public.list_order_refunds(uuid) to authenticated, service_role;

create or replace function public.list_order_refunds_for_orders(p_order_ids uuid[])
returns table (
  order_id uuid, refund_id uuid, original_payment_id uuid, operation_id text,
  amount_centavos bigint, status text, payment_method text,
  reason text, external_reference text, failure_reason text,
  created_at timestamptz, completed_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  order_count integer;
  authorized_count integer;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_order_ids is null or cardinality(p_order_ids) = 0 or cardinality(p_order_ids) > 200
     or exists (select 1 from unnest(p_order_ids) x(id) where id is null) then
    raise exception using errcode = '22023', message = 'provide between 1 and 200 distinct order ids';
  end if;
  select count(distinct id) into order_count from unnest(p_order_ids) x(id);
  if order_count <> cardinality(p_order_ids) then
    raise exception using errcode = '22023', message = 'duplicate order ids are not allowed';
  end if;
  select count(*) into authorized_count
  from public.orders o
  where o.id = any(p_order_ids)
    and (
      o.customer_uid = caller
      or exists (
        select 1 from public.stations s
        join public.organization_members m on m.organization_id = s.organization_id
        left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
        where s.id = o.station_id and m.profile_id = caller and m.is_active
          and m.role in ('OWNER', 'MANAGER', 'STAFF')
          and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
      )
    );
  if authorized_count <> order_count then
    raise exception using errcode = '42501', message = 'refund access denied';
  end if;
  return query
  select r.order_id, r.id,
         case when o.customer_uid = caller then null else r.original_payment_id end,
         case when o.customer_uid = caller then null else r.operation_id end,
         r.amount_centavos, r.status, r.payment_method, r.reason,
         case when o.customer_uid = caller then null else r.external_reference end,
         case when o.customer_uid = caller then null else r.failure_reason end,
         r.created_at, r.completed_at
  from public.payment_refunds r
  join public.orders o on o.id = r.order_id
  where r.order_id = any(p_order_ids)
  order by r.created_at, r.id;
end;
$$;
revoke all on function public.list_order_refunds_for_orders(uuid[]) from public, anon, authenticated, service_role;
grant execute on function public.list_order_refunds_for_orders(uuid[]) to authenticated, service_role;

create or replace function app_private.cancel_order_core(
  p_order_id uuid,
  p_operation_id text,
  p_reason text,
  p_allow_paid boolean
)
returns table (
  order_id uuid, status public.order_status, already_cancelled boolean,
  restored_items integer, refund_requested_centavos bigint, inventory_return_required boolean
)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  item record;
  balance public.inventory_balances%rowtype;
  prior public.order_cancellation_events%rowtype;
  caller_role public.member_role;
  target_org_id uuid;
  operation text := pg_catalog.btrim(p_operation_id);
  normalized_reason text := pg_catalog.left(pg_catalog.btrim(p_reason), 300);
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
  collected bigint;
  reserved bigint;
  available bigint;
  refund_total bigint := 0;
  refund_request record;
  restored integer := 0;
  is_operator boolean := false;
  is_customer boolean := false;
  needs_return boolean := false;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  select * into order_row from public.orders o where o.id = p_order_id for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  select m.role into caller_role
  from public.stations s
  join public.organization_members m on m.organization_id = s.organization_id
  left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
  where s.id = order_row.station_id and m.profile_id = caller and m.is_active
    and m.role in ('OWNER', 'MANAGER', 'STAFF')
    and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
  limit 1;
  is_operator := caller_role is not null;
  is_customer := order_row.customer_uid = caller;
  if not is_operator and not is_customer then
    raise exception using errcode = '42501', message = 'order cancellation access denied';
  end if;
  if not is_operator and order_row.status not in ('PENDING', 'ACCEPTED') then
    raise exception using errcode = '55000', message = 'customer cancellation window has closed';
  end if;
  if not is_operator and p_allow_paid then
    raise exception using errcode = '42501', message = 'customer cannot request station refunds';
  end if;
  if operation is null or operation = '' then operation := 'order:cancel:' || order_row.id::text; end if;
  if length(operation) > 180 or normalized_reason is null or normalized_reason = '' then
    raise exception using errcode = '22023', message = 'cancellation operation and reason are required';
  end if;

  select * into prior from public.order_cancellation_events e where e.operation_id = operation;
  if prior.id is not null then
    if prior.order_id <> order_row.id or prior.reason <> normalized_reason then
      raise exception using errcode = '22023', message = 'cancellation operation was already used for a different request';
    end if;
    return query select order_row.id, order_row.status, true, 0,
      prior.refund_requested_centavos, prior.inventory_return_required;
    return;
  end if;
  if order_row.status = 'CANCELLED' then
    return query select order_row.id, order_row.status, true, 0, 0::bigint, false;
    return;
  end if;
  if order_row.status in ('DELIVERED', 'COMPLETED', 'REJECTED')
     or order_row.status not in ('PENDING', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP', 'RIDER_ASSIGNED', 'OUT_FOR_DELIVERY') then
    raise exception using errcode = '55000', message = 'order cannot be cancelled in its current state';
  end if;

  select coalesce(sum(p.amount_centavos), 0) into collected
  from public.payments p where p.order_id = order_row.id and p.status = 'PAID';
  if collected = 0 and order_row.payment_status = 'PAID' then
    raise exception using errcode = '55000', message = 'paid order has no receipt; financial reconciliation is required';
  end if;
  if collected > 0 and not p_allow_paid then
    raise exception using errcode = '55000', message = 'use the Owner refund workflow to cancel an order with collected payments';
  end if;
  if collected > 0 and caller_role not in ('OWNER', 'MANAGER') then
    raise exception using errcode = '42501', message = 'only an owner or manager can cancel a paid order';
  end if;
  if collected > 0 then
    for item in
      select p.id, p.amount_centavos
      from public.payments p
      where p.order_id = order_row.id and p.status = 'PAID'
      order by p.id for update
    loop
      select coalesce(sum(r.amount_centavos), 0) into reserved
      from public.payment_refunds r
      where r.original_payment_id = item.id and r.status in ('PENDING', 'COMPLETED');
      available := item.amount_centavos - reserved;
      if available > 0 then
        select * into refund_request
        from public.request_payment_refund(
          item.id,
          'cancel-refund:' || pg_catalog.md5(operation || ':' || item.id::text),
          available,
          normalized_reason
        );
        if refund_request.created then refund_total := refund_total + refund_request.amount_centavos; end if;
      end if;
    end loop;
  end if;

  needs_return := order_row.status = 'OUT_FOR_DELIVERY' and exists (
    select 1 from public.order_items oi where oi.order_id = order_row.id and oi.product_id is not null
  );
  if order_row.status <> 'OUT_FOR_DELIVERY' and order_row.order_source <> 'LEGACY_IMPORT' then
    for item in
      select oi.product_id, pg_catalog.sum(oi.quantity)::integer as quantity
      from public.order_items oi
      where oi.order_id = order_row.id and oi.product_id is not null
      group by oi.product_id order by oi.product_id
    loop
      insert into public.inventory_balances (station_id, product_id)
      values (order_row.station_id, item.product_id)
      on conflict (station_id, product_id) do nothing;
      select * into balance from public.inventory_balances b
      where b.station_id = order_row.station_id and b.product_id = item.product_id for update;
      update public.inventory_balances b
      set quantity = balance.quantity + item.quantity,
          version = balance.version + 1, updated_at = now()
      where b.id = balance.id;
      insert into public.inventory_movements (
        station_id, product_id, operation_id, delta, quantity_after, reason,
        order_id, created_by, movement_type, source_id
      ) values (
        order_row.station_id, item.product_id,
        'order:' || order_row.id::text || ':cancel:' || item.product_id::text,
        item.quantity, balance.quantity + item.quantity, normalized_reason,
        order_row.id, caller, 'ORDER_REVERSAL', order_row.id::text
      ) on conflict (operation_id) do nothing;
      restored := restored + item.quantity;
    end loop;
  end if;

  select s.organization_id into target_org_id
  from public.stations s where s.id = order_row.station_id;
  insert into public.order_cancellation_events (
    organization_id, station_id, order_id, operation_id, actor_profile_id,
    reason, from_status, refund_requested_centavos, inventory_return_required
  ) values (
    target_org_id, order_row.station_id, order_row.id, operation,
    caller, normalized_reason, order_row.status, refund_total, needs_return
  );
  update public.orders o
  set status = 'CANCELLED', cancellation_reason = normalized_reason,
      cancelled_by = caller, cancelled_at = now_ms,
      cancelled_from_status = order_row.status,
      updated_at = now(), order_version = o.order_version + 1
  where o.id = order_row.id;
  return query select order_row.id, 'CANCELLED'::public.order_status,
    false, restored, refund_total, needs_return;
end;
$$;
revoke all on function app_private.cancel_order_core(uuid, text, text, boolean)
  from public, anon, authenticated, service_role;
grant execute on function app_private.cancel_order_core(uuid, text, text, boolean) to postgres;

create or replace function public.cancel_order(
  p_order_id uuid,
  p_operation_id text default null,
  p_reason text default 'Cancelled'
)
returns table (order_id uuid, status public.order_status, already_cancelled boolean, restored_items integer)
language plpgsql
security definer
set search_path = ''
as $$
begin
  return query
  select c.order_id, c.status, c.already_cancelled, c.restored_items
  from app_private.cancel_order_core(p_order_id, p_operation_id, p_reason, false) c;
end;
$$;
revoke all on function public.cancel_order(uuid, text, text) from public, anon, authenticated, service_role;
grant execute on function public.cancel_order(uuid, text, text) to authenticated, service_role;

create or replace function public.cancel_order_with_refunds(
  p_order_id uuid,
  p_operation_id text,
  p_reason text
)
returns table (
  order_id uuid, status public.order_status, already_cancelled boolean,
  refund_requested_centavos bigint, inventory_return_required boolean
)
language plpgsql
security definer
set search_path = ''
as $$
begin
  return query
  select c.order_id, c.status, c.already_cancelled,
    c.refund_requested_centavos, c.inventory_return_required
  from app_private.cancel_order_core(p_order_id, p_operation_id, p_reason, true) c;
end;
$$;
revoke all on function public.cancel_order_with_refunds(uuid, text, text)
  from public, anon, authenticated, service_role;
grant execute on function public.cancel_order_with_refunds(uuid, text, text)
  to authenticated, service_role;

create or replace function public.confirm_cancelled_order_return(
  p_order_id uuid,
  p_operation_id text,
  p_items jsonb,
  p_reason text
)
returns table (return_event_id uuid, created boolean, inventory_return_completed boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  existing public.order_return_events%rowtype;
  event_row public.order_return_events%rowtype;
  caller_role public.member_role;
  rider_authorized boolean := false;
  line record;
  balance public.inventory_balances%rowtype;
  movement_id uuid;
  ordered_quantity integer;
  already_returned integer;
  normalized_operation text := pg_catalog.btrim(p_operation_id);
  normalized_reason text := pg_catalog.left(pg_catalog.btrim(p_reason), 300);
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if normalized_operation is null or normalized_operation = '' or length(normalized_operation) > 180
     or normalized_reason is null or normalized_reason = ''
     or p_items is null or jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) = 0 then
    raise exception using errcode = '22023', message = 'return operation, reason, and item quantities are required';
  end if;
  select * into order_row from public.orders o where o.id = p_order_id for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  select m.role into caller_role
  from public.stations s
  join public.organization_members m on m.organization_id = s.organization_id
  left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
  where s.id = order_row.station_id and m.profile_id = caller and m.is_active
    and m.role in ('OWNER', 'MANAGER')
    and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
  limit 1;
  select exists (
    select 1 from public.riders r
    where r.id = order_row.rider_id and r.profile_id = caller
      and r.is_active and r.deleted_at is null
  ) into rider_authorized;
  if caller_role is null and not rider_authorized then
    raise exception using errcode = '42501', message = 'delivery return confirmation access denied';
  end if;
  if order_row.status <> 'CANCELLED' or order_row.cancelled_from_status <> 'OUT_FOR_DELIVERY' then
    raise exception using errcode = '55000', message = 'only a cancelled delivery that left the station can be returned';
  end if;

  select * into existing from public.order_return_events e where e.operation_id = normalized_operation;
  if existing.id is not null then
    if existing.order_id <> order_row.id or existing.request_items <> p_items or existing.reason <> normalized_reason then
      raise exception using errcode = '22023', message = 'return operation was already used for a different request';
    end if;
    return query select existing.id, false, order_row.inventory_return_completed_at is not null;
    return;
  end if;
  if exists (
    select 1 from jsonb_to_recordset(p_items) as x(product_id uuid, quantity integer)
    where x.product_id is null or x.quantity is null or x.quantity <= 0
  ) or (select count(*) from jsonb_to_recordset(p_items) as x(product_id uuid, quantity integer))
       <> (select count(distinct x.product_id) from jsonb_to_recordset(p_items) as x(product_id uuid, quantity integer)) then
    raise exception using errcode = '22023', message = 'return items must contain unique products and positive quantities';
  end if;

  insert into public.order_return_events (
    station_id, order_id, operation_id, request_items, reason, actor_profile_id
  ) values (
    order_row.station_id, order_row.id, normalized_operation, p_items, normalized_reason, caller
  ) returning * into event_row;
  for line in
    select x.product_id, x.quantity
    from jsonb_to_recordset(p_items) as x(product_id uuid, quantity integer)
    order by x.product_id
  loop
    select coalesce(sum(oi.quantity), 0)::integer into ordered_quantity
    from public.order_items oi where oi.order_id = order_row.id and oi.product_id = line.product_id;
    if ordered_quantity = 0 then
      raise exception using errcode = '23503', message = 'return product does not belong to this order';
    end if;
    select coalesce(sum(ri.quantity), 0)::integer into already_returned
    from public.order_return_items ri
    where ri.order_id = order_row.id and ri.product_id = line.product_id;
    if already_returned + line.quantity > ordered_quantity then
      raise exception using errcode = '22023', message = 'returned quantity exceeds the quantity sold';
    end if;
    insert into public.inventory_balances (station_id, product_id)
    values (order_row.station_id, line.product_id)
    on conflict (station_id, product_id) do nothing;
    select * into balance from public.inventory_balances b
    where b.station_id = order_row.station_id and b.product_id = line.product_id for update;
    update public.inventory_balances b
    set quantity = balance.quantity + line.quantity, version = balance.version + 1, updated_at = now()
    where b.id = balance.id returning * into balance;
    insert into public.inventory_movements (
      station_id, product_id, operation_id, delta, quantity_after, reason,
      order_id, created_by, movement_type, source_id
    ) values (
      order_row.station_id, line.product_id,
      'order-return:' || event_row.id::text || ':' || line.product_id::text,
      line.quantity, balance.quantity, normalized_reason,
      order_row.id, caller, 'ORDER_RETURN', event_row.id::text
    ) returning id into movement_id;
    insert into public.order_return_items (
      return_event_id, order_id, station_id, product_id, quantity, inventory_movement_id
    ) values (
      event_row.id, order_row.id, order_row.station_id, line.product_id, line.quantity, movement_id
    );
  end loop;
  if not exists (
    select 1 from public.order_items oi
    where oi.order_id = order_row.id and oi.product_id is not null
      and coalesce((
        select sum(ri.quantity) from public.order_return_items ri
        where ri.order_id = oi.order_id and ri.product_id = oi.product_id
      ), 0) < (
        select sum(oi2.quantity) from public.order_items oi2
        where oi2.order_id = oi.order_id and oi2.product_id = oi.product_id
      )
  ) then
    update public.orders o set inventory_return_completed_at = now(), updated_at = now()
    where o.id = order_row.id;
  end if;
  return query select event_row.id, true,
    exists (select 1 from public.orders o where o.id = order_row.id and o.inventory_return_completed_at is not null);
end;
$$;
revoke all on function public.confirm_cancelled_order_return(uuid, text, jsonb, text)
  from public, anon, authenticated, service_role;
grant execute on function public.confirm_cancelled_order_return(uuid, text, jsonb, text)
  to authenticated, service_role;

commit;
