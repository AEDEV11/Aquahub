-- Group 3: subscription lifecycle hardening.
-- This migration is forward-only. It does not mutate customer transaction data.
begin;

-- Latest intended AquaHub catalog pricing (centavos). Google Play remains the
-- runtime checkout authority; these values are not evidence of Play Console
-- configuration and are never used as checkout prices by the Owner app.
update public.subscription_plans
set monthly_price_centavos = case code
      when 'STARTER' then 29900
      when 'BUSINESS' then 69900
      when 'PRO' then 129900
      else monthly_price_centavos
    end,
    annual_price_centavos = case code
      when 'STARTER' then 287040
      when 'BUSINESS' then 671040
      when 'PRO' then 1247040
      else annual_price_centavos
    end,
    updated_at = now()
where code in ('STARTER', 'BUSINESS', 'PRO');

-- Remove raw purchase tokens retained by the previous RTDN implementation
-- while preserving event metadata and a stable one-way lookup hash.
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
where nullif(e.payload #>> '{subscriptionNotification,purchaseToken}', '') is not null;

-- Keep Play tokens recoverable for server reconciliation without storing the
-- bearer token in plaintext. The encryption key remains an Edge Function
-- secret and must never be added to this repository or PostgreSQL.
alter table public.google_play_subscription_tokens
  add column if not exists encrypted_purchase_token text,
  add column if not exists superseded_at timestamptz;

-- The earlier RTDN hardening revoked even the server service-role table
-- privileges, which prevents Edge Functions from persisting or reconciling
-- events. Restore only the server-side operations they require; anon and
-- authenticated clients remain denied.
grant select on public.google_play_subscription_tokens to service_role;
grant select, insert, update on public.google_play_billing_events to service_role;

create index if not exists google_play_subscription_tokens_current_org_idx
  on public.google_play_subscription_tokens (organization_id, updated_at desc)
  where organization_id is not null and superseded_at is null;

-- All trusted Google Play verification outcomes are applied atomically. The
-- Edge Function verifies the token with subscriptionsv2.get; this RPC then
-- serializes token ownership and subscription state, rejects stale/replaced
-- tokens, and appends history in the same transaction.
create or replace function public.apply_verified_play_subscription(
  p_organization_id uuid,
  p_actor_user_id uuid,
  p_token_hash text,
  p_encrypted_purchase_token text,
  p_product_id text,
  p_base_plan_id text,
  p_plan_code text,
  p_billing_cycle text,
  p_status text,
  p_current_period_start bigint,
  p_current_period_end bigint,
  p_grace_period_ends_at bigint,
  p_auto_renew boolean,
  p_cancel_at_period_end boolean,
  p_provider_event_time bigint,
  p_provider_event_version bigint,
  p_linked_token_hash text,
  p_source text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_mapping record;
  v_previous public.subscriptions%rowtype;
  v_subscription public.subscriptions%rowtype;
  v_token public.google_play_subscription_tokens%rowtype;
  v_linked public.google_play_subscription_tokens%rowtype;
  v_plan_id uuid;
  v_now_ms bigint := (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint;
  v_history_event text;
  v_written boolean := false;
  v_lock_key text;
begin
  if auth.role() is distinct from 'service_role' then
    raise exception using errcode = '42501', message = 'PLAY_SERVICE_ROLE_REQUIRED';
  end if;
  if p_source is null or p_source not in ('PURCHASE', 'RTDN', 'RECONCILIATION') then
    raise exception using errcode = '22023', message = 'PLAY_INVALID_SOURCE';
  end if;
  if p_organization_id is null
     or p_token_hash !~ '^[0-9a-f]{64}$'
     or p_encrypted_purchase_token is null
     or length(p_encrypted_purchase_token) < 20
     or p_product_id is null
     or p_base_plan_id is null
     or p_current_period_start is null
     or p_current_period_end is null
     or p_provider_event_time is null then
    raise exception using errcode = '22023', message = 'PLAY_INVALID_VERIFICATION';
  end if;
  if p_linked_token_hash is not null and p_linked_token_hash !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'PLAY_INVALID_LINKED_TOKEN';
  end if;

  select expected.plan_code, expected.billing_cycle
  into v_mapping
  from (values
    ('aquahub_starter', 'monthly', 'STARTER', 'MONTHLY'),
    ('aquahub_starter', 'yearly', 'STARTER', 'ANNUAL'),
    ('aquahub_business', 'monthly', 'BUSINESS', 'MONTHLY'),
    ('aquahub_business', 'yearly', 'BUSINESS', 'ANNUAL'),
    ('aquahub_pro', 'monthly', 'PRO', 'MONTHLY'),
    ('aquahub_pro', 'yearly', 'PRO', 'ANNUAL')
  ) as expected(product_id, base_plan_id, plan_code, billing_cycle)
  where expected.product_id = p_product_id and expected.base_plan_id = p_base_plan_id;
  if not found or v_mapping.plan_code <> p_plan_code or v_mapping.billing_cycle <> p_billing_cycle then
    raise exception using errcode = '22023', message = 'PLAY_PRODUCT_MAPPING_MISMATCH';
  end if;
  if p_status not in ('ACTIVE', 'ACTIVE_UNTIL_PERIOD_END', 'GRACE_PERIOD', 'SUSPENDED', 'EXPIRED') then
    raise exception using errcode = '22023', message = 'PLAY_INVALID_SUBSCRIPTION_STATE';
  end if;
  if p_status = 'GRACE_PERIOD' and (p_grace_period_ends_at is null or p_grace_period_ends_at <> p_current_period_end) then
    raise exception using errcode = '22023', message = 'PLAY_INVALID_GRACE_CUTOFF';
  end if;
  if p_status <> 'GRACE_PERIOD' and p_grace_period_ends_at is not null then
    raise exception using errcode = '22023', message = 'PLAY_UNEXPECTED_GRACE_CUTOFF';
  end if;
  if p_source = 'PURCHASE' and (p_actor_user_id is null or not exists (
    select 1 from public.organization_members m
    where m.organization_id = p_organization_id and m.profile_id = p_actor_user_id
      and m.role = 'OWNER' and m.is_active
  )) then
    raise exception using errcode = '42501', message = 'PLAY_OWNER_MEMBERSHIP_REQUIRED';
  end if;

  -- Serialize writes to an organization and both sides of a replacement chain.
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('play-org:' || p_organization_id::text, 0));
  for v_lock_key in
    select distinct lock_value
    from unnest(array['play-token:' || p_token_hash, case when p_linked_token_hash is null then null else 'play-token:' || p_linked_token_hash end]) as keys(lock_value)
    where lock_value is not null
    order by lock_value
  loop
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_lock_key, 0));
  end loop;

  select * into v_previous
  from public.subscriptions s
  where s.organization_id = p_organization_id and s.deleted_at is null
  for update;

  select * into v_token
  from public.google_play_subscription_tokens t
  where t.token_hash = p_token_hash
  for update;

  if p_source in ('RTDN', 'RECONCILIATION') then
    if not found or v_token.organization_id is distinct from p_organization_id or v_token.superseded_at is not null then
      return pg_catalog.jsonb_build_object('result', 'STALE_OR_UNBOUND_TOKEN');
    end if;
  elsif found and v_token.organization_id is not null and v_token.organization_id <> p_organization_id then
    raise exception using errcode = '23505', message = 'PLAY_TOKEN_BOUND_TO_ANOTHER_ORGANIZATION';
  elsif found and v_token.superseded_at is not null then
    raise exception using errcode = '23505', message = 'PLAY_TOKEN_SUPERSEDED';
  end if;

  if p_source = 'RTDN' and v_previous.provider_event_time is not null
     and v_previous.provider_event_time > p_provider_event_time then
    return pg_catalog.jsonb_build_object('result', 'STALE_EVENT');
  end if;

  if v_previous.id is not null and v_previous.provider = 'GOOGLE_PLAY'
     and v_previous.external_subscription_id is distinct from p_token_hash
     and (p_linked_token_hash is null or v_previous.external_subscription_id is distinct from p_linked_token_hash) then
    return pg_catalog.jsonb_build_object('result', 'STALE_TOKEN');
  end if;

  if p_linked_token_hash is not null and p_linked_token_hash <> p_token_hash then
    select * into v_linked
    from public.google_play_subscription_tokens t
    where t.token_hash = p_linked_token_hash
    for update;
    if found and v_linked.organization_id is not null and v_linked.organization_id <> p_organization_id then
      raise exception using errcode = '23505', message = 'PLAY_LINKED_TOKEN_BOUND_TO_ANOTHER_ORGANIZATION';
    end if;
    if found and v_linked.superseded_at is not null
       and v_previous.external_subscription_id is distinct from p_linked_token_hash
       and v_previous.external_subscription_id is distinct from p_token_hash then
      return pg_catalog.jsonb_build_object('result', 'STALE_LINKED_TOKEN');
    end if;
  end if;

  if p_source in ('RTDN', 'RECONCILIATION') then
    update public.google_play_subscription_tokens
    set encrypted_purchase_token = p_encrypted_purchase_token,
        product_id = p_product_id,
        base_plan_id = p_base_plan_id,
        updated_at = pg_catalog.clock_timestamp()
    where token_hash = p_token_hash and organization_id = p_organization_id and superseded_at is null
    returning true into v_written;
    if not coalesce(v_written, false) then
      return pg_catalog.jsonb_build_object('result', 'STALE_OR_UNBOUND_TOKEN');
    end if;
  else
    insert into public.google_play_subscription_tokens (
      token_hash, organization_id, product_id, base_plan_id,
      encrypted_purchase_token, updated_at
    ) values (
      p_token_hash, p_organization_id, p_product_id, p_base_plan_id,
      p_encrypted_purchase_token, pg_catalog.clock_timestamp()
    )
    on conflict (token_hash) do update
      set organization_id = excluded.organization_id,
          product_id = excluded.product_id,
          base_plan_id = excluded.base_plan_id,
          encrypted_purchase_token = excluded.encrypted_purchase_token,
          updated_at = excluded.updated_at
      where public.google_play_subscription_tokens.organization_id is null
         or public.google_play_subscription_tokens.organization_id = p_organization_id
    returning true into v_written;
    if not coalesce(v_written, false) then
      raise exception using errcode = '23505', message = 'PLAY_TOKEN_BINDING_CONFLICT';
    end if;
  end if;

  if p_linked_token_hash is not null and p_linked_token_hash <> p_token_hash then
    update public.google_play_subscription_tokens
    set organization_id = p_organization_id,
        superseded_at = coalesce(superseded_at, pg_catalog.clock_timestamp()),
        updated_at = pg_catalog.clock_timestamp()
    where token_hash = p_linked_token_hash
      and (organization_id is null or organization_id = p_organization_id);
  end if;

  select p.id into strict v_plan_id
  from public.subscription_plans p
  where p.code = p_plan_code and p.is_active;

  insert into public.subscriptions (
    organization_id, plan_id, status, billing_cycle, started_at,
    current_period_start, current_period_end, expires_at,
    grace_period_ends_at, auto_renew, cancel_at_period_end,
    provider, external_subscription_id, last_verified_at,
    provider_event_time, provider_event_version, client_updated_at, updated_at
  ) values (
    p_organization_id, v_plan_id, p_status::public.subscription_status, p_billing_cycle,
    p_current_period_start, p_current_period_start, p_current_period_end,
    case when p_status = 'EXPIRED' then p_current_period_end else null end,
    p_grace_period_ends_at, p_auto_renew, p_cancel_at_period_end,
    'GOOGLE_PLAY', p_token_hash, v_now_ms,
    p_provider_event_time, p_provider_event_version, v_now_ms, pg_catalog.clock_timestamp()
  )
  on conflict (organization_id) do update set
    plan_id = excluded.plan_id,
    status = excluded.status,
    billing_cycle = excluded.billing_cycle,
    started_at = excluded.started_at,
    current_period_start = excluded.current_period_start,
    current_period_end = excluded.current_period_end,
    expires_at = excluded.expires_at,
    grace_period_ends_at = excluded.grace_period_ends_at,
    auto_renew = excluded.auto_renew,
    cancel_at_period_end = excluded.cancel_at_period_end,
    provider = excluded.provider,
    external_subscription_id = excluded.external_subscription_id,
    last_verified_at = excluded.last_verified_at,
    provider_event_time = excluded.provider_event_time,
    provider_event_version = excluded.provider_event_version,
    client_updated_at = excluded.client_updated_at,
    updated_at = excluded.updated_at
  returning * into v_subscription;

  if v_previous.id is null then
    v_history_event := 'SUBSCRIBED';
  elsif v_previous.status::text <> p_status then
    v_history_event := case p_status
      when 'EXPIRED' then 'EXPIRED'
      when 'GRACE_PERIOD' then 'GRACE_PERIOD_STARTED'
      when 'SUSPENDED' then 'SUSPENDED'
      when 'ACTIVE' then 'REACTIVATED'
      else 'SUBSCRIBED'
    end;
  elsif v_previous.plan_id is distinct from v_subscription.plan_id then
    v_history_event := 'SUBSCRIBED';
  elsif coalesce(v_previous.current_period_end, 0) < p_current_period_end then
    v_history_event := 'RENEWED';
  end if;

  if v_history_event is not null then
    insert into public.subscription_history (
      organization_id, subscription_id, previous_plan_id, new_plan_id,
      event_type, effective_at, previous_status, new_status, notes
    ) values (
      p_organization_id, v_subscription.id, v_previous.plan_id, v_subscription.plan_id,
      v_history_event, p_provider_event_time, v_previous.status::text, p_status,
      'Google Play state verified by trusted backend'
    );
  end if;

  return pg_catalog.jsonb_build_object(
    'result', 'APPLIED',
    'organization_id', p_organization_id,
    'subscription_id', v_subscription.id,
    'status', p_status,
    'current_period_end', p_current_period_end,
    'verified_at', v_now_ms
  );
