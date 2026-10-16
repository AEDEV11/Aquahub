-- Read-only hosted contract checks. These assertions do not create users,
-- orders, products, balances, or inventory movements.

create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(10);

do $$
begin
  if not exists (
    select 1 from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'create_order'
  ) then
    raise exception 'create_order RPC is missing';
  end if;
  if not exists (
    select 1 from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'cancel_order'
  ) then
    raise exception 'cancel_order RPC is missing';
  end if;
  if not exists (select 1 from pg_constraint where conname = 'orders_order_source_check') then
    raise exception 'order source constraint is missing';
  end if;
  if not exists (select 1 from pg_constraint where conname = 'orders_tax_centavos_check') then
    raise exception 'tax centavo constraint is missing';
  end if;
  if not exists (
    select 1 from pg_trigger
    where tgrelid = 'public.orders'::regclass and tgname = 'orders_validate_insert_context'
  ) then
    raise exception 'order insert context trigger is missing';
  end if;
  if not exists (
    select 1 from pg_trigger
    where tgrelid = 'public.orders'::regclass and tgname = 'orders_keep_walk_in_delivery_unpaid'
  ) then
    raise exception 'walk-in delivery payment guard is missing';
  end if;
  if not exists (
    select 1 from pg_trigger
    where tgrelid = 'public.payments'::regclass and tgname = 'payments_require_rider_delivery_proof'
  ) then
    raise exception 'rider payment proof guard is missing';
  end if;
  if position('assigned_rider' in pg_get_functiondef(
    'public.record_payment(uuid,text,bigint,text,text,text,text,jsonb)'::regprocedure
  )) = 0 then
    raise exception 'assigned riders must be authorized only through the proof-guarded payment RPC';
  end if;
  if position('payment_status' in pg_get_functiondef(
    'app_private.keep_walk_in_delivery_unpaid()'::regprocedure
  )) = 0 then
    raise exception 'walk-in delivery payment status guard is missing';
  end if;
  if to_regprocedure('public.create_station(uuid,jsonb)') is null then
    raise exception 'create_station RPC is missing';
  end if;
  if to_regprocedure('public.reject_order(uuid,text)') is null then
    raise exception 'reject_order RPC is missing';
  end if;
  if position('LEGACY_IMPORT' in pg_get_functiondef('app_private.cancel_order_core(uuid,text,text,boolean)'::regprocedure)) = 0 then
    raise exception 'legacy cancellation must not reverse stock';
  end if;
  if not exists (
    select 1 from pg_trigger
    where tgrelid = 'public.orders'::regclass and tgname = 'orders_customer_cancellation_notification'
  ) then
    raise exception 'online order cancellation notification trigger is missing';
  end if;
end $$;

select extensions.ok(true, 'order creation, cancellation, state guards, and notification contracts exist');

select extensions.ok(not has_column_privilege('authenticated', 'public.orders', 'payment_status', 'UPDATE'),
  'order payment state is RPC-only');
select extensions.ok(not has_table_privilege('authenticated', 'public.payments', 'INSERT'),
  'payment inserts are RPC-only');
select extensions.ok(not has_table_privilege('authenticated', 'public.order_number_counters', 'SELECT'),
  'order counters are not client-readable');
select extensions.ok(not has_function_privilege(
  'anon',
  'public.create_order(uuid,text,text,jsonb,uuid,text,text,public.delivery_mode,text,text,double precision,double precision,bigint,text,text)',
  'EXECUTE'
), 'anonymous callers cannot create orders');
select extensions.ok(not has_function_privilege(
  'anon',
  'public.cancel_order(uuid,text,text)',
  'EXECUTE'
), 'anonymous callers cannot cancel orders');
select extensions.ok(not has_function_privilege(
  'anon', 'public.create_guest_order(text,jsonb,text)', 'EXECUTE'
), 'anonymous clients cannot invoke the trusted guest order RPC directly');
select extensions.ok(not has_function_privilege(
  'anon', 'public.get_guest_order_status(uuid,text)', 'EXECUTE'
), 'anonymous clients cannot invoke the guest tracking RPC directly');
select extensions.ok(not has_function_privilege(
  'anon', 'public.consume_guest_order_rate_limit(text,text)', 'EXECUTE'
), 'anonymous clients cannot manipulate guest order rate limits');
select extensions.ok(not has_table_privilege(
  'anon', 'app_private.guest_web_orders', 'SELECT'
), 'guest capability tokens and mappings are not anonymously readable');

select * from extensions.finish();
rollback;
