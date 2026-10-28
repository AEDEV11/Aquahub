-- Local-only RLS regression test. All fixture rows roll back at the end.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(2);

do $$
declare
  v_owner_id uuid := gen_random_uuid();
  v_other_owner_id uuid := gen_random_uuid();
  v_organization_id uuid := gen_random_uuid();
  v_other_organization_id uuid := gen_random_uuid();
begin
  insert into auth.users (id) values (v_owner_id), (v_other_owner_id);
  insert into public.organizations (id, name)
  values
    (v_organization_id, 'Subscription history RLS owner'),
    (v_other_organization_id, 'Subscription history RLS other');
  insert into public.profiles (id, display_name, role, preferred_business_id)
  values
    (v_owner_id, 'History owner', 'OWNER'::public.member_role, v_organization_id),
    (v_other_owner_id, 'Other owner', 'OWNER'::public.member_role, v_other_organization_id);
  insert into public.organization_members (organization_id, profile_id, role)
  values
    (v_organization_id, v_owner_id, 'OWNER'::public.member_role),
    (v_other_organization_id, v_other_owner_id, 'OWNER'::public.member_role);
  insert into public.subscription_history (
    organization_id, event_type, effective_at, new_status, notes
  ) values
    (v_organization_id, 'SUBSCRIBED', 1000, 'TRIALING', 'owner history'),
    (v_other_organization_id, 'SUBSCRIBED', 2000, 'TRIALING', 'other tenant history');
  perform pg_catalog.set_config('test.subscription_history.owner_id', v_owner_id::text, true);
  perform pg_catalog.set_config('test.subscription_history.organization_id', v_organization_id::text, true);
  perform pg_catalog.set_config('test.subscription_history.other_organization_id', v_other_organization_id::text, true);
end;
$$;

set local role authenticated;
set local request.jwt.claim.sub = '00000000-0000-4000-8000-000000000001';
select pg_catalog.set_config(
  'request.jwt.claim.sub',
  current_setting('test.subscription_history.owner_id'),
  true
);
select extensions.is(
  (select count(*)::integer from public.subscription_history
   where organization_id = current_setting('test.subscription_history.organization_id')::uuid),
  1,
  'an authenticated organization member can read its subscription history'
);
select extensions.is(
  (select count(*)::integer from public.subscription_history
   where organization_id = current_setting('test.subscription_history.other_organization_id')::uuid),
  0,
  'subscription history remains invisible across organizations'
);
reset role;

select * from extensions.finish();
rollback;
