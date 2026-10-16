-- The deployed counter table may predate the atomic-order migration. Ensure
-- the conflict target used by next_order_number is backed by a unique index.
-- Refuse to guess if historical duplicate counter rows exist.
do $$
begin
  if exists (
    select 1
    from public.order_number_counters
    group by station_id, order_date
    having count(*) > 1
  ) then
    raise exception using
      errcode = '23505',
      message = 'duplicate order number counters require manual reconciliation';
  end if;
end;
$$;

create unique index if not exists order_number_counters_station_date_uidx
  on public.order_number_counters (station_id, order_date);
