-- Disposable database only. Fixture rows are rolled back; no real refunds are
-- requested, confirmed, or modified by this test.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(3);

select extensions.ok(
  has_function_privilege('authenticated', 'public.refund_obligation_report(uuid,timestamptz,timestamptz)', 'execute')
  and not has_function_privilege('anon', 'public.refund_obligation_report(uuid,timestamptz,timestamptz)', 'execute'),
  'refund obligation reporting is available to authenticated users only');
select extensions.ok(
  (select prosecdef and proconfig @> array['search_path=""']
   from pg_proc where oid = 'public.refund_obligation_report(uuid,timestamptz,timestamptz)'::regprocedure),
  'report RPC is security-definer with an empty search path');

do $$
declare
  owner_id uuid := gen_random_uuid();
  manager_id uuid := gen_random_uuid();
  staff_id uuid := gen_random_uuid();
  other_owner_id uuid := gen_random_uuid();
  organization_id uuid := gen_random_uuid();
  other_organization_id uuid := gen_random_uuid();
  station_id uuid := gen_random_uuid();
  unassigned_station_id uuid := gen_random_uuid();
  other_station_id uuid := gen_random_uuid();
  owner_member_id uuid := gen_random_uuid();
  manager_member_id uuid := gen_random_uuid();
  staff_member_id uuid := gen_random_uuid();
  other_member_id uuid := gen_random_uuid();
  order_pending_id uuid := gen_random_uuid();
  order_completed_id uuid := gen_random_uuid();
  order_failed_id uuid := gen_random_uuid();
  pending_payment_id uuid := gen_random_uuid();
  completed_payment_id uuid := gen_random_uuid();
  failed_payment_id uuid := gen_random_uuid();
  pending_refund_id uuid := gen_random_uuid();
  completed_refund_id uuid := gen_random_uuid();
  failed_refund_id uuid := gen_random_uuid();
  now_at timestamptz := pg_catalog.transaction_timestamp();
  report_start timestamptz := pg_catalog.transaction_timestamp() - interval '7 days';
  report_end timestamptz := pg_catalog.transaction_timestamp() + interval '1 minute';
  report_row record;
