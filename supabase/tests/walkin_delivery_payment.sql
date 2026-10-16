-- Local-only regression coverage. All fixture rows roll back at the end.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(1);

do $$
declare
  fixture_organization_id uuid := gen_random_uuid();
  station_id uuid := gen_random_uuid();
  delivery_order_id uuid := gen_random_uuid();
  pickup_order_id uuid := gen_random_uuid();
  other_organization_id uuid := gen_random_uuid();
  other_station_id uuid := gen_random_uuid();
  other_order_id uuid := gen_random_uuid();
  account_order_id uuid := gen_random_uuid();
  cash_to_account_order_id uuid := gen_random_uuid();
  rider_account_order_id uuid := gen_random_uuid();
  pre_delivery_rider_account_order_id uuid := gen_random_uuid();
  unlinked_rider_account_order_id uuid := gen_random_uuid();
  plan_guard_account_order_id uuid := gen_random_uuid();
  paid_cancel_order_id uuid := gen_random_uuid();
  customer_order_id uuid := gen_random_uuid();
  saved_customer_id uuid := gen_random_uuid();
  owner_profile_id uuid := gen_random_uuid();
  owner_member_id uuid := gen_random_uuid();
  other_owner_profile_id uuid := gen_random_uuid();
  other_owner_member_id uuid := gen_random_uuid();
  customer_profile_id uuid := gen_random_uuid();
  rider_profile_id uuid := gen_random_uuid();
  rider_row_id uuid := gen_random_uuid();
  other_rider_profile_id uuid := gen_random_uuid();
  other_rider_row_id uuid := gen_random_uuid();
  staff_member_id uuid := gen_random_uuid();
  rider_member_id uuid := gen_random_uuid();
  other_rider_member_id uuid := gen_random_uuid();
  staff_profile_id uuid := gen_random_uuid();
  proof_id uuid := gen_random_uuid();
  free_organization_id uuid := gen_random_uuid();
  free_owner_profile_id uuid := gen_random_uuid();
  free_owner_member_id uuid := gen_random_uuid();
  free_station_id uuid := gen_random_uuid();
  free_station_over_limit_id uuid := gen_random_uuid();
  free_delivery_order_id uuid := gen_random_uuid();
  free_account_order_id uuid := gen_random_uuid();
  free_quota_order_id uuid := gen_random_uuid();
  free_import_order_id uuid := gen_random_uuid();
  plan_entitlements jsonb;
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
  proof_path text;
  receipt_status text;
  receipt_amount bigint;
  retry_created boolean;
