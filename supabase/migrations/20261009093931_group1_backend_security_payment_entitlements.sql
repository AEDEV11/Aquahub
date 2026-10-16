-- Forward-only hardening for the current Supabase contract.
-- Do not rewrite earlier migration history and do not repair historical rows here.
begin;

create or replace function app_private.effective_plan_id(p_organization_id uuid)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  with now_ms as (
    select (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint as value
  ),
  selected_subscription as (
    select s.plan_id, s.status::text as status, s.current_period_end,
           s.trial_ends_at, s.grace_period_ends_at, s.expires_at, p.code as plan_code,
           p.is_active as plan_active
    from public.subscriptions s
    join public.subscription_plans p on p.id = s.plan_id
    where s.organization_id = p_organization_id
      and s.deleted_at is null
    order by s.updated_at desc
    limit 1
  )
  select coalesce(
    (
      select s.plan_id
      from selected_subscription s
      cross join now_ms n
      where s.plan_active
        and (
          (s.status = 'FREE' and s.plan_code = 'FREE')
          or ((s.status = 'TRIALING' and coalesce(s.trial_ends_at, s.current_period_end, 0) > n.value)
            and (s.expires_at is null or s.expires_at > n.value))
          or ((s.status in ('ACTIVE', 'ACTIVE_UNTIL_PERIOD_END') and s.current_period_end > n.value)
            and (s.expires_at is null or s.expires_at > n.value))
          or ((s.status = 'GRACE_PERIOD' and s.grace_period_ends_at > n.value)
            and (s.expires_at is null or s.expires_at > n.value))
        )
    ),
    (select p.id from public.subscription_plans p where p.code = 'FREE' and p.is_active limit 1)
  );
$$;

revoke all on function app_private.effective_plan_id(uuid) from public, anon, authenticated, service_role;
grant execute on function app_private.effective_plan_id(uuid) to postgres;

create or replace function app_private.require_plan_feature(p_organization_id uuid, p_feature text)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  selected_plan_id uuid;
  feature_enabled boolean;
begin
  selected_plan_id := app_private.effective_plan_id(p_organization_id);
  if selected_plan_id is null then
    raise exception using errcode = '55000', message = 'subscription plan configuration is unavailable';
  end if;

  select pf.enabled into feature_enabled
  from public.plan_features pf
  where pf.plan_id = selected_plan_id and pf.feature = p_feature;

  if not coalesce(feature_enabled, false) then
    raise exception using errcode = '42501', message = 'subscription feature is not available';
  end if;
end;
$$;

create or replace function app_private.assert_plan_limit(
  p_organization_id uuid,
  p_limit_type text,
  p_current_count bigint
)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  selected_plan_id uuid;
  maximum bigint;
begin
  selected_plan_id := app_private.effective_plan_id(p_organization_id);
  if selected_plan_id is null then
    raise exception using errcode = '55000', message = 'subscription plan configuration is unavailable';
  end if;

  select pl.max_value into maximum
  from public.plan_limits pl
  where pl.plan_id = selected_plan_id and pl.limit_type = p_limit_type;

  if maximum is null then
    raise exception using errcode = '55000', message = 'subscription limit is not configured';
  end if;
  if p_current_count > maximum then
    raise exception using errcode = '23514', message = 'subscription limit reached';
  end if;
end;
$$;

revoke all on function app_private.require_plan_feature(uuid, text) from public, anon, authenticated, service_role;
revoke all on function app_private.assert_plan_limit(uuid, text, bigint) from public, anon, authenticated, service_role;
grant execute on function app_private.require_plan_feature(uuid, text) to postgres;
grant execute on function app_private.assert_plan_limit(uuid, text, bigint) to postgres;

-- Record server-received order usage separately from client-supplied
-- created_at. This preserves imported historical timestamps without allowing
-- an offline import to evade the current monthly order cap by backdating.
create table if not exists app_private.order_quota_usage (
  order_id uuid primary key references public.orders(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete cascade,
  usage_month date not null
);
create index if not exists order_quota_usage_org_month_idx
  on app_private.order_quota_usage (organization_id, usage_month);
alter table app_private.order_quota_usage enable row level security;
revoke all on table app_private.order_quota_usage from public, anon, authenticated, service_role;
grant all on table app_private.order_quota_usage to postgres;

create or replace function app_private.enforce_plan_write()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  target_organization_id uuid;
  target_station_id uuid;
  source_value text;
  current_count bigint;
  historical_count bigint;
  target_usage_month date;
  needs_count_check boolean := tg_op = 'INSERT';
  is_active_now boolean;
begin
  if tg_table_name = 'orders' then
    if tg_op <> 'INSERT' then return new; end if;
    select s.organization_id into target_organization_id
    from public.stations s where s.id = new.station_id;
    if target_organization_id is null then
      raise exception using errcode = '23503', message = 'order station is unavailable';
    end if;

    if new.order_source in ('CUSTOMER_APP', 'CUSTOMER_WEB') then
      perform app_private.require_plan_feature(target_organization_id, 'MOBILE_APP_ORDERING');
    end if;
    if new.delivery_mode = 'DELIVERY' then
      perform app_private.require_plan_feature(target_organization_id, 'DELIVERY_MANAGEMENT');
    end if;
    if upper(new.payment_method) = 'CLIENT_ACCOUNT' then
      perform app_private.require_plan_feature(target_organization_id, 'ACCOUNTS_RECEIVABLE');
    end if;

    -- Serialize monthly order-cap checks for this organization, then account
    -- for the server-received event even when a legacy import preserves an old
    -- client-created timestamp.
    perform 1 from public.organizations o where o.id = target_organization_id for update;
    target_usage_month := pg_catalog.date_trunc('month', pg_catalog.now())::date;
    insert into app_private.order_quota_usage (order_id, organization_id, usage_month)
    values (new.id, target_organization_id, target_usage_month)
    on conflict (order_id) do nothing;
    select count(*) into current_count
    from app_private.order_quota_usage u
    join public.orders o on o.id = u.order_id
    where u.organization_id = target_organization_id
      and u.usage_month = target_usage_month
      and o.deleted_at is null;
    select count(*) into historical_count
    from public.orders o
    join public.stations s on s.id = o.station_id
    where s.organization_id = target_organization_id
      and o.deleted_at is null
      and o.created_at >= pg_catalog.date_trunc('month', pg_catalog.now())
      and o.created_at < pg_catalog.date_trunc('month', pg_catalog.now()) + interval '1 month'
      and not exists (
        select 1 from app_private.order_quota_usage u where u.order_id = o.id
      );
    current_count := current_count + historical_count;
    perform app_private.assert_plan_limit(target_organization_id, 'MONTHLY_ORDER_COUNT', current_count);
    return new;
  end if;

  if tg_table_name = 'stations' then
    target_organization_id := new.organization_id;
    is_active_now := new.is_active and new.deleted_at is null;
    if not is_active_now then return new; end if;
    if tg_op = 'UPDATE' then
      needs_count_check := (not old.is_active or old.deleted_at is not null)
        or old.organization_id is distinct from new.organization_id;
      if not needs_count_check then return new; end if;
    end if;
    perform app_private.require_plan_feature(target_organization_id, 'STATION_MANAGEMENT');
    perform 1 from public.organizations o where o.id = target_organization_id for update;
    select count(*) into current_count
    from public.stations s
    where s.organization_id = target_organization_id and s.is_active and s.deleted_at is null;
    perform app_private.assert_plan_limit(target_organization_id, 'STATION_COUNT', current_count);
    return new;
  end if;

  if tg_table_name = 'products' then
    select s.organization_id into target_organization_id
    from public.stations s where s.id = new.station_id;
    is_active_now := new.is_active and new.deleted_at is null;
    if tg_op = 'UPDATE' then
      needs_count_check := is_active_now and (
        (not old.is_active or old.deleted_at is not null)
        or old.station_id is distinct from new.station_id
      );
    end if;
    if is_active_now and (tg_op = 'INSERT' or needs_count_check) then
      perform app_private.require_plan_feature(target_organization_id, 'PRODUCT_MANAGEMENT');
      perform 1 from public.organizations o where o.id = target_organization_id for update;
      select count(*) into current_count
      from public.products p
      join public.stations s on s.id = p.station_id
      where s.organization_id = target_organization_id and p.is_active and p.deleted_at is null;
      perform app_private.assert_plan_limit(target_organization_id, 'PRODUCT_COUNT', current_count);
    end if;
    if is_active_now and new.promotion_is_active then
      perform app_private.require_plan_feature(target_organization_id, 'PROMOTIONS');
    end if;
    return new;
  end if;

  if tg_table_name = 'customers' then
    select s.organization_id into target_organization_id
    from public.stations s where s.id = new.station_id;
    is_active_now := new.is_active and new.deleted_at is null;
    if tg_op = 'UPDATE' then
      needs_count_check := is_active_now and (
        (not old.is_active or old.deleted_at is not null)
        or old.station_id is distinct from new.station_id
      );
    end if;
    if is_active_now and (tg_op = 'INSERT' or needs_count_check) then
      perform app_private.require_plan_feature(target_organization_id, 'CUSTOMER_MANAGEMENT');
      perform 1 from public.organizations o where o.id = target_organization_id for update;
      select count(*) into current_count
      from public.customers c
      join public.stations s on s.id = c.station_id
      where s.organization_id = target_organization_id and c.is_active and c.deleted_at is null;
      perform app_private.assert_plan_limit(target_organization_id, 'CUSTOMER_COUNT', current_count);
    end if;
    return new;
  end if;

  if tg_table_name = 'organization_members' then
    target_organization_id := new.organization_id;
    if new.role = 'OWNER' or not new.is_active then return new; end if;
    if tg_op = 'UPDATE' then
      needs_count_check := old.role = 'OWNER' or not old.is_active
        or old.organization_id is distinct from new.organization_id
        or old.role is distinct from new.role;
    end if;
    if tg_op = 'INSERT' or needs_count_check then
      perform app_private.require_plan_feature(target_organization_id, 'STAFF_ACCOUNTS');
      perform 1 from public.organizations o where o.id = target_organization_id for update;
      select count(*) into current_count
      from public.organization_members m
      where m.organization_id = target_organization_id and m.is_active and m.role <> 'OWNER';
      perform app_private.assert_plan_limit(target_organization_id, 'STAFF_COUNT', current_count);
    end if;
    return new;
  end if;

  if tg_table_name = 'riders' then
    select s.organization_id into target_organization_id
    from public.stations s where s.id = new.station_id;
    is_active_now := new.is_active and new.deleted_at is null;
    if tg_op = 'UPDATE' then
      needs_count_check := is_active_now and (
        (not old.is_active or old.deleted_at is not null)
        or old.station_id is distinct from new.station_id
      );
    end if;
    if is_active_now and (tg_op = 'INSERT' or needs_count_check) then
      perform app_private.require_plan_feature(target_organization_id, 'RIDER_MANAGEMENT');
      perform 1 from public.organizations o where o.id = target_organization_id for update;
      select count(*) into current_count
      from public.riders r
      join public.stations s on s.id = r.station_id
      where s.organization_id = target_organization_id and r.is_active and r.deleted_at is null;
      perform app_private.assert_plan_limit(target_organization_id, 'RIDER_COUNT', current_count);
    end if;
    return new;
  end if;

  if tg_table_name = 'order_items' then
    select o.station_id, o.order_source into target_station_id, source_value
    from public.orders o where o.id = new.order_id;
    if source_value is distinct from 'LEGACY_IMPORT' and new.product_id is not null and exists (
      select 1
      from public.products p
      where p.id = new.product_id
        and p.promotion_is_active
        and new.unit_price_centavos < p.price_centavos
    ) then
      select s.organization_id into target_organization_id
      from public.stations s where s.id = target_station_id;
      perform app_private.require_plan_feature(target_organization_id, 'PROMOTIONS');
    end if;
    return new;
  end if;

  if tg_table_name = 'expenses' then
    if not new.is_active or new.deleted_at is not null then return new; end if;
    select s.organization_id into target_organization_id
    from public.stations s where s.id = new.station_id;
    perform app_private.require_plan_feature(target_organization_id, 'EXPENSE_TRACKING');
    return new;
  end if;

  if tg_table_name = 'inventory_movements' then
    if coalesce(new.movement_type, '') not in ('ORDER_DEDUCTION', 'ORDER_RESTOCK', 'ORDER_REVERSAL') then
      select s.organization_id into target_organization_id
      from public.stations s where s.id = new.station_id;
      perform app_private.require_plan_feature(target_organization_id, 'BASIC_INVENTORY');
    end if;
    return new;
  end if;

  if tg_table_name = 'container_movements' then
    select s.organization_id into target_organization_id
    from public.stations s where s.id = new.station_id;
    perform app_private.require_plan_feature(target_organization_id, 'BASIC_CONTAINER_TRACKING');
    return new;
  end if;

  if tg_table_name = 'containers' then
    select s.organization_id into target_organization_id
    from public.stations s where s.id = new.station_id;
    perform app_private.require_plan_feature(target_organization_id, 'BASIC_CONTAINER_TRACKING');
    return new;
  end if;

  raise exception using errcode = '55000', message = 'plan guard is not configured for this table';
end;
$$;

revoke all on function app_private.enforce_plan_write() from public, anon, authenticated, service_role;
grant execute on function app_private.enforce_plan_write() to postgres;

drop trigger if exists plan_guard_orders_insert on public.orders;
create trigger plan_guard_orders_insert after insert on public.orders
for each row execute function app_private.enforce_plan_write();

drop trigger if exists plan_guard_stations_insert on public.stations;
create trigger plan_guard_stations_insert after insert on public.stations
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_stations_update on public.stations;
create trigger plan_guard_stations_update after update of organization_id, is_active, deleted_at on public.stations
for each row execute function app_private.enforce_plan_write();

drop trigger if exists plan_guard_products_insert on public.products;
create trigger plan_guard_products_insert after insert on public.products
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_products_update on public.products;
create trigger plan_guard_products_update after update of station_id, is_active, deleted_at, promotion_is_active,
  promotion_label, promotion_type, promotion_percent_bps, promotional_price_centavos,
  promotion_minimum_quantity, promotion_starts_at, promotion_ends_at on public.products
for each row execute function app_private.enforce_plan_write();

drop trigger if exists plan_guard_customers_insert on public.customers;
create trigger plan_guard_customers_insert after insert on public.customers
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_customers_update on public.customers;
create trigger plan_guard_customers_update after update of station_id, is_active, deleted_at on public.customers
for each row execute function app_private.enforce_plan_write();

drop trigger if exists plan_guard_members_insert on public.organization_members;
create trigger plan_guard_members_insert after insert on public.organization_members
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_members_update on public.organization_members;
create trigger plan_guard_members_update after update of organization_id, role, is_active on public.organization_members
for each row execute function app_private.enforce_plan_write();

drop trigger if exists plan_guard_riders_insert on public.riders;
create trigger plan_guard_riders_insert after insert on public.riders
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_riders_update on public.riders;
create trigger plan_guard_riders_update after update of station_id, is_active, deleted_at on public.riders
for each row execute function app_private.enforce_plan_write();

drop trigger if exists plan_guard_expenses_insert on public.expenses;
create trigger plan_guard_expenses_insert after insert on public.expenses
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_expenses_update on public.expenses;
create trigger plan_guard_expenses_update after update on public.expenses
for each row execute function app_private.enforce_plan_write();

drop trigger if exists plan_guard_inventory_movements_insert on public.inventory_movements;
create trigger plan_guard_inventory_movements_insert after insert on public.inventory_movements
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_container_movements_insert on public.container_movements;
create trigger plan_guard_container_movements_insert after insert on public.container_movements
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_containers_insert on public.containers;
create trigger plan_guard_containers_insert after insert on public.containers
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_containers_update on public.containers;
create trigger plan_guard_containers_update after update on public.containers
for each row execute function app_private.enforce_plan_write();
drop trigger if exists plan_guard_order_items_insert on public.order_items;
create trigger plan_guard_order_items_insert after insert on public.order_items
for each row execute function app_private.enforce_plan_write();

-- Effective UI entitlements and mutation-time guards must resolve the same
-- provider-verified active period. Stale paid rows fall back to the Free plan.
create or replace function public.get_effective_entitlements()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  with selected_membership as (
    select m.organization_id
    from public.organization_members m
    join public.profiles p on p.id = m.profile_id
    where m.profile_id = (select auth.uid()) and m.is_active
    order by (p.preferred_business_id = m.organization_id) desc, m.created_at
    limit 1
  ),
  selected_subscription as (
    select s.id, s.organization_id, s.plan_id, s.status::text as status,
           s.billing_cycle, s.current_period_end, s.trial_ends_at, s.expires_at,
           s.grace_period_ends_at, s.auto_renew, s.cancel_at_period_end
    from public.subscriptions s
    join selected_membership m on m.organization_id = s.organization_id
    where s.deleted_at is null
    order by s.updated_at desc
    limit 1
  ),
  effective_plan as (
    select app_private.effective_plan_id(m.organization_id) as id
    from selected_membership m
    union all
    select (select p.id from public.subscription_plans p where p.code = 'FREE' and p.is_active limit 1)
    where not exists (select 1 from selected_membership)
  ),
  selected_plan as (
    select p.id, p.code, p.name
    from public.subscription_plans p
    join effective_plan e on e.id = p.id
  ),
  output_status as (
    select case
      when s.id is null then 'FREE'
      when s.status = 'FREE' then 'FREE'
      when s.plan_id = (select id from selected_plan) then s.status
      else 'EXPIRED'
    end as status
    from (select 1) singleton
    left join selected_subscription s on true
  )
  select pg_catalog.jsonb_build_object(
    'organization_id', (select organization_id from selected_membership),
    'subscription_id', (select id from selected_subscription),
    'plan_code', coalesce((select code from selected_plan), 'FREE'),
    'plan_name', coalesce((select name from selected_plan), 'Free'),
    'status', coalesce((select status from output_status), 'FREE'),
    'billing_cycle', (select billing_cycle from selected_subscription),
    'current_period_end', (select current_period_end from selected_subscription),
    'trial_ends_at', (select trial_ends_at from selected_subscription),
    'expires_at', (select expires_at from selected_subscription),
    'grace_period_ends_at', (select grace_period_ends_at from selected_subscription),
    'auto_renew', coalesce((select auto_renew from selected_subscription), false),
    'cancel_at_period_end', coalesce((select cancel_at_period_end from selected_subscription), false),
    'features', coalesce((
      select pg_catalog.jsonb_object_agg(pf.feature, pf.enabled)
      from public.plan_features pf join selected_plan sp on sp.id = pf.plan_id
    ), '{}'::jsonb),
    'limits', coalesce((
      select pg_catalog.jsonb_object_agg(pl.limit_type, pl.max_value)
      from public.plan_limits pl join selected_plan sp on sp.id = pl.plan_id
    ), '{}'::jsonb)
  );
$$;

revoke all on function public.get_effective_entitlements() from public, anon;
grant execute on function public.get_effective_entitlements() to authenticated;

-- Orders, lines and receipts are mutated only through validated RPCs. Retain
-- the one non-financial order-note update used by the current Owner client.
revoke insert, update, delete on table public.orders from authenticated;
revoke update (payment_method, payment_status, station_note) on table public.orders from authenticated;
grant update (station_note) on table public.orders to authenticated;
revoke insert, update, delete on table public.order_items from authenticated;
revoke insert, update, delete on table public.payments from authenticated;

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
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  caller_role public.member_role;
  order_row public.orders%rowtype;
  existing public.payments%rowtype;
  paid_total bigint;
  target_organization_id uuid;
  assigned_rider boolean := false;
  normalized_method text := pg_catalog.upper(pg_catalog.btrim(p_payment_method));
  metadata_value jsonb := coalesce(p_metadata, '{}'::jsonb);
  proof_path text;
  expected_prefix text;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if nullif(pg_catalog.btrim(p_operation_id), '') is null or p_amount_centavos is null or p_amount_centavos <= 0 then
    raise exception using errcode = '22023', message = 'payment operation and positive amount are required';
  end if;
  if normalized_method is null or normalized_method not in ('CASH', 'CASH_ON_DELIVERY', 'GCASH', 'MAYA') then
    raise exception using errcode = '22023', message = 'unsupported payment method';
  end if;
  if nullif(pg_catalog.btrim(p_provider), '') is not null
     or nullif(pg_catalog.btrim(p_provider_transaction_id), '') is not null then
    raise exception using errcode = '22023', message = 'provider settlements require trusted server verification';
  end if;
  if pg_catalog.jsonb_typeof(metadata_value) <> 'object' then
    raise exception using errcode = '22023', message = 'payment metadata must be an object';
  end if;

  select * into order_row
  from public.orders o
  where o.id = p_order_id
  for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  if order_row.status in ('CANCELLED', 'REJECTED') then
    raise exception using errcode = '55000', message = 'payment is not allowed for this order';
  end if;

  select s.organization_id into target_organization_id
  from public.stations s where s.id = order_row.station_id;

  select m.role into caller_role
  from public.organization_members m
  left join public.member_stations ms on ms.member_id = m.id and ms.station_id = order_row.station_id
  where m.organization_id = target_organization_id
    and m.profile_id = caller
    and m.is_active
    and m.role in ('OWNER', 'MANAGER', 'STAFF', 'RIDER')
    and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
  limit 1;

  if caller_role is null then
    raise exception using errcode = '42501', message = 'payment access denied';
  end if;

  if caller_role = 'RIDER' then
    select exists (
      select 1 from public.riders r
      where r.id = order_row.rider_id
        and r.profile_id = caller
        and r.is_active
        and r.deleted_at is null
        and order_row.delivery_mode = 'DELIVERY'
        and order_row.status in ('DELIVERED', 'COMPLETED')
    ) into assigned_rider;
    if not assigned_rider then
      raise exception using errcode = '42501', message = 'only the assigned rider can record delivery collection';
    end if;

    proof_path := nullif(metadata_value ->> 'delivery_payment_proof_object_path', '');
    expected_prefix := 'organizations/' || target_organization_id::text
      || '/stations/' || order_row.station_id::text
      || '/delivery-payment-proofs/' || order_row.id::text || '/';
    if proof_path is null
       or pg_catalog.left(proof_path, pg_catalog.length(expected_prefix)) <> expected_prefix
       or proof_path !~ '\.(jpg|png|webp)$'
       or not exists (
         select 1 from storage.objects so
         where so.bucket_id = 'payment-assets'
           and so.name = proof_path
           and so.owner_id = caller::text
       ) then
      raise exception using errcode = '42501', message = 'assigned riders must upload their order payment proof first';
    end if;
  end if;

  select * into existing
  from public.payments p
  where p.operation_id = pg_catalog.btrim(p_operation_id);
  if existing.id is not null then
    if existing.order_id <> p_order_id
       or existing.amount_centavos <> p_amount_centavos
       or existing.payment_method <> normalized_method then
      raise exception using errcode = '22023', message = 'payment operation was already used for a different receipt';
    end if;
    return query select existing.id, existing.order_id, existing.status, existing.amount_centavos, false;
    return;
  end if;

  select coalesce(pg_catalog.sum(p.amount_centavos), 0) into paid_total
  from public.payments p
  where p.order_id = order_row.id and p.status = 'PAID';
  if p_amount_centavos > greatest(order_row.total_centavos - paid_total, 0) then
    raise exception using errcode = '22023', message = 'payment exceeds the remaining order balance';
  end if;

  insert into public.payments (
    organization_id, station_id, customer_id, order_id, payment_method,
    amount_centavos, status, reference_number, provider, provider_transaction_id,
    metadata, operation_id, created_at, paid_at, updated_at
  ) values (
    target_organization_id, order_row.station_id, order_row.customer_id, order_row.id,
    normalized_method, p_amount_centavos, 'PAID',
    nullif(pg_catalog.btrim(p_reference_number), ''), null, null,
    metadata_value, pg_catalog.btrim(p_operation_id), pg_catalog.now(), pg_catalog.now(), pg_catalog.now()
  ) returning id into existing.id;

  paid_total := paid_total + p_amount_centavos;
  update public.orders o
  set payment_status = case when paid_total >= o.total_centavos then 'PAID' else 'PENDING' end,
      payment_method = case when order_row.payment_method = 'CLIENT_ACCOUNT' then order_row.payment_method else normalized_method end,
      updated_at = pg_catalog.now()
  where o.id = order_row.id;

  return query select existing.id, order_row.id, 'PAID'::text, p_amount_centavos, true;
end;
$$;

revoke all on function public.record_payment(uuid, text, bigint, text, text, text, text, jsonb)
  from public, anon, authenticated, service_role;
grant execute on function public.record_payment(uuid, text, bigint, text, text, text, text, jsonb)
  to authenticated, service_role;

-- Cancellation/rejection restores inventory, but must not silently erase an
-- already collected amount. A refund workflow must reconcile paid receipts
-- before an in-progress order can enter either terminal state.
create or replace function app_private.prevent_terminal_order_with_paid_receipt()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if old.status is distinct from new.status
     and new.status in ('CANCELLED', 'REJECTED')
     and (
       old.payment_status = 'PAID'
       or exists (
         select 1
         from public.payments p
         where p.order_id = old.id
           and p.status in ('PAID', 'PARTIALLY_REFUNDED')
       )
     ) then
    raise exception using errcode = '55000',
      message = 'record or reconcile a refund before cancelling or rejecting a paid order';
  end if;
  return new;
end;
$$;

revoke all on function app_private.prevent_terminal_order_with_paid_receipt()
  from public, anon, authenticated, service_role;
grant execute on function app_private.prevent_terminal_order_with_paid_receipt() to postgres;

drop trigger if exists orders_prevent_terminal_with_paid_receipt on public.orders;
create trigger orders_prevent_terminal_with_paid_receipt
before update of status on public.orders
for each row execute function app_private.prevent_terminal_order_with_paid_receipt();

create or replace function public.charge_order_to_client_account(p_order_id uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  target_organization_id uuid;
  caller_role public.member_role;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  select * into order_row from public.orders o where o.id = p_order_id for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  select s.organization_id into target_organization_id from public.stations s where s.id = order_row.station_id;
  select m.role into caller_role
  from public.organization_members m
  left join public.member_stations ms on ms.member_id = m.id and ms.station_id = order_row.station_id
  where m.organization_id = target_organization_id and m.profile_id = caller and m.is_active
    and m.role in ('OWNER', 'MANAGER', 'STAFF')
    and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
  limit 1;
  if caller_role is null then
    raise exception using errcode = '42501', message = 'station access denied';
  end if;
  if order_row.status in ('CANCELLED', 'REJECTED') then
    raise exception using errcode = '55000', message = 'cancelled orders cannot be charged to account';
  end if;
  perform app_private.require_plan_feature(target_organization_id, 'ACCOUNTS_RECEIVABLE');

  if order_row.payment_method = 'CLIENT_ACCOUNT' then
    return order_row.id;
  end if;
  if order_row.payment_status <> 'PENDING' or exists (
    select 1 from public.payments p where p.order_id = order_row.id and p.status = 'PAID'
  ) then
    raise exception using errcode = '55000', message = 'an order with a collected payment cannot be charged to account';
  end if;

  update public.orders
  set payment_method = 'CLIENT_ACCOUNT', payment_status = 'PENDING', updated_at = pg_catalog.now()
  where id = order_row.id;
  return order_row.id;
end;
$$;

revoke all on function public.charge_order_to_client_account(uuid) from public, anon, authenticated, service_role;
grant execute on function public.charge_order_to_client_account(uuid) to authenticated, service_role;

-- Legacy outbox imports are still needed for offline Owner/staff sales. Only
-- active station operators may invoke them. Client-provided paid state is never
-- treated as a receipt; receipt rows must arrive through record_payment.
create or replace function public.import_legacy_order(
  p_order_id uuid,
  p_station_id uuid,
  p_order_number text,
  p_customer_id uuid,
  p_customer_name text,
  p_customer_phone text,
  p_rider_id uuid,
  p_delivery_address_id uuid,
  p_delivery_address text,
  p_delivery_latitude double precision,
  p_delivery_longitude double precision,
  p_delivery_mode public.delivery_mode,
  p_subtotal_centavos bigint,
  p_delivery_fee_centavos bigint,
  p_discount_centavos bigint,
  p_total_centavos bigint,
  p_payment_method text,
  p_payment_status text,
  p_status public.order_status,
  p_scheduled_delivery_start bigint,
  p_scheduled_delivery_end bigint,
  p_customer_note text,
  p_station_note text,
  p_created_at bigint,
  p_accepted_at bigint,
  p_ready_at bigint,
  p_assigned_at bigint,
  p_out_for_delivery_at bigint,
  p_delivered_at bigint,
  p_completed_at bigint,
  p_cancelled_at bigint,
  p_cancellation_reason text,
  p_client_updated_at bigint,
  p_items jsonb
)
returns table (order_id uuid, created boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  station public.stations%rowtype;
  item jsonb;
  item_product uuid;
  item_quantity integer;
  item_unit_price bigint;
  item_subtotal bigint;
  items_subtotal numeric := 0;
  now_ms bigint := (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint;
  existing_station_id uuid;
  actor_role public.member_role;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_order_id is null or p_station_id is null or nullif(pg_catalog.btrim(p_order_number), '') is null then
    raise exception using errcode = '22023', message = 'order identity, station, and number are required';
  end if;
  select * into station from public.stations s
  where s.id = p_station_id and s.is_active and s.deleted_at is null;
  if station.id is null then
    raise exception using errcode = '22023', message = 'station is unavailable';
  end if;
  select m.role into actor_role
  from public.organization_members m
  left join public.member_stations ms on ms.member_id = m.id and ms.station_id = p_station_id
  where m.organization_id = station.organization_id and m.profile_id = caller and m.is_active
    and m.role in ('OWNER', 'MANAGER', 'STAFF')
    and (m.role = 'OWNER' or ms.station_id is not null)
  limit 1;
  if actor_role is null then
    raise exception using errcode = '42501', message = 'legacy order import requires an assigned station operator';
  end if;
  if p_subtotal_centavos is null or p_delivery_fee_centavos is null or p_discount_centavos is null
     or p_total_centavos is null or p_subtotal_centavos < 0 or p_delivery_fee_centavos < 0
     or p_discount_centavos < 0 or p_total_centavos < 0 or p_discount_centavos > p_subtotal_centavos
     or p_total_centavos::numeric <> p_subtotal_centavos::numeric - p_discount_centavos::numeric + p_delivery_fee_centavos::numeric
     or p_created_at is null or p_created_at < 0 or p_created_at > now_ms + 300000 then
    raise exception using errcode = '22023', message = 'legacy order amounts or timestamp are invalid';
  end if;
  if p_delivery_mode is null or p_status is null
     or upper(coalesce(p_payment_method, '')) not in ('CASH', 'CASH_ON_DELIVERY', 'GCASH', 'MAYA', 'CLIENT_ACCOUNT') then
    raise exception using errcode = '22023', message = 'legacy order state or payment method is invalid';
  end if;
  if jsonb_typeof(p_items) <> 'array' or jsonb_array_length(p_items) < 1 or jsonb_array_length(p_items) > 100 then
    raise exception using errcode = '22023', message = 'legacy order items must contain 1 to 100 items';
  end if;
  if p_delivery_mode = 'DELIVERY' and nullif(pg_catalog.btrim(p_delivery_address), '') is null then
    raise exception using errcode = '22023', message = 'delivery address is required';
  end if;
  if (p_delivery_latitude is null) <> (p_delivery_longitude is null)
     or (p_delivery_latitude is not null and (p_delivery_latitude not between -90 and 90 or p_delivery_longitude not between -180 and 180)) then
    raise exception using errcode = '22023', message = 'delivery coordinates are invalid';
  end if;
  if p_customer_id is not null and not exists (
    select 1 from public.customers c where c.id = p_customer_id and c.station_id = p_station_id
  ) then
    raise exception using errcode = '23503', message = 'customer does not belong to this station';
  end if;
  if p_delivery_address_id is not null and not exists (
    select 1 from public.customer_addresses a
    where a.id = p_delivery_address_id and a.station_id = p_station_id
      and (p_customer_id is null or a.customer_id = p_customer_id)
  ) then
    raise exception using errcode = '23503', message = 'delivery address does not belong to this station customer';
  end if;
  if p_rider_id is not null and not exists (
    select 1 from public.riders r where r.id = p_rider_id and r.station_id = p_station_id
  ) then
    raise exception using errcode = '23503', message = 'rider does not belong to this station';
  end if;
  select o.station_id into existing_station_id
  from public.orders o where o.id = p_order_id for key share;
  if existing_station_id is not null then
    if existing_station_id <> p_station_id then
      raise exception using errcode = '42501', message = 'legacy order identity belongs to another station';
    end if;
    return query select p_order_id, false;
    return;
  end if;

  for item in select value from pg_catalog.jsonb_array_elements(p_items)
  loop
    item_quantity := coalesce((item->>'quantity')::integer, 0);
    item_unit_price := coalesce((item->>'unit_price_centavos')::bigint, -1);
    item_subtotal := coalesce((item->>'subtotal_centavos')::bigint, -1);
    if item_quantity not between 1 and 99 or item_unit_price < 0 or item_subtotal < 0
       or nullif(pg_catalog.btrim(item->>'product_name'), '') is null
       or item_subtotal::numeric <> item_unit_price::numeric * item_quantity::numeric then
      raise exception using errcode = '22023', message = 'legacy order item is invalid';
    end if;
    if coalesce(item->>'product_type', 'OTHER') not in ('REFILL', 'NEW_CONTAINER', 'EMPTY_CONTAINER', 'OTHER') then
      raise exception using errcode = '22023', message = 'legacy order product type is invalid';
    end if;
    item_product := nullif(item->>'product_id', '')::uuid;
    if item_product is not null and not exists (
      select 1 from public.products p where p.id = item_product and p.station_id = p_station_id
    ) then
      raise exception using errcode = '23503', message = 'order item product does not belong to this station';
    end if;
    items_subtotal := items_subtotal + item_subtotal::numeric;
  end loop;
  if p_subtotal_centavos::numeric <> items_subtotal + p_discount_centavos::numeric then
    raise exception using errcode = '22023', message = 'legacy order totals do not match its item snapshots';
  end if;

  insert into public.orders (
    id, station_id, customer_id, order_number, customer_name, customer_phone,
    rider_id, delivery_address_id, delivery_address, delivery_latitude, delivery_longitude,
    delivery_mode, subtotal_centavos, delivery_fee_centavos, discount_centavos, total_centavos,
    payment_method, payment_status, status, scheduled_delivery_start, scheduled_delivery_end,
    customer_note, station_note, cancellation_reason, accepted_at, ready_at, assigned_at,
    out_for_delivery_at, delivered_at, completed_at, cancelled_at, requested_at,
    order_source, client_updated_at, created_at, updated_at
  ) values (
    p_order_id, p_station_id, p_customer_id, pg_catalog.btrim(p_order_number), coalesce(p_customer_name, ''), coalesce(p_customer_phone, ''),
    p_rider_id, p_delivery_address_id, coalesce(p_delivery_address, ''), p_delivery_latitude, p_delivery_longitude,
    p_delivery_mode, p_subtotal_centavos, p_delivery_fee_centavos, p_discount_centavos, p_total_centavos,
    upper(pg_catalog.btrim(coalesce(p_payment_method, 'CASH'))), 'PENDING', p_status,
    p_scheduled_delivery_start, p_scheduled_delivery_end, p_customer_note, p_station_note,
    nullif(pg_catalog.btrim(p_cancellation_reason), ''), p_accepted_at, p_ready_at, p_assigned_at,
    p_out_for_delivery_at, p_delivered_at, p_completed_at, p_cancelled_at, p_created_at,
    'LEGACY_IMPORT', p_client_updated_at,
    pg_catalog.to_timestamp(p_created_at::numeric / 1000), pg_catalog.now()
  );

  for item in select value from pg_catalog.jsonb_array_elements(p_items)
  loop
    item_product := nullif(item->>'product_id', '')::uuid;
    insert into public.order_items (
      order_id, product_id, product_name_snapshot, product_type_snapshot,
      size_label_snapshot, unit_price_centavos, quantity, subtotal_centavos
    ) values (
      p_order_id, item_product, pg_catalog.btrim(item->>'product_name'),
      coalesce(nullif(pg_catalog.btrim(item->>'product_type'), ''), 'OTHER'),
      coalesce(item->>'size_label', ''), (item->>'unit_price_centavos')::bigint,
      (item->>'quantity')::integer, (item->>'subtotal_centavos')::bigint
    );
  end loop;

  return query select p_order_id, true;
end;
$$;

revoke all on function public.import_legacy_order(uuid, uuid, text, uuid, text, text, uuid, uuid, text, double precision, double precision, public.delivery_mode, bigint, bigint, bigint, bigint, text, text, public.order_status, bigint, bigint, text, text, bigint, bigint, bigint, bigint, bigint, bigint, bigint, bigint, text, bigint, jsonb)
  from public, anon, authenticated, service_role;
grant execute on function public.import_legacy_order(uuid, uuid, text, uuid, text, text, uuid, uuid, text, double precision, double precision, public.delivery_mode, bigint, bigint, bigint, bigint, text, text, public.order_status, bigint, bigint, text, text, bigint, bigint, bigint, bigint, bigint, bigint, bigint, bigint, text, bigint, jsonb)
  to authenticated, service_role;

-- Keep create_order's existing authoritative transaction intact while adding
-- authorization to its idempotent-return path. The advisory lock also makes
-- concurrent retries for the same client operation serialize before lookup.
alter function public.create_order(
  uuid, text, text, jsonb, uuid, text, text, public.delivery_mode,
  text, text, double precision, double precision, bigint, text, text
) rename to create_order_internal;

revoke all on function public.create_order_internal(
  uuid, text, text, jsonb, uuid, text, text, public.delivery_mode,
  text, text, double precision, double precision, bigint, text, text
) from public, anon, authenticated, service_role;

create function public.create_order(
  p_station_id uuid,
  p_client_operation_id text,
  p_source text,
  p_items jsonb,
  p_customer_id uuid default null,
  p_customer_name text default '',
  p_customer_phone text default '',
  p_delivery_mode public.delivery_mode default 'PICKUP',
  p_delivery_address text default '',
  p_delivery_place_id text default null,
  p_delivery_latitude double precision default null,
  p_delivery_longitude double precision default null,
  p_delivery_fee_centavos bigint default null,
  p_payment_method text default 'CASH',
  p_customer_note text default null
)
returns table (order_id uuid, order_number text, total_centavos bigint, created boolean, status public.order_status)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  existing public.orders%rowtype;
  operation_id text := pg_catalog.btrim(p_client_operation_id);
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if nullif(operation_id, '') is null then
    raise exception using errcode = '22023', message = 'order operation id is required';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(operation_id, 0));
  select o.* into existing
  from public.orders o
  where o.client_operation_id = operation_id
  for update;

  if existing.id is not null then
    if existing.station_id is distinct from p_station_id
       or existing.order_source is distinct from p_source then
      raise exception using errcode = '42501', message = 'order operation belongs to another order context';
    end if;
    if existing.order_source in ('CUSTOMER_APP', 'CUSTOMER_WEB') then
      if existing.customer_uid is distinct from caller then
        raise exception using errcode = '42501', message = 'customer order access denied';
      end if;
    elsif not app_private.can_operate_station(existing.station_id) then
      raise exception using errcode = '42501', message = 'station access denied';
    end if;
    return query select existing.id, existing.order_number, existing.total_centavos, false, existing.status;
    return;
  end if;

  return query
  select * from public.create_order_internal(
    p_station_id, operation_id, p_source, p_items, p_customer_id,
    p_customer_name, p_customer_phone, p_delivery_mode, p_delivery_address,
    p_delivery_place_id, p_delivery_latitude, p_delivery_longitude,
    p_delivery_fee_centavos, p_payment_method, p_customer_note
  );
end;
$$;

revoke all on function public.create_order(
  uuid, text, text, jsonb, uuid, text, text, public.delivery_mode,
  text, text, double precision, double precision, bigint, text, text
) from public, anon, authenticated, service_role;
grant execute on function public.create_order(
  uuid, text, text, jsonb, uuid, text, text, public.delivery_mode,
  text, text, double precision, double precision, bigint, text, text
) to authenticated, service_role;

commit;
