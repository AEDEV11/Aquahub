-- Synthetic token and owner fixtures only. The transaction rolls back.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(46);

create temporary table play_reconciliation_fixture as
select gen_random_uuid() as owner_a,
       gen_random_uuid() as owner_b,
       gen_random_uuid() as organization_a,
       gen_random_uuid() as organization_b,
       floor(extract(epoch from clock_timestamp()) * 1000)::bigint as now_ms;

insert into auth.users (id)
select owner_a from play_reconciliation_fixture
union all select owner_b from play_reconciliation_fixture;
insert into public.organizations (id, name)
select organization_a, 'Play reconciliation A' from play_reconciliation_fixture
union all select organization_b, 'Play reconciliation B' from play_reconciliation_fixture;
insert into public.profiles (id, display_name, role, preferred_business_id)
select owner_a, 'Play reconciliation owner A', 'OWNER'::public.member_role, organization_a from play_reconciliation_fixture
union all select owner_b, 'Play reconciliation owner B', 'OWNER'::public.member_role, organization_b from play_reconciliation_fixture;
insert into public.organization_members (organization_id, profile_id, role)
select organization_a, owner_a, 'OWNER'::public.member_role from play_reconciliation_fixture
union all select organization_b, owner_b, 'OWNER'::public.member_role from play_reconciliation_fixture;

create function pg_temp.apply_test_play_purchase(
  p_organization_id uuid,
  p_owner_id uuid,
  p_token_hash text,
  p_status text,
  p_period_end bigint,
  p_linked_token_hash text default null,
  p_source text default 'PURCHASE',
  p_observed_at bigint default null
) returns jsonb
language sql
as $$
  select public.apply_verified_play_subscription(
    p_organization_id, p_owner_id, p_token_hash,
    'v1.synthetic-encrypted-purchase-token',
    'aquahub_starter', 'monthly', 'STARTER', 'MONTHLY', p_status,
    p_period_end - 2592000000, p_period_end, null,
    true, false,
    coalesce(p_observed_at, floor(extract(epoch from clock_timestamp()) * 1000)::bigint),
    null, p_linked_token_hash, p_source
  );
$$;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select extensions.ok(
  has_function_privilege('service_role', 'public.apply_verified_play_subscription(uuid,uuid,text,text,text,text,text,text,text,bigint,bigint,bigint,boolean,boolean,bigint,bigint,text,text)', 'execute')
  and not has_function_privilege('authenticated', 'public.apply_verified_play_subscription(uuid,uuid,text,text,text,text,text,text,text,bigint,bigint,bigint,boolean,boolean,bigint,bigint,text,text)', 'execute'),
  'replacement RPC remains service-role only'
);

