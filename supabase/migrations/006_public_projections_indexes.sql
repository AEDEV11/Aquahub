create table public.public_stations (
  station_id uuid primary key references public.stations(id) on delete cascade,
  organization_id uuid not null references public.organizations(id) on delete cascade,
  public_station_code text unique,
  name text not null,
  phone text not null,
  address text not null,
  latitude double precision,
  longitude double precision,
  logo_path text,
  opening_time text,
  closing_time text,
  is_open boolean not null,
  manual_open_override boolean not null,
  is_accepting_orders boolean not null,
  delivery_radius_km numeric(8,3) not null default 0,
  delivery_fee_centavos bigint not null default 0,
  estimated_preparation_minutes integer not null default 30,
  business_rules_text text,
  business_rules_version bigint not null default 0,
  cash_payment_enabled boolean not null default true,
  cod_payment_enabled boolean not null default true,
  gcash_payment_enabled boolean not null default false,
  gcash_account_name text,
  gcash_account_number text,
  gcash_qr_image_path text,
  maya_payment_enabled boolean not null default false,
  maya_account_name text,
  maya_account_number text,
  maya_qr_image_path text,
  updated_at timestamptz not null default now()
);

create table public.public_products (
  product_id uuid primary key references public.products(id) on delete cascade,
  station_id uuid not null references public.stations(id) on delete cascade,
  name text not null,
  product_type text not null,
  size_label text not null,
  description text,
  image_path text,
  price_centavos bigint not null,
  is_available_for_ordering boolean not null,
  promotion_label text,
  promotion_type text,
  promotion_percent_bps integer,
  promotional_price_centavos bigint,
  promotion_minimum_quantity integer,
  promotion_starts_at bigint,
  promotion_ends_at bigint,
  updated_at timestamptz not null default now()
);

create index stations_organization_idx on public.stations (organization_id);
create index members_profile_idx on public.organization_members (profile_id, is_active);
create index member_stations_station_idx on public.member_stations (station_id);
create index customers_station_phone_idx on public.customers (station_id, phone);
create index products_station_active_idx on public.products (station_id, is_active, is_available);
create index riders_station_idx on public.riders (station_id, is_active);
create index orders_station_status_created_idx on public.orders (station_id, status, created_at desc);
create index orders_customer_idx on public.orders (customer_id, created_at desc);
create index orders_customer_uid_idx on public.orders (customer_uid, created_at desc);
create index order_items_order_idx on public.order_items (order_id);
create index payments_order_idx on public.payments (order_id, paid_at);
create index expenses_station_incurred_idx on public.expenses (station_id, incurred_at);
create index public_stations_code_idx on public.public_stations (public_station_code);
create index public_products_station_idx on public.public_products (station_id, is_available_for_ordering);

