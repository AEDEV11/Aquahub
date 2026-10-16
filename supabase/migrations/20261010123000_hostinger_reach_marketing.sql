-- Consent-based AquaHub marketing subscriptions and the server-only Reach queue.
create table app_private.marketing_subscribers (
  id uuid primary key default pg_catalog.gen_random_uuid(),
  email_normalized text not null,
  first_name text,
  owner_user_id uuid references auth.users(id) on delete set null,
  source text not null check (source in ('website', 'owner_app')),
  consent_status text not null check (consent_status in ('granted', 'withdrawn')),
  consented_at timestamptz,
  consent_policy_version text,
  unsubscribed_at timestamptz,
  confirmation_status text not null default 'unknown'
    check (confirmation_status in ('unknown', 'pending', 'confirmed', 'not_required')),
  reach_status text not null default 'unknown'
    check (reach_status in ('unknown', 'pending', 'subscribed', 'confirmed', 'unsubscribed', 'suppressed')),
  reach_contact_uuid text,
  sync_status text not null default 'pending'
    check (sync_status in ('pending', 'processing', 'accepted', 'suppressed', 'removal_pending', 'removed', 'failed')),
  sync_action text not null default 'sync' check (sync_action in ('sync', 'remove')),
  sync_attempts integer not null default 0 check (sync_attempts >= 0),
  plan_sync_pending boolean not null default false,
  next_attempt_at timestamptz not null default pg_catalog.now(),
  lease_expires_at timestamptz,
  last_sync_error_code text,
  last_successful_sync_at timestamptz,
  created_at timestamptz not null default pg_catalog.now(),
  updated_at timestamptz not null default pg_catalog.now(),
  constraint marketing_subscribers_email_valid check (
    email_normalized = lower(btrim(email_normalized))
    and length(email_normalized) between 3 and 254
    and email_normalized ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
  ),
  constraint marketing_subscribers_consent_timestamp check (
    (consent_status = 'granted' and consented_at is not null)
    or (consent_status = 'withdrawn' and unsubscribed_at is not null)
  )
);
create unique index marketing_subscribers_email_uidx
  on app_private.marketing_subscribers (email_normalized);
create unique index marketing_subscribers_owner_uidx
  on app_private.marketing_subscribers (owner_user_id) where owner_user_id is not null;
create index marketing_subscribers_sync_queue_idx
  on app_private.marketing_subscribers (next_attempt_at, created_at)
  where sync_status in ('pending', 'removal_pending', 'failed');

create table app_private.marketing_consent_events (
  id uuid primary key default pg_catalog.gen_random_uuid(),
  subscriber_id uuid not null references app_private.marketing_subscribers(id) on delete cascade,
  action text not null check (action in ('consent_granted', 'consent_withdrawn')),
  source text not null check (source in ('website', 'owner_app')),
  policy_version text,
  actor_user_id uuid references auth.users(id) on delete set null,
  occurred_at timestamptz not null default pg_catalog.now()
);
create index marketing_consent_events_subscriber_time_idx
  on app_private.marketing_consent_events (subscriber_id, occurred_at desc);

create table app_private.marketing_rate_limits (
  dimension text not null check (dimension in ('ip', 'email')),
  fingerprint text not null check (fingerprint ~ '^[0-9a-f]{64}$'),
  window_start timestamptz not null,
  request_count integer not null default 1 check (request_count > 0),
  primary key (dimension, fingerprint, window_start)
);

alter table app_private.marketing_subscribers enable row level security;
alter table app_private.marketing_consent_events enable row level security;
alter table app_private.marketing_rate_limits enable row level security;
revoke all on app_private.marketing_subscribers from public, anon, authenticated, service_role;
revoke all on app_private.marketing_consent_events from public, anon, authenticated, service_role;
revoke all on app_private.marketing_rate_limits from public, anon, authenticated, service_role;
comment on table app_private.marketing_subscribers is
  'Private consent state and durable Hostinger Reach synchronization state. Reach status is provider-reported only.';
comment on table app_private.marketing_consent_events is
  'Append-only audit of explicit AquaHub marketing consent grants and withdrawals.';

