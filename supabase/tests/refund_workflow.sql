-- Isolated database-only coverage. Fixtures roll back; no external payment is
-- initiated. External confirmation is tested as an Owner attestation contract.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(8);

select extensions.ok(not has_table_privilege('authenticated', 'public.payment_refunds', 'insert')
  and not has_table_privilege('authenticated', 'public.payment_refunds', 'update')
  and not has_table_privilege('authenticated', 'public.payment_refunds', 'delete'),
  'refund rows cannot be forged or rewritten through direct table access');
select extensions.ok(not has_table_privilege('authenticated', 'public.payments', 'insert')
  and not has_table_privilege('authenticated', 'public.payments', 'update')
  and not has_table_privilege('authenticated', 'public.payments', 'delete'),
  'original payment receipts remain RPC-only');
select extensions.ok(has_function_privilege('authenticated', 'public.request_payment_refund(uuid,text,bigint,text)', 'execute')
  and has_function_privilege('authenticated', 'public.complete_payment_refund(uuid,text,boolean,text)', 'execute')
  and has_function_privilege('authenticated', 'public.fail_payment_refund(uuid,text,text)', 'execute'),
  'authenticated clients can use the guarded refund lifecycle RPCs');
select extensions.ok(not has_function_privilege('anon', 'public.request_payment_refund(uuid,text,bigint,text)', 'execute')
  and not has_function_privilege('anon', 'public.cancel_order_with_refunds(uuid,text,text)', 'execute'),
  'anonymous callers cannot create refunds or cancel paid orders');
select extensions.ok((select relrowsecurity from pg_class where oid = 'public.payment_refunds'::regclass)
  and (select relrowsecurity from pg_class where oid = 'public.order_return_events'::regclass),
  'refund and physical-return ledgers have RLS enabled');
select extensions.ok(has_function_privilege('authenticated', 'public.list_order_refunds_for_orders(uuid[])', 'execute')
  and not has_function_privilege('anon', 'public.list_order_refunds_for_orders(uuid[])', 'execute'),
  'refund report batching is authenticated and guarded');
select extensions.ok(
  (select count(*) = 5 from pg_trigger where not tgisinternal and tgenabled <> 'D'
    and tgname in ('payment_receipts_immutable', 'refund_events_immutable', 'cancellation_events_immutable', 'return_events_immutable', 'return_items_immutable'))
  and not has_table_privilege('service_role', 'public.payments', 'truncate'),
  'receipt and append-only financial history cannot be rewritten or truncated by API roles');

