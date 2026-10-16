-- Static trust-boundary checks; real Google Play purchases and linked-host
-- API behavior require a licensed test account and a nonproduction project.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(27);

select extensions.ok(
  (select monthly_price_centavos = 29900 and annual_price_centavos = 287040 from public.subscription_plans where code = 'STARTER')
  and (select monthly_price_centavos = 69900 and annual_price_centavos = 671040 from public.subscription_plans where code = 'BUSINESS')
  and (select monthly_price_centavos = 129900 and annual_price_centavos = 1247040 from public.subscription_plans where code = 'PRO'),
  'catalog prices match the latest intended PHP monthly and 20%-discount annual values'
);

select extensions.ok(
  exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'google_play_subscription_tokens' and column_name = 'encrypted_purchase_token'),
  'encrypted purchase-token storage exists for server reconciliation'
);
select extensions.ok(
  exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = 'google_play_subscription_tokens' and column_name = 'superseded_at'),
  'replacement-token lineage has an explicit superseded marker'
);
select extensions.ok(
  has_function_privilege('service_role', 'public.apply_verified_play_subscription(uuid,uuid,text,text,text,text,text,text,text,bigint,bigint,bigint,boolean,boolean,bigint,bigint,text,text)', 'execute'),
  'trusted service role can atomically apply verified Play state'
);
select extensions.ok(
  not has_function_privilege('authenticated', 'public.apply_verified_play_subscription(uuid,uuid,text,text,text,text,text,text,text,bigint,bigint,bigint,boolean,boolean,bigint,bigint,text,text)', 'execute'),
  'authenticated clients cannot invoke the Play entitlement writer'
);
select extensions.ok(
  has_function_privilege('service_role', 'public.provision_owner_trial(uuid,uuid,text,text,text[])', 'execute'),
  'trusted service role can provision a trial atomically'
);
select extensions.ok(
  not has_function_privilege('authenticated', 'public.provision_owner_trial(uuid,uuid,text,text,text[])', 'execute'),
  'authenticated clients cannot invoke the trial claim writer'
);
select extensions.ok(
  has_table_privilege('service_role', 'public.google_play_subscription_tokens', 'select'),
  'server-side reconciliation can read token metadata'
);
select extensions.ok(
  has_table_privilege('service_role', 'public.google_play_billing_events', 'insert')
  and has_table_privilege('service_role', 'public.google_play_billing_events', 'update'),
  'server-side RTDN handler can persist redacted event records'
);
select extensions.ok(
  not has_table_privilege('authenticated', 'public.google_play_subscription_tokens', 'select')
  and not has_table_privilege('authenticated', 'public.google_play_billing_events', 'select'),
  'authenticated users cannot read purchase tokens or RTDN event payloads'
);
insert into public.google_play_billing_events (message_id, payload, processed_at, processing_at)
values (
  'group3-redaction-fixture',
  '{"subscriptionNotification":{"version":"1.0","purchaseToken":"group3-synthetic-token-never-live","subscriptionId":"aquahub_starter","notificationType":4}}'::jsonb,
  null,
  null
);
-- Exercise the same scrub expression against a synthetic pre-migration row;
-- the clean replay itself has no historical user events to make this pass.
update public.google_play_billing_events e
set payload = pg_catalog.jsonb_set(
      e.payload,
      '{subscriptionNotification}',
      ((e.payload -> 'subscriptionNotification') - 'purchaseToken')
        || pg_catalog.jsonb_build_object(
          'purchaseTokenHash', pg_catalog.encode(
            extensions.digest('aquahub-play-token-v1:' || (e.payload #>> '{subscriptionNotification,purchaseToken}'), 'sha256'),
            'hex'
          )
        ),
      false
    ),
    purchase_token_hash = coalesce(
      e.purchase_token_hash,
      pg_catalog.encode(
        extensions.digest('aquahub-play-token-v1:' || (e.payload #>> '{subscriptionNotification,purchaseToken}'), 'sha256'),
        'hex'
      )
    )
where e.message_id = 'group3-redaction-fixture'
  and nullif(e.payload #>> '{subscriptionNotification,purchaseToken}', '') is not null;
select extensions.ok(
  not exists (
    select 1 from public.google_play_billing_events
    where message_id = 'group3-redaction-fixture'
      and payload #>> '{subscriptionNotification,purchaseToken}' is not null
  )
  and (select purchase_token_hash = pg_catalog.encode(
    extensions.digest('aquahub-play-token-v1:group3-synthetic-token-never-live', 'sha256'), 'hex'
  ) from public.google_play_billing_events where message_id = 'group3-redaction-fixture')
  and (select payload #>> '{subscriptionNotification,purchaseTokenHash}' = purchase_token_hash
    from public.google_play_billing_events where message_id = 'group3-redaction-fixture'),
  'historical RTDN payload scrub removes raw token and preserves matching lookup hash'
);

-- Transactional integration fixture: two isolated owner identities, one
-- replacement chain, and one shared trial identity. Everything rolls back.
create temporary table group3_billing_fixture as
select gen_random_uuid() as owner_a,
       gen_random_uuid() as owner_b,
       gen_random_uuid() as owner_c,
       gen_random_uuid() as organization_a,
       gen_random_uuid() as organization_b,
       gen_random_uuid() as organization_c,
       floor(extract(epoch from clock_timestamp()) * 1000)::bigint as now_ms;
insert into auth.users (id) select owner_a from group3_billing_fixture
union all select owner_b from group3_billing_fixture
union all select owner_c from group3_billing_fixture;
insert into public.organizations (id, name)
select organization_a, 'Play billing fixture A' from group3_billing_fixture
union all select organization_b, 'Play billing fixture B' from group3_billing_fixture
union all select organization_c, 'Play billing fixture C' from group3_billing_fixture;
insert into public.profiles (id, display_name, role, preferred_business_id)
select owner_a, 'Play owner fixture A', 'OWNER'::public.member_role, organization_a from group3_billing_fixture
union all select owner_b, 'Play owner fixture B', 'OWNER'::public.member_role, organization_b from group3_billing_fixture
union all select owner_c, 'Play owner fixture C', 'OWNER'::public.member_role, organization_c from group3_billing_fixture;
insert into public.organization_members (organization_id, profile_id, role)
select organization_a, owner_a, 'OWNER'::public.member_role from group3_billing_fixture
union all select organization_b, owner_b, 'OWNER'::public.member_role from group3_billing_fixture
union all select organization_c, owner_c, 'OWNER'::public.member_role from group3_billing_fixture;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select extensions.is(
  (public.apply_verified_play_subscription(
    (select organization_a from group3_billing_fixture), (select owner_a from group3_billing_fixture), repeat('a', 64),
    'v1.abcdefghijklmnop.abcdefghijklmnopqrstuvwxyz', 'aquahub_starter', 'monthly', 'STARTER', 'MONTHLY', 'ACTIVE',
    (select now_ms from group3_billing_fixture), (select now_ms + 2592000000 from group3_billing_fixture), null,
    true, false, (select now_ms from group3_billing_fixture), null, null, 'PURCHASE'
  ) ->> 'result'),
  'APPLIED', 'trusted purchase persists verified state and token binding'
);
select extensions.is(
  (public.apply_verified_play_subscription(
    (select organization_a from group3_billing_fixture), (select owner_a from group3_billing_fixture), repeat('a', 64),
    'v1.abcdefghijklmnop.abcdefghijklmnopqrstuvwxyz', 'aquahub_starter', 'monthly', 'STARTER', 'MONTHLY', 'ACTIVE',
    (select now_ms from group3_billing_fixture), (select now_ms + 2592000000 from group3_billing_fixture), null,
    true, false, (select now_ms + 1 from group3_billing_fixture), null, null, 'PURCHASE'
  ) ->> 'result'),
  'APPLIED', 'repeated purchase callback remains idempotently applied'
);
select extensions.ok(
  (select count(*) = 1 from public.subscription_history h
   join public.subscriptions s on s.id = h.subscription_id
   where s.organization_id = (select organization_a from group3_billing_fixture)),
  'duplicate purchase callback does not duplicate subscription history'
);

do $$
begin
  begin
    perform public.apply_verified_play_subscription(
      (select organization_b from group3_billing_fixture), (select owner_b from group3_billing_fixture), repeat('a', 64),
      'v1.abcdefghijklmnop.abcdefghijklmnopqrstuvwxyz', 'aquahub_starter', 'monthly', 'STARTER', 'MONTHLY', 'ACTIVE',
      (select now_ms from group3_billing_fixture), (select now_ms + 2592000000 from group3_billing_fixture), null,
      true, false, (select now_ms + 2 from group3_billing_fixture), null, null, 'PURCHASE'
    );
    raise exception 'expected token to reject a second organization';
  exception when unique_violation then
    null;
  end;
end $$;
select extensions.ok(true, 'purchase token cannot be rebound across organizations');

select extensions.is(
  (public.apply_verified_play_subscription(
    (select organization_a from group3_billing_fixture), (select owner_a from group3_billing_fixture), repeat('b', 64),
    'v1.abcdefghijklmnop.abcdefghijklmnopqrstuvwxyz', 'aquahub_starter', 'monthly', 'STARTER', 'MONTHLY', 'ACTIVE',
    (select now_ms from group3_billing_fixture), (select now_ms + 2592000000 from group3_billing_fixture), null,
    true, false, (select now_ms + 3 from group3_billing_fixture), null, repeat('a', 64), 'PURCHASE'
  ) ->> 'result'),
  'APPLIED', 'linked replacement token atomically supersedes its predecessor'
);
select extensions.is(
  (public.apply_verified_play_subscription(
    (select organization_a from group3_billing_fixture), null, repeat('a', 64),
    'v1.abcdefghijklmnop.abcdefghijklmnopqrstuvwxyz', 'aquahub_starter', 'monthly', 'STARTER', 'MONTHLY', 'ACTIVE',
    (select now_ms from group3_billing_fixture), (select now_ms + 2592000000 from group3_billing_fixture), null,
    true, false, (select now_ms + 4 from group3_billing_fixture), null, null, 'RTDN'
  ) ->> 'result'),
  'STALE_OR_UNBOUND_TOKEN', 'late notification from a replaced token cannot overwrite current state'
);

select extensions.is(
  (public.apply_verified_play_subscription(
    (select organization_a from group3_billing_fixture), (select owner_a from group3_billing_fixture), repeat('b', 64),
    'v1.abcdefghijklmnop.abcdefghijklmnopqrstuvwxyz', 'aquahub_starter', 'monthly', 'STARTER', 'MONTHLY', 'ACTIVE_UNTIL_PERIOD_END',
    (select now_ms from group3_billing_fixture), (select now_ms + 2592000000 from group3_billing_fixture), null,
    false, true, (select now_ms + 5 from group3_billing_fixture), null, repeat('a', 64), 'PURCHASE'
  ) ->> 'result'),
  'APPLIED', 'cancellation is recorded without immediate loss of unexpired access'
);
select extensions.is(
  (select p.code from public.subscription_plans p
   where p.id = app_private.effective_plan_id((select organization_a from group3_billing_fixture))),
  'STARTER', 'canceled subscription retains plan entitlement through verified expiry'
);

select extensions.is(
  (public.apply_verified_play_subscription(
    (select organization_a from group3_billing_fixture), (select owner_a from group3_billing_fixture), repeat('b', 64),
    'v1.abcdefghijklmnop.abcdefghijklmnopqrstuvwxyz', 'aquahub_starter', 'monthly', 'STARTER', 'MONTHLY', 'GRACE_PERIOD',
    (select now_ms from group3_billing_fixture), (select now_ms + 604800000 from group3_billing_fixture), (select now_ms + 604800000 from group3_billing_fixture),
    true, false, (select now_ms + 6 from group3_billing_fixture), null, repeat('a', 64), 'PURCHASE'
  ) ->> 'result'),
  'APPLIED', 'grace state stores the provider-verified entitlement cutoff'
);
select extensions.is(
  (select p.code from public.subscription_plans p
   where p.id = app_private.effective_plan_id((select organization_a from group3_billing_fixture))),
  'STARTER', 'grace-period access remains available only through its non-null cutoff'
);

select extensions.is(
  (public.apply_verified_play_subscription(
    (select organization_a from group3_billing_fixture), (select owner_a from group3_billing_fixture), repeat('b', 64),
    'v1.abcdefghijklmnop.abcdefghijklmnopqrstuvwxyz', 'aquahub_starter', 'monthly', 'STARTER', 'MONTHLY', 'EXPIRED',
    (select now_ms from group3_billing_fixture), (select now_ms - 1 from group3_billing_fixture), null,
    false, false, (select now_ms + 7 from group3_billing_fixture), null, repeat('a', 64), 'PURCHASE'
  ) ->> 'result'),
  'APPLIED', 'provider expiration is persisted'
);
select extensions.is(
  (select p.code from public.subscription_plans p
   where p.id = app_private.effective_plan_id((select organization_a from group3_billing_fixture))),
  'FREE', 'expired paid plan falls back to Free immediately'
);

select extensions.is(
  (public.provision_owner_trial(
    (select organization_a from group3_billing_fixture), (select owner_a from group3_billing_fixture),
    'BUSINESS', 'MONTHLY', array[repeat('c', 64)]
  ) ->> 'result'),
  'SUBSCRIPTION_EXISTS', 'trial request cannot overwrite an existing paid subscription'
);

select extensions.is(
  (public.provision_owner_trial(
    (select organization_b from group3_billing_fixture), (select owner_b from group3_billing_fixture),
    'BUSINESS', 'MONTHLY', array[repeat('c', 64)]
  ) ->> 'result'),
  'PROVISIONED', 'first verified identity claim provisions a trial atomically'
);
select extensions.is(
  (public.provision_owner_trial(
    (select organization_c from group3_billing_fixture), (select owner_c from group3_billing_fixture),
    'BUSINESS', 'MONTHLY', array[repeat('c', 64)]
  ) ->> 'result'),
  'TRIAL_ALREADY_CONSUMED', 'same verified identity cannot claim a second organization trial'
);
select extensions.ok(
  (select count(*) = 1 from public.owner_trial_claims where identity_hash = repeat('c', 64))
  and (select count(*) = 1 from public.subscription_history h
       join public.subscriptions s on s.id = h.subscription_id
       where s.organization_id = (select organization_b from group3_billing_fixture)
         and h.event_type = 'TRIAL_STARTED'),
  'trial claim and single history event are committed together'
);

select * from extensions.finish();
rollback;
