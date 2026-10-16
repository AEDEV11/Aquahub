-- Customer catalog projection for the Supabase-only public read path.
-- Legacy Firebase identifiers are retained as lookup keys only so existing
-- station QR links and customer preferences can resolve without Firestore.
alter table public.public_stations
  add column station_firebase_id text,
  add column organization_firebase_id text;

alter table public.public_products
  add column product_firebase_id text,
  add column station_firebase_id text,
  add column promotion_is_active boolean not null default false;

create index public_stations_station_firebase_id_idx
  on public.public_stations (station_firebase_id);
create index public_products_station_firebase_id_idx
  on public.public_products (station_firebase_id, is_available_for_ordering);

create or replace function app_private.refresh_public_station_projection(target_station uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  station_row public.stations;
  organization_row public.organizations;
begin
  select * into station_row
  from public.stations
  where id = target_station;

  if not found or not station_row.is_active or station_row.deleted_at is not null then
    delete from public.public_stations where station_id = target_station;
    delete from public.public_products where station_id = target_station;
    return;
  end if;

  select * into organization_row
  from public.organizations
  where id = station_row.organization_id and is_active;

  if not found then
    delete from public.public_stations where station_id = target_station;
    delete from public.public_products where station_id = target_station;
    return;
  end if;

  insert into public.public_stations (
    station_id, organization_id, station_firebase_id, organization_firebase_id,
    public_station_code, name, phone, address, latitude, longitude, logo_path,
    opening_time, closing_time, is_open, manual_open_override, is_accepting_orders,
    delivery_radius_km, delivery_fee_centavos, estimated_preparation_minutes,
    business_rules_text, business_rules_version, cash_payment_enabled,
    cod_payment_enabled, gcash_payment_enabled, gcash_account_name,
    gcash_account_number, gcash_qr_image_path, maya_payment_enabled,
    maya_account_name, maya_account_number, maya_qr_image_path, updated_at
  ) values (
    station_row.id, station_row.organization_id, station_row.firebase_id, organization_row.firebase_id,
    station_row.public_station_code, station_row.name, station_row.phone, station_row.address,
    station_row.latitude, station_row.longitude, station_row.logo_path,
    station_row.opening_time, station_row.closing_time, station_row.is_open,
    station_row.manual_open_override, station_row.is_accepting_online_orders,
    coalesce(station_row.delivery_radius_km, 0), station_row.delivery_fee_centavos,
    station_row.estimated_preparation_minutes, station_row.business_rules_text,
    station_row.business_rules_version, station_row.cash_payment_enabled,
    station_row.cod_payment_enabled, station_row.gcash_payment_enabled,
    station_row.gcash_account_name, station_row.gcash_account_number,
    station_row.gcash_qr_image_path, station_row.maya_payment_enabled,
    station_row.maya_account_name, station_row.maya_account_number,
    station_row.maya_qr_image_path, station_row.updated_at
  )
  on conflict (station_id) do update set
    organization_id = excluded.organization_id,
    station_firebase_id = excluded.station_firebase_id,
    organization_firebase_id = excluded.organization_firebase_id,
    public_station_code = excluded.public_station_code,
    name = excluded.name,
    phone = excluded.phone,
    address = excluded.address,
    latitude = excluded.latitude,
    longitude = excluded.longitude,
    logo_path = excluded.logo_path,
    opening_time = excluded.opening_time,
    closing_time = excluded.closing_time,
    is_open = excluded.is_open,
    manual_open_override = excluded.manual_open_override,
    is_accepting_orders = excluded.is_accepting_orders,
    delivery_radius_km = excluded.delivery_radius_km,
    delivery_fee_centavos = excluded.delivery_fee_centavos,
    estimated_preparation_minutes = excluded.estimated_preparation_minutes,
    business_rules_text = excluded.business_rules_text,
    business_rules_version = excluded.business_rules_version,
    cash_payment_enabled = excluded.cash_payment_enabled,
    cod_payment_enabled = excluded.cod_payment_enabled,
    gcash_payment_enabled = excluded.gcash_payment_enabled,
    gcash_account_name = excluded.gcash_account_name,
    gcash_account_number = excluded.gcash_account_number,
    gcash_qr_image_path = excluded.gcash_qr_image_path,
    maya_payment_enabled = excluded.maya_payment_enabled,
    maya_account_name = excluded.maya_account_name,
    maya_account_number = excluded.maya_account_number,
    maya_qr_image_path = excluded.maya_qr_image_path,
    updated_at = excluded.updated_at;

  insert into public.public_products (
    product_id, station_id, product_firebase_id, station_firebase_id,
    name, product_type, size_label, description, image_path, price_centavos,
    is_available_for_ordering, promotion_label, promotion_type,
    promotion_percent_bps, promotional_price_centavos, promotion_minimum_quantity,
    promotion_starts_at, promotion_ends_at, promotion_is_active, updated_at
  )
  select p.id, p.station_id, p.firebase_id, station_row.firebase_id,
    p.name, p.product_type, p.size_label, p.description, p.image_path,
    p.price_centavos, p.is_available,
    p.promotion_label, p.promotion_type, p.promotion_percent_bps,
    p.promotional_price_centavos, p.promotion_minimum_quantity,
    p.promotion_starts_at, p.promotion_ends_at, p.promotion_is_active, p.updated_at
  from public.products p
  where p.station_id = station_row.id
    and p.is_active
    and p.deleted_at is null
  on conflict (product_id) do update set
    station_id = excluded.station_id,
    product_firebase_id = excluded.product_firebase_id,
    station_firebase_id = excluded.station_firebase_id,
    name = excluded.name,
    product_type = excluded.product_type,
    size_label = excluded.size_label,
    description = excluded.description,
    image_path = excluded.image_path,
    price_centavos = excluded.price_centavos,
    is_available_for_ordering = excluded.is_available_for_ordering,
    promotion_label = excluded.promotion_label,
    promotion_type = excluded.promotion_type,
    promotion_percent_bps = excluded.promotion_percent_bps,
    promotional_price_centavos = excluded.promotional_price_centavos,
    promotion_minimum_quantity = excluded.promotion_minimum_quantity,
    promotion_starts_at = excluded.promotion_starts_at,
    promotion_ends_at = excluded.promotion_ends_at,
    promotion_is_active = excluded.promotion_is_active,
    updated_at = excluded.updated_at;

  delete from public.public_products pp
  where pp.station_id = station_row.id
    and not exists (
      select 1 from public.products p
      where p.id = pp.product_id and p.station_id = station_row.id
        and p.is_active and p.deleted_at is null
    );
end;
$$;

revoke all on function app_private.refresh_public_station_projection(uuid) from public, anon, authenticated, service_role;
grant execute on function app_private.refresh_public_station_projection(uuid) to postgres;

create or replace function app_private.refresh_public_station_projection_trigger()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  perform app_private.refresh_public_station_projection(coalesce(new.station_id, old.station_id));
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;

create or replace function app_private.refresh_public_station_from_station_trigger()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
  perform app_private.refresh_public_station_projection(coalesce(new.id, old.id));
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;

revoke all on function app_private.refresh_public_station_projection_trigger() from public, anon, authenticated, service_role;
revoke all on function app_private.refresh_public_station_from_station_trigger() from public, anon, authenticated, service_role;
grant execute on function app_private.refresh_public_station_projection_trigger() to postgres;
grant execute on function app_private.refresh_public_station_from_station_trigger() to postgres;

drop trigger if exists products_refresh_public_projection on public.products;
create trigger products_refresh_public_projection
after insert or update or delete on public.products
for each row execute function app_private.refresh_public_station_projection_trigger();

drop trigger if exists stations_refresh_public_projection on public.stations;
create trigger stations_refresh_public_projection
after insert or update or delete on public.stations
for each row execute function app_private.refresh_public_station_from_station_trigger();

drop trigger if exists organizations_refresh_public_projection on public.organizations;
create or replace function app_private.refresh_public_stations_for_organization_trigger()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare station_id uuid;
begin
  for station_id in select s.id from public.stations s where s.organization_id = coalesce(new.id, old.id)
  loop
    perform app_private.refresh_public_station_projection(station_id);
  end loop;
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;
revoke all on function app_private.refresh_public_stations_for_organization_trigger() from public, anon, authenticated, service_role;
grant execute on function app_private.refresh_public_stations_for_organization_trigger() to postgres;
create trigger organizations_refresh_public_projection
after insert or update or delete on public.organizations
for each row execute function app_private.refresh_public_stations_for_organization_trigger();

do $$
declare station_id uuid;
begin
  for station_id in select id from public.stations loop
    perform app_private.refresh_public_station_projection(station_id);
  end loop;
end;
$$;
