begin;

-- Preserve the existing RPC contract while allowing only the rider assigned to
-- a delivered order to record a saved customer's unpaid balance.
create or replace function public.charge_order_to_client_account(p_order_id uuid)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
  target_organization_id uuid;
  caller_role public.member_role;
  assigned_rider_authorized boolean := false;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;

  select * into order_row
  from public.orders o
  where o.id = p_order_id
  for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;

  select s.organization_id into target_organization_id
  from public.stations s
  where s.id = order_row.station_id;

  select m.role into caller_role
  from public.organization_members m
  left join public.member_stations ms
    on ms.member_id = m.id and ms.station_id = order_row.station_id
  where m.organization_id = target_organization_id
    and m.profile_id = caller
    and m.is_active
    and m.role in ('OWNER', 'MANAGER', 'STAFF')
    and (m.role in ('OWNER', 'MANAGER') or ms.station_id is not null)
  limit 1;

  select exists (
    select 1
    from public.riders r
    where r.id = order_row.rider_id
      and r.station_id = order_row.station_id
      and r.profile_id = caller
      and r.is_active
      and r.deleted_at is null
  ) into assigned_rider_authorized;

  if caller_role is null and not assigned_rider_authorized then
    raise exception using errcode = '42501', message = 'station access denied';
  end if;
  if assigned_rider_authorized and (
    order_row.delivery_mode <> 'DELIVERY'
    or order_row.status not in ('DELIVERED', 'COMPLETED')
  ) then
    raise exception using errcode = '42501', message = 'only the assigned rider may charge a delivered order';
  end if;
  if order_row.status in ('CANCELLED', 'REJECTED') then
    raise exception using errcode = '55000', message = 'cancelled orders cannot be charged to account';
  end if;
  if order_row.customer_id is null or not exists (
    select 1
    from public.customers c
    where c.id = order_row.customer_id
      and c.station_id = order_row.station_id
      and c.is_active
      and c.deleted_at is null
  ) then
    raise exception using errcode = '55000', message = 'a saved active customer is required to charge this order';
  end if;

  perform app_private.require_plan_feature(target_organization_id, 'ACCOUNTS_RECEIVABLE');

  if order_row.payment_method = 'CLIENT_ACCOUNT' then
    return order_row.id;
  end if;
  if order_row.payment_status <> 'PENDING' or exists (
    select 1
    from public.payments p
    where p.order_id = order_row.id and p.status = 'PAID'
  ) then
    raise exception using errcode = '55000', message = 'an order with a collected payment cannot be charged to account';
  end if;

  update public.orders
  set payment_method = 'CLIENT_ACCOUNT', payment_status = 'PENDING', updated_at = pg_catalog.now()
  where id = order_row.id;
  return order_row.id;
end;
$$;

revoke all on function public.charge_order_to_client_account(uuid)
  from public, anon, authenticated, service_role;
grant execute on function public.charge_order_to_client_account(uuid)
  to authenticated, service_role;

commit;
