-- Keep the legacy repair RPC's profile/workspace transaction while restoring
-- verification state from Supabase Auth. Workspace repair must not downgrade
-- an already verified email or phone.
create or replace function public.provision_owner_workspace_legacy_safe(
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
  result jsonb;
begin
  if caller_id is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;

  result := public.provision_owner_workspace_legacy(
    p_firebase_business_id, p_firebase_station_id, p_business_name,
    p_owner_name, p_phone, p_email, p_station_name, p_station_description,
    p_station_phone, p_station_address, p_barangay, p_city, p_province,
    p_latitude, p_longitude, p_place_id, p_opening_time, p_closing_time
  );

  update public.profiles
  set email_verified = exists (
        select 1 from auth.users u where u.id = caller_id and u.email_confirmed_at is not null
      ),
      phone_verified = exists (
        select 1 from auth.users u where u.id = caller_id and u.phone_confirmed_at is not null
      ),
      updated_at = now()
  where id = caller_id;

  return result;
end;
$$;

revoke all on function public.provision_owner_workspace_legacy_safe(
  text, text, text, text, text, text, text, text, text, text, text, text, text,
  double precision, double precision, text, text, text
) from public, anon, authenticated, service_role;
grant execute on function public.provision_owner_workspace_legacy_safe(
  text, text, text, text, text, text, text, text, text, text, text, text, text,
  double precision, double precision, text, text, text
) to authenticated;
