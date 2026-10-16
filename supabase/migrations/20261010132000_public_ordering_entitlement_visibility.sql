-- Public catalog reads must reflect the same effective, time-bounded plan as
-- the order insert guard. The stored projection contains only the station's
-- own switch and does not change when a subscription expires.
begin;

-- A downgrade retains station rows. Keep only the oldest active stations in
-- the effective plan's slots; UUID makes equal timestamps deterministic.
create or replace function app_private.station_within_effective_plan_limit(p_station_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce((
    select count(*) <= pl.max_value
    from public.stations target
    join public.organizations o on o.id = target.organization_id and o.is_active
    join public.plan_limits pl
      on pl.plan_id = app_private.effective_plan_id(target.organization_id)
     and pl.limit_type = 'STATION_COUNT'
    join public.stations earlier
      on earlier.organization_id = target.organization_id
     and earlier.is_active and earlier.deleted_at is null
     and (earlier.created_at, earlier.id) <= (target.created_at, target.id)
    where target.id = p_station_id and target.is_active and target.deleted_at is null
    group by pl.max_value
  ), false);
$$;

revoke all on function app_private.station_within_effective_plan_limit(uuid)
  from public, anon, authenticated, service_role;
grant execute on function app_private.station_within_effective_plan_limit(uuid) to postgres;

create or replace function public.get_public_station_ordering_eligibility(p_station_ids uuid[])
returns table (station_id uuid, is_ordering_available boolean)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if p_station_ids is null or pg_catalog.cardinality(p_station_ids) > 100 then
    raise exception using errcode = '22023', message = 'invalid station id list';
  end if;

  -- Return only rows already present in the deliberately public projection.
  -- No subscription, plan, or organization details leave this function.
  return query
  select ps.station_id,
         ps.is_open and ps.is_accepting_orders and coalesce(pf.enabled, false)
           and app_private.station_within_effective_plan_limit(ps.station_id)
  from public.public_stations ps
  left join public.plan_features pf
    on pf.plan_id = app_private.effective_plan_id(ps.organization_id)
   and pf.feature = 'MOBILE_APP_ORDERING'
  where ps.station_id = any(p_station_ids)
  order by ps.station_id;
end;
$$;

revoke all on function public.get_public_station_ordering_eligibility(uuid[])
  from public, anon, authenticated, service_role;
grant execute on function public.get_public_station_ordering_eligibility(uuid[])
  to anon, authenticated, service_role;

comment on function public.get_public_station_ordering_eligibility(uuid[]) is
  'Read-time public station ordering availability; returns projected station IDs and eligibility only.';

-- Apply the same slot decision to both authenticated app and guest web order
-- inserts. The existing plan feature and monthly quota guard remains in force.
create or replace function app_private.enforce_customer_ordering_station_slot()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.order_source in ('CUSTOMER_APP', 'CUSTOMER_WEB')
     and not app_private.station_within_effective_plan_limit(new.station_id) then
    raise exception using errcode = '42501', message = 'station exceeds subscription limit';
  end if;
  return new;
end;
$$;

revoke all on function app_private.enforce_customer_ordering_station_slot()
  from public, anon, authenticated, service_role;
grant execute on function app_private.enforce_customer_ordering_station_slot() to postgres;

create trigger a_plan_guard_customer_ordering_station_slot
before insert on public.orders
for each row execute function app_private.enforce_customer_ordering_station_slot();

commit;