do $$
declare
  org_id uuid := gen_random_uuid();
  v_station_id uuid := gen_random_uuid();
  owner_id uuid := gen_random_uuid();
  owner_member_id uuid := gen_random_uuid();
  staff_id uuid := gen_random_uuid();
  staff_member_id uuid := gen_random_uuid();
  customer_id uuid := gen_random_uuid();
  other_org_id uuid := gen_random_uuid();
  other_station_id uuid := gen_random_uuid();
  other_owner_id uuid := gen_random_uuid();
  other_member_id uuid := gen_random_uuid();
  v_product_id uuid := gen_random_uuid();
  partial_order_id uuid := gen_random_uuid();
  delivery_order_id uuid := gen_random_uuid();
  full_refund_order_id uuid := gen_random_uuid();
  customer_order_id uuid := gen_random_uuid();
  cash_payment_id uuid := gen_random_uuid();
  delivery_payment_id uuid := gen_random_uuid();
  full_refund_payment_id uuid := gen_random_uuid();
  refund_row record;
  duplicate_row record;
  cancellation_row record;
  return_row record;
  return_items jsonb;
  balance_before integer;
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  insert into auth.users (id) values (owner_id), (staff_id), (other_owner_id), (customer_id);
  insert into public.organizations (id, name) values (org_id, 'Refund test tenant'), (other_org_id, 'Refund isolation tenant');
  insert into public.profiles (id, display_name, role, preferred_business_id) values
    (owner_id, 'Refund owner test', 'OWNER', org_id),
    (staff_id, 'Refund staff test', 'STAFF', org_id),
    (other_owner_id, 'Other owner test', 'OWNER', other_org_id),
    (customer_id, 'Customer test', 'CUSTOMER', null);
  insert into public.stations (id, organization_id, name, address) values
    (v_station_id, org_id, 'Refund test station', 'Local fixture'),
    (other_station_id, other_org_id, 'Other test station', 'Local fixture');
  insert into public.subscriptions (
    organization_id, plan_id, status, billing_cycle, current_period_start, current_period_end, updated_at
  )
  select org_id, p.id, 'ACTIVE', 'MONTHLY', now_ms, now_ms + 2592000000, now()
  from public.subscription_plans p where p.code = 'BUSINESS';
  insert into public.organization_members (id, organization_id, profile_id, role) values
    (owner_member_id, org_id, owner_id, 'OWNER'),
    (staff_member_id, org_id, staff_id, 'STAFF'),
    (other_member_id, other_org_id, other_owner_id, 'OWNER');
  insert into public.member_stations (member_id, station_id) values
    (owner_member_id, v_station_id), (staff_member_id, v_station_id), (other_member_id, other_station_id);
  insert into public.subscriptions (
    organization_id, plan_id, status, billing_cycle, current_period_start, current_period_end, updated_at
  )
  select other_org_id, p.id, 'ACTIVE', 'MONTHLY', now_ms, now_ms + 2592000000, now()
  from public.subscription_plans p where p.code = 'BUSINESS';
  insert into public.products (id, station_id, name, product_type, size_label, price_centavos, stock_quantity)
  values (v_product_id, v_station_id, 'Return test item', 'REFILL', '5 gallon', 250, 8);
  insert into public.inventory_balances (station_id, product_id, quantity)
  values (v_station_id, v_product_id, 6);

  -- CUSTOMER_APP rows must be inserted under the matching test customer JWT;
  -- the production identity trigger intentionally rejects an unset auth.uid().
  perform set_config('request.jwt.claim.sub', customer_id::text, true);
  insert into public.orders (
    id, station_id, customer_uid, order_number, customer_name, delivery_address, delivery_mode,
    subtotal_centavos, total_centavos, payment_method, payment_status, status, order_source
  ) values
    (partial_order_id, v_station_id, null, 'REFUND-PARTIAL-TEST', 'Local test customer',
     'Station pickup', 'PICKUP', 1000, 1000, 'CASH', 'PAID', 'PREPARING', 'LEGACY_IMPORT'),
    (delivery_order_id, v_station_id, customer_id, 'REFUND-DELIVERY-TEST', 'Local test customer',
     'Local delivery address', 'DELIVERY', 500, 500, 'CASH_ON_DELIVERY', 'PAID', 'OUT_FOR_DELIVERY', 'CUSTOMER_APP'),
    (full_refund_order_id, v_station_id, null, 'REFUND-FULL-TEST', 'Local test customer',
     'Station pickup', 'PICKUP', 1000, 1000, 'CASH', 'PAID', 'COMPLETED', 'LEGACY_IMPORT');
  insert into public.orders (
    id, station_id, customer_uid, order_number, customer_name, delivery_address,
    delivery_mode, subtotal_centavos, total_centavos, payment_method, payment_status,
    status, order_source
  ) values (
    customer_order_id, v_station_id, customer_id, 'REFUND-CUSTOMER-CANCEL-TEST',
    'Customer test', 'Station pickup', 'PICKUP', 250, 250, 'CASH', 'PENDING',
    'PENDING', 'CUSTOMER_APP'
  );
  insert into public.order_items (
    order_id, product_id, product_name_snapshot, product_type_snapshot,
    size_label_snapshot, unit_price_centavos, quantity, subtotal_centavos
  ) values
    (partial_order_id, v_product_id, 'Return test item', 'REFILL', '5 gallon', 250, 4, 1000),
    (delivery_order_id, v_product_id, 'Return test item', 'REFILL', '5 gallon', 250, 2, 500),
    (full_refund_order_id, v_product_id, 'Return test item', 'REFILL', '5 gallon', 250, 4, 1000);
  insert into public.payments (
    id, organization_id, station_id, order_id, payment_method,
    amount_centavos, status, operation_id, created_at, paid_at, updated_at
  ) values
    (cash_payment_id, org_id, v_station_id, partial_order_id, 'CASH', 600, 'PAID',
     'refund-test-payment-partial', now(), now(), now()),
    (delivery_payment_id, org_id, v_station_id, delivery_order_id, 'CASH_ON_DELIVERY', 500, 'PAID',
     'refund-test-payment-delivery', now(), now(), now()),
    (full_refund_payment_id, org_id, v_station_id, full_refund_order_id, 'CASH', 1000, 'PAID',
     'refund-test-payment-full', now(), now(), now());

  perform set_config('request.jwt.claim.sub', owner_id::text, true);
  select * into refund_row from public.request_payment_refund(
    cash_payment_id, 'refund-test-partial-operation', 200, 'Partial refund test'
  );
  if refund_row.status <> 'PENDING' or refund_row.amount_centavos <> 200 or not refund_row.created then
    raise exception 'partial refund was not persisted as a pending 200-centavo request';
  end if;
  select * into duplicate_row from public.request_payment_refund(
    cash_payment_id, 'refund-test-partial-operation', 200, 'Partial refund test'
  );
  if duplicate_row.created or duplicate_row.refund_id <> refund_row.refund_id then
    raise exception 'retry created a second refund instead of returning the original';
  end if;
  begin
    perform * from public.request_payment_refund(
      cash_payment_id, 'refund-test-over-refund', 401, 'Must exceed remaining amount'
    );
    raise exception 'refund above the unrefunded receipt amount unexpectedly succeeded';
  exception when invalid_parameter_value then null;
  end;

  perform set_config('request.jwt.claim.sub', staff_id::text, true);
  begin
    perform * from public.request_payment_refund(
      cash_payment_id, 'refund-test-staff-denied', 100, 'Staff must not refund'
    );
    raise exception 'station staff unexpectedly requested a refund';
  exception when insufficient_privilege then null;
  end;
  perform set_config('request.jwt.claim.sub', other_owner_id::text, true);
  begin
    perform * from public.request_payment_refund(
      cash_payment_id, 'refund-test-cross-tenant-denied', 100, 'Other tenant must not refund'
    );
    raise exception 'another tenant owner unexpectedly requested a refund';
  exception when insufficient_privilege then null;
  end;
  begin
    perform * from public.list_order_refunds_for_orders(array[partial_order_id]);
    raise exception 'another tenant owner unexpectedly read refund history';
  exception when insufficient_privilege then null;
  end;

  perform set_config('request.jwt.claim.sub', owner_id::text, true);
  select * into refund_row from public.complete_payment_refund(
    refund_row.refund_id, 'refund-test-cash-confirmation', true, null
  );
  if refund_row.status <> 'COMPLETED' or not refund_row.created then
    raise exception 'cash refund did not complete after explicit confirmation';
  end if;
  select * into duplicate_row from public.complete_payment_refund(
    refund_row.refund_id, 'refund-test-cash-confirmation', true, null
  );
  if duplicate_row.created then raise exception 'duplicate refund confirmation was not idempotent'; end if;

  select * into refund_row from public.request_payment_refund(
    cash_payment_id, 'refund-test-failed-operation', 100, 'Refund transfer failed'
  );
  select * into refund_row from public.fail_payment_refund(
    refund_row.refund_id, 'refund-test-failure-confirmation', 'Cash was not handed back'
  );
  if refund_row.status <> 'FAILED' then raise exception 'failed refund state was not recorded'; end if;
  select * into refund_row from public.request_payment_refund(
    cash_payment_id, 'refund-test-retry-after-failure', 400, 'Retry remaining refund'
  );
  if refund_row.status <> 'PENDING' or refund_row.amount_centavos <> 400 then
    raise exception 'failed refund did not release its reserved amount';
  end if;

  select quantity into balance_before from public.inventory_balances
  where station_id = v_station_id and product_id = v_product_id;
  select * into cancellation_row from public.cancel_order_with_refunds(
    delivery_order_id, 'refund-test-delivery-cancel', 'Customer cancelled during delivery'
  );
  if cancellation_row.status <> 'CANCELLED'
     or cancellation_row.refund_requested_centavos <> 500
     or not cancellation_row.inventory_return_required then
    raise exception 'paid out-for-delivery cancellation did not request refund and defer stock return';
  end if;
  if (select quantity from public.inventory_balances b
      where b.station_id = v_station_id and b.product_id = v_product_id) <> balance_before then
    raise exception 'out-for-delivery cancellation incorrectly restored inventory before physical return';
  end if;
  if (select status from public.payment_refunds r where r.original_payment_id = delivery_payment_id) <> 'PENDING' then
    raise exception 'cancellation marked money refunded before confirmation';
  end if;
  if not exists (
    select 1 from public.notification_outbox n
    where n.event_type = 'ORDER_CANCELLED' and n.entity_id = delivery_order_id
      and n.recipient_profile_id = customer_id and n.payload ->> 'cancellation_reason' = 'Customer cancelled during delivery'
  ) then
    raise exception 'owner cancellation did not enqueue the online customer notification';
  end if;
  perform set_config('request.jwt.claim.sub', customer_id::text, true);
  select * into duplicate_row from public.list_order_refunds_for_orders(array[delivery_order_id]);
  if duplicate_row.status <> 'PENDING' or duplicate_row.original_payment_id is not null
     or duplicate_row.operation_id is not null or duplicate_row.external_reference is not null then
    raise exception 'customer refund read exposed owner-only ledger details or missed status';
  end if;
  select * into cancellation_row from public.cancel_order(
    customer_order_id, 'customer-legacy-cancel', 'Customer changed their mind'
  );
  if cancellation_row.status <> 'CANCELLED' then
    raise exception 'legacy Customer cancel_order signature no longer cancels an eligible unpaid order';
  end if;
  perform set_config('request.jwt.claim.sub', owner_id::text, true);

  return_items := jsonb_build_array(jsonb_build_object('product_id', v_product_id, 'quantity', 1));
  begin
    perform * from public.confirm_cancelled_order_return(
      delivery_order_id, 'refund-test-invalid-return', jsonb_build_array(
        jsonb_build_object('product_id', v_product_id, 'quantity', 3)
      ), 'Invalid over-return'
    );
    raise exception 'return quantity above sold quantity unexpectedly succeeded';
  exception when invalid_parameter_value then null;
  end;
  select * into return_row from public.confirm_cancelled_order_return(
    delivery_order_id, 'refund-test-return-one', return_items, 'Rider returned one item'
  );
  if not return_row.created or return_row.inventory_return_completed then
    raise exception 'partial physical return completion state is incorrect';
  end if;
  select * into duplicate_row from public.confirm_cancelled_order_return(
    delivery_order_id, 'refund-test-return-one', return_items, 'Rider returned one item'
  );
  if duplicate_row.created then raise exception 'duplicate physical return operation changed stock'; end if;
  select * into return_row from public.confirm_cancelled_order_return(
    delivery_order_id, 'refund-test-return-two', return_items, 'Rider returned final item'
  );
  if not return_row.inventory_return_completed then
    raise exception 'inventory return did not complete after all sold items were recorded';
  end if;
  if (select quantity from public.inventory_balances b
      where b.station_id = v_station_id and b.product_id = v_product_id) <> balance_before + 2 then
    raise exception 'physical return quantity was not applied exactly once';
  end if;

  select * into refund_row from public.request_payment_refund(
    full_refund_payment_id, 'refund-test-partial-full-order', 200, 'Partial refund status test'
  );
  perform * from public.complete_payment_refund(
    refund_row.refund_id, 'refund-test-partial-full-order-complete', true, null
  );
  if (select payment_status from public.orders where id = full_refund_order_id) <> 'PAID' then
    raise exception 'partial refund incorrectly changed an originally fully paid order to pending';
  end if;
  select * into refund_row from public.request_payment_refund(
    full_refund_payment_id, 'refund-test-final-full-order', 800, 'Final refund status test'
  );
  perform * from public.complete_payment_refund(
    refund_row.refund_id, 'refund-test-final-full-order-complete', true, null
  );
  if (select payment_status from public.orders where id = full_refund_order_id) <> 'REFUNDED' then
    raise exception 'fully refunded order did not reach the legacy-compatible refunded state';
  end if;
end;
$$;

select extensions.ok(true, 'partial/full refunds, roles, idempotency, cancellation and physical-return inventory invariants hold');
select * from extensions.finish();
rollback;