select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('a', 64), 'EXPIRED', (select now_ms - 1000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'prior paid token can be recorded as expired'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('e', 64), 'EXPIRED', (select now_ms - 500 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_TOKEN', 'an already expired new token cannot replace the prior token'
);
select extensions.ok(
  (select external_subscription_id = repeat('a', 64) from public.subscriptions where organization_id = (select organization_a from play_reconciliation_fixture))
  and not exists (select 1 from public.google_play_subscription_tokens where token_hash = repeat('e', 64)),
  'stale unlinked callback leaves current state and token binding untouched'
);

-- A historical row can have a later grace cutoff than its billing-period end.
-- Such a row is still entitled and must retain its current purchase binding.
update public.subscriptions
set status = 'GRACE_PERIOD',
    grace_period_ends_at = (select now_ms + 604800000 from play_reconciliation_fixture),
    expires_at = null
where organization_id = (select organization_a from play_reconciliation_fixture);
select extensions.is(
  (select p.code from public.subscription_plans p
   where p.id = app_private.effective_plan_id((select organization_a from play_reconciliation_fixture))),
  'STARTER', 'later grace cutoff still grants the previous paid entitlement'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('b', 64), 'ACTIVE', (select now_ms + 2592000000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_TOKEN', 'unlinked replacement is denied while prior grace access remains active'
);
select extensions.ok(
  not exists (select 1 from public.google_play_subscription_tokens where token_hash = repeat('b', 64))
  and (select external_subscription_id = repeat('a', 64)
       from public.subscriptions where organization_id = (select organization_a from play_reconciliation_fixture)),
  'grace-period rejection leaves the prior token and subscription current'
);
update public.subscriptions
set status = 'EXPIRED', grace_period_ends_at = null, expires_at = current_period_end
where organization_id = (select organization_a from play_reconciliation_fixture);

update public.google_play_subscription_tokens
set organization_id = (select organization_b from play_reconciliation_fixture)
where token_hash = repeat('a', 64);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('b', 64), 'ACTIVE', (select now_ms + 2592000000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_TOKEN', 'unlinked replacement rejects a prior token bound to another organization'
);
select extensions.ok(
  not exists (select 1 from public.google_play_subscription_tokens where token_hash = repeat('b', 64))
  and (select external_subscription_id = repeat('a', 64)
       from public.subscriptions where organization_id = (select organization_a from play_reconciliation_fixture)),
  'ownership mismatch leaves the new token and subscription untouched'
);
update public.google_play_subscription_tokens
set organization_id = (select organization_a from play_reconciliation_fixture)
where token_hash = repeat('a', 64);

select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('b', 64), 'ACTIVE', (select now_ms + 2592000000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'new verified unlinked purchase follows an expired paid period'
);
select extensions.ok(
  (select superseded_at is not null from public.google_play_subscription_tokens where token_hash = repeat('a', 64))
  and (select organization_id = (select organization_a from play_reconciliation_fixture)
       and superseded_at is null from public.google_play_subscription_tokens where token_hash = repeat('b', 64))
  and (select external_subscription_id = repeat('b', 64)
       from public.subscriptions where organization_id = (select organization_a from play_reconciliation_fixture)),
  'unlinked replacement atomically supersedes the expired token and binds the new one'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('b', 64), 'ACTIVE', (select now_ms + 2592000000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'retry of the newly bound token succeeds idempotently'
);
select extensions.is(
  (select count(*)::integer from public.subscription_history where organization_id = (select organization_a from play_reconciliation_fixture)),
  2, 'retry does not duplicate purchase history'
);

select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture),
    (select owner_b from play_reconciliation_fixture),
    repeat('c', 64), 'ACTIVE', (select now_ms + 2592000000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'a second organization can bind its own active token'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture),
    (select owner_b from play_reconciliation_fixture),
    repeat('d', 64), 'ACTIVE', (select now_ms + 5184000000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_TOKEN', 'an unlinked token cannot replace an active paid period'
);
select extensions.ok(
  (select external_subscription_id = repeat('c', 64) from public.subscriptions where organization_id = (select organization_b from play_reconciliation_fixture))
  and (select superseded_at is null from public.google_play_subscription_tokens where token_hash = repeat('c', 64))
  and not exists (select 1 from public.google_play_subscription_tokens where token_hash = repeat('d', 64)),
  'active subscription and binding remain unchanged after unlinked rejection'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture),
    (select owner_b from play_reconciliation_fixture),
    repeat('d', 64), 'ACTIVE', (select now_ms + 5184000000 from play_reconciliation_fixture), repeat('c', 64)
  ) ->> 'result'),
  'APPLIED', 'provider-linked replacement still works during an active period'
);
select extensions.ok(
  (select superseded_at is not null from public.google_play_subscription_tokens where token_hash = repeat('c', 64))
  and (select external_subscription_id = repeat('d', 64) from public.subscriptions where organization_id = (select organization_b from play_reconciliation_fixture)),
  'linked replacement supersedes only the prior organization token'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture),
    null, repeat('c', 64), 'ACTIVE', (select now_ms + 5184000000 from play_reconciliation_fixture), null, 'RTDN'
  ) ->> 'result'),
  'STALE_OR_UNBOUND_TOKEN', 'late RTDN for a superseded token cannot restore it'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture),
    (select owner_b from play_reconciliation_fixture),
    repeat('f', 64), 'ACTIVE', (select now_ms + 7776000000 from play_reconciliation_fixture), repeat('c', 64)
  ) ->> 'result'),
  'STALE_TOKEN', 'a stale linked predecessor cannot replace the current token'
);

