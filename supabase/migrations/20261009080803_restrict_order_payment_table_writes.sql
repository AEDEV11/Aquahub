begin;

-- Business order and receipt mutations are authoritative RPC operations.
-- Keep only the non-financial owner note as a direct order update.
revoke insert, update, delete on table public.orders from authenticated;
revoke update (payment_method) on table public.orders from authenticated;
revoke update (station_note) on table public.orders from authenticated;
grant update (station_note) on table public.orders to authenticated;

revoke insert, update, delete on table public.order_items from authenticated;
revoke insert, update, delete on table public.payments from authenticated;

create or replace function public.charge_order_to_client_account(p_order_id uuid)
returns uuid
language plpgsql
security definer
set search_path = pg_catalog, public, auth, app_private
as $$
declare
  caller uuid := auth.uid();
  order_row public.orders%rowtype;
begin
  if caller is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;

  select * into order_row
  from public.orders
  where id = p_order_id
  for update;
  if order_row.id is null then
    raise exception using errcode = 'P0002', message = 'order not found';
  end if;
  if not app_private.can_operate_station(order_row.station_id) then
    raise exception using errcode = '42501', message = 'station access denied';
  end if;
  if order_row.status in ('CANCELLED', 'REJECTED') then
    raise exception using errcode = '55000', message = 'cancelled orders cannot be charged to account';
  end if;

  if order_row.payment_method = 'CLIENT_ACCOUNT' then
    return order_row.id;
  end if;
  if order_row.payment_status <> 'PENDING' or exists (
    select 1 from public.payments p
    where p.order_id = order_row.id and p.status = 'PAID'
  ) then
    raise exception using errcode = '55000', message = 'an order with a collected payment cannot be charged to account';
  end if;

  update public.orders
  set payment_method = 'CLIENT_ACCOUNT', payment_status = 'PENDING', updated_at = now()
  where id = order_row.id;
  return order_row.id;
end;
$$;

revoke all on function public.charge_order_to_client_account(uuid)
  from public, anon, authenticated, service_role;
grant execute on function public.charge_order_to_client_account(uuid)
  to authenticated, service_role;

commit;
