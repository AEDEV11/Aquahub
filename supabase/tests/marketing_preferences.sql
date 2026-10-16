-- Local only. Verifies owner consent RPC behavior and rejects non-owners.
create extension if not exists pgtap with schema extensions;

begin;
select extensions.plan(7);

create temporary table marketing_preference_fixture as
select pg_catalog.gen_random_uuid() as owner_id,
       pg_catalog.gen_random_uuid() as rider_id;

insert into auth.users (id, email)
select owner_id, 'marketing-owner-' || owner_id || '@example.invalid'
from marketing_preference_fixture
union all
select rider_id, 'marketing-rider-' || rider_id || '@example.invalid'
from marketing_preference_fixture;

insert into public.profiles (id, display_name, email, role)
select owner_id, 'Marketing Preference Owner',
       'marketing-owner-' || owner_id || '@example.invalid', 'OWNER'::public.member_role
from marketing_preference_fixture
union all
select rider_id, 'Marketing Preference Rider',
       'marketing-rider-' || rider_id || '@example.invalid', 'RIDER'::public.member_role
from marketing_preference_fixture;

select set_config(
  'request.jwt.claims',
  pg_catalog.jsonb_build_object(
    'role', 'authenticated',
    'sub', (select owner_id::text from marketing_preference_fixture)
  )::text,
  true
);

select extensions.results_eq(
  $$select subscribed, suppressed, email_available from public.get_my_marketing_preference()$$,
  $$values (false, false, true)$$,
  'owner can read an available email preference before opting in'
);

select extensions.results_eq(
  $$select subscribed, suppressed, email_available from public.set_my_marketing_preference(true)$$,
  $$values (true, false, true)$$,
  'owner can subscribe through the preference RPC'
);

select extensions.ok(
  (select consent_status = 'granted'
      and source = 'owner_app'
      and sync_status = 'pending'
      and sync_action = 'sync'
      and consented_at is not null
      and consent_policy_version = 'owner-marketing-v1'
   from app_private.marketing_subscribers s
   join marketing_preference_fixture f on s.owner_user_id = f.owner_id)
  and exists (
    select 1
    from app_private.marketing_consent_events e
    join app_private.marketing_subscribers s on s.id = e.subscriber_id
    join marketing_preference_fixture f on s.owner_user_id = f.owner_id
    where e.action = 'consent_granted' and e.actor_user_id = f.owner_id
  ),
  'subscription records explicit owner consent and queues synchronization'
);

select extensions.results_eq(
  $$select subscribed, suppressed, email_available from public.set_my_marketing_preference(false)$$,
  $$values (false, false, true)$$,
  'owner can withdraw consent through the preference RPC'
);

select extensions.ok(
  (select consent_status = 'withdrawn'
      and unsubscribed_at is not null
      and sync_status = 'removal_pending'
      and sync_action = 'remove'
   from app_private.marketing_subscribers s
   join marketing_preference_fixture f on s.owner_user_id = f.owner_id)
  and exists (
    select 1
    from app_private.marketing_consent_events e
    join app_private.marketing_subscribers s on s.id = e.subscriber_id
    join marketing_preference_fixture f on s.owner_user_id = f.owner_id
    where e.action = 'consent_withdrawn' and e.actor_user_id = f.owner_id
  ),
  'withdrawal is audited and queues provider removal'
);

select set_config(
  'request.jwt.claims',
  pg_catalog.jsonb_build_object(
    'role', 'authenticated',
    'sub', (select rider_id::text from marketing_preference_fixture)
  )::text,
  true
);

select extensions.throws_ok(
  $$select * from public.get_my_marketing_preference()$$,
  '42501', 'owner access is required',
  'riders cannot read owner marketing preferences'
);

select extensions.throws_ok(
  $$select * from public.set_my_marketing_preference(true)$$,
  '42501', 'owner access is required',
  'riders cannot change marketing preferences'
);

select * from extensions.finish();
rollback;