begin
  insert into storage.buckets (id, name, public)
  values ('payment-assets', 'payment-assets', false)
  on conflict (id) do nothing;

  insert into auth.users (id)
  values (owner_profile_id);
  insert into public.organizations (id, name)
  values (fixture_organization_id, 'Walk-in payment regression fixture');
  insert into public.profiles (id, display_name, role, preferred_business_id)
  values (owner_profile_id, 'Local owner fixture', 'OWNER', fixture_organization_id);

  insert into public.stations (id, organization_id, name, address)
  values (station_id, fixture_organization_id, 'Walk-in payment fixture', 'Local test only');
  insert into public.customers (id, station_id, name, phone)
  values (saved_customer_id, station_id, 'Saved customer fixture', '09170000000');
  insert into public.organization_members (id, organization_id, profile_id, role)
  values (owner_member_id, fixture_organization_id, owner_profile_id, 'OWNER');
  insert into public.member_stations (member_id, station_id)
  values (owner_member_id, station_id);

  insert into public.subscriptions (
    organization_id, plan_id, status, billing_cycle,
    current_period_start, current_period_end, updated_at
  )
  select fixture_organization_id, p.id, 'ACTIVE', 'MONTHLY', now_ms, now_ms + 2592000000, now()
  from public.subscription_plans p where p.code = 'BUSINESS';

  -- A Free organization may provision one station, but a second active station
  -- is rejected by the database quota trigger. Premium customer ordering is
  -- denied when the authoritative plan feature is absent.
  insert into public.organizations (id, name)
  values (free_organization_id, 'Free quota fixture');
  insert into auth.users (id) values (free_owner_profile_id);
  insert into public.profiles (id, display_name, role, preferred_business_id)
  values (free_owner_profile_id, 'Free quota owner', 'OWNER', free_organization_id);
  insert into public.stations (id, organization_id, name, address)
  values (free_station_id, free_organization_id, 'Free station', 'Local test only');
  insert into public.organization_members (id, organization_id, profile_id, role)
  values (free_owner_member_id, free_organization_id, free_owner_profile_id, 'OWNER');
  insert into public.member_stations (member_id, station_id)
  values (free_owner_member_id, free_station_id);
  begin
    insert into public.stations (id, organization_id, name, address)
    values (free_station_over_limit_id, free_organization_id, 'Over-limit station', 'Local test only');
    raise exception 'Free plan station limit unexpectedly allowed a second station';
  exception when check_violation then
    null;
  end;
  begin
    perform app_private.require_plan_feature(free_organization_id, 'MOBILE_APP_ORDERING');
    raise exception 'Free plan unexpectedly included online ordering';
  exception when insufficient_privilege then
    null;
  end;
  begin
    insert into public.orders (
      id, station_id, order_number, customer_name, delivery_address, delivery_mode,
      subtotal_centavos, total_centavos, payment_method, payment_status, status, order_source
    ) values (
      free_delivery_order_id, free_station_id, 'FREE-DELIVERY-BLOCKED', 'Free fixture',
      'Local test address', 'DELIVERY', 100, 100, 'CASH_ON_DELIVERY', 'PENDING', 'PENDING', 'OWNER_WALK_IN'
    );
    raise exception 'Free plan unexpectedly accepted a delivery order';
  exception when insufficient_privilege then
    null;
  end;
  begin
    insert into public.orders (
      id, station_id, order_number, customer_name, delivery_address, delivery_mode,
      subtotal_centavos, total_centavos, payment_method, payment_status, status, order_source
    ) values (
      free_account_order_id, free_station_id, 'FREE-ACCOUNT-BLOCKED', 'Free fixture',
      'Station pickup', 'PICKUP', 100, 100, 'CLIENT_ACCOUNT', 'PENDING', 'COMPLETED', 'OWNER_WALK_IN'
    );
    raise exception 'Free plan unexpectedly accepted a Client Account order';
  exception when insufficient_privilege then
    null;
  end;
  update public.plan_limits pl
  set max_value = 0
  where pl.plan_id = (select p.id from public.subscription_plans p where p.code = 'FREE')
    and pl.limit_type = 'MONTHLY_ORDER_COUNT';
  begin
    insert into public.orders (
      id, station_id, order_number, customer_name, delivery_address, delivery_mode,
      subtotal_centavos, total_centavos, payment_method, payment_status, status, order_source
    ) values (
      free_quota_order_id, free_station_id, 'FREE-ORDER-QUOTA-BLOCKED', 'Free fixture',
      'Station pickup', 'PICKUP', 100, 100, 'CASH', 'PENDING', 'COMPLETED', 'OWNER_WALK_IN'
    );
    raise exception 'Free plan unexpectedly exceeded its monthly order quota';
  exception when check_violation then
    null;
  end;
  perform pg_catalog.set_config('request.jwt.claim.sub', free_owner_profile_id::text, true);
  begin
    perform * from public.import_legacy_order(
      p_order_id := free_import_order_id,
      p_station_id := free_station_id,
      p_order_number := 'BACKDATED-FREE-IMPORT-ATTEMPT',
      p_customer_id := null,
      p_customer_name := 'Historical import fixture',
      p_customer_phone := '',
      p_rider_id := null,
      p_delivery_address_id := null,
      p_delivery_address := 'Station pickup',
      p_delivery_latitude := null,
      p_delivery_longitude := null,
      p_delivery_mode := 'PICKUP',
      p_subtotal_centavos := 100,
      p_delivery_fee_centavos := 0,
      p_discount_centavos := 0,
      p_total_centavos := 100,
      p_payment_method := 'CASH',
      p_payment_status := 'PENDING',
      p_status := 'COMPLETED',
      p_scheduled_delivery_start := null,
      p_scheduled_delivery_end := null,
      p_customer_note := null,
      p_station_note := null,
      p_created_at := 0,
      p_accepted_at := null,
      p_ready_at := null,
      p_assigned_at := null,
      p_out_for_delivery_at := null,
      p_delivered_at := null,
      p_completed_at := null,
      p_cancelled_at := null,
      p_cancellation_reason := null,
      p_client_updated_at := 0,
      p_items := pg_catalog.jsonb_build_array(pg_catalog.jsonb_build_object(
        'product_name', 'Historical item',
        'product_type', 'OTHER',
        'size_label', '',
        'unit_price_centavos', 100,
        'quantity', 1,
        'subtotal_centavos', 100
      ))
    );
    raise exception 'a backdated import unexpectedly bypassed the monthly order quota';
  exception when check_violation then
    null;
  end;
  update public.plan_limits pl
  set max_value = 100
  where pl.plan_id = (select p.id from public.subscription_plans p where p.code = 'FREE')
    and pl.limit_type = 'MONTHLY_ORDER_COUNT';
  if exists (
    select 1 from public.orders o where o.id in (
      free_delivery_order_id, free_account_order_id, free_quota_order_id, free_import_order_id
    )
  ) then
    raise exception 'a Free plan order rejected by an entitlement guard persisted';
  end if;

  insert into public.orders (
    id, station_id, order_number, customer_name, delivery_address, delivery_mode,
    subtotal_centavos, total_centavos, payment_method, payment_status, status, order_source
  ) values (
    delivery_order_id, station_id, 'PAYMENT-REGRESSION-DELIVERY', 'Local fixture',
    'Local test address', 'DELIVERY', 10000, 10000, 'CASH', 'PAID', 'PENDING', 'OWNER_WALK_IN'
  );

  if (select o.payment_status from public.orders o where o.id = delivery_order_id) <> 'PENDING' then
    raise exception 'walk-in delivery with no receipt must be pending';
  end if;
  if exists (select 1 from public.payments p where p.order_id = delivery_order_id) then
    raise exception 'walk-in delivery must not manufacture a paid receipt';
  end if;
  perform pg_catalog.set_config('request.jwt.claim.sub', owner_profile_id::text, true);
  begin
    perform * from public.transition_delivery_status(
      p_order_id := delivery_order_id,
      p_target_status := 'COMPLETED',
      p_operation_id := 'invalid-direct-completion'
    );
    raise exception 'invalid order transition unexpectedly succeeded';
  exception when object_not_in_prerequisite_state then
    null;
  end;
  if (select o.status from public.orders o where o.id = delivery_order_id) <> 'READY_FOR_PICKUP' then
    raise exception 'invalid order transition changed the persisted state to %',
      (select o.status from public.orders o where o.id = delivery_order_id);
  end if;

  insert into public.orders (
    id, station_id, order_number, client_operation_id, customer_name,
    delivery_address, delivery_mode, subtotal_centavos, total_centavos,
    payment_method, payment_status, status, order_source
  ) values (
    pickup_order_id, station_id, 'PAYMENT-REGRESSION-PICKUP', 'pickup-payment-proof-operation',
    'Local fixture', 'Station pickup', 'PICKUP', 1200, 1200,
    'CASH', 'PAID', 'COMPLETED', 'OWNER_WALK_IN'
  );
  if (select count(*) from public.payments p where p.order_id = pickup_order_id
      and p.status = 'PAID' and p.amount_centavos = 1200 and p.payment_method = 'CASH') <> 1 then
    raise exception 'collected walk-in pickup must create exactly one paid receipt';
  end if;

  -- An Owner from a separate tenant cannot settle this order or replay its
  -- create_order idempotency key to learn its persisted state.
  insert into auth.users (id) values (other_owner_profile_id);
  insert into public.organizations (id, name)
  values (other_organization_id, 'Isolated tenant fixture');
  insert into public.profiles (id, display_name, role, preferred_business_id)
  values (other_owner_profile_id, 'Other tenant owner', 'OWNER', other_organization_id);
  insert into public.stations (id, organization_id, name, address)
  values (other_station_id, other_organization_id, 'Isolated station', 'Local test only');
  insert into public.organization_members (id, organization_id, profile_id, role)
  values (other_owner_member_id, other_organization_id, other_owner_profile_id, 'OWNER');
  insert into public.member_stations (member_id, station_id)
  values (other_owner_member_id, other_station_id);
  insert into public.orders (
    id, station_id, order_number, client_operation_id, customer_name,
    delivery_address, delivery_mode, subtotal_centavos, total_centavos,
    payment_method, payment_status, status, order_source
  ) values (
    other_order_id, other_station_id, 'OTHER-TENANT-ORDER', 'other-tenant-known-operation',
    'Other tenant customer', 'Station pickup', 'PICKUP', 5000, 5000,
    'CASH', 'PENDING', 'PENDING', 'OWNER_WALK_IN'
  );
  perform pg_catalog.set_config('request.jwt.claim.sub', owner_profile_id::text, true);
  begin
    perform * from public.record_payment(
      p_order_id := other_order_id,
      p_operation_id := 'cross-tenant-payment-attempt',
      p_amount_centavos := 5000,
      p_payment_method := 'CASH'
    );
    raise exception 'Owner A unexpectedly recorded payment for Owner B';
  exception when insufficient_privilege then
    null;
  end;
  begin
    perform * from public.create_order(
      p_station_id := other_station_id,
      p_client_operation_id := 'other-tenant-known-operation',
      p_source := 'OWNER_WALK_IN',
      p_items := '[]'::jsonb
    );
    raise exception 'Owner A unexpectedly replayed Owner B order operation';
  exception when insufficient_privilege then
    null;
  end;
  if exists (select 1 from public.payments p where p.order_id = other_order_id)
     or (select o.payment_status from public.orders o where o.id = other_order_id) <> 'PENDING' then
    raise exception 'cross-tenant payment attempt changed Owner B order';
  end if;

  -- A buyer's authenticated identity is not authority to assert that cash was
  -- collected. The order may be visible to that buyer, but settlement is not.
  insert into auth.users (id) values (customer_profile_id);
  insert into public.profiles (id, display_name, role, preferred_business_id, preferred_station_id)
  values (customer_profile_id, 'Local customer fixture', 'CUSTOMER', fixture_organization_id, station_id);
  insert into public.orders (
    id, station_id, customer_uid, order_number, customer_name, customer_phone,
    delivery_address, delivery_mode, subtotal_centavos, total_centavos,
    payment_method, payment_status, status, order_source
  ) values (
    customer_order_id, station_id, customer_profile_id, 'PAYMENT-REGRESSION-CUSTOMER',
    'Local customer fixture', '', 'Station pickup', 'PICKUP', 10000, 10000,
    'CASH', 'PENDING', 'PENDING', 'LEGACY_IMPORT'
  );
  perform pg_catalog.set_config('request.jwt.claim.sub', customer_profile_id::text, true);
  begin
    perform * from public.record_payment(
      p_order_id := customer_order_id,
      p_operation_id := 'customer-cannot-assert-cash',
      p_amount_centavos := 10000,
      p_payment_method := 'CASH'
    );
    raise exception 'customer self-settlement unexpectedly succeeded';
  exception when insufficient_privilege then
    null;
  end;
  if exists (select 1 from public.payments p where p.order_id = customer_order_id)
     or (select o.payment_status from public.orders o where o.id = customer_order_id) <> 'PENDING' then
    raise exception 'customer self-settlement changed payment state';
  end if;

  insert into public.orders (
    id, station_id, order_number, customer_name, delivery_address, delivery_mode,
    subtotal_centavos, total_centavos, payment_method, payment_status, status, order_source
  ) values (
    account_order_id, station_id, 'PAYMENT-REGRESSION-ACCOUNT', 'Local fixture',
    'Station pickup', 'PICKUP', 10000, 10000, 'CLIENT_ACCOUNT', 'PAID', 'COMPLETED', 'OWNER_WALK_IN'
  );

  if (select o.payment_status from public.orders o where o.id = account_order_id) <> 'PENDING' then
    raise exception 'client-account walk-in without a receipt must be pending';
  end if;
  if exists (select 1 from public.payments p where p.order_id = account_order_id) then
    raise exception 'client-account walk-in must not manufacture a paid receipt';
  end if;

  perform pg_catalog.set_config('request.jwt.claim.sub', owner_profile_id::text, true);

  -- Staff assigned to a station may operate it, but cannot provision stations.
  insert into auth.users (id) values (staff_profile_id);
  insert into public.profiles (id, display_name, role, preferred_business_id)
  values (staff_profile_id, 'Local staff fixture', 'STAFF', fixture_organization_id);
  insert into public.organization_members (id, organization_id, profile_id, role)
  values (staff_member_id, fixture_organization_id, staff_profile_id, 'STAFF');
  insert into public.member_stations (member_id, station_id)
  values (staff_member_id, station_id);
  perform pg_catalog.set_config('request.jwt.claim.sub', staff_profile_id::text, true);
  begin
    perform public.create_station(gen_random_uuid(), '{"name":"Unauthorized staff station"}'::jsonb);
    raise exception 'station staff unexpectedly provisioned a station';
  exception when insufficient_privilege then
    null;
  end;
  perform pg_catalog.set_config('request.jwt.claim.sub', owner_profile_id::text, true);

  insert into public.orders (
    id, station_id, customer_id, order_number, customer_name, delivery_address, delivery_mode,
    subtotal_centavos, total_centavos, payment_method, payment_status, status, order_source
  ) values (
    cash_to_account_order_id, station_id, saved_customer_id, 'PAYMENT-REGRESSION-ACCOUNT-CONVERSION', 'Local fixture',
    'Station pickup', 'PICKUP', 5000, 5000, 'CASH', 'PENDING', 'COMPLETED', 'OWNER_WALK_IN'
  );
  perform public.charge_order_to_client_account(cash_to_account_order_id);
  if (select o.payment_method from public.orders o where o.id = cash_to_account_order_id) <> 'CLIENT_ACCOUNT' then
    raise exception 'authorized cash-to-account transition did not set Client Account';
  end if;
  if (select o.payment_status from public.orders o where o.id = cash_to_account_order_id) <> 'PENDING' then
    raise exception 'cash-to-account transition must remain unpaid';
  end if;
  if exists (select 1 from public.payments p where p.order_id = cash_to_account_order_id) then
    raise exception 'cash-to-account transition must not manufacture a receipt';
  end if;

  insert into public.orders (
    id, station_id, order_number, customer_name, delivery_address, delivery_mode,
    subtotal_centavos, total_centavos, payment_method, payment_status, status, order_source
  ) values (
    paid_cancel_order_id, station_id, 'PAID-ORDER-CANCELLATION-GUARD', 'Local fixture',
    'Station pickup', 'PICKUP', 2500, 2500, 'CASH', 'PENDING', 'PENDING', 'OWNER_WALK_IN'
  );
  perform * from public.record_payment(
    p_order_id := paid_cancel_order_id,
    p_operation_id := 'paid-order-cancellation-receipt',
    p_amount_centavos := 2500,
    p_payment_method := 'CASH'
  );
  begin
    perform * from public.cancel_order(
      p_order_id := paid_cancel_order_id,
      p_operation_id := 'order:cancel:' || paid_cancel_order_id::text,
      p_reason := 'test cancellation requires refund'
    );
    raise exception 'paid order cancellation unexpectedly succeeded without refund';
  exception when object_not_in_prerequisite_state then
    null;
  end;
  begin
    perform * from public.reject_order(
      p_order_id := paid_cancel_order_id,
      p_operation_id := 'paid-order-rejection-without-refund'
    );
    raise exception 'paid order rejection unexpectedly succeeded without refund';
  exception when object_not_in_prerequisite_state then
    null;
  end;
  if (select o.status from public.orders o where o.id = paid_cancel_order_id) <> 'PENDING'
     or (select o.payment_status from public.orders o where o.id = paid_cancel_order_id) <> 'PAID'
     or (select count(*) from public.payments p where p.order_id = paid_cancel_order_id and p.status = 'PAID') <> 1 then
    raise exception 'paid order was not preserved after rejected cancellation/rejection';
  end if;

  perform * from public.record_payment(
    p_order_id := account_order_id,
    p_operation_id := 'account-partial-receipt',
    p_amount_centavos := 4000,
    p_payment_method := 'CASH'
  );
  select result.created into retry_created
  from public.record_payment(
    p_order_id := account_order_id,
    p_operation_id := 'account-partial-receipt',
    p_amount_centavos := 4000,
    p_payment_method := 'CASH'
  ) result;
  if retry_created or (select count(*) from public.payments p where p.order_id = account_order_id) <> 1 then
    raise exception 'replayed payment operation created a duplicate receipt';
  end if;
  if (select o.payment_method from public.orders o where o.id = account_order_id) <> 'CLIENT_ACCOUNT' then
    raise exception 'partial cash receipt must not reclassify a client-account order';
  end if;
  if (select o.payment_status from public.orders o where o.id = account_order_id) <> 'PENDING' then
    raise exception 'partial receipt must leave the account order pending';
  end if;
  if (select p.payment_method from public.payments p where p.order_id = account_order_id) <> 'CASH' then
    raise exception 'receipt row must retain its actual collection method';
  end if;

  perform * from public.record_payment(
    p_order_id := account_order_id,
    p_operation_id := 'account-final-receipt',
    p_amount_centavos := 6000,
    p_payment_method := 'GCASH'
  );
  if (select o.payment_method from public.orders o where o.id = account_order_id) <> 'CLIENT_ACCOUNT' then
    raise exception 'full receipt must not reclassify a client-account order';
  end if;
  if (select o.payment_status from public.orders o where o.id = account_order_id) <> 'PAID' then
    raise exception 'full receipt must mark the account order paid';
  end if;
  if (select p.payment_method from public.payments p where p.operation_id = 'account-final-receipt') <> 'GCASH' then
    raise exception 'final receipt row must retain its actual collection method';
  end if;
  begin
    perform * from public.record_payment(
      p_order_id := account_order_id,
      p_operation_id := 'account-overpayment',
      p_amount_centavos := 1,
      p_payment_method := 'CASH'
    );
    raise exception 'overpayment unexpectedly succeeded';
  exception when invalid_parameter_value then
    null;
  end;

  -- Assigned rider collection must fail without proof, then succeed with a
  -- private object owned by that rider and scoped to this delivered order.
  insert into auth.users (id)
  values (rider_profile_id);
  insert into public.profiles (id, display_name, phone, role)
  values (rider_profile_id, 'Local rider fixture', '+63 917 000 0001', 'RIDER');
  insert into public.organization_members (id, organization_id, profile_id, role)
  values (rider_member_id, fixture_organization_id, rider_profile_id, 'RIDER');
  insert into public.member_stations (member_id, station_id)
  values (rider_member_id, station_id);
  insert into public.riders (id, station_id, profile_id, name, phone)
  values (rider_row_id, station_id, owner_profile_id, 'Local rider fixture', '0917-000-0001');
  if (select r.profile_id from public.riders r where r.id = rider_row_id) is distinct from rider_profile_id then
    raise exception 'fleet rider did not link to the unique Rider account with the matching station and phone';
  end if;

  insert into public.orders (
    id, station_id, customer_id, rider_id, order_number, customer_name,
    delivery_address, delivery_mode, subtotal_centavos, total_centavos,
    payment_method, payment_status, status, order_source
  ) values
    (
      rider_account_order_id, station_id, saved_customer_id, rider_row_id,
      'PAYMENT-REGRESSION-RIDER-ACCOUNT', 'Saved customer fixture',
      'Local test address', 'DELIVERY', 5000, 5000,
      'CASH_ON_DELIVERY', 'PENDING', 'DELIVERED', 'OWNER_WALK_IN'
    ),
    (
      pre_delivery_rider_account_order_id, station_id, saved_customer_id, rider_row_id,
      'PAYMENT-REGRESSION-RIDER-ACCOUNT-PREDELIVERY', 'Saved customer fixture',
      'Local test address', 'DELIVERY', 5000, 5000,
      'CASH_ON_DELIVERY', 'PENDING', 'READY_FOR_PICKUP', 'OWNER_WALK_IN'
    ),
    (
      unlinked_rider_account_order_id, station_id, null, rider_row_id,
      'PAYMENT-REGRESSION-RIDER-ACCOUNT-UNLINKED', 'Guest fixture',
      'Local test address', 'DELIVERY', 5000, 5000,
      'CASH_ON_DELIVERY', 'PENDING', 'DELIVERED', 'OWNER_WALK_IN'
    ),
    (
      plan_guard_account_order_id, station_id, saved_customer_id, rider_row_id,
      'PAYMENT-REGRESSION-RIDER-ACCOUNT-EXPIRED', 'Saved customer fixture',
      'Local test address', 'DELIVERY', 5000, 5000,
      'CASH_ON_DELIVERY', 'PENDING', 'DELIVERED', 'OWNER_WALK_IN'
    );

  update public.orders
  set rider_id = rider_row_id
  where id = delivery_order_id;
  update public.orders
  set status = 'DELIVERED'
  where id = delivery_order_id;

  insert into auth.users (id) values (other_rider_profile_id);
  insert into public.profiles (id, display_name, phone, role)
  values (other_rider_profile_id, 'Unassigned rider fixture', '09170000002', 'RIDER');
  insert into public.organization_members (id, organization_id, profile_id, role)
  values (other_rider_member_id, fixture_organization_id, other_rider_profile_id, 'RIDER');
  insert into public.member_stations (member_id, station_id)
  values (other_rider_member_id, station_id);
  insert into public.riders (id, station_id, profile_id, name, phone)
  values (other_rider_row_id, station_id, other_rider_profile_id, 'Unassigned rider fixture', '09170000002');
  perform pg_catalog.set_config('request.jwt.claim.sub', other_rider_profile_id::text, true);
  begin
    perform public.charge_order_to_client_account(rider_account_order_id);
    raise exception 'unassigned rider unexpectedly charged a client account';
  exception when insufficient_privilege then
    null;
  end;
  if (select o.payment_method from public.orders o where o.id = rider_account_order_id) <> 'CASH_ON_DELIVERY' then
    raise exception 'unassigned rider changed the order payment method';
  end if;
  begin
    perform * from public.record_payment(
      p_order_id := delivery_order_id,
      p_operation_id := 'unassigned-rider-cannot-collect',
      p_amount_centavos := 10000,
      p_payment_method := 'CASH'
    );
    raise exception 'unassigned rider payment unexpectedly succeeded';
  exception when insufficient_privilege then
    null;
  end;
  if exists (select 1 from public.payments p where p.order_id = delivery_order_id) then
    raise exception 'unassigned rider payment persisted';
  end if;

  perform pg_catalog.set_config('request.jwt.claim.sub', rider_profile_id::text, true);

  begin
    perform public.charge_order_to_client_account(pre_delivery_rider_account_order_id);
    raise exception 'assigned rider unexpectedly charged an order before delivery';
  exception when insufficient_privilege then
    null;
  end;
  if (select o.payment_method from public.orders o where o.id = pre_delivery_rider_account_order_id) <> 'CASH_ON_DELIVERY' then
    raise exception 'pre-delivery rider account charge changed the payment method';
  end if;

  begin
    perform public.charge_order_to_client_account(unlinked_rider_account_order_id);
    raise exception 'rider unexpectedly charged an order without a saved customer';
  exception when object_not_in_prerequisite_state then
    null;
  end;
  if (select o.payment_method from public.orders o where o.id = unlinked_rider_account_order_id) <> 'CASH_ON_DELIVERY' then
    raise exception 'unlinked rider order changed its payment method';
  end if;

  perform public.charge_order_to_client_account(rider_account_order_id);
  if (select o.payment_method from public.orders o where o.id = rider_account_order_id) <> 'CLIENT_ACCOUNT' then
    raise exception 'assigned rider could not charge a linked delivered order to client balance';
  end if;
  if (select o.payment_status from public.orders o where o.id = rider_account_order_id) <> 'PENDING' then
    raise exception 'rider account charge must remain unpaid';
  end if;
  if exists (select 1 from public.payments p where p.order_id = rider_account_order_id) then
    raise exception 'rider account charge must not manufacture a paid receipt';
  end if;

  begin
    perform * from public.record_payment(
      p_order_id := delivery_order_id,
      p_operation_id := 'walkin-payment-no-proof',
      p_amount_centavos := 10000,
      p_payment_method := 'CASH',
      p_metadata := '{}'::jsonb
    );
    raise exception 'rider payment without proof unexpectedly succeeded';
  exception when insufficient_privilege then
    null;
  end;
  if exists (select 1 from public.payments p where p.order_id = delivery_order_id) then
    raise exception 'rejected rider payment without proof persisted';
  end if;

  proof_path := 'organizations/' || fixture_organization_id || '/stations/' || station_id ||
    '/delivery-payment-proofs/' || delivery_order_id || '/' || proof_id || '.jpg';
  perform pg_catalog.set_config('request.jwt.claim.sub', rider_profile_id::text, true);
  if not app_private.storage_delivery_payment_proof_allowed(proof_path) then
    raise exception 'assigned rider cannot pass the payment-proof Storage predicate';
  end if;
  perform pg_catalog.set_config('request.jwt.claim.sub', other_rider_profile_id::text, true);
  if app_private.storage_delivery_payment_proof_allowed(proof_path) then
    raise exception 'another station rider passed the assigned rider payment-proof Storage predicate';
  end if;
  perform pg_catalog.set_config('request.jwt.claim.sub', rider_profile_id::text, true);

  insert into storage.objects (bucket_id, name, owner_id, metadata)
  values ('payment-assets', proof_path, rider_profile_id::text, '{"mimetype":"image/jpeg"}'::jsonb);

  select result.status, result.amount_centavos
  into receipt_status, receipt_amount
  from public.record_payment(
    p_order_id := delivery_order_id,
    p_operation_id := 'walkin-payment-with-proof',
    p_amount_centavos := 10000,
    p_payment_method := 'CASH',
    p_metadata := jsonb_build_object('delivery_payment_proof_object_path', proof_path)
  ) result;
  if receipt_status <> 'PAID' or receipt_amount <> 10000 then
    raise exception 'proof-backed rider payment did not return a paid receipt';
  end if;
  if (select o.payment_status from public.orders o where o.id = delivery_order_id) <> 'PAID' then
    raise exception 'proof-backed receipt did not update order payment status';
  end if;

  update public.subscriptions
  set status = 'EXPIRED', current_period_end = now_ms - 1, trial_ends_at = null,
      grace_period_ends_at = null, updated_at = now()
  where public.subscriptions.organization_id = fixture_organization_id;
  perform pg_catalog.set_config('request.jwt.claim.sub', rider_profile_id::text, true);
  begin
    perform public.charge_order_to_client_account(plan_guard_account_order_id);
    raise exception 'expired subscription unexpectedly allowed a rider client-account charge';
  exception when insufficient_privilege then
    null;
  end;
  if (select o.payment_method from public.orders o where o.id = plan_guard_account_order_id) <> 'CASH_ON_DELIVERY' then
    raise exception 'expired subscription changed the rider order payment method';
  end if;
  perform pg_catalog.set_config('request.jwt.claim.sub', owner_profile_id::text, true);
  plan_entitlements := public.get_effective_entitlements();
  if plan_entitlements->>'plan_code' <> 'FREE' or plan_entitlements->>'status' <> 'FREE' then
    raise exception 'expired paid subscription still exposed paid entitlements: %', plan_entitlements;
  end if;
  if (select s.status from public.subscriptions s where s.organization_id = fixture_organization_id) <> 'EXPIRED' then
    raise exception 'effective Free access changed the historical paid billing row';
  end if;
end;
$$;

select extensions.ok(true, 'delivery and client-account walk-ins remain unpaid until a guarded receipt is recorded');
select * from extensions.finish();

rollback;
