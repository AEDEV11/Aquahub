-- Local only. Verifies subscription updates queue fresh Reach audience tags.
create extension if not exists pgtap with schema extensions;

begin;
select extensions.plan(2);
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

create temporary table marketing_plan_fixture (
  owner_id uuid not null default pg_catalog.gen_random_uuid(),
  organization_id uuid not null default pg_catalog.gen_random_uuid(),
  subscriber_id uuid
);
insert into marketing_plan_fixture default values;

insert into auth.users (id)
select owner_id from marketing_plan_fixture;

insert into public.profiles (id, display_name, role)
select owner_id, 'Reach plan test owner', 'OWNER'::public.member_role
from marketing_plan_fixture;

insert into public.organizations (id, owner_profile_id, name)
select organization_id, owner_id, 'Reach plan test station'
from marketing_plan_fixture;

insert into public.subscriptions (organization_id, plan_id, status, billing_cycle)
select f.organization_id, p.id, 'FREE'::public.subscription_status, 'MONTHLY'
from marketing_plan_fixture f
cross join public.subscription_plans p
where p.code = 'FREE';

update marketing_plan_fixture f
set subscriber_id = (
  public.record_marketing_consent(
    'reach-plan-owner@example.invalid',
    'Plan Test Owner',
    'owner_app',
    'owner-marketing-v1',
    f.owner_id
  ) ->> 'subscriber_id'
)::uuid;

update app_private.marketing_subscribers s
set sync_status = 'accepted', reach_status = 'subscribed'
from marketing_plan_fixture f
where s.id = f.subscriber_id;

update public.subscriptions s
set plan_id = (select p.id from public.subscription_plans p where p.code = 'STARTER'),
    status = 'ACTIVE'::public.subscription_status,
    updated_at = pg_catalog.now()
from marketing_plan_fixture f
where s.organization_id = f.organization_id;

select extensions.ok(
  (select s.sync_status = 'pending' and s.sync_action = 'sync'
   from app_private.marketing_subscribers s
   join marketing_plan_fixture f on f.subscriber_id = s.id),
  'plan changes enqueue a new Reach tag synchronization only for consented owners'
);

select extensions.is(
  (select c.current_plan_code
   from public.claim_marketing_reach_sync(10, (select subscriber_id from marketing_plan_fixture)) c),
  'STARTER'::text,
  'the sync queue reads the owner''s current plan for Reach segmentation'
);

select * from extensions.finish();
rollback;
