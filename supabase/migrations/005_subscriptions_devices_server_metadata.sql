create table public.subscription_plans (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  code text not null unique,
  name text not null,
  description text,
  monthly_price_centavos bigint not null default 0 check (monthly_price_centavos >= 0),
  annual_price_centavos bigint not null default 0 check (annual_price_centavos >= 0),
  is_popular boolean not null default false,
  trial_days integer not null default 0 check (trial_days >= 0),
  cta_text text not null default '',
  is_active boolean not null default true,
  sort_order integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.plan_features (
  plan_id uuid not null references public.subscription_plans(id) on delete cascade,
  feature text not null,
  enabled boolean not null,
  limit_value bigint,
  primary key (plan_id, feature)
);

create table public.plan_limits (
  plan_id uuid not null references public.subscription_plans(id) on delete cascade,
  limit_type text not null,
  max_value bigint not null check (max_value >= 0),
  primary key (plan_id, limit_type)
);

create table public.subscriptions (
  id uuid primary key default gen_random_uuid(),
  firebase_id text unique,
  organization_id uuid not null unique references public.organizations(id) on delete cascade,
  plan_id uuid references public.subscription_plans(id) on delete restrict,
  status public.subscription_status not null,
  billing_cycle text not null,
  started_at bigint,
  current_period_start bigint,
  current_period_end bigint,
  trial_started_at bigint,
  trial_ends_at bigint,
  cancelled_at bigint,
  expires_at bigint,
  grace_period_ends_at bigint,
  auto_renew boolean not null default false,
  cancel_at_period_end boolean not null default false,
  provider text,
  external_subscription_id text,
  external_customer_id text,
  last_payment_id text,
  currency text not null default 'PHP',
  client_updated_at bigint,
  deleted_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.subscription_history (
  id uuid primary key default gen_random_uuid(),
  organization_id uuid not null references public.organizations(id) on delete cascade,
  subscription_id uuid references public.subscriptions(id) on delete set null,
  previous_plan_id uuid references public.subscription_plans(id) on delete set null,
  new_plan_id uuid references public.subscription_plans(id) on delete set null,
  event_type text not null,
  effective_at bigint not null,
  previous_status text,
  new_status text,
  notes text,
  created_at timestamptz not null default now()
);

create table public.device_tokens (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references public.profiles(id) on delete cascade,
  token_hash text not null,
  token text not null,
  platform text not null,
  device_id text,
  last_seen_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (profile_id, token_hash)
);

create table public.customer_order_requests (
  id uuid primary key default gen_random_uuid(),
  request_key text not null unique,
  customer_uid uuid not null references public.profiles(id) on delete cascade,
  order_id uuid references public.orders(id) on delete set null,
  response jsonb,
  created_at timestamptz not null default now()
);

create table public.audit_events (
  id uuid primary key default gen_random_uuid(),
  event_type text not null,
  actor_uid uuid references public.profiles(id) on delete set null,
  organization_id uuid references public.organizations(id) on delete set null,
  station_id uuid references public.stations(id) on delete set null,
  order_id uuid references public.orders(id) on delete set null,
  payload jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table public.owner_trial_claims (
  identity_hash text primary key,
  claimed_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.google_play_subscription_tokens (
  token_hash text primary key,
  organization_id uuid references public.organizations(id) on delete set null,
  product_id text,
  base_plan_id text,
  updated_at timestamptz not null default now()
);

create table public.google_play_billing_events (
  message_id text primary key,
  payload jsonb not null,
  processed_at timestamptz not null default now()
);

create table public.inventory_compensations (
  order_id uuid not null references public.orders(id) on delete cascade,
  product_id uuid not null references public.products(id) on delete restrict,
  quantity integer not null check (quantity > 0),
  created_at timestamptz not null default now(),
  primary key (order_id, product_id)
);

