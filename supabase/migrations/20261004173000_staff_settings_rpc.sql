-- Whitelisted business/station settings update. Auth Admin mutations stay in
-- the staff-admin Edge Function; this RPC only changes existing station data.
create or replace function public.update_station_settings(
  p_station_id uuid,
  p_settings jsonb
)
returns public.stations
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  updated_station public.stations;
begin
  if not app_private.can_manage_org((select s.organization_id from public.stations s where s.id = p_station_id)) then
    raise exception using errcode = '42501', message = 'not authorized';
  end if;
  if p_settings is null or jsonb_typeof(p_settings) <> 'object' then
    raise exception using errcode = '22023', message = 'invalid settings';
  end if;
  if exists (
    select 1 from jsonb_object_keys(p_settings) key
    where key not in (
      'name','description','phone','email','address','barangay','city','province',
      'latitude','longitude','logo_path','opening_time','closing_time','is_open',
      'manual_open_override','is_accepting_online_orders','delivery_radius_km',
      'delivery_fee_centavos','estimated_preparation_minutes','business_rules_text',
      'public_station_code','cash_payment_enabled','cod_payment_enabled',
      'gcash_payment_enabled','gcash_account_name','gcash_account_number',
      'gcash_qr_image_path','maya_payment_enabled','maya_account_name',
      'maya_account_number','maya_qr_image_path'
    )
  ) then
    raise exception using errcode = '22023', message = 'unsupported settings field';
  end if;

  update public.stations s set
    name = coalesce(p_settings->>'name', s.name),
    description = case when p_settings ? 'description' then p_settings->>'description' else s.description end,
    phone = coalesce(p_settings->>'phone', s.phone),
    email = case when p_settings ? 'email' then p_settings->>'email' else s.email end,
    address = coalesce(p_settings->>'address', s.address),
    barangay = case when p_settings ? 'barangay' then p_settings->>'barangay' else s.barangay end,
    city = case when p_settings ? 'city' then p_settings->>'city' else s.city end,
    province = case when p_settings ? 'province' then p_settings->>'province' else s.province end,
    latitude = case when p_settings ? 'latitude' then (p_settings->>'latitude')::double precision else s.latitude end,
    longitude = case when p_settings ? 'longitude' then (p_settings->>'longitude')::double precision else s.longitude end,
    logo_path = case when p_settings ? 'logo_path' then p_settings->>'logo_path' else s.logo_path end,
    opening_time = case when p_settings ? 'opening_time' then p_settings->>'opening_time' else s.opening_time end,
    closing_time = case when p_settings ? 'closing_time' then p_settings->>'closing_time' else s.closing_time end,
    is_open = coalesce((p_settings->>'is_open')::boolean, s.is_open),
    manual_open_override = coalesce((p_settings->>'manual_open_override')::boolean, s.manual_open_override),
    is_accepting_online_orders = coalesce((p_settings->>'is_accepting_online_orders')::boolean, s.is_accepting_online_orders),
    delivery_radius_km = case when p_settings ? 'delivery_radius_km' then (p_settings->>'delivery_radius_km')::numeric else s.delivery_radius_km end,
    delivery_fee_centavos = coalesce((p_settings->>'delivery_fee_centavos')::bigint, s.delivery_fee_centavos),
    estimated_preparation_minutes = coalesce((p_settings->>'estimated_preparation_minutes')::integer, s.estimated_preparation_minutes),
    business_rules_text = case when p_settings ? 'business_rules_text' then p_settings->>'business_rules_text' else s.business_rules_text end,
    public_station_code = case when p_settings ? 'public_station_code' then p_settings->>'public_station_code' else s.public_station_code end,
    cash_payment_enabled = coalesce((p_settings->>'cash_payment_enabled')::boolean, s.cash_payment_enabled),
    cod_payment_enabled = coalesce((p_settings->>'cod_payment_enabled')::boolean, s.cod_payment_enabled),
    gcash_payment_enabled = coalesce((p_settings->>'gcash_payment_enabled')::boolean, s.gcash_payment_enabled),
    gcash_account_name = case when p_settings ? 'gcash_account_name' then p_settings->>'gcash_account_name' else s.gcash_account_name end,
    gcash_account_number = case when p_settings ? 'gcash_account_number' then p_settings->>'gcash_account_number' else s.gcash_account_number end,
    gcash_qr_image_path = case when p_settings ? 'gcash_qr_image_path' then p_settings->>'gcash_qr_image_path' else s.gcash_qr_image_path end,
    maya_payment_enabled = coalesce((p_settings->>'maya_payment_enabled')::boolean, s.maya_payment_enabled),
    maya_account_name = case when p_settings ? 'maya_account_name' then p_settings->>'maya_account_name' else s.maya_account_name end,
    maya_account_number = case when p_settings ? 'maya_account_number' then p_settings->>'maya_account_number' else s.maya_account_number end,
    maya_qr_image_path = case when p_settings ? 'maya_qr_image_path' then p_settings->>'maya_qr_image_path' else s.maya_qr_image_path end,
    updated_at = now()
  where s.id = p_station_id
  returning s.* into updated_station;

  if updated_station.id is null then raise exception using errcode = 'P0002', message = 'station not found'; end if;
  return updated_station;
end;
$$;

revoke all on function public.update_station_settings(uuid, jsonb) from public, anon;
grant execute on function public.update_station_settings(uuid, jsonb) to authenticated, service_role;