begin
  insert into auth.users (id) values (owner_id), (manager_id), (staff_id), (other_owner_id);
  insert into public.organizations (id, name) values
    (organization_id, 'Synthetic reporting tenant'),
    (other_organization_id, 'Synthetic isolated tenant');
  insert into public.subscriptions (
    organization_id, plan_id, status, billing_cycle, current_period_start,
    current_period_end, updated_at
  )
  select organization_id, p.id, 'ACTIVE', 'MONTHLY',
         floor(extract(epoch from now_at) * 1000)::bigint,
         floor(extract(epoch from (now_at + interval '30 days')) * 1000)::bigint,
         now_at
  from public.subscription_plans p where p.code = 'BUSINESS';
  insert into public.profiles (id, display_name, role, preferred_business_id) values
    (owner_id, 'Synthetic report owner', 'OWNER', organization_id),
    (manager_id, 'Synthetic report manager', 'MANAGER', organization_id),
    (staff_id, 'Synthetic report staff', 'STAFF', organization_id),
    (other_owner_id, 'Synthetic other owner', 'OWNER', other_organization_id);
  insert into public.stations (id, organization_id, name, address) values
    (station_id, organization_id, 'Synthetic report station', 'Local fixture'),
    (unassigned_station_id, organization_id, 'Unassigned manager station', 'Local fixture'),
    (other_station_id, other_organization_id, 'Other tenant station', 'Local fixture');
  insert into public.organization_members (id, organization_id, profile_id, role) values
    (owner_member_id, organization_id, owner_id, 'OWNER'),
    (manager_member_id, organization_id, manager_id, 'MANAGER'),
    (staff_member_id, organization_id, staff_id, 'STAFF'),
    (other_member_id, other_organization_id, other_owner_id, 'OWNER');
  insert into public.member_stations (member_id, station_id) values
    (manager_member_id, station_id),
    (staff_member_id, station_id),
    (other_member_id, other_station_id);
  insert into public.orders (
    id, station_id, order_number, customer_name, delivery_address, delivery_mode,
    subtotal_centavos, total_centavos, payment_method, payment_status, status
  ) values
    (order_pending_id, station_id, 'REPORT-PENDING', 'Synthetic customer', 'Pickup', 'PICKUP', 10000, 10000, 'CASH', 'PAID', 'COMPLETED'),
    (order_completed_id, station_id, 'REPORT-COMPLETED', 'Synthetic customer', 'Pickup', 'PICKUP', 10000, 10000, 'CASH', 'PAID', 'COMPLETED'),
    (order_failed_id, station_id, 'REPORT-FAILED', 'Synthetic customer', 'Pickup', 'PICKUP', 5000, 5000, 'GCASH', 'PAID', 'COMPLETED');
  insert into public.payments (
    id, organization_id, station_id, order_id, payment_method, amount_centavos,
    status, operation_id, created_at, paid_at, updated_at
  ) values
    (pending_payment_id, organization_id, station_id, order_pending_id, 'CASH', 10000, 'PAID', 'report-payment-pending', now_at, now_at, now_at),
    (completed_payment_id, organization_id, station_id, order_completed_id, 'CASH', 10000, 'PAID', 'report-payment-completed', now_at, now_at, now_at),
    (failed_payment_id, organization_id, station_id, order_failed_id, 'GCASH', 5000, 'PAID', 'report-payment-failed', now_at, now_at, now_at);
  insert into public.payment_refunds (
    id, organization_id, station_id, order_id, original_payment_id,
    operation_id, amount_centavos, payment_method, status, reason,
    requested_by, created_at
  ) values (
    pending_refund_id, organization_id, station_id, order_pending_id,
    pending_payment_id, 'report-pending-refund', 900, 'CASH', 'PENDING',
    'Synthetic old pending request', owner_id, now_at - interval '10 days'
  );
  insert into public.payment_refunds (
    id, organization_id, station_id, order_id, original_payment_id,
    operation_id, amount_centavos, payment_method, status, reason,
    requested_by, confirmed_by, created_at, completed_at
  ) values (
    completed_refund_id, organization_id, station_id, order_completed_id,
    completed_payment_id, 'report-completed-refund', 2500, 'CASH', 'COMPLETED',
    'Synthetic completed partial refund', owner_id, owner_id, now_at, now_at
  );
  insert into public.payment_refunds (
    id, organization_id, station_id, order_id, original_payment_id,
    operation_id, amount_centavos, payment_method, status, reason,
    requested_by, failed_by, created_at, failed_at, failure_reason
  ) values (
    failed_refund_id, organization_id, station_id, order_failed_id,
    failed_payment_id, 'report-failed-refund', 3000, 'GCASH', 'FAILED',
    'Synthetic failed refund', owner_id, owner_id, now_at, now_at, 'Synthetic failure'
  );

  perform pg_catalog.set_config('request.jwt.claim.sub', owner_id::text, true);
  select * into report_row
  from public.refund_obligation_report(station_id, report_start, report_end)
  where payment_method = 'CASH' and refund_status = 'PENDING';
  if report_row.pending_count <> 1
     or report_row.pending_centavos <> 900
     or report_row.reserved_refundable_centavos <> 900
     or report_row.pending_8_to_30d_count <> 1
     or report_row.pending_8_to_30d_centavos <> 900
     or report_row.oldest_pending_at <> now_at - interval '10 days' then
    raise exception 'old pending obligation or disjoint aging bucket was not reported exactly';
  end if;

  select * into report_row
  from public.refund_obligation_report(station_id, report_start, report_end)
  where payment_method = 'CASH' and refund_status = 'COMPLETED';
  if report_row.requested_count <> 1
     or report_row.requested_centavos <> 2500
     or report_row.completed_count <> 1
     or report_row.completed_centavos <> 2500
     or report_row.partial_payment_count <> 1
     or report_row.partial_refund_centavos <> 2500 then
    raise exception 'completed/partial refund aggregates are incorrect or not integer-centavo exact';
  end if;

  select * into report_row
  from public.refund_obligation_report(station_id, report_start, report_end)
  where payment_method = 'GCASH' and refund_status = 'FAILED';
  if report_row.failed_attempt_count <> 1
     or report_row.failed_attempt_centavos <> 3000
     or report_row.pending_count <> 0
     or report_row.reserved_refundable_centavos <> 0 then
    raise exception 'failed refund attempt was counted as an active refund obligation';
  end if;

  perform pg_catalog.set_config('request.jwt.claim.sub', manager_id::text, true);
  perform * from public.refund_obligation_report(station_id, report_start, report_end);
  begin
    perform * from public.refund_obligation_report(unassigned_station_id, report_start, report_end);
    raise exception 'manager read a refund report for an unassigned station';
  exception when insufficient_privilege then null;
  end;
  perform pg_catalog.set_config('request.jwt.claim.sub', staff_id::text, true);
  begin
    perform * from public.refund_obligation_report(station_id, report_start, report_end);
    raise exception 'staff read an owner/manager refund obligation report';
  exception when insufficient_privilege then null;
  end;
  perform pg_catalog.set_config('request.jwt.claim.sub', other_owner_id::text, true);
  begin
    perform * from public.refund_obligation_report(station_id, report_start, report_end);
    raise exception 'cross-tenant owner read another organization refund report';
  exception when insufficient_privilege then null;
  end;
  begin
    perform * from public.refund_obligation_report(other_station_id, report_end, report_start);
    raise exception 'invalid or unauthorized report request unexpectedly succeeded';
  exception when invalid_parameter_value or insufficient_privilege then null;
  end;
end;
$$;

select extensions.ok(true, 'synthetic tenant, role, status, period, aging, and centavo aggregates are correct');
select * from extensions.finish();
rollback;
