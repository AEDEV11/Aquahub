-- Disposable integration fixture for reservation, cash commit, replay,
-- snapshot conflict, expiration, reconciliation, and tenant isolation.
-- The test runs in a transaction and rolls every fixture row back.
create extension if not exists pgtap with schema extensions;
begin;

insert into auth.users (id,aud,role,email,encrypted_password,email_confirmed_at,raw_app_meta_data,raw_user_meta_data,created_at,updated_at)
values ('10000000-0000-4000-8000-000000000001','authenticated','authenticated','offline-pos-test@example.invalid','',now(),'{}','{}',now(),now());
insert into public.profiles (id,email,phone,display_name,email_verified,phone_verified,account_state,role,setup_complete)
values ('10000000-0000-4000-8000-000000000001','offline-pos-test@example.invalid','+639000000001','Offline POS Test',true,true,'ACTIVE','OWNER',true);
insert into public.organizations (id,owner_profile_id,name)
values ('20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','Offline POS Test Organization');
insert into public.stations (id,organization_id,name,address)
values ('30000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','Offline POS Test Station','Test address');
insert into public.organization_members (id,organization_id,profile_id,role,is_active)
values ('40000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001','10000000-0000-4000-8000-000000000001','OWNER',true);
insert into public.products (id,station_id,name,product_type,size_label,price_centavos,stock_quantity)
values ('50000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','Purified Refill','REFILL','5 gal',12345,10);
insert into public.inventory_balances (station_id,product_id,quantity,version)
values ('30000000-0000-4000-8000-000000000001','50000000-0000-4000-8000-000000000001',10,1);
select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-000000000001',true);

select public.register_offline_pos_device('offline-device-00000001',
  '20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','dGVzdC1rZXktMQ==');
select public.register_offline_pos_device('offline-device-00000002',
  '20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','dGVzdC1rZXktMg==');
create temporary table offline_grant as
select public.issue_offline_pos_grant_and_lease(
  'offline-device-00000001','20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',
  '60000000-0000-4000-8000-000000000001','70000000-0000-4000-8000-000000000001',
  '[{"product_id":"50000000-0000-4000-8000-000000000001","quantity":6}]'::jsonb,8,8
) as response;
create temporary table inventory_rpc_baseline as
select quantity, version from public.inventory_balances
where station_id='30000000-0000-4000-8000-000000000001'
  and product_id='50000000-0000-4000-8000-000000000001';

select extensions.plan(28);
select extensions.is(
  (select quantity from public.set_inventory_quantity(
    '30000000-0000-4000-8000-000000000001',
    '50000000-0000-4000-8000-000000000001',
    (select quantity from inventory_rpc_baseline),
    (select version from inventory_rpc_baseline),
    'offline-pos-stock-set-noop',
    'Verify revision-aware inventory RPC'
  )),
  (select quantity from inventory_rpc_baseline),
  'revision-aware stock RPC executes without ambiguous conflict target'
);
select extensions.is(
  (select version::integer from public.inventory_balances
   where station_id='30000000-0000-4000-8000-000000000001'
     and product_id='50000000-0000-4000-8000-000000000001'),
  (select version::integer from inventory_rpc_baseline),
  'no-op quantity validation preserves the inventory revision'
);
select extensions.ok((select ((response->'grant'->>'exp')::bigint-(response->'grant'->>'iat')::bigint)=28800 from offline_grant),
  'authorization grant defaults to an eight-hour shift');
select extensions.ok((select ((response->'lease'->>'exp')::bigint-(response->'lease'->>'iat')::bigint)=28800 from offline_grant),
  'inventory lease defaults to eight hours independently');
select extensions.is((select reserved_quantity from public.inventory_balances where product_id='50000000-0000-4000-8000-000000000001'),6,
  'first device reserves only its requested quantity');

select extensions.ok((public.issue_offline_pos_grant_and_lease(
  'offline-device-00000001','20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',
  '60000000-0000-4000-8000-000000000001','70000000-0000-4000-8000-000000000001',
  '[{"product_id":"50000000-0000-4000-8000-000000000001","quantity":6}]'::jsonb,8,8
)->>'duplicate')::boolean, 'lost authorization response can be retried idempotently');
select extensions.is((select reserved_quantity from public.inventory_balances where product_id='50000000-0000-4000-8000-000000000001'),6,
  'reservation retry does not reserve stock twice');