do $$
begin
  begin
    perform pg_temp.apply_test_play_purchase(
      (select organization_b from play_reconciliation_fixture),
      (select owner_b from play_reconciliation_fixture),
      repeat('b', 64), 'ACTIVE', (select now_ms + 7776000000 from play_reconciliation_fixture), repeat('d', 64)
    );
    raise exception 'expected cross-organization token rejection';
  exception when unique_violation then null;
  end;
end $$;
select extensions.ok(true, 'new token cannot move from another organization');

do $$
begin
  begin
    perform pg_temp.apply_test_play_purchase(
      (select organization_a from play_reconciliation_fixture),
      (select owner_a from play_reconciliation_fixture),
      repeat('a', 64), 'ACTIVE', (select now_ms + 2592000000 from play_reconciliation_fixture)
    );
    raise exception 'expected superseded token rejection';
  exception when unique_violation then null;
  end;
end $$;
select extensions.ok(true, 'superseded old token cannot be reactivated by replay');

select extensions.ok(
  (select external_subscription_id = repeat('b', 64) from public.subscriptions where organization_id = (select organization_a from play_reconciliation_fixture))
  and (select external_subscription_id = repeat('d', 64) from public.subscriptions where organization_id = (select organization_b from play_reconciliation_fixture)),
  'rejected stale and cross-organization callbacks leave both current bindings intact'
);

-- A provider response started before a newer revoked-state verification must
-- never restore paid access merely because its RPC committed later.
update public.subscriptions
set status = 'EXPIRED', expires_at = current_period_end,
    provider_event_time = (select now_ms + 10000 from play_reconciliation_fixture)
where organization_id = (select organization_b from play_reconciliation_fixture);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture),
    (select owner_b from play_reconciliation_fixture), repeat('d', 64), 'ACTIVE',
    (select now_ms + 5184000000 from play_reconciliation_fixture), null, 'PURCHASE',
    (select now_ms + 9000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_EVENT', 'older checkout verification cannot reverse a newer revocation'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture),
    null, repeat('d', 64), 'ACTIVE',
    (select now_ms + 5184000000 from play_reconciliation_fixture), null, 'RECONCILIATION',
    (select now_ms + 9001 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_EVENT', 'older reconciliation cannot reverse a newer revocation'
);
select extensions.is(
  (select status::text from public.subscriptions where organization_id =
    (select organization_b from play_reconciliation_fixture)),
  'EXPIRED', 'stale fetches leave revoked access unchanged'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture),
    null, repeat('d', 64), 'ACTIVE',
    (select now_ms + 5184000000 from play_reconciliation_fixture), null, 'RTDN',
    (select now_ms + 11000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'fresh Play re-verification can update a delayed notification'
);

select extensions.ok(
  has_column_privilege('service_role', 'public.google_play_subscription_tokens', 'updated_at', 'UPDATE')
  and not has_column_privilege('authenticated', 'public.google_play_subscription_tokens', 'updated_at', 'UPDATE'),
  'only the trusted service role can advance reconciliation attempts'
);
insert into public.google_play_subscription_tokens (
  token_hash, organization_id, product_id, base_plan_id, encrypted_purchase_token, updated_at
)
select lpad(g::text, 64, '0'), organization_a, 'aquahub_starter', 'monthly',
       'v1.synthetic-encrypted-purchase-token',
       clock_timestamp() - interval '200 days' + g * interval '1 second'
from play_reconciliation_fixture, generate_series(1, 101) g;
with oldest_page as (
  select token_hash from public.google_play_subscription_tokens
  where organization_id = (select organization_a from play_reconciliation_fixture)
    and superseded_at is null
  order by updated_at, token_hash limit 100
)
update public.google_play_subscription_tokens t
set updated_at = clock_timestamp()
from oldest_page p where t.token_hash = p.token_hash;
select extensions.is(
  (select token_hash from public.google_play_subscription_tokens
   where organization_id = (select organization_a from play_reconciliation_fixture)
     and superseded_at is null
   order by updated_at, token_hash limit 1),
  lpad('101', 64, '0'),
  'rotating attempted rows exposes the next token after an all-failed 100-row page'
);

update public.subscriptions
set provider_event_time = (select now_ms + 12000 from play_reconciliation_fixture)
where organization_id = (select organization_b from play_reconciliation_fixture);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture), null,
    repeat('d', 64), 'ACTIVE',
    (select now_ms + 5184000000 from play_reconciliation_fixture),
    null, 'RECONCILIATION', (select now_ms + 12000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'same-millisecond identical retry remains idempotent'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture), null,
    repeat('d', 64), 'EXPIRED',
    (select now_ms + 5184000000 from play_reconciliation_fixture),
    null, 'RTDN', (select now_ms + 12000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_EVENT', 'same-millisecond conflicting provider state fails closed'
);

