-- A fresh Play purchase may have no linkedPurchaseToken after the prior paid
-- period has ended. Play Store resubscriptions instead identify the expired
-- predecessor through outOfAppPurchaseContext. Reconcile both cases without
-- weakening current-token, cross-organization, or stale-event protections.
-- No existing rows are changed; only future trusted RPC calls take these paths.
begin;

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
  v_unlinked_replacement_hash text;
  v_replaced_token_hash text;
  v_claim_linked_rtdn boolean := false;
  v_claim_out_of_app boolean := false;
begin
  if auth.role() is distinct from 'service_role' then
    raise exception using errcode = '42501', message = 'PLAY_SERVICE_ROLE_REQUIRED';
  end if;
  if p_source is null or p_source not in ('PURCHASE', 'OUT_OF_APP_PURCHASE', 'RTDN', 'RECONCILIATION') then
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
  if p_source in ('PURCHASE', 'OUT_OF_APP_PURCHASE') and (p_actor_user_id is null or not exists (
    select 1 from public.organization_members m
    where m.organization_id = p_organization_id and m.profile_id = p_actor_user_id
      and m.role = 'OWNER' and m.is_active
  )) then
    raise exception using errcode = '42501', message = 'PLAY_OWNER_MEMBERSHIP_REQUIRED';
  end if;

  -- The organization lock lets us inspect its current subscription before
  -- acquiring all relevant token locks in one stable order. This includes the
  -- old token discovered for a previously unlinked, now-expired period.
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('play-org:' || p_organization_id::text, 0));
  select * into v_previous
  from public.subscriptions s
  where s.organization_id = p_organization_id and s.deleted_at is null
  for update;

  if p_source = 'PURCHASE' and p_linked_token_hash is null
     and v_previous.id is not null and v_previous.provider = 'GOOGLE_PLAY'
     and v_previous.external_subscription_id is not null
     and v_previous.external_subscription_id is distinct from p_token_hash
     and v_previous.current_period_end is not null
     and v_previous.current_period_end <= v_now_ms
     and (v_previous.grace_period_ends_at is null or v_previous.grace_period_ends_at <= v_now_ms)
     and p_current_period_end > v_now_ms
     and p_current_period_end > v_previous.current_period_end
     and p_status <> 'EXPIRED' then
    v_unlinked_replacement_hash := v_previous.external_subscription_id;
  end if;

  for v_lock_key in
    select distinct lock_value
    from unnest(array[
      'play-token:' || p_token_hash,
      case when p_linked_token_hash is null then null else 'play-token:' || p_linked_token_hash end,
      case when v_unlinked_replacement_hash is null then null else 'play-token:' || v_unlinked_replacement_hash end
    ]) as keys(lock_value)
    where lock_value is not null
    order by lock_value
  loop
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_lock_key, 0));
  end loop;

  select * into v_token
  from public.google_play_subscription_tokens t
  where t.token_hash = p_token_hash
  for update;

  if p_source in ('RTDN', 'RECONCILIATION') then
    if not found or v_token.organization_id is distinct from p_organization_id or v_token.superseded_at is not null then
      -- RTDN may arrive before checkout verification. It may claim a new token
      -- only through Play's linked predecessor, and only while that token is
      -- still the organization's current subscription. Reconciliation cannot
      -- establish new ownership from a stored row alone.
      if p_source = 'RTDN'
         and (not found or v_token.organization_id is null)
         and (not found or v_token.superseded_at is null)
         and p_linked_token_hash is not null
         and v_previous.id is not null
         and v_previous.provider = 'GOOGLE_PLAY'
         and v_previous.external_subscription_id = p_linked_token_hash then
        v_claim_linked_rtdn := true;
      else
        return pg_catalog.jsonb_build_object('result', 'STALE_OR_UNBOUND_TOKEN');
      end if;
    end if;
  elsif found and v_token.organization_id is not null and v_token.organization_id <> p_organization_id then
    raise exception using errcode = '23505', message = 'PLAY_TOKEN_BOUND_TO_ANOTHER_ORGANIZATION';
  elsif found and v_token.superseded_at is not null then
    raise exception using errcode = '23505', message = 'PLAY_TOKEN_SUPERSEDED';
  end if;

  if p_source = 'OUT_OF_APP_PURCHASE'
     and (v_token.token_hash is null or v_token.organization_id is null) then
    -- The authenticated restore may claim Play's out-of-app renewal only via
    -- its verified, still-current, expired predecessor. A linked token from
    -- another business or an already-replaced token cannot be repurposed.
    if p_linked_token_hash is null
       or v_previous.id is null
       or v_previous.provider <> 'GOOGLE_PLAY'
       or v_previous.external_subscription_id is distinct from p_linked_token_hash
       or v_previous.current_period_end is null
       or v_previous.current_period_end > v_now_ms
       or (v_previous.grace_period_ends_at is not null
           and v_previous.grace_period_ends_at > v_now_ms)
       or p_current_period_end <= v_now_ms
       or p_current_period_end <= v_previous.current_period_end
       or p_status = 'EXPIRED' then
      return pg_catalog.jsonb_build_object('result', 'STALE_LINKED_TOKEN');
    end if;
    v_claim_out_of_app := true;
  end if;

  -- Every caller passes the time immediately before its Play API fetch.
  -- A delayed response must not overwrite a newer verification, regardless
  -- of whether it came from checkout, RTDN, or scheduled reconciliation.
  if v_previous.provider_event_time = p_provider_event_time then
    -- Millisecond clocks can tie on an immediate retry. Report an identical
    -- already-committed state as success so acknowledgement can finish, but
    -- fail closed if the provider state differs within that millisecond.
    if v_previous.external_subscription_id = p_token_hash
       and v_previous.plan_id = (
         select p.id from public.subscription_plans p where p.code = p_plan_code and p.is_active
       )
       and v_previous.status::text = p_status
       and v_previous.billing_cycle = p_billing_cycle
       and v_previous.current_period_start = p_current_period_start
       and v_previous.current_period_end = p_current_period_end
       and v_previous.grace_period_ends_at is not distinct from p_grace_period_ends_at
       and v_previous.auto_renew = p_auto_renew
       and v_previous.cancel_at_period_end = p_cancel_at_period_end then
      return pg_catalog.jsonb_build_object(
        'result', 'APPLIED', 'organization_id', p_organization_id,
        'subscription_id', v_previous.id, 'status', p_status,
        'current_period_end', p_current_period_end, 'verified_at', v_now_ms
      );
    end if;
    return pg_catalog.jsonb_build_object('result', 'STALE_EVENT');
  end if;
  if v_previous.provider_event_time is not null
     and v_previous.provider_event_time > p_provider_event_time then
    return pg_catalog.jsonb_build_object('result', 'STALE_EVENT');
  end if;

  if v_previous.id is not null and v_previous.provider = 'GOOGLE_PLAY'
     and v_previous.external_subscription_id is distinct from p_token_hash
     and (p_linked_token_hash is null or v_previous.external_subscription_id is distinct from p_linked_token_hash)
     and v_unlinked_replacement_hash is null then
    return pg_catalog.jsonb_build_object('result', 'STALE_TOKEN');
  end if;

  v_replaced_token_hash := coalesce(v_unlinked_replacement_hash, p_linked_token_hash);
  if v_replaced_token_hash is not null and v_replaced_token_hash <> p_token_hash then
    select * into v_linked
    from public.google_play_subscription_tokens t
    where t.token_hash = v_replaced_token_hash
    for update;
    if (v_claim_linked_rtdn or v_claim_out_of_app)
       and (not found or v_linked.organization_id is distinct from p_organization_id
         or v_linked.superseded_at is not null) then
      return pg_catalog.jsonb_build_object('result', 'STALE_LINKED_TOKEN');
    end if;
    if v_unlinked_replacement_hash is not null
       and (not found or v_linked.organization_id is distinct from p_organization_id
         or v_linked.superseded_at is not null) then
      return pg_catalog.jsonb_build_object('result', 'STALE_TOKEN');
    end if;
    if found and v_linked.organization_id is not null and v_linked.organization_id <> p_organization_id then
      raise exception using errcode = '23505', message = 'PLAY_LINKED_TOKEN_BOUND_TO_ANOTHER_ORGANIZATION';
    end if;
    if found and v_linked.superseded_at is not null
       and v_previous.external_subscription_id is distinct from v_replaced_token_hash
       and v_previous.external_subscription_id is distinct from p_token_hash then
      return pg_catalog.jsonb_build_object('result', 'STALE_LINKED_TOKEN');
    end if;
  end if;

  if p_source = 'RECONCILIATION' or (p_source = 'RTDN' and not v_claim_linked_rtdn) then
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

  if v_replaced_token_hash is not null and v_replaced_token_hash <> p_token_hash then
    update public.google_play_subscription_tokens
    set organization_id = p_organization_id,
        superseded_at = coalesce(superseded_at, pg_catalog.clock_timestamp()),
        updated_at = pg_catalog.clock_timestamp()
    where token_hash = v_replaced_token_hash
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

-- Scheduled reconciliation rotates attempted rows so a permanently invalid
-- token cannot starve other renewals. Only the trusted service role may touch
-- this cursor field directly; token ownership remains inside the RPC.
grant update(updated_at) on public.google_play_subscription_tokens to service_role;

commit;
