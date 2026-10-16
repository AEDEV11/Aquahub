-- Group 4: read-only, tenant-authorized refund obligation aggregates.
-- Pending obligations are reported as-of-now regardless of the selected
-- activity period so an old unresolved refund cannot disappear from a report.
begin;

create or replace function public.refund_obligation_report(
  p_station_id uuid,
  p_start_at timestamptz,
  p_end_at timestamptz
)
returns table (
  organization_id uuid,
  organization_name text,
  station_id uuid,
  station_name text,
  payment_method text,
  refund_status text,
  period_start timestamptz,
  period_end timestamptz,
  requested_count bigint,
  requested_centavos bigint,
  pending_count bigint,
  pending_centavos bigint,
  reserved_refundable_centavos bigint,
  completed_count bigint,
  completed_centavos bigint,
  failed_attempt_count bigint,
  failed_attempt_centavos bigint,
  partial_payment_count bigint,
  partial_refund_centavos bigint,
  oldest_pending_at timestamptz,
  pending_under_24h_count bigint,
  pending_under_24h_centavos bigint,
  pending_1_to_7d_count bigint,
  pending_1_to_7d_centavos bigint,
  pending_8_to_30d_count bigint,
  pending_8_to_30d_centavos bigint,
  pending_over_30d_count bigint,
  pending_over_30d_centavos bigint
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  caller_id uuid := auth.uid();
  selected_organization_id uuid;
  report_time timestamptz := pg_catalog.transaction_timestamp();
begin
  if caller_id is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;
  if p_station_id is null or p_start_at is null or p_end_at is null
     or p_end_at <= p_start_at
     or p_start_at < p_end_at - interval '10 years'
     or p_end_at > report_time + interval '1 day' then
    raise exception using errcode = '22023', message = 'valid station and report period are required';
  end if;

  select s.organization_id into selected_organization_id
  from public.stations s
  join public.organization_members m on m.organization_id = s.organization_id
  left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
  where s.id = p_station_id
    and m.profile_id = caller_id
    and m.is_active
    and m.role in ('OWNER', 'MANAGER')
    and (m.role = 'OWNER' or ms.station_id is not null)
  limit 1;

  if selected_organization_id is null then
    raise exception using errcode = '42501', message = 'refund report access denied';
  end if;

  perform app_private.require_plan_feature(selected_organization_id, 'BASIC_REPORTS');

  return query
  with scoped_refunds as (
    select r.organization_id, o.name as organization_name,
           r.station_id, s.name as station_name, r.payment_method, r.status,
           r.amount_centavos, r.created_at, r.completed_at, r.failed_at,
           r.original_payment_id
    from public.payment_refunds r
    join public.organizations o on o.id = r.organization_id
    join public.stations s on s.id = r.station_id and s.organization_id = r.organization_id
    where r.station_id = p_station_id
  ),
  partial_receipts as (
    select p.organization_id, p.station_id, p.payment_method,
           pg_catalog.count(*)::bigint as partial_payment_count,
           pg_catalog.sum(t.completed_centavos)::bigint as partial_refund_centavos
    from public.payments p
    join lateral (
      select pg_catalog.sum(r.amount_centavos)::bigint as completed_centavos
      from public.payment_refunds r
      where r.original_payment_id = p.id and r.status = 'COMPLETED'
    ) t on t.completed_centavos > 0 and t.completed_centavos < p.amount_centavos
    where p.station_id = p_station_id and p.status = 'PAID'
    group by p.organization_id, p.station_id, p.payment_method
  ),
  report_keys as (
    select r.organization_id, r.organization_name, r.station_id, r.station_name,
           r.payment_method, r.status as refund_status
    from scoped_refunds r
    where (r.created_at >= p_start_at and r.created_at < p_end_at)
       or r.status = 'PENDING'
       or (r.status = 'COMPLETED' and r.completed_at >= p_start_at and r.completed_at < p_end_at)
       or (r.status = 'FAILED' and r.failed_at >= p_start_at and r.failed_at < p_end_at)
    group by r.organization_id, r.organization_name, r.station_id, r.station_name,
             r.payment_method, r.status
    union
    select o.id, o.name, s.id, s.name, pr.payment_method, 'COMPLETED'
    from partial_receipts pr
    join public.organizations o on o.id = pr.organization_id
    join public.stations s on s.id = pr.station_id
  ),
  activity as (
    select r.organization_id, r.station_id, r.payment_method, r.status,
           pg_catalog.count(*) filter (
             where r.created_at >= p_start_at and r.created_at < p_end_at
           )::bigint as requested_count,
           coalesce(pg_catalog.sum(r.amount_centavos) filter (
             where r.created_at >= p_start_at and r.created_at < p_end_at
           ), 0)::bigint as requested_centavos,
           pg_catalog.count(*) filter (where r.status = 'PENDING')::bigint as pending_count,
           coalesce(pg_catalog.sum(r.amount_centavos) filter (where r.status = 'PENDING'), 0)::bigint as pending_centavos,
           pg_catalog.count(*) filter (
             where r.status = 'COMPLETED' and r.completed_at >= p_start_at and r.completed_at < p_end_at
           )::bigint as completed_count,
           coalesce(pg_catalog.sum(r.amount_centavos) filter (
             where r.status = 'COMPLETED' and r.completed_at >= p_start_at and r.completed_at < p_end_at
           ), 0)::bigint as completed_centavos,
           pg_catalog.count(*) filter (
             where r.status = 'FAILED' and r.failed_at >= p_start_at and r.failed_at < p_end_at
           )::bigint as failed_attempt_count,
           coalesce(pg_catalog.sum(r.amount_centavos) filter (
             where r.status = 'FAILED' and r.failed_at >= p_start_at and r.failed_at < p_end_at
           ), 0)::bigint as failed_attempt_centavos,
           min(r.created_at) filter (where r.status = 'PENDING') as oldest_pending_at,
           pg_catalog.count(*) filter (
             where r.status = 'PENDING' and r.created_at >= report_time - interval '24 hours'
           )::bigint as pending_under_24h_count,
           coalesce(pg_catalog.sum(r.amount_centavos) filter (
             where r.status = 'PENDING' and r.created_at >= report_time - interval '24 hours'
           ), 0)::bigint as pending_under_24h_centavos,
           pg_catalog.count(*) filter (
             where r.status = 'PENDING'
               and r.created_at < report_time - interval '24 hours'
               and r.created_at >= report_time - interval '7 days'
           )::bigint as pending_1_to_7d_count,
           coalesce(pg_catalog.sum(r.amount_centavos) filter (
             where r.status = 'PENDING'
               and r.created_at < report_time - interval '24 hours'
               and r.created_at >= report_time - interval '7 days'
           ), 0)::bigint as pending_1_to_7d_centavos,
           pg_catalog.count(*) filter (
             where r.status = 'PENDING'
               and r.created_at < report_time - interval '7 days'
               and r.created_at >= report_time - interval '30 days'
           )::bigint as pending_8_to_30d_count,
           coalesce(pg_catalog.sum(r.amount_centavos) filter (
             where r.status = 'PENDING'
               and r.created_at < report_time - interval '7 days'
               and r.created_at >= report_time - interval '30 days'
           ), 0)::bigint as pending_8_to_30d_centavos,
           pg_catalog.count(*) filter (
             where r.status = 'PENDING' and r.created_at < report_time - interval '30 days'
           )::bigint as pending_over_30d_count,
           coalesce(pg_catalog.sum(r.amount_centavos) filter (
             where r.status = 'PENDING' and r.created_at < report_time - interval '30 days'
           ), 0)::bigint as pending_over_30d_centavos
    from scoped_refunds r
    group by r.organization_id, r.station_id, r.payment_method, r.status
  )
  select k.organization_id, k.organization_name, k.station_id, k.station_name,
         k.payment_method, k.refund_status, p_start_at, p_end_at,
         coalesce(a.requested_count, 0), coalesce(a.requested_centavos, 0),
         coalesce(a.pending_count, 0), coalesce(a.pending_centavos, 0),
         coalesce(a.pending_centavos, 0),
         coalesce(a.completed_count, 0), coalesce(a.completed_centavos, 0),
         coalesce(a.failed_attempt_count, 0), coalesce(a.failed_attempt_centavos, 0),
         case when k.refund_status = 'COMPLETED' then coalesce(pr.partial_payment_count, 0) else 0 end,
         case when k.refund_status = 'COMPLETED' then coalesce(pr.partial_refund_centavos, 0) else 0 end,
         a.oldest_pending_at,
         coalesce(a.pending_under_24h_count, 0), coalesce(a.pending_under_24h_centavos, 0),
         coalesce(a.pending_1_to_7d_count, 0), coalesce(a.pending_1_to_7d_centavos, 0),
         coalesce(a.pending_8_to_30d_count, 0), coalesce(a.pending_8_to_30d_centavos, 0),
         coalesce(a.pending_over_30d_count, 0), coalesce(a.pending_over_30d_centavos, 0)
  from report_keys k
  left join activity a on a.organization_id = k.organization_id
    and a.station_id = k.station_id and a.payment_method = k.payment_method
    and a.status = k.refund_status
  left join partial_receipts pr on pr.organization_id = k.organization_id
    and pr.station_id = k.station_id and pr.payment_method = k.payment_method
  order by k.payment_method, k.refund_status;
end;
$$;

revoke all on function public.refund_obligation_report(uuid, timestamptz, timestamptz)
  from public, anon, authenticated, service_role;
grant execute on function public.refund_obligation_report(uuid, timestamptz, timestamptz)
  to authenticated;

commit;
