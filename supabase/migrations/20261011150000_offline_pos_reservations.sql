-- Offline cash POS. Reservations are held in the canonical inventory ledger;
-- expiration alone never releases stock. All operations are scoped to the
-- authenticated owner, organization, station and registered device.
begin;

alter table public.inventory_balances
  add column if not exists reserved_quantity integer not null default 0
  check (reserved_quantity >= 0);

create or replace function app_private.enforce_inventory_reservation_floor()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.quantity < 0 or new.reserved_quantity < 0
     or new.reserved_quantity > new.quantity then
    raise exception using errcode = '23514',
      message = 'inventory change would consume reserved stock';
  end if;
  return new;
end;
$$;
revoke all on function app_private.enforce_inventory_reservation_floor()
  from public, anon, authenticated, service_role;
drop trigger if exists inventory_balance_reservation_floor on public.inventory_balances;
create trigger inventory_balance_reservation_floor
before insert or update of quantity, reserved_quantity on public.inventory_balances
for each row execute function app_private.enforce_inventory_reservation_floor();

-- stock_quantity is a legacy catalog mirror. Operational writes must use the
-- versioned inventory ledger RPC; prevent old/direct clients from bypassing it.
create or replace function app_private.reject_legacy_product_stock_write()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.stock_quantity is distinct from old.stock_quantity
     and coalesce(auth.uid(), null) is not null then
    raise exception using errcode = '42501',
      message = 'product stock must be changed through the versioned inventory RPC';
  end if;
  return new;
end;
$$;
revoke all on function app_private.reject_legacy_product_stock_write()
  from public, anon, authenticated, service_role;
drop trigger if exists products_reject_legacy_stock_write on public.products;
create trigger products_reject_legacy_stock_write
before update of stock_quantity on public.products
for each row execute function app_private.reject_legacy_product_stock_write();

create table app_private.offline_pos_devices (
  device_id text primary key check (length(device_id) between 16 and 80),
  profile_id uuid not null references public.profiles(id) on delete restrict,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  station_id uuid not null references public.stations(id) on delete restrict,
  public_key_der text not null,
  public_key_sha256 text not null check (public_key_sha256 ~ '^[0-9a-f]{64}$'),
  status text not null default 'ACTIVE' check (status in ('ACTIVE','REVOKED')),
  created_at timestamptz not null default now(),
  revoked_at timestamptz,
  revoked_by uuid references public.profiles(id) on delete restrict,
  unique (device_id, profile_id, organization_id, station_id)
);

create table app_private.offline_pos_grants (
  id uuid primary key,
  profile_id uuid not null references public.profiles(id) on delete restrict,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  station_id uuid not null references public.stations(id) on delete restrict,
  device_id text not null references app_private.offline_pos_devices(device_id) on delete restrict,
  issued_at timestamptz not null,
  expires_at timestamptz not null,
  allowed_operations text[] not null default array['OFFLINE_WALKIN_CASH']::text[],
  status text not null default 'ACTIVE' check (status in ('ACTIVE','REVOKED','EXPIRED')),
  created_at timestamptz not null default now(),
  check (expires_at > issued_at and expires_at <= issued_at + interval '12 hours')
);
create index offline_pos_grants_scope_idx
  on app_private.offline_pos_grants(profile_id, organization_id, station_id, device_id, expires_at desc);

create table app_private.offline_pos_leases (
  id uuid primary key,
  grant_id uuid not null references app_private.offline_pos_grants(id) on delete restrict,
  profile_id uuid not null references public.profiles(id) on delete restrict,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  station_id uuid not null references public.stations(id) on delete restrict,
  device_id text not null references app_private.offline_pos_devices(device_id) on delete restrict,
  issued_at timestamptz not null,
  expires_at timestamptz not null,
  catalog_snapshot_at timestamptz not null,
  status text not null default 'ACTIVE'
    check (status in ('ACTIVE','EXPIRED_UNRESOLVED','REVOKED_UNRESOLVED','CLOSED','RECONCILED')),
  closed_at timestamptz,
  created_at timestamptz not null default now(),
  check (expires_at > issued_at and expires_at <= issued_at + interval '24 hours')
);
create index offline_pos_leases_scope_idx
  on app_private.offline_pos_leases(organization_id, station_id, device_id, expires_at);

create table app_private.offline_pos_lease_items (
  lease_id uuid not null references app_private.offline_pos_leases(id) on delete restrict,
  product_id uuid not null references public.products(id) on delete restrict,
  quantity_reserved integer not null check (quantity_reserved > 0),
  quantity_consumed integer not null default 0 check (quantity_consumed >= 0),
  quantity_released integer not null default 0 check (quantity_released >= 0),
  product_name text not null,
  product_type text not null,
  size_label text not null,
  unit_price_centavos bigint not null check (unit_price_centavos >= 0),
  product_updated_at timestamptz not null,
  inventory_version bigint not null check (inventory_version >= 0),
  primary key (lease_id, product_id),
  check (quantity_consumed + quantity_released <= quantity_reserved)
);

create table app_private.offline_pos_operations (
  operation_id uuid primary key,
  payload_digest text not null check (payload_digest ~ '^[0-9a-f]{64}$'),
  profile_id uuid not null references public.profiles(id) on delete restrict,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  station_id uuid not null references public.stations(id) on delete restrict,
  device_id text not null references app_private.offline_pos_devices(device_id) on delete restrict,
  grant_id uuid not null references app_private.offline_pos_grants(id) on delete restrict,
  lease_id uuid not null references app_private.offline_pos_leases(id) on delete restrict,
  transaction_at timestamptz not null,
  cash_received_centavos bigint not null check (cash_received_centavos > 0),
  change_centavos bigint not null default 0 check (change_centavos >= 0),
  payload jsonb not null,
  order_id uuid references public.orders(id) on delete restrict,
  status text not null default 'COMMITTED' check (status in ('COMMITTED','RECONCILIATION_REQUIRED')),
  created_at timestamptz not null default now(),
  check (jsonb_typeof(payload) = 'object')
);
create index offline_pos_operations_lease_idx
  on app_private.offline_pos_operations(lease_id, created_at);

