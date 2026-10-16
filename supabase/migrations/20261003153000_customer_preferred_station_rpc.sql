-- Customer station context must be resolved server-side. The client supplies
-- only the public station UUID and acquisition source.

create or replace function public.set_customer_preferred_station(
  p_station_id uuid,
  p_acquisition_source text default 'UNKNOWN'
)
returns public.profiles
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  caller uuid := auth.uid();
  station public.stations%rowtype;
  updated_profile public.profiles;
begin
  if caller is null then raise exception using errcode = '28000', message = 'authenticated user is required'; end if;
  select * into station from public.stations where id = p_station_id and is_active and deleted_at is null;
  if station.id is null or not station.is_accepting_online_orders then raise exception using errcode = '22023', message = 'station is unavailable'; end if;
  if upper(coalesce(p_acquisition_source, 'UNKNOWN')) not in ('STATION_QR', 'STATION_LINK', 'OWNER_REFERRAL', 'ORGANIC_APP', 'SEARCH', 'UNKNOWN') then
    raise exception using errcode = '22023', message = 'invalid acquisition source';
  end if;
  update public.profiles
  set preferred_business_id = station.organization_id,
      preferred_station_id = station.id,
      acquisition_source = upper(coalesce(p_acquisition_source, 'UNKNOWN')),
      acquired_at = coalesce(acquired_at, now()),
      updated_at = now()
  where id = caller
  returning * into updated_profile;
  if updated_profile.id is null then raise exception using errcode = '42501', message = 'customer profile is not provisioned'; end if;
  return updated_profile;
end;
$$;

revoke all on function public.set_customer_preferred_station(uuid, text) from public, anon, authenticated, service_role;
grant execute on function public.set_customer_preferred_station(uuid, text) to authenticated, service_role;