create or replace function public.consume_marketing_rate_limit(
  p_ip_fingerprint text,
  p_email_fingerprint text
)
returns table (allowed boolean, retry_after_seconds integer)
language plpgsql
security definer
set search_path = ''
as $$
declare
  ip_bucket timestamptz := pg_catalog.date_trunc('hour', pg_catalog.now())
    + pg_catalog.make_interval(mins => (extract(minute from pg_catalog.now())::integer / 15) * 15);
  email_bucket timestamptz := pg_catalog.date_trunc('hour', pg_catalog.now());
  ip_hits integer;
  email_hits integer;
  retry_seconds integer;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role'
     or p_ip_fingerprint is null or p_ip_fingerprint !~ '^[0-9a-f]{64}$'
     or p_email_fingerprint is null or p_email_fingerprint !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '42501', message = 'rate limit request is invalid';
  end if;
  insert into app_private.marketing_rate_limits (dimension, fingerprint, window_start, request_count)
  values ('ip', p_ip_fingerprint, ip_bucket, 1)
  on conflict (dimension, fingerprint, window_start)
  do update set request_count = app_private.marketing_rate_limits.request_count + 1
  returning request_count into ip_hits;
  insert into app_private.marketing_rate_limits (dimension, fingerprint, window_start, request_count)
  values ('email', p_email_fingerprint, email_bucket, 1)
  on conflict (dimension, fingerprint, window_start)
  do update set request_count = app_private.marketing_rate_limits.request_count + 1
  returning request_count into email_hits;
  delete from app_private.marketing_rate_limits
  where window_start < pg_catalog.now() - interval '2 days';
  retry_seconds := greatest(
    1,
    ceil(extract(epoch from ip_bucket + interval '15 minutes' - pg_catalog.now()))::integer,
    ceil(extract(epoch from email_bucket + interval '1 hour' - pg_catalog.now()))::integer
  );
  return query select ip_hits <= 8 and email_hits <= 4, retry_seconds;
end;
$$;
revoke all on function public.consume_marketing_rate_limit(text, text) from public, anon, authenticated, service_role;
grant execute on function public.consume_marketing_rate_limit(text, text) to service_role;

create or replace function public.record_marketing_consent(
  p_email text,
  p_first_name text,
  p_source text,
  p_policy_version text,
  p_owner_user_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  normalized_email text := lower(btrim(coalesce(p_email, '')));
  normalized_first_name text := nullif(btrim(coalesce(p_first_name, '')), '');
  subscriber app_private.marketing_subscribers%rowtype;
  is_new boolean := false;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role' then
    raise exception using errcode = '42501', message = 'marketing request is not authorized';
  end if;
  if p_source is null or p_source not in ('website', 'owner_app')
     or length(normalized_email) not between 3 and 254
     or normalized_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
     or (p_owner_user_id is not null and p_source <> 'owner_app') then
    raise exception using errcode = '22023', message = 'marketing request is invalid';
  end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(normalized_email, 0));
  select * into subscriber
  from app_private.marketing_subscribers s
  where s.email_normalized = normalized_email
  for update;

  if subscriber.id is null then
    insert into app_private.marketing_subscribers (
      email_normalized, first_name, owner_user_id, source, consent_status,
      consented_at, consent_policy_version, sync_status, sync_action, next_attempt_at
    ) values (
      normalized_email, normalized_first_name, p_owner_user_id, p_source, 'granted',
      pg_catalog.now(), nullif(btrim(coalesce(p_policy_version, '')), ''), 'pending', 'sync', pg_catalog.now()
    ) returning * into subscriber;
    is_new := true;
  elsif subscriber.consent_status = 'withdrawn' then
    update app_private.marketing_subscribers s
    set consent_status = 'granted',
        consented_at = pg_catalog.now(),
        consent_policy_version = nullif(btrim(coalesce(p_policy_version, '')), ''),
        unsubscribed_at = null,
        first_name = coalesce(normalized_first_name, s.first_name),
        owner_user_id = coalesce(s.owner_user_id, p_owner_user_id),
        source = case when p_owner_user_id is not null then 'owner_app' else s.source end,
        sync_status = case when s.reach_status = 'unsubscribed' then 'suppressed' else 'pending' end,
        sync_action = 'sync', sync_attempts = 0, next_attempt_at = pg_catalog.now(),
        lease_expires_at = null, last_sync_error_code = null, updated_at = pg_catalog.now()
    where s.id = subscriber.id
    returning * into subscriber;
    insert into app_private.marketing_consent_events (
      subscriber_id, action, source, policy_version, actor_user_id
    ) values (
      subscriber.id, 'consent_granted', p_source,
      nullif(btrim(coalesce(p_policy_version, '')), ''), p_owner_user_id
    );
  else
    update app_private.marketing_subscribers s
    set first_name = coalesce(normalized_first_name, s.first_name),
        owner_user_id = coalesce(s.owner_user_id, p_owner_user_id),
        source = case when p_owner_user_id is not null then 'owner_app' else s.source end,
        next_attempt_at = case when s.sync_status in ('failed', 'pending') then least(s.next_attempt_at, pg_catalog.now()) else s.next_attempt_at end,
        updated_at = pg_catalog.now()
    where s.id = subscriber.id
    returning * into subscriber;
  end if;

  if is_new then
    insert into app_private.marketing_consent_events (
      subscriber_id, action, source, policy_version, actor_user_id
    ) values (
      subscriber.id, 'consent_granted', p_source,
      nullif(btrim(coalesce(p_policy_version, '')), ''), p_owner_user_id
    );
  end if;
  return pg_catalog.jsonb_build_object(
    'subscriber_id', subscriber.id,
    'status', 'accepted',
    'sync_required', subscriber.sync_status in ('pending', 'failed')
  );