create table app_private.offline_pos_reconciliation_events (
  id uuid primary key default gen_random_uuid(),
  operation_id uuid references app_private.offline_pos_operations(operation_id) on delete restrict,
  lease_id uuid references app_private.offline_pos_leases(id) on delete restrict,
  device_id text references app_private.offline_pos_devices(device_id) on delete restrict,
  organization_id uuid not null references public.organizations(id) on delete restrict,
  station_id uuid not null references public.stations(id) on delete restrict,
  actor_profile_id uuid not null references public.profiles(id) on delete restrict,
  action text not null check (action in ('DEVICE_REVOKED','LEASE_RECONCILED','SALE_ACCEPTED','SALE_REJECTED')),
  reason text not null check (length(btrim(reason)) between 5 and 500),
  physical_counts jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

alter table app_private.offline_pos_devices enable row level security;
alter table app_private.offline_pos_grants enable row level security;
alter table app_private.offline_pos_leases enable row level security;
alter table app_private.offline_pos_lease_items enable row level security;
alter table app_private.offline_pos_operations enable row level security;
alter table app_private.offline_pos_reconciliation_events enable row level security;
revoke all on table app_private.offline_pos_devices, app_private.offline_pos_grants,
  app_private.offline_pos_leases, app_private.offline_pos_lease_items,
  app_private.offline_pos_operations, app_private.offline_pos_reconciliation_events
  from public, anon, authenticated, service_role;
grant all on app_private.offline_pos_devices, app_private.offline_pos_grants,
  app_private.offline_pos_leases, app_private.offline_pos_lease_items,
  app_private.offline_pos_operations, app_private.offline_pos_reconciliation_events
  to postgres;

create or replace function public.register_offline_pos_device(
  p_device_id text,
  p_organization_id uuid,
  p_station_id uuid,
  p_public_key_der text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  station_org uuid;
  key_hash text;
  prior app_private.offline_pos_devices%rowtype;
begin
  if caller is null then raise exception using errcode='28000', message='authenticated user is required'; end if;
  if p_device_id !~ '^[A-Za-z0-9_-]{16,80}$' or p_public_key_der is null
     or length(p_public_key_der) > 2048 then
    raise exception using errcode='22023', message='offline device key is invalid';
  end if;
  if not app_private.can_operate_station(p_station_id) then
    raise exception using errcode='42501', message='station access denied';
  end if;
  perform app_private.require_plan_feature(p_organization_id,'ORDER_MANAGEMENT');
  select s.organization_id into station_org from public.stations s
    where s.id=p_station_id and s.is_active and s.deleted_at is null;
  if station_org is null or station_org <> p_organization_id then
    raise exception using errcode='42501', message='organization and station do not match';
  end if;
  key_hash := encode(extensions.digest(convert_to(p_public_key_der,'UTF8'),'sha256'),'hex');
  select * into prior from app_private.offline_pos_devices d where d.device_id=p_device_id for update;
  if prior.device_id is not null then
    if prior.profile_id<>caller or prior.organization_id<>p_organization_id
       or prior.station_id<>p_station_id or prior.public_key_sha256<>key_hash then
      raise exception using errcode='23505', message='device identity is already bound to another key or account';
    end if;
    if prior.status<>'ACTIVE' then raise exception using errcode='55000', message='device has been revoked'; end if;
  else
    insert into app_private.offline_pos_devices(device_id,profile_id,organization_id,station_id,public_key_der,public_key_sha256)
      values(p_device_id,caller,p_organization_id,p_station_id,p_public_key_der,key_hash);
  end if;
  return jsonb_build_object('device_id',p_device_id,'public_key_sha256',key_hash);
end;
$$;
revoke all on function public.register_offline_pos_device(text,uuid,uuid,text)
  from public, anon, authenticated, service_role;
grant execute on function public.register_offline_pos_device(text,uuid,uuid,text) to authenticated;

create or replace function public.get_offline_pos_device_key(p_device_id text,p_profile_id uuid)
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select d.public_key_der
  from app_private.offline_pos_devices d
  where d.device_id=p_device_id and d.profile_id=p_profile_id and d.status='ACTIVE'
  limit 1;
$$;
revoke all on function public.get_offline_pos_device_key(text,uuid)
  from public,anon,authenticated,service_role;
grant execute on function public.get_offline_pos_device_key(text,uuid) to service_role;

create or replace function public.issue_offline_pos_grant_and_lease(
  p_device_id text,
  p_organization_id uuid,
  p_station_id uuid,
  p_grant_id uuid,
  p_lease_id uuid,
  p_requested_items jsonb,
  p_grant_duration_hours integer default 8,
  p_lease_duration_hours integer default 8
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  station_org uuid;
  station_cash_enabled boolean;
  device app_private.offline_pos_devices%rowtype;
  item record;
  product public.products%rowtype;
  balance public.inventory_balances%rowtype;
  now_at timestamptz := date_trunc('milliseconds',clock_timestamp());
  grant_exp timestamptz;
  lease_exp timestamptz;
  grant_claims jsonb;
  lease_items jsonb := '[]'::jsonb;
  reserved_items integer := 0;
  prior_grant app_private.offline_pos_grants%rowtype;
  prior_lease app_private.offline_pos_leases%rowtype;
  prior_items jsonb;
begin
  if caller is null then raise exception using errcode='28000', message='authenticated user is required'; end if;
  if p_grant_id is null or p_lease_id is null or p_requested_items is null
     or jsonb_typeof(p_requested_items)<>'array'
     or jsonb_array_length(p_requested_items) not between 1 and 100
     or p_grant_duration_hours not between 1 and 12
     or p_lease_duration_hours not between 1 and 24 then
    raise exception using errcode='22023', message='offline grant or lease request is invalid';
  end if;
  if not app_private.can_operate_station(p_station_id) then
    raise exception using errcode='42501', message='station access denied';
  end if;
  select s.organization_id,s.cash_payment_enabled into station_org,station_cash_enabled from public.stations s
    where s.id=p_station_id and s.is_active and s.deleted_at is null;
  if station_org is null or station_org<>p_organization_id then
    raise exception using errcode='42501', message='organization and station do not match';
  end if;
  if not station_cash_enabled then
    raise exception using errcode='55000',message='cash payments are disabled for this station';
  end if;
  perform app_private.require_plan_feature(p_organization_id,'ORDER_MANAGEMENT');
  perform app_private.require_plan_feature(p_organization_id,'BASIC_INVENTORY');
  select * into device from app_private.offline_pos_devices d
    where d.device_id=p_device_id and d.profile_id=caller
      and d.organization_id=p_organization_id and d.station_id=p_station_id
      and d.status='ACTIVE' for update;
  if device.device_id is null then raise exception using errcode='42501', message='active device registration required'; end if;
  if exists (select 1 from jsonb_array_elements(p_requested_items) v
    where coalesce((v->>'quantity')::integer,0) not between 1 and 500
       or (v->>'product_id') is null) then
    raise exception using errcode='22023', message='offline reservation quantities are invalid';
  end if;
  if (select count(*) from jsonb_array_elements(p_requested_items)) <>
     (select count(distinct (v->>'product_id')::uuid) from jsonb_array_elements(p_requested_items) v) then
    raise exception using errcode='22023', message='duplicate products are not allowed in a reservation';
  end if;

  -- An Edge Function response can be lost after this transaction commits. A
  -- retry with the same stable IDs must return the original reservation and
  -- must never increment reserved_quantity a second time.
  select * into prior_grant from app_private.offline_pos_grants g where g.id=p_grant_id for update;
  select * into prior_lease from app_private.offline_pos_leases l where l.id=p_lease_id for update;
  if prior_grant.id is not null or prior_lease.id is not null then
    if prior_grant.id is null or prior_lease.id is null
       or prior_lease.grant_id<>p_grant_id
       or prior_grant.profile_id<>caller or prior_lease.profile_id<>caller
       or prior_grant.organization_id<>p_organization_id or prior_lease.organization_id<>p_organization_id
       or prior_grant.station_id<>p_station_id or prior_lease.station_id<>p_station_id
       or prior_grant.device_id<>p_device_id or prior_lease.device_id<>p_device_id
       or prior_grant.status<>'ACTIVE' or prior_lease.status not in ('ACTIVE','EXPIRED_UNRESOLVED') then
      raise exception using errcode='23505', message='offline reservation identity conflicts with an existing lease';
    end if;
    if exists (
      select 1 from (
        (select (v->>'product_id')::uuid product_id,(v->>'quantity')::integer quantity
         from jsonb_array_elements(p_requested_items) v)
        except
        (select i.product_id,i.quantity_reserved quantity
         from app_private.offline_pos_lease_items i where i.lease_id=p_lease_id)
      ) request_difference
    ) or exists (
      select 1 from (
        (select i.product_id,i.quantity_reserved quantity
         from app_private.offline_pos_lease_items i where i.lease_id=p_lease_id)
        except
        (select (v->>'product_id')::uuid product_id,(v->>'quantity')::integer quantity
         from jsonb_array_elements(p_requested_items) v)
      ) lease_difference
    ) then
      raise exception using errcode='23505', message='offline reservation retry changed its requested quantities';
    end if;
    select coalesce(jsonb_agg(jsonb_build_object(
      'product_id',i.product_id,'quantity_reserved',i.quantity_reserved,
      'product_name',i.product_name,'product_type',i.product_type,
      'size_label',i.size_label,'unit_price_centavos',i.unit_price_centavos,
      'inventory_version',i.inventory_version,
      'product_updated_at',i.product_updated_at
    ) order by i.product_id),'[]'::jsonb) into prior_items
    from app_private.offline_pos_lease_items i where i.lease_id=p_lease_id;
    grant_claims:=jsonb_build_object(
      'iss','aquahub-offline-pos','sub',caller,'org',p_organization_id,
      'station',p_station_id,'device',p_device_id,'jti',p_grant_id,
      'iat',floor(extract(epoch from prior_grant.issued_at))::bigint,
      'server_time_anchor_ms',floor(extract(epoch from prior_grant.issued_at)*1000)::bigint,
      'exp',floor(extract(epoch from prior_grant.expires_at))::bigint,
      'operations',to_jsonb(prior_grant.allowed_operations)
    );
    return jsonb_build_object(
      'grant',grant_claims,
      'lease',jsonb_build_object(
        'iss','aquahub-offline-pos','sub',caller,'org',p_organization_id,
        'station',p_station_id,'device',p_device_id,'jti',p_lease_id,
        'grant_id',p_grant_id,'iat',floor(extract(epoch from prior_lease.issued_at))::bigint,
        'server_time_anchor_ms',floor(extract(epoch from prior_lease.issued_at)*1000)::bigint,
        'exp',floor(extract(epoch from prior_lease.expires_at))::bigint,
        'catalog_snapshot_at',prior_lease.catalog_snapshot_at,'items',prior_items
      ),
      'duplicate',true
    );
  end if;
  grant_exp := now_at + make_interval(hours=>p_grant_duration_hours);
  lease_exp := now_at + make_interval(hours=>p_lease_duration_hours);
  insert into app_private.offline_pos_grants(
    id,profile_id,organization_id,station_id,device_id,issued_at,expires_at
  ) values(p_grant_id,caller,p_organization_id,p_station_id,p_device_id,now_at,grant_exp);
  insert into app_private.offline_pos_leases(
    id,grant_id,profile_id,organization_id,station_id,device_id,issued_at,expires_at,catalog_snapshot_at
  ) values(p_lease_id,p_grant_id,caller,p_organization_id,p_station_id,p_device_id,now_at,lease_exp,now_at);

  for item in select (v->>'product_id')::uuid product_id, (v->>'quantity')::integer quantity
    from jsonb_array_elements(p_requested_items) v order by (v->>'product_id')::uuid
  loop
    select * into product from public.products p
      where p.id=item.product_id and p.station_id=p_station_id and p.is_active
        and p.is_available and p.deleted_at is null for share;
    if product.id is null then raise exception using errcode='23503', message='product is unavailable for offline sale'; end if;
    if product.promotion_is_active
       or (product.promotion_starts_at is not null and product.promotion_starts_at > floor(extract(epoch from now_at)*1000)::bigint
            and product.promotion_starts_at < floor(extract(epoch from lease_exp)*1000)::bigint)
       or (product.promotion_ends_at is not null and product.promotion_ends_at > floor(extract(epoch from now_at)*1000)::bigint
            and product.promotion_ends_at < floor(extract(epoch from lease_exp)*1000)::bigint) then
      raise exception using errcode='55000', message='products with changing promotional prices cannot be reserved offline';
    end if;
    insert into public.inventory_balances(station_id,product_id)
      values(p_station_id,product.id) on conflict(station_id,product_id) do nothing;
    select * into balance from public.inventory_balances b
      where b.station_id=p_station_id and b.product_id=product.id for update;
    if balance.quantity-balance.reserved_quantity < item.quantity then
      raise exception using errcode='23514', message='insufficient unreserved inventory for offline lease';
    end if;
    update public.inventory_balances b
      set reserved_quantity=b.reserved_quantity+item.quantity,
          version=b.version+1,updated_at=now_at where b.id=balance.id returning * into balance;
    insert into app_private.offline_pos_lease_items(
      lease_id,product_id,quantity_reserved,product_name,product_type,size_label,
      unit_price_centavos,product_updated_at,inventory_version
    ) values(
      p_lease_id,product.id,item.quantity,product.name,product.product_type,
      product.size_label,product.price_centavos,product.updated_at,balance.version
    );
    lease_items := lease_items || jsonb_build_array(jsonb_build_object(
      'product_id',product.id,'quantity_reserved',item.quantity,
      'product_name',product.name,'product_type',product.product_type,
      'size_label',product.size_label,'unit_price_centavos',product.price_centavos,
      'inventory_version',balance.version,
      'product_updated_at',product.updated_at
    ));
    reserved_items := reserved_items+1;
  end loop;
  if reserved_items=0 then raise exception using errcode='22023', message='offline lease is empty'; end if;

  grant_claims := jsonb_build_object(
    'iss','aquahub-offline-pos','sub',caller,'org',p_organization_id,
    'station',p_station_id,'device',p_device_id,'jti',p_grant_id,
    'iat',floor(extract(epoch from now_at))::bigint,
    'server_time_anchor_ms',floor(extract(epoch from now_at)*1000)::bigint,
    'exp',floor(extract(epoch from grant_exp))::bigint,
    'operations',jsonb_build_array('OFFLINE_WALKIN_CASH')
  );
  return jsonb_build_object(
    'grant',grant_claims,
    'lease',jsonb_build_object(
      'iss','aquahub-offline-pos','sub',caller,'org',p_organization_id,
      'station',p_station_id,'device',p_device_id,'jti',p_lease_id,
      'grant_id',p_grant_id,'iat',floor(extract(epoch from now_at))::bigint,
      'server_time_anchor_ms',floor(extract(epoch from now_at)*1000)::bigint,
      'exp',floor(extract(epoch from lease_exp))::bigint,
      'catalog_snapshot_at',now_at,'items',lease_items
    )
  );
end;
$$;
revoke all on function public.issue_offline_pos_grant_and_lease(text,uuid,uuid,uuid,uuid,jsonb,integer,integer)
  from public, anon, authenticated, service_role;
grant execute on function public.issue_offline_pos_grant_and_lease(text,uuid,uuid,uuid,uuid,jsonb,integer,integer)
  to authenticated;

create or replace function public.set_inventory_quantity(
  p_station_id uuid,p_product_id uuid,p_quantity integer,p_expected_version bigint,
  p_operation_id text,p_reason text default 'Owner stock count'
)
returns table(balance_id uuid,station_id uuid,product_id uuid,quantity integer,version bigint,operation_id text)
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  balance public.inventory_balances%rowtype;
  movement public.inventory_movements%rowtype;
  delta integer;
begin
  if caller is null then raise exception using errcode='28000',message='authenticated user is required'; end if;
  if p_quantity<0 or p_expected_version<0 or nullif(btrim(p_operation_id),'') is null
     or nullif(btrim(p_reason),'') is null then
    raise exception using errcode='22023',message='stock target, revision and operation are required';
  end if;
  if not app_private.can_operate_station(p_station_id) then
    raise exception using errcode='42501',message='station access denied';
  end if;
  perform app_private.require_plan_feature(
    (select s.organization_id from public.stations s where s.id=p_station_id),
    'BASIC_INVENTORY'
  );
  select * into movement from public.inventory_movements m where m.operation_id=btrim(p_operation_id) for update;
  if movement.id is not null then
    select * into balance from public.inventory_balances b where b.station_id=p_station_id and b.product_id=p_product_id;
    if movement.station_id<>p_station_id or movement.product_id<>p_product_id
       or movement.quantity_after<>p_quantity then
      raise exception using errcode='23505',message='inventory operation id conflict';
    end if;
    return query select balance.id,balance.station_id,balance.product_id,balance.quantity,balance.version,movement.operation_id; return;
  end if;
  insert into public.inventory_balances(station_id,product_id) values(p_station_id,p_product_id)
    on conflict(station_id,product_id) do nothing;
  select * into balance from public.inventory_balances b
    where b.station_id=p_station_id and b.product_id=p_product_id for update;
  if balance.version<>p_expected_version then
    raise exception using errcode='40001',message='inventory revision conflict';
  end if;
  if p_quantity<balance.reserved_quantity then
    raise exception using errcode='23514',message='stock target is below reserved inventory';
  end if;
  delta:=p_quantity-balance.quantity;
  if delta=0 then return query select balance.id,balance.station_id,balance.product_id,balance.quantity,balance.version,btrim(p_operation_id); return; end if;
  update public.inventory_balances b set quantity=p_quantity,version=b.version+1,updated_at=now()
    where b.id=balance.id returning * into balance;
  insert into public.inventory_movements(station_id,product_id,operation_id,delta,quantity_after,reason,created_by)
    values(p_station_id,p_product_id,btrim(p_operation_id),delta,balance.quantity,btrim(p_reason),caller)
    returning * into movement;
  return query select balance.id,balance.station_id,balance.product_id,balance.quantity,balance.version,movement.operation_id;
end;
$$;
revoke all on function public.set_inventory_quantity(uuid,uuid,integer,bigint,text,text)
  from public,anon,authenticated,service_role;
grant execute on function public.set_inventory_quantity(uuid,uuid,integer,bigint,text,text)
  to authenticated,service_role;

-- Called only by the signature-verifying offline-pos Edge Function. A receipt
-- is one transaction: order, payment trigger, items, inventory ledger, lease
-- consumption, operation digest and result either all commit or all roll back.
create or replace function app_private.commit_offline_pos_sale(
  p_profile_id uuid,p_device_id text,p_grant_id uuid,p_lease_id uuid,
  p_operation_id uuid,p_payload_digest text,p_canonical_payload text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  payload jsonb;
  prior app_private.offline_pos_operations%rowtype;
  grant_row app_private.offline_pos_grants%rowtype;
  lease_row app_private.offline_pos_leases%rowtype;
  device app_private.offline_pos_devices%rowtype;
  station public.stations%rowtype;
  balance public.inventory_balances%rowtype;
  lease_item app_private.offline_pos_lease_items%rowtype;
  line record;
  created_at timestamptz;
  total_cents bigint:=0;
  received_cents bigint;
  change_cents bigint;
  customer_id_value uuid;
  created_order_id uuid:=gen_random_uuid();
  order_number text;
  now_ms bigint:=floor(extract(epoch from clock_timestamp())*1000)::bigint;
  digest_actual text;
begin
  if p_profile_id is null or p_device_id is null or p_operation_id is null
     or p_payload_digest !~ '^[0-9a-f]{64}$' or p_canonical_payload is null
     or length(p_canonical_payload)>32768 then
    raise exception using errcode='22023',message='offline sale is invalid';
  end if;
  digest_actual:=encode(extensions.digest(convert_to(p_canonical_payload,'UTF8'),'sha256'),'hex');
  if digest_actual<>p_payload_digest then
    raise exception using errcode='22023',message='offline payload digest mismatch';
  end if;
  payload:=p_canonical_payload::jsonb;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(p_operation_id::text,0));
  select * into prior from app_private.offline_pos_operations o where o.operation_id=p_operation_id for update;
  if prior.operation_id is not null then
    if prior.payload_digest<>p_payload_digest or prior.profile_id<>p_profile_id
       or prior.device_id<>p_device_id or prior.lease_id<>p_lease_id or prior.grant_id<>p_grant_id then
      raise exception using errcode='23505',message='offline operation id was reused with different data';
    end if;
    return jsonb_build_object('operation_id',prior.operation_id,'order_id',prior.order_id,'status',prior.status,'duplicate',true);
  end if;
  select * into grant_row from app_private.offline_pos_grants g
    where g.id=p_grant_id for update;
  select * into lease_row from app_private.offline_pos_leases l
    where l.id=p_lease_id for update;
  select * into device from app_private.offline_pos_devices d
    where d.device_id=p_device_id for update;
  if grant_row.id is null or lease_row.id is null or device.device_id is null
     or grant_row.profile_id<>p_profile_id or lease_row.profile_id<>p_profile_id
     or grant_row.organization_id<>lease_row.organization_id
     or grant_row.station_id<>lease_row.station_id
     or grant_row.device_id<>p_device_id or lease_row.device_id<>p_device_id
     or lease_row.grant_id<>p_grant_id or device.profile_id<>p_profile_id
     or device.organization_id<>lease_row.organization_id or device.station_id<>lease_row.station_id
     or lease_row.status not in ('ACTIVE','EXPIRED_UNRESOLVED')
     or grant_row.status<>'ACTIVE' or device.status<>'ACTIVE'
     or not ('OFFLINE_WALKIN_CASH'=any(grant_row.allowed_operations)) then
    raise exception using errcode='42501',message='offline authorization was revoked or scope changed';
  end if;
  if not exists (
    select 1 from public.organization_members m
    where m.organization_id=lease_row.organization_id and m.profile_id=p_profile_id
      and m.is_active and m.role in ('OWNER','MANAGER','STAFF')
      and (m.role in ('OWNER','MANAGER') or exists (
        select 1 from public.member_stations ms where ms.member_id=m.id and ms.station_id=lease_row.station_id))
  ) then raise exception using errcode='42501',message='current station permission is required for reconciliation'; end if;
  perform app_private.require_plan_feature(lease_row.organization_id,'ORDER_MANAGEMENT');
  select * into station from public.stations s where s.id=lease_row.station_id and s.organization_id=lease_row.organization_id;
  if station.id is null or not station.is_active or station.deleted_at is not null then
    raise exception using errcode='42501',message='station is unavailable';
  end if;
  if coalesce(payload->>'operation_id','')<>p_operation_id::text
     or coalesce(payload->>'grant_id','')<>p_grant_id::text
     or coalesce(payload->>'lease_id','')<>p_lease_id::text
     or coalesce(payload->>'device_id','')<>p_device_id
     or coalesce(payload->>'organization_id','')<>lease_row.organization_id::text
     or coalesce(payload->>'station_id','')<>lease_row.station_id::text
     or coalesce(payload->>'profile_id','')<>p_profile_id::text
     or coalesce(payload->>'payment_method','')<>'CASH'
     or coalesce(payload->>'delivery_mode','')<>'PICKUP'
     or jsonb_typeof(payload->'items')<>'array'
     or jsonb_array_length(payload->'items') not between 1 and 100 then
    raise exception using errcode='22023',message='offline sale payload scope or tender is invalid';
  end if;
  if payload ? 'container_movements' and jsonb_array_length(coalesce(payload->'container_movements','[]'::jsonb))>0 then
    raise exception using errcode='22023',message='container movements require online verification';
  end if;
  begin
    created_at:=to_timestamp((payload->>'transaction_at_ms')::numeric/1000.0);
    received_cents:=(payload->>'cash_received_centavos')::bigint;
    change_cents:=(payload->>'change_centavos')::bigint;
  exception when others then
    raise exception using errcode='22023',message='offline sale timestamps or cash values are invalid';
  end;
  if created_at<grant_row.issued_at or created_at>grant_row.expires_at
     or created_at<lease_row.issued_at or created_at>lease_row.expires_at
     or received_cents<=0 or change_cents<0 then
    raise exception using errcode='55000',message='offline sale was recorded outside its grant or lease';
  end if;
  customer_id_value:=nullif(payload->>'customer_id','')::uuid;
  if customer_id_value is not null and not exists(
    select 1 from public.customers c where c.id=customer_id_value
      and c.station_id=lease_row.station_id and c.is_active and c.deleted_at is null
  ) then raise exception using errcode='42501',message='customer does not belong to the leased station'; end if;
  if jsonb_typeof(payload->'items')<>'array' then
    raise exception using errcode='22023',message='offline sale items are invalid';
  end if;
  if (select count(*) from jsonb_array_elements(payload->'items')) <>
     (select count(distinct (v->>'product_id')::uuid) from jsonb_array_elements(payload->'items') v) then
    raise exception using errcode='22023',message='duplicate products are not allowed in an offline sale';
  end if;
  for line in select (v->>'product_id')::uuid product_id,(v->>'quantity')::integer quantity,
      (v->>'unit_price_centavos')::bigint unit_price_centavos
    from jsonb_array_elements(payload->'items') v order by (v->>'product_id')::uuid
  loop
    if line.quantity not between 1 and 99 or line.unit_price_centavos<0 then
      raise exception using errcode='22023',message='offline sale line is invalid';
    end if;
    select * into lease_item from app_private.offline_pos_lease_items i
      where i.lease_id=p_lease_id and i.product_id=line.product_id for update;
    if lease_item.lease_id is null or lease_item.unit_price_centavos<>line.unit_price_centavos
       or lease_item.quantity_reserved-lease_item.quantity_consumed-lease_item.quantity_released<line.quantity then
      raise exception using errcode='23514',message='offline lease quantity or price snapshot conflict';
    end if;
    if not exists (select 1 from public.products p where p.id=line.product_id
        and p.station_id=lease_row.station_id and p.is_active and p.is_available and p.deleted_at is null
        and p.name=lease_item.product_name and p.product_type=lease_item.product_type
        and p.size_label=lease_item.size_label and p.price_centavos=lease_item.unit_price_centavos
        and p.updated_at=lease_item.product_updated_at) then
      raise exception using errcode='55000',message='product catalog changed after the offline price snapshot';
    end if;
    total_cents:=total_cents+line.quantity*line.unit_price_centavos;
  end loop;
  if total_cents<=0 or received_cents-change_cents<>total_cents then
    raise exception using errcode='22023',message='cash received, change and order total do not balance';
  end if;

  order_number:=app_private.next_order_number(lease_row.station_id);
  insert into public.orders(
    id,client_operation_id,station_id,customer_id,customer_uid,order_number,
    customer_name,customer_phone,delivery_mode,delivery_address,subtotal_centavos,
    delivery_fee_centavos,discount_centavos,tax_centavos,total_centavos,
    payment_method,payment_status,status,customer_note,station_note,requested_at,
    accepted_at,ready_at,completed_at,order_source
  ) values(
    created_order_id,p_operation_id::text,lease_row.station_id,customer_id_value,null,order_number,
    coalesce(nullif(btrim(payload->>'customer_name'),''),'Walk-in Customer'),
    coalesce(payload->>'customer_phone',''),'PICKUP','Station pickup',total_cents,0,0,0,total_cents,
    'CASH','PAID','COMPLETED',nullif(btrim(payload->>'notes'),''),
    'Offline cash sale; accepted from verified device grant and inventory lease',
    floor(extract(epoch from created_at)*1000)::bigint,now_ms,now_ms,now_ms,'OWNER_WALK_IN'
  );
  for line in select (v->>'product_id')::uuid product_id,(v->>'quantity')::integer quantity,
      (v->>'unit_price_centavos')::bigint unit_price_centavos
    from jsonb_array_elements(payload->'items') v order by (v->>'product_id')::uuid
  loop
    select * into lease_item from app_private.offline_pos_lease_items i
      where i.lease_id=p_lease_id and i.product_id=line.product_id for update;
    insert into public.order_items(order_id,product_id,product_name_snapshot,product_type_snapshot,
      size_label_snapshot,unit_price_centavos,quantity,subtotal_centavos)
    values(created_order_id,line.product_id,lease_item.product_name,lease_item.product_type,
      lease_item.size_label,line.unit_price_centavos,line.quantity,line.unit_price_centavos*line.quantity);
    select * into balance from public.inventory_balances b
      where b.station_id=lease_row.station_id and b.product_id=line.product_id for update;
    if balance.quantity<line.quantity or balance.reserved_quantity<line.quantity then
      raise exception using errcode='23514',message='offline reservation balance is inconsistent';
    end if;
    update public.inventory_balances b set quantity=b.quantity-line.quantity,
      reserved_quantity=b.reserved_quantity-line.quantity,version=b.version+1,updated_at=now()
      where b.id=balance.id returning * into balance;
    update app_private.offline_pos_lease_items i set quantity_consumed=i.quantity_consumed+line.quantity
      where i.lease_id=p_lease_id and i.product_id=line.product_id;
    insert into public.inventory_movements(station_id,product_id,operation_id,delta,quantity_after,
      reason,order_id,created_by,movement_type,source_id)
    values(lease_row.station_id,line.product_id,
      'offline-pos:'||p_operation_id::text||':'||line.product_id::text,-line.quantity,balance.quantity,
      'Offline POS reservation consumption',created_order_id,p_profile_id,'OFFLINE_POS_DEDUCTION',p_operation_id::text);
  end loop;
  update public.payments p set operation_id='offline-pos-payment:'||p_operation_id::text,
    metadata=coalesce(p.metadata,'{}'::jsonb)||jsonb_build_object(
      'offline_operation_id',p_operation_id,'cash_received_centavos',received_cents,
      'change_centavos',change_cents,'payload_digest',p_payload_digest)
  where p.order_id=created_order_id and p.status='PAID';
  if not found then raise exception using errcode='55000',message='offline cash receipt was not created'; end if;
  insert into app_private.offline_pos_operations(
    operation_id,payload_digest,profile_id,organization_id,station_id,device_id,
    grant_id,lease_id,transaction_at,cash_received_centavos,change_centavos,payload,order_id
  ) values(
    p_operation_id,p_payload_digest,p_profile_id,lease_row.organization_id,lease_row.station_id,
    p_device_id,p_grant_id,p_lease_id,created_at,received_cents,change_cents,payload,created_order_id
  );
  insert into app_private.offline_pos_reconciliation_events(
    operation_id,lease_id,device_id,organization_id,station_id,actor_profile_id,action,reason
  ) values(p_operation_id,p_lease_id,p_device_id,lease_row.organization_id,lease_row.station_id,
    p_profile_id,'SALE_ACCEPTED','Atomic offline sale reconciliation accepted');
  return jsonb_build_object('operation_id',p_operation_id,'order_id',created_order_id,
    'order_number',order_number,'status','COMMITTED','duplicate',false);
end;
$$;
revoke all on function app_private.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)
  from public,anon,authenticated,service_role;
grant execute on function app_private.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)
  to service_role;

-- PostgREST RPCs resolve functions in the exposed public schema. Keep the
-- implementation in app_private and expose only this service-role-only,
-- SECURITY INVOKER entry point to the signature-verifying Edge Function.
create or replace function public.commit_offline_pos_sale(
  p_profile_id uuid,p_device_id text,p_grant_id uuid,p_lease_id uuid,
  p_operation_id uuid,p_payload_digest text,p_canonical_payload text
)
returns jsonb
language sql
security invoker
set search_path = ''
as $$
  select app_private.commit_offline_pos_sale(
    p_profile_id,p_device_id,p_grant_id,p_lease_id,p_operation_id,p_payload_digest,p_canonical_payload
  );
$$;
revoke all on function public.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)
  from public,anon,authenticated,service_role;