end;
$$;

revoke all on function public.apply_verified_play_subscription(
  uuid, uuid, text, text, text, text, text, text, text, bigint, bigint, bigint,
  boolean, boolean, bigint, bigint, text, text
) from public, anon, authenticated;
grant execute on function public.apply_verified_play_subscription(
  uuid, uuid, text, text, text, text, text, text, text, bigint, bigint, bigint,
  boolean, boolean, bigint, bigint, text, text
) to service_role;

-- Trial claim and entitlement creation share one transaction. HMAC identities
-- are supplied by subscription-trial; raw phone/email are never persisted.
create or replace function public.provision_owner_trial(
  p_organization_id uuid,
  p_profile_id uuid,
  p_plan_code text,
  p_billing_cycle text,
  p_identity_hashes text[]
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_plan public.subscription_plans%rowtype;
  v_existing public.subscriptions%rowtype;
  v_subscription public.subscriptions%rowtype;
  v_now timestamptz := pg_catalog.clock_timestamp();
  v_now_ms bigint;
  v_trial_end bigint;
  v_identity text;
  v_claimed integer := 0;
begin
  if auth.role() is distinct from 'service_role' then
    raise exception using errcode = '42501', message = 'TRIAL_SERVICE_ROLE_REQUIRED';
  end if;
  if p_organization_id is null or p_profile_id is null or p_plan_code is null or p_billing_cycle is null
     or p_plan_code not in ('FREE', 'STARTER', 'BUSINESS', 'PRO')
     or p_billing_cycle not in ('MONTHLY', 'ANNUAL') then
    raise exception using errcode = '22023', message = 'TRIAL_INVALID_REQUEST';
  end if;
  if not exists (
    select 1 from public.organization_members m
    where m.organization_id = p_organization_id and m.profile_id = p_profile_id
      and m.role = 'OWNER' and m.is_active
  ) then
    raise exception using errcode = '42501', message = 'TRIAL_OWNER_MEMBERSHIP_REQUIRED';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('trial-org:' || p_organization_id::text, 0));
  select * into v_existing
  from public.subscriptions s
  where s.organization_id = p_organization_id and s.deleted_at is null
  for update;
  if found then
    return pg_catalog.jsonb_build_object('result', 'SUBSCRIPTION_EXISTS', 'subscription_id', v_existing.id);
  end if;

  select * into v_plan from public.subscription_plans p
  where p.code = p_plan_code and p.is_active;
  if not found then
    raise exception using errcode = '22023', message = 'TRIAL_PLAN_UNAVAILABLE';
  end if;

  if p_plan_code <> 'FREE' then
    if coalesce(array_length(p_identity_hashes, 1), 0) = 0
       or array_length(p_identity_hashes, 1) > 2
       or exists (select 1 from unnest(p_identity_hashes) h where h !~ '^[0-9a-f]{64}$') then
      raise exception using errcode = '22023', message = 'TRIAL_VERIFIED_CONTACT_REQUIRED';
    end if;
    for v_identity in
      select distinct h from unnest(p_identity_hashes) h order by h
    loop
      perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('trial-identity:' || v_identity, 0));
    end loop;
    if exists (
      select 1 from public.owner_trial_claims c
      join (select distinct unnest(p_identity_hashes) as identity_hash) incoming using (identity_hash)
    ) then
      return pg_catalog.jsonb_build_object('result', 'TRIAL_ALREADY_CONSUMED');
    end if;
  elsif coalesce(array_length(p_identity_hashes, 1), 0) > 0 then
    raise exception using errcode = '22023', message = 'TRIAL_FREE_PLAN_MUST_NOT_CLAIM';
  end if;

  v_now_ms := (extract(epoch from v_now) * 1000)::bigint;
  v_trial_end := case when p_plan_code = 'FREE' then null
    else v_now_ms + greatest(v_plan.trial_days, 0)::bigint * 86400000 end;

  begin
    if p_plan_code <> 'FREE' then
      insert into public.owner_trial_claims (identity_hash, claimed_at, updated_at)
      select distinct h, v_now, v_now
      from unnest(p_identity_hashes) h;
      get diagnostics v_claimed = row_count;
      if v_claimed <> (select count(distinct h) from unnest(p_identity_hashes) h) then
        raise unique_violation using message = 'TRIAL_IDENTITY_ALREADY_CLAIMED';
      end if;
    end if;

    insert into public.subscriptions (
      organization_id, plan_id, status, billing_cycle, started_at,
      current_period_start, current_period_end, trial_started_at, trial_ends_at,
      auto_renew, cancel_at_period_end, provider, client_updated_at, updated_at
    ) values (
      p_organization_id, v_plan.id,
      case when p_plan_code = 'FREE' then 'FREE'::public.subscription_status else 'TRIALING'::public.subscription_status end,
      case when p_plan_code = 'FREE' then 'MONTHLY' else p_billing_cycle end,
      v_now_ms, v_now_ms, coalesce(v_trial_end, v_now_ms),
      case when p_plan_code = 'FREE' then null else v_now_ms end,
      v_trial_end, false, false, 'AQUAHUB_TRIAL', v_now_ms, v_now
    ) returning * into v_subscription;

    insert into public.subscription_history (
      organization_id, subscription_id, new_plan_id, event_type,
      effective_at, new_status, notes
    ) values (
      p_organization_id, v_subscription.id, v_plan.id,
      case when p_plan_code = 'FREE' then 'SUBSCRIBED' else 'TRIAL_STARTED' end,
      v_now_ms, v_subscription.status::text, 'Supabase owner trial provisioning'
    );
  exception
    when unique_violation then
      if exists (
        select 1 from public.subscriptions s
        where s.organization_id = p_organization_id and s.deleted_at is null
      ) then
        return pg_catalog.jsonb_build_object('result', 'SUBSCRIPTION_EXISTS');
      end if;
      return pg_catalog.jsonb_build_object('result', 'TRIAL_ALREADY_CONSUMED');
  end;

  return pg_catalog.jsonb_build_object('result', 'PROVISIONED', 'subscription_id', v_subscription.id);
end;
$$;

revoke all on function public.provision_owner_trial(uuid, uuid, text, text, text[]) from public, anon, authenticated;
grant execute on function public.provision_owner_trial(uuid, uuid, text, text, text[]) to service_role;

commit;