select extensions.throws_ok($sql$
  select public.issue_offline_pos_grant_and_lease(
    'offline-device-00000002','20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000002','70000000-0000-4000-8000-000000000002',
    '[{"product_id":"50000000-0000-4000-8000-000000000001","quantity":5}]'::jsonb,8,8)
$sql$,'23514','insufficient unreserved inventory for offline lease','second device cannot reserve beyond unallocated stock');
select extensions.is((select reserved_quantity from public.inventory_balances where product_id='50000000-0000-4000-8000-000000000001'),6,
  'failed second-device reservation rolls back without changing inventory');

create temporary table offline_sale as
with raw as (
  select gen_random_uuid() operation_id,
    jsonb_build_object(
      'operation_id','80000000-0000-4000-8000-000000000001',
      'profile_id','10000000-0000-4000-8000-000000000001',
      'organization_id','20000000-0000-4000-8000-000000000001',
      'station_id','30000000-0000-4000-8000-000000000001',
      'device_id','offline-device-00000001',
      'grant_id','60000000-0000-4000-8000-000000000001',
      'lease_id','70000000-0000-4000-8000-000000000001',
      'transaction_at_ms',(extract(epoch from clock_timestamp())*1000)::bigint,
      'payment_method','CASH','delivery_mode','PICKUP','customer_id',null,
      'customer_name','Walk-in Customer','customer_phone','','notes',null,
      'cash_received_centavos',30000,'change_centavos',5310,'container_movements','[]'::jsonb,
      'items',jsonb_build_array(jsonb_build_object(
        'product_id','50000000-0000-4000-8000-000000000001','quantity',2,'unit_price_centavos',12345
      ))
    )::text payload
)
select '80000000-0000-4000-8000-000000000001'::uuid operation_id,
       payload,encode(extensions.digest(convert_to(payload,'UTF8'),'sha256'),'hex') payload_digest
from raw;
create temporary table offline_commit as
select public.commit_offline_pos_sale(
  '10000000-0000-4000-8000-000000000001','offline-device-00000001',
  '60000000-0000-4000-8000-000000000001','70000000-0000-4000-8000-000000000001',
  operation_id,payload_digest,payload
) as response from offline_sale;

select extensions.ok((select response->>'status'='COMMITTED' and response->>'duplicate'='false' from offline_commit),
  'cash sale commits atomically with a stable operation UUID');
select extensions.is((select operation_id from public.payments
  where order_id=((select response->>'order_id' from offline_commit)::uuid)),
  'offline-pos-payment:80000000-0000-4000-8000-000000000001',
  'immutable cash receipt is bound to the offline operation UUID');
select extensions.is((select metadata->>'payload_digest' from public.payments
  where order_id=((select response->>'order_id' from offline_commit)::uuid)),
  (select payload_digest from offline_sale),
  'cash receipt retains the exact offline payload digest');
select extensions.is((select count(*)::integer from public.payments
  where order_id=((select response->>'order_id' from offline_commit)::uuid) and status='PAID'),1,
  'offline reconciliation creates exactly one paid receipt');
select extensions.is((select quantity_consumed from app_private.offline_pos_lease_items where lease_id='70000000-0000-4000-8000-000000000001'),2,
  'partial lease consumption records exactly the sold quantity');
select extensions.is((select quantity from public.inventory_balances where product_id='50000000-0000-4000-8000-000000000001'),8,
  'committed sale decrements physical inventory once');
select extensions.is((select reserved_quantity from public.inventory_balances where product_id='50000000-0000-4000-8000-000000000001'),4,
  'committed sale consumes only its matching reservation quantity');
select extensions.ok((select (public.commit_offline_pos_sale(
  '10000000-0000-4000-8000-000000000001','offline-device-00000001',
  '60000000-0000-4000-8000-000000000001','70000000-0000-4000-8000-000000000001',
  operation_id,payload_digest,payload)->>'duplicate')::boolean from offline_sale),
  'retry after a successful server commit returns the original result');
select extensions.is((select count(*)::integer from public.inventory_movements where operation_id like 'offline-pos:80000000-0000-4000-8000-000000000001:%'),1,
  'duplicate commit produces no second inventory movement');

update public.products set price_centavos=13000 where id='50000000-0000-4000-8000-000000000001';
create temporary table stale_price_sale as
select '80000000-0000-4000-8000-000000000002'::uuid operation_id,
  (payload::jsonb || jsonb_build_object(
    'operation_id','80000000-0000-4000-8000-000000000002',
    'transaction_at_ms',(extract(epoch from clock_timestamp())*1000)::bigint
  ))::text payload
