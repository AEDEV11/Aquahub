-- Local-only integration coverage for guest order integrity and protected tracking.
-- Fixtures are transactional and rolled back after this file completes.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(5);

do $$
declare
  v_organization_id uuid := gen_random_uuid();
  v_station_id uuid := gen_random_uuid();
  v_product_id uuid := gen_random_uuid();
  v_owner_id uuid := gen_random_uuid();
  v_owner_member_id uuid := gen_random_uuid();
  v_foreign_station_id uuid := gen_random_uuid();
  v_foreign_product_id uuid := gen_random_uuid();
  v_unrelated_customer_id uuid := '99999999-9999-4999-8999-999999999999';
  operation_id text := 'guest:11111111-1111-4111-8111-111111111111';
  tracking_token text := repeat('T', 43);
  order_request jsonb;
  second_order_request jsonb;
  limit_order_request jsonb;
  feature_order_request jsonb;
  result jsonb;
  second_result jsonb;
  limit_result jsonb;
  feature_result jsonb;
  retry_result jsonb;
  resume_result jsonb;
  tracking jsonb;
  err text;
  current_quantity integer;
  first_customer_id uuid;
  second_customer_id uuid;
  other_station_customer_id uuid;
  limit_customer_id uuid;
  rate_row record;
  index integer;
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  insert into public.organizations (id, name)
  values (v_organization_id, 'Guest web ordering test fixture');
  insert into auth.users (id) values (v_owner_id);
  insert into public.profiles (id, display_name, role, preferred_business_id)
  values (v_owner_id, 'Guest order owner fixture', 'OWNER', v_organization_id);
  insert into public.organization_members (id, organization_id, profile_id, role)
  values (v_owner_member_id, v_organization_id, v_owner_id, 'OWNER');
  insert into public.subscriptions (
    organization_id, plan_id, status, billing_cycle,
    current_period_start, current_period_end
  )
  select v_organization_id, p.id, 'ACTIVE', 'MONTHLY', now_ms, now_ms + 2592000000
  from public.subscription_plans p where p.code = 'BUSINESS';
  insert into public.stations (
    id, organization_id, name, address, latitude, longitude,
    delivery_radius_km, delivery_fee_centavos, public_station_code,
    business_rules_text, business_rules_version,
    cash_payment_enabled, cod_payment_enabled, gcash_payment_enabled, maya_payment_enabled
  ) values (
    v_station_id, v_organization_id, 'Guest fixture station', '1 Test Road, Manila',
    14.5995, 120.9842, 10, 1500, 'GUEST001',
    'Please confirm your delivery address.', 3,
    false, true, false, true
  );
  insert into public.member_stations (member_id, station_id)
  values (v_owner_member_id, v_station_id);
  insert into public.stations (
    id, organization_id, name, address, public_station_code
  ) values (
    v_foreign_station_id, v_organization_id, 'Other station fixture', '2 Test Road, Manila', 'GUEST002'
  );
  insert into public.products (
    id, station_id, name, product_type, size_label, price_centavos,
    is_active, is_available, promotion_label, promotion_type,
    promotional_price_centavos, promotion_minimum_quantity, promotion_is_active
  ) values (
    v_product_id, v_station_id, 'Purified refill', 'REFILL', '5 gal', 5000,
    true, true, '2 or more', 'QUANTITY_BREAK', 4000, 2, true
  );
  insert into public.products (
    id, station_id, name, product_type, size_label, price_centavos, is_active, is_available
  ) values (
    v_foreign_product_id, v_foreign_station_id, 'Other station refill', 'REFILL', '5 gal', 4000, true, true
  );
  insert into auth.users (id) values (v_unrelated_customer_id);
  insert into public.profiles (id, display_name, role, preferred_business_id)
  values (v_unrelated_customer_id, 'Unrelated guest-order fixture', 'CUSTOMER', null);
  insert into public.inventory_balances (station_id, product_id, quantity)
  values (v_station_id, v_product_id, 20);

  perform set_config('request.jwt.claims', '{"role":"service_role"}', true);
  order_request := jsonb_build_object(
    'operation_id', operation_id,
    'request_fingerprint', repeat('a', 64),
    'customer_name', 'Guest Customer',
    'customer_phone', '+639171234567',
    'delivery_mode', 'DELIVERY',
    'delivery_address', '1 Test Road, Barangay Central, Manila',
    'delivery_place_id', null,
    'latitude', 14.5995,
    'longitude', 120.9842,
    'payment_method', 'COD',
    'customer_note', null,
    'accepted_rules_version', '3',
    'items', jsonb_build_array(jsonb_build_object('product_id', v_product_id, 'quantity', 2))
  );

  order_request := jsonb_set(order_request, '{items,0,container_source}', to_jsonb('STATION_BORROW'::text));
  result := public.create_guest_order_with_container_sources('GUEST001', order_request, tracking_token);
  if result ->> 'created' is distinct from 'true' then
    raise exception 'guest request did not create one order';
  end if;
  if (result ->> 'total_centavos')::bigint is distinct from 9500::bigint then
    raise exception 'promotion and station delivery fee did not use server centavos';
  end if;
  if not exists (
    select 1 from public.orders o
    where o.id = (result ->> 'order_id')::uuid
      and o.customer_uid is null and o.order_source = 'CUSTOMER_WEB'
      and o.status = 'PENDING' and o.payment_status = 'PENDING'
      and o.customer_name = 'Guest Customer' and o.customer_phone = '+639171234567'
      and o.customer_id is not null
  ) then
    raise exception 'guest order does not link its station customer or match the Owner app fields';
  end if;
  select o.customer_id into first_customer_id
    from public.orders o where o.id = (result ->> 'order_id')::uuid;
  if not exists (
    select 1 from public.customers c
    where c.id = first_customer_id and c.station_id = v_station_id
      and c.name = 'Guest Customer' and c.phone = '+639171234567'
      and c.total_orders = 1
  ) then
    raise exception 'guest customer was not created with exactly one linked order';
  end if;
  if not exists (
    select 1 from public.order_items i
    where i.order_id = (result ->> 'order_id')::uuid
      and i.product_id = v_product_id and i.unit_price_centavos = 4000 and i.quantity = 2
      and i.container_source = 'STATION_BORROW'
  ) then
    raise exception 'order item did not snapshot the active promotion and container choice';
  end if;

  retry_result := public.create_guest_order_with_container_sources('GUEST001', order_request, tracking_token);
  if retry_result ->> 'created' is distinct from 'false'
    or retry_result ->> 'order_id' is distinct from result ->> 'order_id' then
    raise exception 'same operation and payload did not return the original order';
  end if;
  if (select c.total_orders from public.customers c where c.id = first_customer_id) is distinct from 1 then
    raise exception 'an idempotent retry incremented the customer order count';
  end if;

  second_order_request := jsonb_set(
    jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:12111111-1111-4111-8111-111111111111'::text)),
      '{request_fingerprint}', to_jsonb(repeat('b', 64))
    ),
    '{items,0,quantity}', '1'::jsonb
  );
  second_result := public.create_guest_order_with_container_sources(
    'GUEST001', second_order_request, repeat('J', 43)
  );
  select o.customer_id into second_customer_id
    from public.orders o where o.id = (second_result ->> 'order_id')::uuid;
  if second_result ->> 'created' is distinct from 'true'
    or second_customer_id is distinct from first_customer_id
    or (select c.total_orders from public.customers c where c.id = first_customer_id) is distinct from 2 then
    raise exception 'a later guest order did not reuse its station customer and increment its count once';
  end if;

  -- Simulate legacy rows created before auto-linking. The idempotent backfill
  -- should link all matching orders and count each once.
  update public.orders
     set customer_id = null
   where id in ((result ->> 'order_id')::uuid, (second_result ->> 'order_id')::uuid);
  update public.customers set total_orders = 0 where id = first_customer_id;
  perform app_private.backfill_guest_web_order_customers();
  if exists (
    select 1 from public.orders o
    where o.id in ((result ->> 'order_id')::uuid, (second_result ->> 'order_id')::uuid)
      and o.customer_id is distinct from first_customer_id
  ) or (select c.total_orders from public.customers c where c.id = first_customer_id) is distinct from 2 then
    raise exception 'legacy web-order backfill did not link and count matching orders';
  end if;
  perform app_private.backfill_guest_web_order_customers();
  if (select c.total_orders from public.customers c where c.id = first_customer_id) is distinct from 2 then
    raise exception 're-running the legacy backfill changed the customer order count';
  end if;

  other_station_customer_id := app_private.get_or_create_guest_station_customer(
    v_foreign_station_id, 'Same Phone, Other Station', '+639171234567'
  );
  if other_station_customer_id is null
    or other_station_customer_id = first_customer_id
    or not exists (
      select 1 from public.customers c
      where c.id = other_station_customer_id and c.station_id = v_foreign_station_id
    ) then
    raise exception 'guest customer matching crossed a station boundary';
  end if;

  resume_result := public.resume_guest_order(operation_id, repeat('a', 64), tracking_token);
  if resume_result ->> 'created' is distinct from 'false'
    or resume_result ->> 'order_id' is distinct from result ->> 'order_id' then
    raise exception 'lost-response recovery did not return the committed order';
  end if;
  begin
    perform public.create_guest_order(
      'GUEST001', jsonb_set(
        jsonb_set(order_request, '{items,0,quantity}', '3'::jsonb),
        '{request_fingerprint}', to_jsonb(repeat('b', 64))
      ), tracking_token
    );
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%different order request%' then
    raise exception 'operation reuse with a different payload was not rejected: %', err;
  end if;
  begin
    perform public.resume_guest_order(operation_id, repeat('b', 64), tracking_token);
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%different order request%' then
    raise exception 'retry recovery accepted a changed payload: %', err;
  end if;

  begin
    perform public.create_guest_order_with_container_sources('GUEST001', jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:dddddddd-dddd-4ddd-8ddd-dddddddddddd'::text)),
      '{items,0,container_source}', to_jsonb('UNKNOWN_SOURCE'::text)
    ), repeat('H', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%container option is invalid%' then
    raise exception 'an unsupported container choice was accepted: %', err;
  end if;

  select b.quantity into current_quantity from public.inventory_balances b
  where b.station_id = v_station_id and b.product_id = v_product_id;
  if current_quantity is distinct from 17 then
    raise exception 'stock was not decremented exactly once (quantity: %)', current_quantity;
  end if;
  if not (
    select count(*) = 1 and bool_and(created_by is null)
    from public.inventory_movements m where m.order_id = (result ->> 'order_id')::uuid
  ) then
    raise exception 'guest stock movement fabricated an actor or has incorrect count';
  end if;

  tracking := public.get_guest_order_status((result ->> 'order_id')::uuid, tracking_token);
  if not (tracking ? 'status' and tracking ? 'total_centavos'
    and not (tracking ? 'customer_phone') and not (tracking ? 'delivery_address')) then
    raise exception 'tracking token did not return a minimal non-private order projection';
  end if;
  begin
    perform public.get_guest_order_status((result ->> 'order_id')::uuid, repeat('X', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%tracking is unavailable%' then
    raise exception 'incorrect tracking token was not rejected: %', err;
  end if;

  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:22222222-2222-4222-8222-222222222222'::text)),
      '{latitude}', '15.5'::jsonb
    ), repeat('U', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%outside this station%' then
    raise exception 'out-of-radius delivery was not rejected server-side: %', err;
  end if;

  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:44444444-4444-4444-8444-444444444444'::text)),
      '{items,0,product_id}', to_jsonb(v_foreign_product_id)
    ), repeat('W', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%product is unavailable%' then
    raise exception 'a product from another station was accepted: %', err;
  end if;

  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:55555555-5555-4555-8555-555555555555'::text)),
      '{delivery_address}', to_jsonb('123'::text)
    ), repeat('A', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%delivery address%' then
    raise exception 'a short delivery address was accepted: %', err;
  end if;

  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:66666666-6666-4666-8666-666666666666'::text)),
      '{accepted_rules_version}', to_jsonb('2'::text)
    ), repeat('B', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%ordering rules changed%' then
    raise exception 'an order with a stale rules version was accepted: %', err;
  end if;

  perform set_config(
    'request.jwt.claims',
    jsonb_build_object('sub', v_owner_id, 'role', 'authenticated')::text,
    true
  );
  perform public.update_station_settings(v_station_id, jsonb_build_object(
    'business_rules_text', 'Updated fixture rules; confirm before ordering.'
  ));
  if not exists (
    select 1 from public.stations s
    join public.public_stations ps on ps.station_id = s.id
    where s.id = v_station_id
      and s.business_rules_version = 4
      and ps.business_rules_version = 4
      and s.business_rules_text = 'Updated fixture rules; confirm before ordering.'
      and ps.business_rules_text = s.business_rules_text
  ) then
    raise exception 'editing station rules did not increment and project the rules version';
  end if;
  perform public.update_station_settings(v_station_id, jsonb_build_object(
    'business_rules_text', 'Updated fixture rules; confirm before ordering.'
  ));
  if (select business_rules_version from public.stations where id = v_station_id) is distinct from 4 then
    raise exception 'saving unchanged station rules incremented the version';
  end if;
  perform set_config('request.jwt.claims', '{"role":"service_role"}', true);
  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:99999999-9999-4999-8999-999999999998'::text)),
      '{request_fingerprint}', to_jsonb(repeat('f', 64))
    ), repeat('Z', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%ordering rules changed%' then
    raise exception 'a checkout accepted before a rules edit was still accepted: %', err;
  end if;
  order_request := jsonb_set(order_request, '{accepted_rules_version}', to_jsonb('4'::text));

  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      jsonb_set(jsonb_set(order_request,
        '{operation_id}', to_jsonb('guest:77777777-7777-4777-8777-777777777777'::text)),
        '{latitude}', 'null'::jsonb),
      '{longitude}', 'null'::jsonb
    ), repeat('C', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%share your delivery location%' then
    raise exception 'delivery without a required location was accepted: %', err;
  end if;

  update public.stations set latitude = null, longitude = null where id = v_station_id;
  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      order_request, '{operation_id}', to_jsonb('guest:88888888-8888-4888-8888-888888888888'::text)
    ), repeat('D', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%station delivery location is unavailable%' then
    raise exception 'delivery station without valid coordinates was accepted: %', err;
  end if;
  update public.stations set latitude = 14.5995, longitude = 120.9842, delivery_radius_km = -1 where id = v_station_id;
  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      order_request, '{operation_id}', to_jsonb('guest:aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'::text)
    ), repeat('E', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%station delivery settings are unavailable%' then
    raise exception 'invalid station delivery radius was accepted: %', err;
  end if;
  update public.stations set delivery_radius_km = 10 where id = v_station_id;

  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:33333333-3333-4333-8333-333333333333'::text)),
      '{payment_method}', to_jsonb('CASH'::text)
    ), repeat('V', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%cash payment is not available%' then
    raise exception 'disabled cash payment was not rejected: %', err;
  end if;

  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'::text)),
      '{items,0,quantity}', '19'::jsonb
    ), repeat('F', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  if err not like '%insufficient inventory%' then
    raise exception 'an order exceeding available stock was accepted: %', err;
  end if;

  execute $ddl$
    create function public.test_guest_order_fail_inventory_movement()
    returns trigger language plpgsql as $body$
    begin
      if new.reason = 'Order sale' then
        raise exception using errcode = 'P0001', message = 'fixture forced inventory movement failure';
      end if;
      return new;
    end;
    $body$
  $ddl$;
  execute 'create trigger test_guest_order_fail_inventory_movement before insert on public.inventory_movements for each row execute function public.test_guest_order_fail_inventory_movement()';
  begin
    perform public.create_guest_order('GUEST001', jsonb_set(
      jsonb_set(order_request, '{operation_id}', to_jsonb('guest:cccccccc-cccc-4ccc-8ccc-cccccccccccc'::text)),
      '{items,0,quantity}', '1'::jsonb
    ), repeat('G', 43));
    err := 'no error';
  exception when others then err := sqlerrm;
  end;
  execute 'drop trigger test_guest_order_fail_inventory_movement on public.inventory_movements';
  execute 'drop function public.test_guest_order_fail_inventory_movement()';
  if err not like '%fixture forced inventory movement failure%' then
    raise exception 'fixture did not force inventory movement failure: %', err;
  end if;
  if exists (select 1 from public.orders where client_operation_id = 'guest:cccccccc-cccc-4ccc-8ccc-cccccccccccc') then
    raise exception 'failed inventory movement left an order behind';
  end if;

  select b.quantity into current_quantity from public.inventory_balances b
  where b.station_id = v_station_id and b.product_id = v_product_id;
  if current_quantity is distinct from 17 then
    raise exception 'rejected requests changed inventory (quantity: %)', current_quantity;
  end if;

  for index in 1..4 loop
    select * into rate_row from public.consume_guest_order_rate_limit(repeat('a', 64), repeat('b', 64));
    if not rate_row.allowed then raise exception 'rate limit rejected an allowed fixture attempt'; end if;
  end loop;
  select * into rate_row from public.consume_guest_order_rate_limit(repeat('a', 64), repeat('b', 64));
  if rate_row.allowed then
    raise exception 'server-side phone rate limit allowed excess attempts';
  end if;

  -- At the configured customer cap, a valid pickup checkout still succeeds
  -- and retains contact details without creating an over-limit profile.
  update public.plan_limits
     set max_value = 50
   where plan_id = (select p.id from public.subscription_plans p where p.code = 'BUSINESS')
     and limit_type = 'CUSTOMER_COUNT';
  for index in 1..48 loop
    insert into public.customers (station_id, name, phone)
    values (v_station_id, 'Customer limit fixture ' || index, '+639' || pg_catalog.lpad(index::text, 9, '0'));
  end loop;
  if (select count(*) from public.customers c
      join public.stations s on s.id = c.station_id
      where s.organization_id = v_organization_id and c.is_active and c.deleted_at is null) <> 50 then
    raise exception 'fixture did not reach the configured customer cap';
  end if;
  limit_customer_id := app_private.get_or_create_guest_station_customer(
    v_station_id, 'Guest at customer limit', '+639191234567'
  );
  if limit_customer_id is not null then
    raise exception 'customer helper exceeded the organization customer cap';
  end if;

  update public.products set promotion_is_active = false where id = v_product_id;
  limit_order_request := jsonb_set(
    jsonb_set(
      jsonb_set(
        jsonb_set(
          jsonb_set(
            jsonb_set(
              jsonb_set(order_request,
                '{operation_id}', to_jsonb('guest:eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'::text)),
                '{request_fingerprint}', to_jsonb(repeat('c', 64))
            ),
            '{customer_name}', to_jsonb('Guest at customer limit'::text)
          ),
          '{customer_phone}', to_jsonb('+639191234568'::text)
        ),
        '{delivery_mode}', to_jsonb('PICKUP'::text)
      ),
      '{delivery_address}', 'null'::jsonb
    ),
    '{items,0,quantity}', '1'::jsonb
  );
  limit_order_request := jsonb_set(limit_order_request, '{latitude}', 'null'::jsonb);
  limit_order_request := jsonb_set(limit_order_request, '{longitude}', 'null'::jsonb);
  limit_order_request := jsonb_set(limit_order_request, '{payment_method}', to_jsonb('MAYA'::text));
  limit_result := public.create_guest_order_with_container_sources(
    'GUEST001', limit_order_request, repeat('K', 43)
  );
  if limit_result ->> 'created' is distinct from 'true'
    or not exists (
      select 1 from public.orders o
      where o.id = (limit_result ->> 'order_id')::uuid
        and o.customer_id is null
        and o.customer_name = 'Guest at customer limit'
        and o.customer_phone = '+639191234568'
    ) then
    raise exception 'a full customer list blocked checkout or lost guest contact details';
  end if;

  -- A disabled customer-management entitlement must also leave a valid guest
  -- checkout available while retaining its contact details on the order.
  update public.plan_features
     set enabled = false
   where plan_id = (select p.id from public.subscription_plans p where p.code = 'BUSINESS')
     and feature = 'CUSTOMER_MANAGEMENT';
  feature_order_request := jsonb_set(
    jsonb_set(
      jsonb_set(
        jsonb_set(
          jsonb_set(
            jsonb_set(
              jsonb_set(order_request,
                '{operation_id}', to_jsonb('guest:ffffffff-ffff-4fff-8fff-ffffffffffff'::text)),
              '{request_fingerprint}', to_jsonb(repeat('d', 64))
            ),
            '{customer_name}', to_jsonb('Guest with customer feature disabled'::text)
          ),
          '{customer_phone}', to_jsonb('+639191234569'::text)
        ),
        '{delivery_mode}', to_jsonb('PICKUP'::text)
      ),
      '{delivery_address}', 'null'::jsonb
    ),
    '{items,0,quantity}', '1'::jsonb
  );
  feature_order_request := jsonb_set(feature_order_request, '{latitude}', 'null'::jsonb);
  feature_order_request := jsonb_set(feature_order_request, '{longitude}', 'null'::jsonb);
  feature_order_request := jsonb_set(feature_order_request, '{payment_method}', to_jsonb('MAYA'::text));
  feature_result := public.create_guest_order_with_container_sources(
    'GUEST001', feature_order_request, repeat('M', 43)
  );
  if feature_result ->> 'created' is distinct from 'true'
    or not exists (
      select 1 from public.orders o
      where o.id = (feature_result ->> 'order_id')::uuid
        and o.customer_id is null
        and o.customer_name = 'Guest with customer feature disabled'
        and o.customer_phone = '+639191234569'
    ) then
    raise exception 'disabled customer management blocked checkout or lost guest contact details';
  end if;
end;
$$;

select extensions.ok(true, 'guest ordering, owner compatibility, atomic inventory, idempotency, validation, rate limiting, and protected tracking hold');
select pg_catalog.set_config(
  'request.jwt.claims',
  jsonb_build_object('sub', (select p.id from public.profiles p where p.display_name = 'Guest order owner fixture'), 'role', 'authenticated')::text,
  true
);
set local role authenticated;
select extensions.ok(exists (
  select 1 from public.orders o
  where o.client_operation_id = 'guest:11111111-1111-4111-8111-111111111111'
    and o.customer_uid is null and o.order_source = 'CUSTOMER_WEB' and o.status = 'PENDING'
), 'station owner RLS exposes the guest order in the Owner app queue');
select extensions.ok(exists (
  select 1 from public.order_items i
  join public.orders o on o.id = i.order_id
  where o.client_operation_id = 'guest:11111111-1111-4111-8111-111111111111'
    and i.quantity = 2 and i.unit_price_centavos = 4000
    and i.container_source = 'STATION_BORROW'
), 'station owner RLS exposes guest order details with the committed item price');
reset role;
select pg_catalog.set_config(
  'request.jwt.claims',
  jsonb_build_object('sub', '99999999-9999-4999-8999-999999999999'::uuid, 'role', 'authenticated')::text,
  true
);
set local role authenticated;
select extensions.ok(not exists (
  select 1 from public.orders o
  where o.client_operation_id = 'guest:11111111-1111-4111-8111-111111111111'
), 'unrelated authenticated customers cannot read guest orders');
reset role;
select extensions.ok(
  not has_function_privilege('anon', 'public.create_guest_order(text,jsonb,text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.create_guest_order(text,jsonb,text)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.resume_guest_order(text,text,text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.resume_guest_order(text,text,text)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.get_guest_order_status(uuid,text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.get_guest_order_status(uuid,text)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.consume_guest_order_rate_limit(text,text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.consume_guest_order_rate_limit(text,text)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.consume_guest_tracking_rate_limit(text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.consume_guest_tracking_rate_limit(text)', 'EXECUTE')
  and not has_function_privilege('anon', 'public.create_guest_order_with_container_sources(text,jsonb,text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'public.create_guest_order_with_container_sources(text,jsonb,text)', 'EXECUTE')
  and has_function_privilege('service_role', 'public.create_guest_order_with_container_sources(text,jsonb,text)', 'EXECUTE')
  and not has_function_privilege('anon', 'app_private.get_or_create_guest_station_customer(uuid,text,text)', 'EXECUTE')
  and not has_function_privilege('authenticated', 'app_private.get_or_create_guest_station_customer(uuid,text,text)', 'EXECUTE')
  and has_function_privilege('service_role', 'app_private.get_or_create_guest_station_customer(uuid,text,text)', 'EXECUTE')
  and not has_function_privilege('anon', 'app_private.backfill_guest_web_order_customers()', 'EXECUTE')
  and not has_function_privilege('authenticated', 'app_private.backfill_guest_web_order_customers()', 'EXECUTE')
  and not has_function_privilege('service_role', 'app_private.backfill_guest_web_order_customers()', 'EXECUTE')
  and not has_table_privilege('anon', 'app_private.guest_web_orders', 'SELECT')
  and not has_table_privilege('authenticated', 'app_private.guest_web_orders', 'SELECT')
  and not has_table_privilege('anon', 'app_private.guest_order_rate_limits', 'SELECT')
  and not has_table_privilege('authenticated', 'app_private.guest_order_rate_limits', 'SELECT')
  and not exists (
    select 1
    from pg_catalog.pg_proc p
    join pg_catalog.pg_namespace n on n.oid = p.pronamespace
    where n.nspname in ('public', 'app_private')
      and p.proname in (
        'validate_order_insert_context', 'consume_guest_rate', 'consume_guest_order_rate_limit',
        'consume_guest_tracking_rate_limit', 'create_order_internal', 'create_guest_order', 'create_guest_order_with_container_sources', 'resume_guest_order',
        'get_guest_order_status'
      )
      and p.prosecdef
      and not exists (
        select 1 from pg_catalog.unnest(coalesce(p.proconfig, array[]::text[])) as settings(setting)
        where settings.setting like 'search_path=%'
      )
  ),
  'guest APIs and customer-link helper grants are restricted; security definer functions pin search_path'
);
select * from extensions.finish();
rollback;
