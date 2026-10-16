create table public.customers (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  station_id uuid not null references public.stations(id) on delete restrict,
  firebase_customer_uid text,
  name text not null,
  phone text not null default '',
  email text,
  notes text,
  total_orders integer not null default 0 check (total_orders >= 0),
  total_spent_centavos bigint not null default 0 check (total_spent_centavos >= 0),
  is_active boolean not null default true,
  client_updated_at bigint,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.customer_addresses (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  station_id uuid not null references public.stations(id) on delete restrict,
  customer_id uuid not null references public.customers(id) on delete cascade,
  label text not null,
  address_line text not null,
  barangay text,
  city text,
  province text,
  place_id text,
  latitude double precision,
  longitude double precision,
  is_default boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((latitude is null) = (longitude is null))
);

create table public.products (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  station_id uuid not null references public.stations(id) on delete restrict,
  name text not null,
  product_type text not null check (product_type in ('REFILL', 'NEW_CONTAINER', 'EMPTY_CONTAINER', 'OTHER')),
  size_label text not null default '',
  description text,
  price_centavos bigint not null check (price_centavos >= 0),
  stock_quantity integer check (stock_quantity is null or stock_quantity >= 0),
  image_path text,
  is_available boolean not null default true,
  is_active boolean not null default true,
  promotion_label text,
  promotion_type text check (promotion_type is null or promotion_type in ('PERCENTAGE', 'FIXED_PRICE', 'QUANTITY_BREAK')),
  promotion_percent_bps integer check (promotion_percent_bps is null or promotion_percent_bps between 0 and 9000),
  promotional_price_centavos bigint check (promotional_price_centavos is null or promotional_price_centavos >= 0),
  promotion_minimum_quantity integer check (promotion_minimum_quantity is null or promotion_minimum_quantity between 1 and 99),
  promotion_starts_at bigint,
  promotion_ends_at bigint,
  promotion_is_active boolean not null default false,
  client_updated_at bigint,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.riders (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  station_id uuid not null references public.stations(id) on delete restrict,
  profile_id uuid references public.profiles(id) on delete set null,
  name text not null,
  phone text not null default '',
  vehicle_type text,
  vehicle_name text,
  plate_number text,
  rating numeric(4,2) not null default 0 check (rating between 0 and 5),
  is_active boolean not null default true,
  is_online boolean not null default false,
  is_available boolean not null default false,
  latitude double precision,
  longitude double precision,
  active_order_count integer not null default 0 check (active_order_count >= 0),
  client_updated_at bigint,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check ((latitude is null) = (longitude is null))
);

create table public.containers (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  station_id uuid not null references public.stations(id) on delete restrict,
  size_label text not null,
  total_owned integer not null default 0 check (total_owned >= 0),
  available integer not null default 0 check (available >= 0),
  with_customers integer not null default 0 check (with_customers >= 0),
  damaged integer not null default 0 check (damaged >= 0),
  lost integer not null default 0 check (lost >= 0),
  deposit_price_centavos bigint not null default 0 check (deposit_price_centavos >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (station_id, size_label)
);

create table public.container_movements (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  client_operation_id text unique,
  station_id uuid not null references public.stations(id) on delete restrict,
  container_id uuid references public.containers(id) on delete set null,
  customer_id uuid references public.customers(id) on delete set null,
  order_id uuid,
  customer_name text,
  movement_type text not null,
  quantity integer not null check (quantity > 0),
  container_size text not null,
  notes text,
  created_at timestamptz not null default now(),
  client_updated_at bigint,
  deleted_at timestamptz
);

