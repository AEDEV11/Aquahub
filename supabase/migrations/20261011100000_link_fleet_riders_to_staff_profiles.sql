-- Fleet riders are selected from the active Rider staff directory by phone.
-- Resolve that same station-scoped account link on the server so rider proof
-- uploads and delivery mutations use the authenticated profile identity.
create or replace function app_private.match_active_rider_profile(
  target_station_id uuid,
  target_phone text
)
returns uuid
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
  with normalized_target as (
    select case
      when left(digits, 1) = '0' then '63' || substr(digits, 2)
      else digits
    end as phone
    from (
      select pg_catalog.regexp_replace(coalesce(target_phone, ''), '[^0-9]', '', 'g') as digits
    ) value
  ), profile_phones as (
    select distinct m.profile_id
      , case
          when left(digits, 1) = '0' then '63' || substr(digits, 2)
          else digits
        end as phone
    from public.stations s
    join public.organization_members m
      on m.organization_id = s.organization_id
     and m.role = 'RIDER'
     and m.is_active
    join public.member_stations ms
      on ms.member_id = m.id
     and ms.station_id = s.id
    join public.profiles p on p.id = m.profile_id
    cross join lateral (
      select pg_catalog.regexp_replace(coalesce(p.phone, ''), '[^0-9]', '', 'g') as digits
    ) normalized
    where s.id = target_station_id
      and digits <> ''
  ), candidates as (
    select profile_phones.profile_id
    from profile_phones
    cross join normalized_target target
    where target.phone <> ''
      and profile_phones.phone = target.phone
  ), unique_candidate as (
    select min(profile_id::text)::uuid as profile_id
    from candidates
    having count(*) = 1
  )
  select profile_id from unique_candidate;
$$;

revoke all on function app_private.match_active_rider_profile(uuid, text)
  from public, anon, authenticated, service_role;
grant execute on function app_private.match_active_rider_profile(uuid, text) to postgres;

create or replace function app_private.link_fleet_rider_profile()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  if new.is_active and new.deleted_at is null then
    new.profile_id := app_private.match_active_rider_profile(new.station_id, new.phone);
  else
    new.profile_id := null;
  end if;
  return new;
end;
$$;

revoke all on function app_private.link_fleet_rider_profile()
  from public, anon, authenticated, service_role;
grant execute on function app_private.link_fleet_rider_profile() to postgres;

drop trigger if exists riders_link_staff_profile on public.riders;
create trigger riders_link_staff_profile
before insert or update on public.riders
for each row execute function app_private.link_fleet_rider_profile();

-- Repair only active, non-deleted fleet riders that have one exact account
-- match. Ambiguous and unmatched records remain unlinked for owner correction.
with uniquely_matched_riders as (
  select r.id, app_private.match_active_rider_profile(r.station_id, r.phone) as profile_id
  from public.riders r
  where r.profile_id is null
    and r.is_active
    and r.deleted_at is null
)
update public.riders r
set profile_id = matches.profile_id
from uniquely_matched_riders matches
where r.id = matches.id
  and matches.profile_id is not null;
