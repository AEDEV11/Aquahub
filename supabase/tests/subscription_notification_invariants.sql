-- Static hosted invariants. Authenticated purchase/fixture success tests remain
-- separate because they require trusted disposable credentials and a real or
-- approved Google Play test purchase.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(1);

do $$
begin
  if not exists (
    select 1 from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'get_effective_entitlements'
  ) then
    raise exception 'get_effective_entitlements RPC missing';
  end if;
  if exists (
    select 1 from information_schema.routine_privileges
    where routine_schema = 'public'
      and routine_name = 'get_effective_entitlements'
      and grantee in ('PUBLIC', 'anon')
      and privilege_type = 'EXECUTE'
  ) then
    raise exception 'entitlement resolver must not be executable by anon/public';
  end if;
  if has_table_privilege('authenticated', 'public.subscriptions', 'insert')
     or has_table_privilege('authenticated', 'public.subscriptions', 'update') then
    raise exception 'authenticated clients must not write subscriptions';
  end if;
  if has_table_privilege('authenticated', 'public.subscription_plans', 'update')
     or has_table_privilege('authenticated', 'public.plan_features', 'update')
     or has_table_privilege('authenticated', 'public.plan_limits', 'update') then
    raise exception 'authenticated clients must not write plan or entitlement definitions';
  end if;
  if has_table_privilege('authenticated', 'public.notification_outbox', 'insert')
     or has_table_privilege('authenticated', 'public.notification_outbox', 'update') then
    raise exception 'authenticated clients must not write notification outbox';
  end if;
  if has_table_privilege('authenticated', 'public.google_play_billing_events', 'insert')
     or has_table_privilege('authenticated', 'public.google_play_billing_events', 'update') then
    raise exception 'authenticated clients must not write RTDN audit events';
  end if;
  if not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'subscriptions' and column_name = 'provider_event_time'
  ) then
    raise exception 'subscription provider ordering columns missing';
  end if;
  if not has_function_privilege('service_role', 'app_private.claim_notification_outbox(integer)', 'execute') then
    raise exception 'service_role claim function grant missing';
  end if;
  if not has_function_privilege('service_role', 'app_private.claim_notification_outbox_item(uuid)', 'execute') then
    raise exception 'service_role item claim function grant missing';
  end if;
  if not has_function_privilege('service_role', 'public.claim_play_rtdn_event(text,jsonb)', 'execute') then
    raise exception 'service_role RTDN claim wrapper grant missing';
  end if;
  if has_function_privilege('anon', 'public.claim_play_rtdn_event(text,jsonb)', 'execute')
     or has_function_privilege('authenticated', 'public.claim_play_rtdn_event(text,jsonb)', 'execute')
     or has_function_privilege('service_role', 'app_private.claim_play_rtdn_event(text,jsonb)', 'execute') then
    raise exception 'RTDN claim wrapper/private implementation privileges are too broad';
  end if;
  if exists (
    select 1 from information_schema.routine_privileges
    where routine_schema = 'public'
      and routine_name = 'claim_play_rtdn_event'
      and grantee = 'PUBLIC'
      and privilege_type = 'EXECUTE'
  ) then
    raise exception 'PUBLIC must not execute RTDN claim wrapper';
  end if;
end $$;

select extensions.ok(true, 'subscription authority, event ordering, and service-only notification grants exist');
select * from extensions.finish();
rollback;
