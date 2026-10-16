-- Hosted-safe inventory contract checks. These are read-only assertions and
-- do not create fixture users, products, balances, or movements.

create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(6);

select extensions.ok(has_function_privilege(
  'authenticated',
  'public.adjust_inventory(uuid,uuid,integer,text,text,uuid)',
  'execute'
), 'authenticated clients can invoke the original inventory RPC');

select extensions.ok(has_function_privilege(
  'authenticated',
  'public.adjust_inventory(uuid,uuid,integer,text,text,uuid,text,text)',
  'execute'
), 'authenticated clients can invoke the metadata-aware inventory RPC');

select extensions.ok(not has_function_privilege(
  'anon',
  'public.adjust_inventory(uuid,uuid,integer,text,text,uuid,text,text)',
  'execute'
), 'anonymous clients cannot invoke inventory adjustment');

select extensions.ok(exists (
  select 1 from pg_constraint
  where conrelid = 'public.inventory_balances'::regclass
    and contype = 'u'
    and pg_get_constraintdef(oid) like '%(station_id, product_id)%'
), 'inventory balances have one row per station/product');

select extensions.ok(exists (
  select 1 from pg_constraint
  where conrelid = 'public.inventory_movements'::regclass
    and contype = 'u'
    and pg_get_constraintdef(oid) like '%(operation_id)%'
), 'inventory movement operation IDs are unique');

select extensions.ok((
  select count(*) = 2 and bool_and(relrowsecurity)
  from pg_class
  where oid in ('public.inventory_balances'::regclass, 'public.inventory_movements'::regclass)
), 'inventory tables have row-level security enabled');

select * from extensions.finish();
rollback;

-- Concurrency, insufficient-stock rollback, duplicate replay, and cross-tenant
-- mutation tests require authenticated disposable fixtures and must run in the
-- controlled hosted validation harness. They must assert:
--   * two concurrent decrements cannot produce a negative/lost balance;
--   * a rejected decrement creates neither movement nor partial order;
--   * a repeated operation_id creates exactly one movement;
--   * another station cannot call the RPC successfully;
--   * order cancellation/reversal cannot restore twice.
