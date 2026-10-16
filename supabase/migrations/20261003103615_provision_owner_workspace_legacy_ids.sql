-- Idempotent repair path for owners whose legacy Firebase workspace already
-- exists but whose Supabase organization/station boundary was never created.
-- Firebase IDs are preserved so businesses/{businessId}/stations/{stationId}
-- remains an unambiguous compatibility mapping.
create or replace function public.provision_owner_workspace_legacy(
  p_firebase_business_id text,
  p_firebase_station_id text,
  p_business_name text,
  p_owner_name text,
  p_phone text,
  p_email text,
  p_station_name text,
  p_station_description text,
  p_station_phone text,
  p_station_address text,
  p_barangay text,
  p_city text,
  p_province text,
  p_latitude double precision,
  p_longitude double precision,
  p_place_id text,
  p_opening_time text,
  p_closing_time text
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  caller_id uuid := auth.uid();
  owner_profile public.profiles;
  owner_organization public.organizations;
  owner_member public.organization_members;
  owner_station public.stations;
begin
  if caller_id is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if nullif(btrim(p_firebase_business_id), '') is null
     or nullif(btrim(p_firebase_station_id), '') is null
     or nullif(btrim(p_business_name), '') is null
     or nullif(btrim(p_owner_name), '') is null
     or nullif(btrim(p_phone), '') is null
     or nullif(btrim(p_station_name), '') is null
     or nullif(btrim(p_station_address), '') is null then
    raise exception using errcode = '22023', message = 'required workspace fields are missing';
  end if;
  if (p_latitude is null) <> (p_longitude is null)
     or (p_latitude is not null and (p_latitude < -90 or p_latitude > 90))
     or (p_longitude is not null and (p_longitude < -180 or p_longitude > 180)) then
    raise exception using errcode = '22023', message = 'invalid station coordinates';
  end if;

  insert into public.profiles (
    id, display_name, email, phone, email_verified, phone_verified,
    account_state, role, setup_complete, updated_at
  ) values (
    caller_id, btrim(p_owner_name), nullif(btrim(p_email), ''), btrim(p_phone),
    false, false, 'ACTIVE', 'OWNER', true, now()
  )
  on conflict (id) do update set
    display_name = excluded.display_name,
    email = coalesce(excluded.email, public.profiles.email),
    phone = excluded.phone,
    account_state = case when public.profiles.account_state = 'DISABLED' then 'DISABLED' else 'ACTIVE' end,
    role = 'OWNER', setup_complete = true, updated_at = now()
  returning * into owner_profile;

  if owner_profile.account_state = 'DISABLED' then
    raise exception using errcode = '42501', message = 'profile is disabled';
  end if;

  select * into owner_organization
  from public.organizations
  where firebase_id = btrim(p_firebase_business_id)
  limit 1;
  if owner_organization.id is not null and owner_organization.owner_profile_id is distinct from caller_id then
    raise exception using errcode = '23505', message = 'legacy business is owned by another profile';
  end if;

  if owner_organization.id is null then
    select * into owner_organization
    from public.organizations
    where owner_profile_id = caller_id and is_active = true
    order by created_at limit 1;
  end if;
  if owner_organization.id is null then
    insert into public.organizations (firebase_id, owner_profile_id, name, is_active)
    values (btrim(p_firebase_business_id), caller_id, btrim(p_business_name), true)
    returning * into owner_organization;
  else
    update public.organizations
    set firebase_id = coalesce(firebase_id, btrim(p_firebase_business_id)),
        name = btrim(p_business_name), is_active = true, updated_at = now()
    where id = owner_organization.id
    returning * into owner_organization;
  end if;

  insert into public.organization_members (organization_id, profile_id, role, is_active, firebase_uid)
  values (owner_organization.id, caller_id, 'OWNER', true, null)
  on conflict (organization_id, profile_id) do update set
    role = 'OWNER', is_active = true, updated_at = now()
  returning * into owner_member;

  select * into owner_station
  from public.stations
  where firebase_id = btrim(p_firebase_station_id)
  limit 1;
  if owner_station.id is not null and owner_station.organization_id <> owner_organization.id then
    raise exception using errcode = '23505', message = 'legacy station belongs to another organization';
  end if;
  if owner_station.id is null then
    insert into public.stations (
      firebase_id, organization_id, name, description, phone, email, address,
      barangay, city, province, place_id, latitude, longitude,
      opening_time, closing_time, is_open, is_active, client_updated_at
    ) values (
      btrim(p_firebase_station_id), owner_organization.id, btrim(p_station_name),
      nullif(btrim(p_station_description), ''), btrim(coalesce(nullif(p_station_phone, ''), p_phone)),
      nullif(btrim(p_email), ''), btrim(p_station_address), nullif(btrim(p_barangay), ''),
      nullif(btrim(p_city), ''), nullif(btrim(p_province), ''), nullif(btrim(p_place_id), ''),
      p_latitude, p_longitude, nullif(btrim(p_opening_time), ''), nullif(btrim(p_closing_time), ''),
      true, true, floor(extract(epoch from clock_timestamp()) * 1000)::bigint
    ) returning * into owner_station;
  else
    update public.stations set
      name = btrim(p_station_name), description = nullif(btrim(p_station_description), ''),
      phone = coalesce(nullif(btrim(p_station_phone), ''), phone, btrim(p_phone)),
      email = coalesce(nullif(btrim(p_email), ''), email), address = btrim(p_station_address),
      barangay = nullif(btrim(p_barangay), ''), city = nullif(btrim(p_city), ''),
      province = nullif(btrim(p_province), ''), place_id = nullif(btrim(p_place_id), ''),
      latitude = p_latitude, longitude = p_longitude,
      opening_time = nullif(btrim(p_opening_time), ''), closing_time = nullif(btrim(p_closing_time), ''),
      is_active = true, deleted_at = null,
      client_updated_at = floor(extract(epoch from clock_timestamp()) * 1000)::bigint, updated_at = now()
    where id = owner_station.id returning * into owner_station;
  end if;

  insert into public.member_stations (member_id, station_id)
  values (owner_member.id, owner_station.id) on conflict do nothing;
  update public.profiles set preferred_business_id = owner_organization.id,
    preferred_station_id = owner_station.id, setup_complete = true, updated_at = now()
  where id = caller_id;

  return jsonb_build_object('profile_id', caller_id, 'organization_id', owner_organization.id,
    'member_id', owner_member.id, 'station_id', owner_station.id,
    'firebase_business_id', owner_organization.firebase_id,
    'firebase_station_id', owner_station.firebase_id);
end;
$$;

revoke all on function public.provision_owner_workspace_legacy(
  text, text, text, text, text, text, text, text, text, text, text, text, text,
  double precision, double precision, text, text, text
) from public, anon, authenticated, service_role;
grant execute on function public.provision_owner_workspace_legacy(
  text, text, text, text, text, text, text, text, text, text, text, text, text,
  double precision, double precision, text, text, text
) to authenticated;
