-- Local only. Exercises database idempotency and provider/local state separation.
create extension if not exists pgtap with schema extensions;

begin;
select extensions.plan(4);
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

create temporary table marketing_test_subscriber as
select (public.record_marketing_consent(
  'reach-integration-test@example.invalid',
  'AquaHub Test',
  'website',
  '2026-10-v1',
  null
) ->> 'subscriber_id')::uuid as id;

select extensions.ok(
  (select s.sync_status = 'pending' and s.reach_status = 'unknown'
   from app_private.marketing_subscribers s
   join marketing_test_subscriber t on t.id = s.id),
  'local consent is queued without assuming Reach subscription status'
);

update app_private.marketing_subscribers s
set sync_status = 'processing'
from marketing_test_subscriber t
where s.id = t.id;

select public.complete_marketing_reach_sync(
  p_subscriber_id := (select id from marketing_test_subscriber),
  p_sync_action := 'sync',
  p_success := true,
  p_reach_status := 'pending',
  p_contact_uuid := 'reach-test-contact',
  p_confirmation_status := 'pending'
);

select extensions.ok(
  (select s.sync_status = 'accepted'
      and s.reach_status = 'pending'
      and s.confirmation_status = 'pending'
   from app_private.marketing_subscribers s
   join marketing_test_subscriber t on t.id = s.id),
  'provider confirmation state is saved only after Reach reports it'
);

select public.record_marketing_consent(
  'reach-integration-test@example.invalid',
  'AquaHub Test',
  'website',
  '2026-10-v1',
  null
);

select extensions.ok(
  (select count(*) = 1
   from app_private.marketing_consent_events e
   join marketing_test_subscriber t on t.id = e.subscriber_id),
  'duplicate consent submission does not create duplicate consent events'
);

update app_private.marketing_subscribers s
set reach_status = 'unsubscribed', sync_status = 'suppressed'
from marketing_test_subscriber t
where s.id = t.id;

select public.record_marketing_consent(
  'reach-integration-test@example.invalid',
  'AquaHub Test',
  'website',
  '2026-10-v1',
  null
);

select extensions.ok(
  (select s.reach_status = 'unsubscribed' and s.sync_status = 'suppressed'
   from app_private.marketing_subscribers s
   join marketing_test_subscriber t on t.id = s.id),
  'a provider-reported unsubscribe remains suppressed after a duplicate submission'
);

select * from extensions.finish();
rollback;