from offline_sale;
alter table stale_price_sale add column payload_digest text;
update stale_price_sale set payload_digest=encode(extensions.digest(convert_to(payload,'UTF8'),'sha256'),'hex');
select extensions.throws_ok($sql$
  select app_private.commit_offline_pos_sale(
    '10000000-0000-4000-8000-000000000001','offline-device-00000001',
    '60000000-0000-4000-8000-000000000001','70000000-0000-4000-8000-000000000001',
    (select operation_id from stale_price_sale),(select payload_digest from stale_price_sale),(select payload from stale_price_sale))
$sql$,'55000','product catalog changed after the offline price snapshot','server refuses to rewrite a completed offline sale to a newer price');
select extensions.is((select count(*)::integer from app_private.offline_pos_operations
  where operation_id='80000000-0000-4000-8000-000000000001'),1,
  'rejected price conflict creates no server order or financial receipt');

update app_private.offline_pos_leases
set issued_at=clock_timestamp()-interval '2 hours',expires_at=clock_timestamp()-interval '1 minute'
where id='70000000-0000-4000-8000-000000000001';
select extensions.is((select reserved_quantity from public.inventory_balances where product_id='50000000-0000-4000-8000-000000000001'),4,
  'lease expiration does not automatically release unresolved reserved stock');
select public.reconcile_offline_pos_lease(
  '70000000-0000-4000-8000-000000000001','Verified shelf count after device reconciliation',
  '{"50000000-0000-4000-8000-000000000001":7}'::jsonb,false);
select extensions.is((select quantity from public.inventory_balances where product_id='50000000-0000-4000-8000-000000000001'),7,
  'authorized physical reconciliation records the verified stock count');
select extensions.ok((select quantity_consumed=2 and quantity_released=4
  from app_private.offline_pos_lease_items where lease_id='70000000-0000-4000-8000-000000000001'),
  'reconciliation preserves consumed stock and explicitly releases only the unused lease remainder');

create temporary table closed_lease_sale as
select '80000000-0000-4000-8000-000000000003'::uuid operation_id,
  (payload::jsonb || jsonb_build_object('operation_id','80000000-0000-4000-8000-000000000003'))::text payload
from offline_sale;
alter table closed_lease_sale add column payload_digest text;
update closed_lease_sale set payload_digest=encode(extensions.digest(convert_to(payload,'UTF8'),'sha256'),'hex');
select extensions.throws_ok($sql$
  select public.commit_offline_pos_sale(
    '10000000-0000-4000-8000-000000000001','offline-device-00000001',
    '60000000-0000-4000-8000-000000000001','70000000-0000-4000-8000-000000000001',
    operation_id,payload_digest,payload) from closed_lease_sale
$sql$,'42501','offline authorization was revoked or scope changed',
  'a new sale cannot consume a lease after physical reconciliation has released its reservation');
select extensions.is((select count(*)::integer from app_private.offline_pos_operations
  where operation_id='80000000-0000-4000-8000-000000000001'),1,
  'a late, unreported sale remains outside server revenue after its lease was reconciled');

select set_config('request.jwt.claim.sub','90000000-0000-4000-8000-000000000001',true);
select extensions.throws_ok($sql$
  select public.register_offline_pos_device('offline-device-tenant-0001',
    '20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001','dGVzdC1rZXktMw==')
$sql$,'42501','station access denied','a different tenant cannot register a device against this station');
select set_config('request.jwt.claim.sub','10000000-0000-4000-8000-000000000001',true);
select extensions.throws_ok($sql$
  select public.issue_offline_pos_grant_and_lease(
    'offline-device-00000002','20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000003','70000000-0000-4000-8000-000000000003',
    '[{"product_id":"50000000-0000-4000-8000-000000000001","quantity":1}]'::jsonb,13,8)
$sql$,'22023','offline grant or lease request is invalid','authorization grants cannot exceed twelve hours');
select extensions.throws_ok($sql$
  select public.issue_offline_pos_grant_and_lease(
    'offline-device-00000002','20000000-0000-4000-8000-000000000001','30000000-0000-4000-8000-000000000001',
    '60000000-0000-4000-8000-000000000004','70000000-0000-4000-8000-000000000004',
    '[{"product_id":"50000000-0000-4000-8000-000000000001","quantity":1}]'::jsonb,8,25)
$sql$,'22023','offline grant or lease request is invalid','inventory leases cannot exceed twenty-four hours');

select * from extensions.finish();
rollback;
