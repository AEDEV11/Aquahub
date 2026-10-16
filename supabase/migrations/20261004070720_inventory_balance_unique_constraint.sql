-- The deployed inventory table may predate the ledger migration. Ensure the
-- conflict target used by order/inventory RPCs is backed by a unique index.
-- Refuse to guess if duplicate historical balances exist.
do $$
begin
  if exists (
    select 1
    from public.inventory_balances
    group by station_id, product_id
    having count(*) > 1
  ) then
    raise exception using
      errcode = '23505',
      message = 'duplicate inventory balances require manual reconciliation';
  end if;
end;
$$;

create unique index if not exists inventory_balances_station_product_uidx
  on public.inventory_balances (station_id, product_id);
