-- Local/test-only extension setup; this is not part of production migrations.
create extension if not exists pgtap with schema extensions;

begin;
select extensions.plan(28);

select extensions.ok(has_table_privilege('anon', 'public.public_stations', 'select'),
  'anonymous catalogue projection remains readable');
select extensions.ok(has_table_privilege('authenticated', 'public.orders', 'select'),
  'authenticated order reads remain available behind RLS');
select extensions.ok(not has_table_privilege('authenticated', 'public.orders', 'insert'),
  'authenticated users cannot directly insert orders');
select extensions.ok(not has_table_privilege('authenticated', 'public.orders', 'update'),
  'authenticated users cannot directly update orders');
select extensions.ok(not has_table_privilege('authenticated', 'public.orders', 'delete'),
  'authenticated users cannot directly delete orders');
select extensions.ok(not has_column_privilege('authenticated', 'public.orders', 'payment_status', 'update'),
  'payment status is RPC-only');
select extensions.ok(not has_column_privilege('authenticated', 'public.orders', 'payment_method', 'update'),
  'payment method is RPC-only');
select extensions.ok(has_column_privilege('authenticated', 'public.orders', 'station_note', 'update'),
  'station notes retain their limited direct update path');
select extensions.ok(not has_table_privilege('authenticated', 'public.order_items', 'insert'),
  'authenticated users cannot directly insert order items');
select extensions.ok(not has_table_privilege('authenticated', 'public.order_items', 'update'),
  'authenticated users cannot directly update order items');
select extensions.ok(not has_table_privilege('authenticated', 'public.order_items', 'delete'),
  'authenticated users cannot directly delete order items');
select extensions.ok(not has_table_privilege('authenticated', 'public.payments', 'insert'),
  'authenticated users cannot directly insert receipts');
select extensions.ok(not has_table_privilege('authenticated', 'public.payments', 'update'),
  'authenticated users cannot directly update receipts');
select extensions.ok(not has_table_privilege('authenticated', 'public.payments', 'delete'),
  'authenticated users cannot directly delete receipts');
select extensions.ok(not has_table_privilege('authenticated', 'public.audit_events', 'select'),
  'audit events are server-only');
select extensions.ok(not has_table_privilege('anon', 'public.customer_order_requests', 'insert'),
  'anonymous users cannot insert private customer order requests');
select extensions.ok(not has_table_privilege('authenticated', 'public.order_number_counters', 'select'),
  'order number counters are not directly readable');
select extensions.ok(not has_function_privilege('anon', 'public.charge_order_to_client_account(uuid)', 'execute'),
  'anonymous users cannot charge an order to a client account');
select extensions.ok(has_function_privilege('authenticated', 'public.charge_order_to_client_account(uuid)', 'execute'),
  'authenticated operators can use the guarded account-charge RPC');

select extensions.ok(not has_table_privilege('anon', 'app_private.marketing_subscribers', 'select'),
  'anonymous visitors cannot read marketing subscribers');
select extensions.ok(not has_table_privilege('authenticated', 'app_private.marketing_subscribers', 'select'),
  'owners cannot enumerate marketing subscriber records');
select extensions.ok(not has_table_privilege('authenticated', 'app_private.marketing_consent_events', 'select'),
  'owners cannot read other consent audit records');
select extensions.ok(not has_table_privilege('anon', 'app_private.marketing_rate_limits', 'select'),
  'anonymous visitors cannot read rate-limit fingerprints');
select extensions.ok(not has_function_privilege('anon', 'public.record_marketing_consent(text,text,text,text,uuid)', 'execute'),
  'anonymous visitors cannot write subscriber state directly');
select extensions.ok(has_function_privilege('service_role', 'public.record_marketing_consent(text,text,text,text,uuid)', 'execute'),
  'only server-side service role can write website subscriber records');
select extensions.ok(has_function_privilege('authenticated', 'public.get_my_marketing_preference()', 'execute'),
  'authenticated owners can read their own marketing preference through the guarded RPC');
select extensions.ok(has_function_privilege('authenticated', 'public.set_my_marketing_preference(boolean)', 'execute'),
  'authenticated owners can update their own preference through the guarded RPC');
select extensions.ok(not has_function_privilege('anon', 'public.set_my_marketing_preference(boolean)', 'execute'),
  'anonymous visitors cannot change owner marketing preferences');

select * from extensions.finish();
rollback;