-- A replacement RTDN may arrive before the app verifies its new token. Only
-- Play's link to the still-current, bound predecessor can establish ownership.
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture), null,
    repeat('1', 64), 'ACTIVE',
    (select now_ms + 7776000000 from play_reconciliation_fixture),
    null, 'RTDN', (select now_ms + 13000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_OR_UNBOUND_TOKEN', 'unlinked new RTDN token cannot choose an organization'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture), null,
    repeat('1', 64), 'ACTIVE',
    (select now_ms + 7776000000 from play_reconciliation_fixture),
    repeat('b', 64), 'RTDN', (select now_ms + 13000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_OR_UNBOUND_TOKEN', 'another organization predecessor cannot bind an RTDN token'
);
select extensions.ok(
  not exists (select 1 from public.google_play_subscription_tokens where token_hash = repeat('1', 64)),
  'rejected unbound notifications do not retain a new token'
);

update public.google_play_subscription_tokens
set superseded_at = pg_catalog.clock_timestamp()
where token_hash = repeat('d', 64);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture), null,
    repeat('1', 64), 'ACTIVE',
    (select now_ms + 7776000000 from play_reconciliation_fixture),
    repeat('d', 64), 'RTDN', (select now_ms + 13000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_LINKED_TOKEN', 'a superseded predecessor cannot establish RTDN ownership'
);
update public.google_play_subscription_tokens
set superseded_at = null
where token_hash = repeat('d', 64);

select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture), null,
    repeat('1', 64), 'ACTIVE',
    (select now_ms + 7776000000 from play_reconciliation_fixture),
    repeat('d', 64), 'RTDN', (select now_ms + 13000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'verified replacement RTDN binds through the current predecessor'
);
select extensions.ok(
  (select organization_id = (select organization_b from play_reconciliation_fixture)
          and superseded_at is null
   from public.google_play_subscription_tokens where token_hash = repeat('1', 64))
  and (select superseded_at is not null
       from public.google_play_subscription_tokens where token_hash = repeat('d', 64))
  and (select external_subscription_id = repeat('1', 64)
       from public.subscriptions where organization_id = (select organization_b from play_reconciliation_fixture)),
  'RTDN claim atomically retires the predecessor and moves the subscription'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture), null,
    repeat('1', 64), 'ACTIVE',
    (select now_ms + 7776000000 from play_reconciliation_fixture),
    repeat('d', 64), 'RTDN', (select now_ms + 14000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'repeat RTDN for the now-bound token remains idempotent'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_b from play_reconciliation_fixture), null,
    repeat('d', 64), 'ACTIVE',
    (select now_ms + 7776000000 from play_reconciliation_fixture),
    null, 'RTDN', (select now_ms + 15000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_OR_UNBOUND_TOKEN', 'late predecessor notification cannot restore the old token'
);