grant execute on function public.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)
  to service_role;

create or replace function public.reconcile_offline_pos_lease(
  p_lease_id uuid,p_reason text,p_physical_counts jsonb,p_revoke_device boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid:=auth.uid();
  lease_row app_private.offline_pos_leases%rowtype;
  device app_private.offline_pos_devices%rowtype;
  item record;
  balance public.inventory_balances%rowtype;
  actual_count integer;
  remaining integer;
  other_reserved integer;
  old_quantity integer;
  op text;
begin
  if caller is null then raise exception using errcode='28000',message='authenticated user is required'; end if;
  if nullif(btrim(p_reason),'') is null or length(p_reason)>500
     or p_physical_counts is null or jsonb_typeof(p_physical_counts)<>'object' then
    raise exception using errcode='22023',message='physical count and reconciliation reason are required';
  end if;
  select * into lease_row from app_private.offline_pos_leases l where l.id=p_lease_id for update;
  if lease_row.id is null then raise exception using errcode='P0002',message='offline lease not found'; end if;
  if not app_private.can_manage_org(lease_row.organization_id) then
    raise exception using errcode='42501',message='owner or manager authorization is required';
  end if;
  if lease_row.status in ('CLOSED','RECONCILED') then
    raise exception using errcode='55000',message='offline lease was already reconciled';
  end if;
  if not p_revoke_device and lease_row.expires_at>clock_timestamp() then
    raise exception using errcode='55000',message='active leases cannot be released before expiry';
  end if;
  select * into device from app_private.offline_pos_devices d where d.device_id=lease_row.device_id for update;
  if p_revoke_device then
    update app_private.offline_pos_devices set status='REVOKED',revoked_at=clock_timestamp(),revoked_by=caller
      where device_id=lease_row.device_id and status='ACTIVE';
    update app_private.offline_pos_grants set status='REVOKED'
      where device_id=lease_row.device_id and status='ACTIVE';
    insert into app_private.offline_pos_reconciliation_events(
      lease_id,device_id,organization_id,station_id,actor_profile_id,action,reason,physical_counts
    ) values(p_lease_id,lease_row.device_id,lease_row.organization_id,lease_row.station_id,
      caller,'DEVICE_REVOKED',btrim(p_reason),p_physical_counts);
  end if;
  for item in select * from app_private.offline_pos_lease_items i
      where i.lease_id=p_lease_id order by i.product_id
  loop
    if not (p_physical_counts ? item.product_id::text) then
      raise exception using errcode='22023',message='physical count is required for every leased product';
    end if;
    begin actual_count:=(p_physical_counts->>item.product_id::text)::integer;
    exception when others then raise exception using errcode='22023',message='physical count must be a non-negative integer'; end;
    if actual_count<0 then raise exception using errcode='22023',message='physical count must be non-negative'; end if;
    remaining:=item.quantity_reserved-item.quantity_consumed;
    select * into balance from public.inventory_balances b
      where b.station_id=lease_row.station_id and b.product_id=item.product_id for update;
    old_quantity:=balance.quantity;
    other_reserved:=balance.reserved_quantity-remaining;
    if actual_count<other_reserved then
      raise exception using errcode='23514',message='physical count is below other active reservations; reconcile all affected leases together';
    end if;
    if actual_count<>balance.quantity then
      op:='offline-reconcile:'||p_lease_id::text||':'||item.product_id::text;
      update public.inventory_balances b set quantity=actual_count,
        reserved_quantity=other_reserved,version=b.version+1,updated_at=now()
        where b.id=balance.id returning * into balance;
      insert into public.inventory_movements(station_id,product_id,operation_id,delta,quantity_after,
        reason,created_by,movement_type,source_id)
      values(lease_row.station_id,item.product_id,op,actual_count-old_quantity,
        actual_count,'Audited physical reconciliation: '||btrim(p_reason),caller,'OFFLINE_RECONCILIATION',p_lease_id::text)
      on conflict(operation_id) do nothing;
    else
      update public.inventory_balances set reserved_quantity=other_reserved,version=version+1,updated_at=now()
        where id=balance.id;
    end if;
    update app_private.offline_pos_lease_items set quantity_released=quantity_released+remaining
      where lease_id=p_lease_id and product_id=item.product_id;
  end loop;
  update app_private.offline_pos_leases set status='RECONCILED',closed_at=clock_timestamp()
    where id=p_lease_id;
  insert into app_private.offline_pos_reconciliation_events(
    lease_id,device_id,organization_id,station_id,actor_profile_id,action,reason,physical_counts
  ) values(p_lease_id,lease_row.device_id,lease_row.organization_id,lease_row.station_id,
    caller,'LEASE_RECONCILED',btrim(p_reason),p_physical_counts);
  return jsonb_build_object('lease_id',p_lease_id,'status','RECONCILED','device_revoked',p_revoke_device);
end;
$$;
revoke all on function public.reconcile_offline_pos_lease(uuid,text,jsonb,boolean)
  from public,anon,authenticated,service_role;
grant execute on function public.reconcile_offline_pos_lease(uuid,text,jsonb,boolean) to authenticated;

-- Bulk reconciliation is required when several expired/lost leases jointly
-- reserve more stock than a verified physical count can satisfy. Selecting the
-- full affected set releases those allocations atomically; no reservation is
-- discarded merely because its lease expired.
create or replace function public.reconcile_offline_pos_leases(
  p_station_id uuid,
  p_lease_ids uuid[],
  p_reason text,
  p_physical_counts jsonb,
  p_revoke_devices boolean default false
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  station_org uuid;
  lease_row app_private.offline_pos_leases%rowtype;
  item record;
  balance public.inventory_balances%rowtype;
  selected_remaining integer;
  other_reserved integer;
  actual_count integer;
  old_quantity integer;
  lease_count integer := 0;
  device_id_value text;
  op text;
begin
  if caller is null then raise exception using errcode='28000',message='authenticated user is required'; end if;
  if p_station_id is null or p_lease_ids is null or cardinality(p_lease_ids) not between 1 and 100
     or array_position(p_lease_ids,null) is not null
     or cardinality(p_lease_ids)<>(select count(distinct x) from unnest(p_lease_ids) x)
     or nullif(btrim(p_reason),'') is null or length(p_reason)>500
     or p_physical_counts is null or jsonb_typeof(p_physical_counts)<>'object' then
    raise exception using errcode='22023',message='lease set, physical counts and reconciliation reason are required';
  end if;
  select s.organization_id into station_org from public.stations s where s.id=p_station_id;
  if station_org is null or not app_private.can_manage_org(station_org) then
    raise exception using errcode='42501',message='owner or manager authorization is required';
  end if;
  for lease_row in
    select l.* from app_private.offline_pos_leases l
    where l.id=any(p_lease_ids) order by l.id for update
  loop
    lease_count:=lease_count+1;
    if lease_row.station_id<>p_station_id or lease_row.organization_id<>station_org
       or lease_row.status in ('CLOSED','RECONCILED') then
      raise exception using errcode='55000',message='selected lease is closed or belongs to another station';
    end if;
    if not p_revoke_devices and lease_row.expires_at>clock_timestamp() then
      raise exception using errcode='55000',message='active leases require verified device revocation before reconciliation';
    end if;
  end loop;
  if lease_count<>cardinality(p_lease_ids) then
    raise exception using errcode='P0002',message='one or more selected leases were not found';
  end if;

  for item in
    select i.product_id,
      sum(i.quantity_reserved-i.quantity_consumed-i.quantity_released)::integer as remaining
    from app_private.offline_pos_lease_items i
    where i.lease_id=any(p_lease_ids)
    group by i.product_id order by i.product_id
  loop
    if not (p_physical_counts ? item.product_id::text) then
      raise exception using errcode='22023',message='physical count is required for every affected leased product';
    end if;
    begin actual_count:=(p_physical_counts->>item.product_id::text)::integer;
    exception when others then raise exception using errcode='22023',message='physical counts must be non-negative integers'; end;
    if actual_count<0 then raise exception using errcode='22023',message='physical counts must be non-negative'; end if;
    selected_remaining:=item.remaining;
    select * into balance from public.inventory_balances b
      where b.station_id=p_station_id and b.product_id=item.product_id for update;
    if balance.id is null then raise exception using errcode='23503',message='reserved inventory balance is missing'; end if;
    old_quantity:=balance.quantity;
    other_reserved:=balance.reserved_quantity-selected_remaining;
    if other_reserved<0 then raise exception using errcode='23514',message='reservation ledger is inconsistent'; end if;
    if actual_count<other_reserved then
      raise exception using errcode='23514',message='physical count is below reservations outside the selected set; include all affected leases';
    end if;
    update public.inventory_balances b set quantity=actual_count,
      reserved_quantity=other_reserved,version=b.version+1,updated_at=now()
      where b.id=balance.id;
    if actual_count<>old_quantity then
      op:='offline-reconcile-bulk:'||p_station_id::text||':'||item.product_id::text||':'||
        encode(extensions.digest(convert_to(array_to_string(p_lease_ids,','),'UTF8'),'sha256'),'hex');
      insert into public.inventory_movements(station_id,product_id,operation_id,delta,quantity_after,
        reason,created_by,movement_type,source_id)
      values(p_station_id,item.product_id,op,actual_count-old_quantity,actual_count,
        'Audited physical reconciliation: '||btrim(p_reason),caller,'OFFLINE_RECONCILIATION',
        array_to_string(p_lease_ids,','));
    end if;
  end loop;

  for lease_row in
    select l.* from app_private.offline_pos_leases l
    where l.id=any(p_lease_ids) order by l.id
  loop
    if p_revoke_devices then
      update app_private.offline_pos_devices d set status='REVOKED',revoked_at=clock_timestamp(),revoked_by=caller
        where d.device_id=lease_row.device_id and d.status='ACTIVE';
      update app_private.offline_pos_grants g set status='REVOKED'
        where g.device_id=lease_row.device_id and g.organization_id=station_org and g.status='ACTIVE';
      insert into app_private.offline_pos_reconciliation_events(
        lease_id,device_id,organization_id,station_id,actor_profile_id,action,reason,physical_counts
      ) values(lease_row.id,lease_row.device_id,station_org,p_station_id,caller,
        'DEVICE_REVOKED',btrim(p_reason),p_physical_counts);
    end if;
    update app_private.offline_pos_lease_items i
      set quantity_released=quantity_released+(i.quantity_reserved-i.quantity_consumed-i.quantity_released)
      where i.lease_id=lease_row.id;
    update app_private.offline_pos_leases l set status='RECONCILED',closed_at=clock_timestamp()
      where l.id=lease_row.id;
    insert into app_private.offline_pos_reconciliation_events(
      lease_id,device_id,organization_id,station_id,actor_profile_id,action,reason,physical_counts
    ) values(lease_row.id,lease_row.device_id,station_org,p_station_id,caller,
      'LEASE_RECONCILED',btrim(p_reason),p_physical_counts);
  end loop;
  return jsonb_build_object('station_id',p_station_id,'lease_ids',to_jsonb(p_lease_ids),
    'status','RECONCILED','device_revocation_requested',p_revoke_devices);
end;
$$;
revoke all on function public.reconcile_offline_pos_leases(uuid,uuid[],text,jsonb,boolean)
  from public,anon,authenticated,service_role;
grant execute on function public.reconcile_offline_pos_leases(uuid,uuid[],text,jsonb,boolean)
  to authenticated;

commit;
