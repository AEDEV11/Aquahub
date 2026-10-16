-- Fix the owner guard in the SECURITY DEFINER marketing preference RPCs.
-- CURRENT_ROLE is special PostgreSQL syntax, so PL/pgSQL cannot safely use it
-- as an ordinary local variable name. Under SECURITY DEFINER it resolves to
-- the function owner's role instead of the profile's role.
create or replace function public.get_my_marketing_preference()
returns table (subscribed boolean, suppressed boolean, email_available boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  current_email text;
  owner_profile_role text;
  subscriber app_private.marketing_subscribers%rowtype;
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'authenticated' or current_user_id is null then
    raise exception using errcode = '42501', message = 'owner authentication is required';
  end if;
  select lower(coalesce(u.email, p.email)), p.role::text
    into current_email, owner_profile_role
  from auth.users u join public.profiles p on p.id = u.id
  where u.id = current_user_id;
  if owner_profile_role is distinct from 'OWNER' then
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
  owner_profile_role text;
  subscriber app_private.marketing_subscribers%rowtype;
  preference_source text := 'owner_app';
  policy_version text := 'owner-marketing-v1';
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'authenticated' or current_user_id is null
     or p_subscribed is null then
    raise exception using errcode = '42501', message = 'owner preference request is invalid';
  end if;
  select lower(coalesce(p.email, u.email)), p.display_name, p.role::text
    into current_email, current_name, owner_profile_role
  from auth.users u join public.profiles p on p.id = u.id
  where u.id = current_user_id;
  if owner_profile_role is distinct from 'OWNER' then
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
