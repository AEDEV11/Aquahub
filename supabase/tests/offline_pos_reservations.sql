-- Read-only contract and privilege checks for offline POS reservations.
-- Transactional fixture/concurrency coverage runs only on the disposable local DB.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(21);

select extensions.ok((
  select count(*) = 6 and bool_and(c.relrowsecurity)
  from pg_class c
  where c.oid in (
    'app_private.offline_pos_devices'::regclass,
    'app_private.offline_pos_grants'::regclass,
    'app_private.offline_pos_leases'::regclass,
    'app_private.offline_pos_lease_items'::regclass,
    'app_private.offline_pos_operations'::regclass,
    'app_private.offline_pos_reconciliation_events'::regclass
  )
), 'all offline POS private tables enforce row-level security');

select extensions.ok(not has_table_privilege('authenticated','app_private.offline_pos_operations','SELECT'),
  'authenticated clients cannot read offline operation payloads');
select extensions.ok(not has_table_privilege('anon','app_private.offline_pos_leases','SELECT'),
  'anonymous clients cannot read inventory leases');
select extensions.ok(has_function_privilege('authenticated',
  'public.register_offline_pos_device(text,uuid,uuid,text)','EXECUTE'),
  'authenticated owners can register a device through the scoped RPC');
select extensions.ok(has_function_privilege('authenticated',
  'public.issue_offline_pos_grant_and_lease(text,uuid,uuid,uuid,uuid,jsonb,integer,integer)','EXECUTE'),
  'authenticated owners can request bounded grant and lease issuance');
select extensions.ok(not has_function_privilege('anon',
  'public.issue_offline_pos_grant_and_lease(text,uuid,uuid,uuid,uuid,jsonb,integer,integer)','EXECUTE'),
  'anonymous callers cannot request offline authorization');
select extensions.ok(has_function_privilege('service_role',
  'public.get_offline_pos_device_key(text,uuid)','EXECUTE'),
  'only the signature-verifying service path can read registered device keys');
select extensions.ok(not has_function_privilege('authenticated',
  'public.get_offline_pos_device_key(text,uuid)','EXECUTE'),
  'mobile clients cannot retrieve device public keys through the service RPC');
select extensions.ok(has_function_privilege('service_role',
  'app_private.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)','EXECUTE'),
  'offline sale commit is available to the trusted Edge Function role');
select extensions.ok(not has_function_privilege('authenticated',
  'app_private.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)','EXECUTE'),
  'authenticated clients cannot bypass Edge Function signature verification');
select extensions.ok(has_function_privilege('service_role',
  'public.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)','EXECUTE'),
  'the signature-verifying Edge Function has a public PostgREST RPC entry point');
select extensions.ok(not has_function_privilege('authenticated',
  'public.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)','EXECUTE'),
  'authenticated clients cannot invoke the server-only sale commit RPC');
select extensions.ok(not has_function_privilege('anon',
  'public.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)','EXECUTE'),
  'anonymous clients cannot invoke the server-only sale commit RPC');
select extensions.ok(not (select p.prosecdef from pg_proc p
  where p.oid='public.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)'::regprocedure),
  'the public RPC wrapper is SECURITY INVOKER and delegates only under service-role grants');
select extensions.ok(has_function_privilege('authenticated',
  'public.set_inventory_quantity(uuid,uuid,integer,bigint,text,text)','EXECUTE'),
  'inventory targets use the authenticated revision-aware RPC');
select extensions.ok(has_function_privilege('authenticated',
  'public.reconcile_offline_pos_lease(uuid,text,jsonb,boolean)','EXECUTE'),
  'authorized owners can request audited single-lease reconciliation');
select extensions.ok(has_function_privilege('authenticated',
  'public.reconcile_offline_pos_leases(uuid,uuid[],text,jsonb,boolean)','EXECUTE'),
  'authorized owners can atomically reconcile multiple affected leases');
select extensions.ok(exists (
  select 1 from pg_trigger
  where tgrelid='public.inventory_balances'::regclass
    and tgname='inventory_balance_reservation_floor' and not tgisinternal
), 'every ledger mutation is guarded against spending reserved inventory');
select extensions.ok(position('p_expected_version' in pg_get_functiondef(
  'public.set_inventory_quantity(uuid,uuid,integer,bigint,text,text)'::regprocedure
)) > 0, 'absolute stock updates validate the inventory revision');
select extensions.ok(position('quantity_released' in pg_get_functiondef(
  'public.reconcile_offline_pos_leases(uuid,uuid[],text,jsonb,boolean)'::regprocedure
)) > 0, 'bulk physical reconciliation records released quantities explicitly');
select extensions.ok(position('payload_digest' in pg_get_functiondef(
  'app_private.commit_offline_pos_sale(uuid,text,uuid,uuid,uuid,text,text)'::regprocedure
)) > 0, 'atomic sale commit validates and stores an immutable payload digest');

select * from extensions.finish();
rollback;
