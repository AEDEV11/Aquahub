create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  firebase_uid text unique,
  display_name text not null default '',
  email text,
  phone text not null default '',
  photo_url text,
  address text,
  latitude double precision,
  longitude double precision,
  setup_complete boolean not null default false,
  email_verified boolean not null default false,
  role public.member_role not null default 'CUSTOMER',
  preferred_business_id uuid,
  preferred_station_id uuid,
  acquisition_source text not null default 'UNKNOWN',
  acquired_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (latitude is null or latitude between -90 and 90),
  check (longitude is null or longitude between -180 and 180)
);

create table public.organizations (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  owner_profile_id uuid references public.profiles(id) on delete set null,
  name text not null default '',
  is_active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.stations (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  name text not null,
  description text,
  phone text not null default '',
  email text,
  address text not null default '',
  barangay text,
  city text,
  province text,
  place_id text,
  latitude double precision,
  longitude double precision,
  logo_path text,
  opening_time text,
  closing_time text,
  is_open boolean not null default true,
  manual_open_override boolean not null default false,
  is_accepting_online_orders boolean not null default true,
  delivery_radius_km numeric(8,3),
  delivery_fee_centavos bigint not null default 0 check (delivery_fee_centavos >= 0),
  estimated_preparation_minutes integer not null default 30 check (estimated_preparation_minutes >= 0),
  business_rules_text text,
  business_rules_version bigint not null default 0,
  public_station_code text unique,
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
  is_active boolean not null default true,
  firebase_created_at bigint,
  client_updated_at bigint,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (latitude is null or latitude between -90 and 90),
  check (longitude is null or longitude between -180 and 180),
  check ((latitude is null) = (longitude is null))
);

create table public.organization_members (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  profile_id uuid not null references public.profiles(id) on delete cascade,
  role public.member_role not null,
  is_active boolean not null default true,
  firebase_uid text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (organization_id, profile_id),
  check (role in ('OWNER', 'MANAGER', 'STAFF', 'RIDER'))
);

create table public.member_stations (
  member_id uuid not null references public.organization_members(id) on delete cascade,
  station_id uuid not null references public.stations(id) on delete cascade,
  primary key (member_id, station_id)
);

alter table public.profiles add constraint profiles_preferred_business_fk foreign key (preferred_business_id) references public.organizations(id) on delete set null;
alter table public.profiles add constraint profiles_preferred_station_fk foreign key (preferred_station_id) references public.stations(id) on delete set null;