-- Play Store resubscriptions after expiry have outOfAppPurchaseContext instead
-- of linkedPurchaseToken. The trusted restore passes that expired token hash
-- through a distinct source so the database checks the expired predecessor.
update public.subscriptions
set status = 'EXPIRED',
    current_period_end = (select now_ms - 1000 from play_reconciliation_fixture),
    expires_at = (select now_ms - 1000 from play_reconciliation_fixture),
    provider_event_time = (select now_ms + 16000 from play_reconciliation_fixture)
where organization_id = (select organization_a from play_reconciliation_fixture);

do $$
begin
  begin
    perform pg_temp.apply_test_play_purchase(
      (select organization_a from play_reconciliation_fixture), null,
      repeat('2', 64), 'ACTIVE',
      (select now_ms + 2592000000 from play_reconciliation_fixture),
      repeat('b', 64), 'OUT_OF_APP_PURCHASE',
      (select now_ms + 17000 from play_reconciliation_fixture)
    );
    raise exception 'expected owner authorization failure';
  exception when insufficient_privilege then
    perform pg_catalog.set_config('test.play.out_of_app_owner_required', 'true', true);
  end;
end $$;
select extensions.ok(
  pg_catalog.current_setting('test.play.out_of_app_owner_required', true) = 'true',
  'out-of-app restore still requires an active owner'
);

select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('2', 64), 'ACTIVE',
    (select now_ms + 2592000000 from play_reconciliation_fixture),
    null, 'OUT_OF_APP_PURCHASE',
    (select now_ms + 17000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_LINKED_TOKEN', 'out-of-app restore rejects a missing expired predecessor'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('2', 64), 'ACTIVE',
    (select now_ms + 2592000000 from play_reconciliation_fixture),
    repeat('1', 64), 'OUT_OF_APP_PURCHASE',
    (select now_ms + 17000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_LINKED_TOKEN', 'out-of-app restore rejects another organization predecessor'
);

update public.google_play_subscription_tokens
set superseded_at = pg_catalog.clock_timestamp()
where token_hash = repeat('b', 64);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('2', 64), 'ACTIVE',
    (select now_ms + 2592000000 from play_reconciliation_fixture),
    repeat('b', 64), 'OUT_OF_APP_PURCHASE',
    (select now_ms + 17000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'STALE_LINKED_TOKEN', 'out-of-app restore rejects a superseded predecessor'
);
update public.google_play_subscription_tokens
set superseded_at = null
where token_hash = repeat('b', 64);

select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('2', 64), 'ACTIVE',
    (select now_ms + 2592000000 from play_reconciliation_fixture),
    repeat('b', 64), 'OUT_OF_APP_PURCHASE',
    (select now_ms + 17000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'verified Play Store resubscription follows its expired predecessor'
);
select extensions.ok(
  (select superseded_at is not null
   from public.google_play_subscription_tokens where token_hash = repeat('b', 64))
  and (select organization_id = (select organization_a from play_reconciliation_fixture)
          and superseded_at is null
       from public.google_play_subscription_tokens where token_hash = repeat('2', 64))
  and (select external_subscription_id = repeat('2', 64)
       from public.subscriptions where organization_id = (select organization_a from play_reconciliation_fixture)),
  'out-of-app renewal atomically retires the expired token and binds the new one'
);
select extensions.is(
  (pg_temp.apply_test_play_purchase(
    (select organization_a from play_reconciliation_fixture),
    (select owner_a from play_reconciliation_fixture),
    repeat('2', 64), 'ACTIVE',
    (select now_ms + 2592000000 from play_reconciliation_fixture),
    repeat('b', 64), 'OUT_OF_APP_PURCHASE',
    (select now_ms + 18000 from play_reconciliation_fixture)
  ) ->> 'result'),
  'APPLIED', 'out-of-app acknowledgement retry accepts the already-bound token'
);

select * from extensions.finish();
rollback;
