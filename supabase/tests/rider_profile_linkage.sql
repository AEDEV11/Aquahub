-- Fleet rider identity is resolved from the uniquely matching active Rider
-- staff account assigned to the same station. Fixtures roll back below.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(4);

do $$
declare
  organization_id uuid := '00000000-0000-4000-8000-000000000101';
  station_id uuid := '00000000-0000-4000-8000-000000000111';
  other_station_id uuid := '00000000-0000-4000-8000-000000000112';
  owner_profile_id uuid := '00000000-0000-4000-8000-000000000121';
  owner_member_id uuid := '00000000-0000-4000-8000-000000000131';
  rider_profile_id uuid := '00000000-0000-4000-8000-000000000122';
  rider_member_id uuid := '00000000-0000-4000-8000-000000000132';
  duplicate_profile_id uuid := '00000000-0000-4000-8000-000000000123';
  duplicate_member_id uuid := '00000000-0000-4000-8000-000000000133';
  rider_row_id uuid := '00000000-0000-4000-8000-000000000141';
  ambiguous_rider_row_id uuid := '00000000-0000-4000-8000-000000000142';
  cross_station_rider_row_id uuid := '00000000-0000-4000-8000-000000000143';
  now_ms bigint := floor(extract(epoch from clock_timestamp()) * 1000)::bigint;
begin
  insert into auth.users (id) values (owner_profile_id), (rider_profile_id), (duplicate_profile_id);
  insert into public.profiles (id, display_name, phone, role) values
    (owner_profile_id, 'Owner fixture', '', 'OWNER'),
    (rider_profile_id, 'Rider fixture', '+63 917 000 0001', 'RIDER'),
    (duplicate_profile_id, 'Duplicate rider fixture', '09170000001', 'RIDER');
  insert into public.organizations (id, owner_profile_id, name)
  values (organization_id, owner_profile_id, 'Rider profile-link regression fixture');
  insert into public.subscriptions (
    organization_id, plan_id, status, billing_cycle,
    current_period_start, current_period_end, updated_at
  )
  select organization_id, p.id, 'ACTIVE', 'MONTHLY', now_ms, now_ms + 2592000000, now()
  from public.subscription_plans p where p.code = 'BUSINESS';
  insert into public.stations (id, organization_id, name, address) values
    (station_id, organization_id, 'Rider-link station', 'Local test only'),
    (other_station_id, organization_id, 'Unassigned station', 'Local test only');
  insert into public.organization_members (id, organization_id, profile_id, role)
  values (owner_member_id, organization_id, owner_profile_id, 'OWNER');
  insert into public.member_stations (member_id, station_id) values
    (owner_member_id, station_id),
    (owner_member_id, other_station_id);

  perform pg_catalog.set_config('request.jwt.claim.sub', owner_profile_id::text, true);

  insert into public.organization_members (id, organization_id, profile_id, role)
  values (rider_member_id, organization_id, rider_profile_id, 'RIDER');
  insert into public.member_stations (member_id, station_id)
  values (rider_member_id, station_id);

  -- The server derives the selected Rider account, even if a stale or forged
  -- profile_id was included in the owner-managed fleet payload.
  insert into public.riders (id, station_id, profile_id, name, phone)
  values (rider_row_id, station_id, owner_profile_id, 'Rider fixture', '0917-000-0001');

  insert into public.organization_members (id, organization_id, profile_id, role)
  values (duplicate_member_id, organization_id, duplicate_profile_id, 'RIDER');
  insert into public.member_stations (member_id, station_id)
  values (duplicate_member_id, station_id);

  -- More than one active same-station account with the phone is ambiguous.
  insert into public.riders (id, station_id, profile_id, name, phone)
  values (ambiguous_rider_row_id, station_id, owner_profile_id, 'Ambiguous fixture', '09170000001');

  -- The first Rider account is not assigned to this station, so it cannot link.
  insert into public.riders (id, station_id, profile_id, name, phone)
  values (cross_station_rider_row_id, other_station_id, owner_profile_id, 'Cross-station fixture', '09170000001');
end;
$$;

select extensions.ok(
  (select profile_id = '00000000-0000-4000-8000-000000000122'::uuid
   from public.riders where id = '00000000-0000-4000-8000-000000000141'::uuid),
  'unique station-assigned Rider account is linked despite a stale payload profile id'
);
select extensions.ok(
  (select profile_id is null from public.riders where id = '00000000-0000-4000-8000-000000000142'::uuid),
  'ambiguous same-station phone matches remain unlinked'
);
select extensions.ok(
  (select profile_id is null from public.riders where id = '00000000-0000-4000-8000-000000000143'::uuid),
  'a Rider account without this station assignment is not linked'
);
select extensions.ok(
  not has_function_privilege('authenticated', 'app_private.match_active_rider_profile(uuid,text)', 'execute')
  and not has_function_privilege('anon', 'app_private.match_active_rider_profile(uuid,text)', 'execute'),
  'account-link resolver is not directly executable by API roles'
);

select * from extensions.finish();
rollback;