end;
$$;
revoke all on function public.record_marketing_consent(text, text, text, text, uuid) from public, anon, authenticated, service_role;
grant execute on function public.record_marketing_consent(text, text, text, text, uuid) to service_role;

create or replace function public.get_my_marketing_preference()
returns table (subscribed boolean, suppressed boolean, email_available boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  current_email text;
  current_role text;
  subscriber app_private.marketing_subscribers%rowtype;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'authenticated' or current_user_id is null then
    raise exception using errcode = '42501', message = 'owner authentication is required';
  end if;
  select lower(coalesce(u.email, p.email)), p.role::text into current_email, current_role
  from auth.users u join public.profiles p on p.id = u.id
  where u.id = current_user_id;
  if current_role is distinct from 'OWNER' then
    raise exception using errcode = '42501', message = 'owner access is required';
  end if;
  if current_email is null then
    return query select false, false, false;
    return;
  end if;
  select * into subscriber
  from app_private.marketing_subscribers s
  where s.owner_user_id = current_user_id or s.email_normalized = current_email
  order by (s.owner_user_id = current_user_id) desc, s.updated_at desc
  limit 1;
  return query select
    coalesce(subscriber.consent_status = 'granted' and subscriber.reach_status <> 'unsubscribed', false),
    coalesce(subscriber.reach_status = 'unsubscribed' or subscriber.sync_status = 'suppressed', false),
    true;
end;
$$;
revoke all on function public.get_my_marketing_preference() from public, anon, service_role;
grant execute on function public.get_my_marketing_preference() to authenticated;

create or replace function public.set_my_marketing_preference(p_subscribed boolean)
returns table (subscribed boolean, suppressed boolean, email_available boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  current_email text;
  current_name text;
  current_role text;
  subscriber app_private.marketing_subscribers%rowtype;
  preference_source text := 'owner_app';
  policy_version text := 'owner-marketing-v1';
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'authenticated' or current_user_id is null
     or p_subscribed is null then
    raise exception using errcode = '42501', message = 'owner preference request is invalid';
  end if;
  select lower(coalesce(p.email, u.email)), p.display_name, p.role::text
  into current_email, current_name, current_role
  from auth.users u join public.profiles p on p.id = u.id
  where u.id = current_user_id;
  if current_role is distinct from 'OWNER' then
    raise exception using errcode = '42501', message = 'owner access is required';
  end if;
  if current_email is null then
    return query select false, false, false;
    return;
  end if;

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(current_email, 0));
  select * into subscriber
  from app_private.marketing_subscribers s
  where s.owner_user_id = current_user_id or s.email_normalized = current_email
  order by (s.owner_user_id = current_user_id) desc, s.updated_at desc
  limit 1
  for update;

  if p_subscribed then
    if subscriber.id is null then
      insert into app_private.marketing_subscribers (
        email_normalized, first_name, owner_user_id, source, consent_status,
        consented_at, consent_policy_version, sync_status, sync_action, next_attempt_at
      ) values (
        current_email, nullif(split_part(btrim(coalesce(current_name, '')), ' ', 1), ''),
        current_user_id, preference_source, 'granted', pg_catalog.now(), policy_version,
        'pending', 'sync', pg_catalog.now()
      ) returning * into subscriber;
      insert into app_private.marketing_consent_events (
        subscriber_id, action, source, policy_version, actor_user_id
      ) values (subscriber.id, 'consent_granted', preference_source, policy_version, current_user_id);
    elsif subscriber.reach_status = 'unsubscribed' then
      update app_private.marketing_subscribers s
      set consent_status = 'granted', consented_at = pg_catalog.now(),
          consent_policy_version = policy_version, unsubscribed_at = null,
          updated_at = pg_catalog.now()
      where s.id = subscriber.id returning * into subscriber;
      insert into app_private.marketing_consent_events (
        subscriber_id, action, source, policy_version, actor_user_id
      ) values (subscriber.id, 'consent_granted', preference_source, policy_version, current_user_id);
    else
      update app_private.marketing_subscribers s
      set consent_status = 'granted', consented_at = pg_catalog.now(),
          consent_policy_version = policy_version, unsubscribed_at = null,
          owner_user_id = coalesce(s.owner_user_id, current_user_id),
          first_name = coalesce(nullif(split_part(btrim(coalesce(current_name, '')), ' ', 1), ''), s.first_name),
          source = 'owner_app', sync_status = 'pending', sync_action = 'sync',
          sync_attempts = 0, next_attempt_at = pg_catalog.now(), lease_expires_at = null,
          last_sync_error_code = null, updated_at = pg_catalog.now()
      where s.id = subscriber.id returning * into subscriber;
      insert into app_private.marketing_consent_events (
        subscriber_id, action, source, policy_version, actor_user_id
      ) values (subscriber.id, 'consent_granted', preference_source, policy_version, current_user_id);
    end if;
  else
    if subscriber.id is null then
      insert into app_private.marketing_subscribers (
        email_normalized, first_name, owner_user_id, source, consent_status,
        unsubscribed_at, consent_policy_version, sync_status, sync_action, next_attempt_at
      ) values (
        current_email, nullif(split_part(btrim(coalesce(current_name, '')), ' ', 1), ''),
        current_user_id, preference_source, 'withdrawn', pg_catalog.now(), policy_version,
        'removal_pending', 'remove', pg_catalog.now()
      ) returning * into subscriber;
      insert into app_private.marketing_consent_events (
        subscriber_id, action, source, policy_version, actor_user_id
      ) values (subscriber.id, 'consent_withdrawn', preference_source, policy_version, current_user_id);
    elsif subscriber.consent_status <> 'withdrawn' then
      update app_private.marketing_subscribers s
      set consent_status = 'withdrawn', unsubscribed_at = pg_catalog.now(),
          sync_status = case when s.reach_status = 'unsubscribed' then 'suppressed' else 'removal_pending' end,
          sync_action = 'remove', next_attempt_at = pg_catalog.now(), lease_expires_at = null,
          last_sync_error_code = null, updated_at = pg_catalog.now()
      where s.id = subscriber.id returning * into subscriber;
      insert into app_private.marketing_consent_events (
        subscriber_id, action, source, policy_version, actor_user_id
      ) values (subscriber.id, 'consent_withdrawn', preference_source, policy_version, current_user_id);
    end if;
  end if;
  return query select
    coalesce(subscriber.consent_status = 'granted' and subscriber.reach_status <> 'unsubscribed', false),
    coalesce(subscriber.reach_status = 'unsubscribed' or subscriber.sync_status = 'suppressed', false),
    true;
end;
$$;
revoke all on function public.set_my_marketing_preference(boolean) from public, anon, service_role;
grant execute on function public.set_my_marketing_preference(boolean) to authenticated;

create or replace function public.claim_marketing_reach_sync(
  p_batch_size integer default 10,
  p_subscriber_id uuid default null
)
returns table (
  subscriber_id uuid,
  email text,
  first_name text,
  owner_user_id uuid,
  source text,
  sync_action text,
  reach_contact_uuid text,
  attempt_count integer,
  current_plan_code text
)
language plpgsql
security definer
set search_path = ''
as $$
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role'
     or p_batch_size not between 1 and 20 then
    raise exception using errcode = '42501', message = 'sync claim is invalid';
  end if;
  return query
  with candidates as (
    select s.id
    from app_private.marketing_subscribers s
    where (s.sync_status in ('pending', 'removal_pending', 'failed')
      or (s.sync_status = 'processing' and s.lease_expires_at < pg_catalog.now()))
      and s.next_attempt_at <= pg_catalog.now()
      and (p_subscriber_id is null or s.id = p_subscriber_id)
    order by s.next_attempt_at, s.created_at
    limit p_batch_size
    for update skip locked
  ), claimed as (
    update app_private.marketing_subscribers s
    set sync_status = 'processing', lease_expires_at = pg_catalog.now() + interval '2 minutes',
        sync_attempts = s.sync_attempts + 1, updated_at = pg_catalog.now()
    from candidates c
    where s.id = c.id
    returning s.*
  )
  select c.id, c.email_normalized, c.first_name, c.owner_user_id, c.source,
    c.sync_action, c.reach_contact_uuid, c.sync_attempts,
    (select sp.code
      from public.organizations o
      join public.subscriptions sub on sub.organization_id = o.id
      join public.subscription_plans sp on sp.id = sub.plan_id
      where o.owner_profile_id = c.owner_user_id
        and sub.deleted_at is null
        and sub.status::text in ('FREE', 'TRIALING', 'ACTIVE', 'ACTIVE_UNTIL_PERIOD_END', 'GRACE_PERIOD')
      order by sub.updated_at desc
      limit 1)
  from claimed c;
end;
$$;
revoke all on function public.claim_marketing_reach_sync(integer, uuid) from public, anon, authenticated, service_role;
grant execute on function public.claim_marketing_reach_sync(integer, uuid) to service_role;

-- Subscription changes enqueue a fresh tag sync for owners who already gave
-- marketing consent. An in-flight request is allowed to finish, then queued
-- once more so the final Reach tags reflect the latest plan.
create or replace function app_private.queue_marketing_plan_sync()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  old_owner uuid;
  new_owner uuid;
begin
  if tg_op = 'UPDATE' then
    if row(
      old.organization_id, old.plan_id, old.status, old.deleted_at,
      old.current_period_end, old.expires_at, old.grace_period_ends_at, old.trial_ends_at
    ) is not distinct from row(
      new.organization_id, new.plan_id, new.status, new.deleted_at,
      new.current_period_end, new.expires_at, new.grace_period_ends_at, new.trial_ends_at
    ) then
      return new;
    end if;
  end if;

  if tg_op <> 'INSERT' then
    select o.owner_profile_id into old_owner
    from public.organizations o where o.id = old.organization_id;
  end if;
  if tg_op <> 'DELETE' then
    select o.owner_profile_id into new_owner
    from public.organizations o where o.id = new.organization_id;
  end if;

  update app_private.marketing_subscribers s
  set plan_sync_pending = (s.sync_status = 'processing'),
      sync_status = case when s.sync_status = 'processing' then s.sync_status else 'pending' end,
      sync_action = 'sync',
      sync_attempts = case when s.sync_status = 'processing' then s.sync_attempts else 0 end,
      next_attempt_at = case when s.sync_status = 'processing' then s.next_attempt_at else pg_catalog.now() end,
      lease_expires_at = case when s.sync_status = 'processing' then s.lease_expires_at else null end,
      last_sync_error_code = case when s.sync_status = 'processing' then s.last_sync_error_code else null end,
      updated_at = pg_catalog.now()
  where s.owner_user_id in (old_owner, new_owner)
    and s.consent_status = 'granted'
    and s.reach_status is distinct from 'unsubscribed'
    and s.sync_status <> 'suppressed'
    and s.sync_action <> 'remove';

  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;
revoke all on function app_private.queue_marketing_plan_sync() from public, anon, authenticated, service_role;
create trigger queue_marketing_plan_sync
after insert or update or delete on public.subscriptions
for each row execute function app_private.queue_marketing_plan_sync();

create or replace function public.complete_marketing_reach_sync(
  p_subscriber_id uuid,
  p_sync_action text,
  p_success boolean,
  p_reach_status text default null,
  p_contact_uuid text default null,
  p_retry_after_seconds integer default null,
  p_safe_error_code text default null,
  p_permanent_failure boolean default false,
  p_confirmation_status text default null
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  updated_count integer;
  provider_status text := case when p_reach_status in ('pending', 'subscribed', 'confirmed', 'unsubscribed', 'suppressed') then p_reach_status else null end;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role'
     or p_sync_action not in ('sync', 'remove')
     or (p_confirmation_status is not null and p_confirmation_status not in ('unknown', 'pending', 'confirmed', 'not_required'))
     or (p_retry_after_seconds is not null and p_retry_after_seconds < 0) then
    raise exception using errcode = '42501', message = 'sync completion is invalid';
  end if;
  update app_private.marketing_subscribers s
  set reach_status = case
        when p_success and p_sync_action = 'remove' then coalesce(provider_status, 'unknown')
        else coalesce(provider_status, s.reach_status)
      end,
      confirmation_status = coalesce(p_confirmation_status, s.confirmation_status),
      plan_sync_pending = false,
      reach_contact_uuid = coalesce(nullif(btrim(coalesce(p_contact_uuid, '')), ''), s.reach_contact_uuid),
      sync_status = case
        when provider_status = 'unsubscribed' then 'suppressed'
        when s.plan_sync_pending then 'pending'
        when p_success and p_sync_action = 'remove' then 'removed'
        when p_success then 'accepted'
        when p_permanent_failure then 'failed'
        when p_sync_action = 'remove' then 'removal_pending'
        else 'pending'
      end,
      sync_action = p_sync_action,
      next_attempt_at = case
        when p_success or provider_status = 'unsubscribed' then pg_catalog.now()
        when p_permanent_failure then pg_catalog.now() + interval '6 hours'
        else pg_catalog.now() + pg_catalog.make_interval(secs => greatest(
          coalesce(p_retry_after_seconds, 60),
          least(86400, 30 * (2 ^ least(s.sync_attempts, 10))::integer)
        ))
      end,
      lease_expires_at = null,
      sync_attempts = case when s.plan_sync_pending then 0 else s.sync_attempts end,
      last_sync_error_code = case when p_success then null else coalesce(nullif(p_safe_error_code, ''), 'provider_unavailable') end,
      last_successful_sync_at = case when p_success then pg_catalog.now() else s.last_successful_sync_at end,
      updated_at = pg_catalog.now()
  where s.id = p_subscriber_id and s.sync_status = 'processing';
  get diagnostics updated_count = row_count;
  return updated_count = 1;
end;
$$;
revoke all on function public.complete_marketing_reach_sync(uuid, text, boolean, text, text, integer, text, boolean, text) from public, anon, authenticated, service_role;
grant execute on function public.complete_marketing_reach_sync(uuid, text, boolean, text, text, integer, text, boolean, text) to service_role;

create or replace function public.reconcile_marketing_reach_suppressions(p_contacts jsonb)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  contact jsonb;
  normalized_email text;
  provider_uuid text;
  changed_count integer := 0;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role'
     or jsonb_typeof(p_contacts) is distinct from 'array'
     or jsonb_array_length(p_contacts) > 100 then
    raise exception using errcode = '42501', message = 'suppression reconciliation request is invalid';
  end if;
  for contact in select value from jsonb_array_elements(p_contacts)
  loop
    if lower(coalesce(contact ->> 'subscription_status', '')) <> 'unsubscribed' then continue; end if;
    normalized_email := lower(btrim(coalesce(contact ->> 'email', '')));
    provider_uuid := nullif(btrim(coalesce(contact ->> 'uuid', '')), '');
    if normalized_email = '' then continue; end if;
    update app_private.marketing_subscribers s
    set reach_status = 'unsubscribed',
        reach_contact_uuid = coalesce(provider_uuid, s.reach_contact_uuid),
        sync_status = 'suppressed', sync_action = 'sync',
        next_attempt_at = pg_catalog.now(), lease_expires_at = null,
        last_sync_error_code = null, updated_at = pg_catalog.now()
    where s.email_normalized = normalized_email
      and s.reach_status is distinct from 'unsubscribed';
    changed_count := changed_count + case when found then 1 else 0 end;
  end loop;
  return changed_count;
end;
$$;
revoke all on function public.reconcile_marketing_reach_suppressions(jsonb) from public, anon, authenticated, service_role;
grant execute on function public.reconcile_marketing_reach_suppressions(jsonb) to service_role;
