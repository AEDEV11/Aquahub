-- Expense authority and Storage foundation. Additive only: legacy rows/objects
-- remain available for import and read compatibility until separately approved.
alter table public.expenses
  add column if not exists organization_id uuid references public.organizations(id) on delete restrict,
  add column if not exists created_by uuid references public.profiles(id) on delete set null default auth.uid(),
  add column if not exists receipt_path text,
  add column if not exists operation_id text;

update public.expenses e
set organization_id = s.organization_id
from public.stations s
where s.id = e.station_id and e.organization_id is null;

create unique index if not exists expenses_operation_id_uidx
  on public.expenses(operation_id)
  where operation_id is not null;
create index if not exists expenses_org_station_date_idx
  on public.expenses(organization_id, station_id, incurred_at);

create or replace function app_private.protect_expense_context()
returns trigger
language plpgsql
set search_path = pg_catalog, public, auth
as $$
begin
  if tg_op = 'INSERT' then
    select s.organization_id into new.organization_id
    from public.stations s where s.id = new.station_id;
    new.created_by := coalesce(new.created_by, auth.uid());
  elsif tg_op = 'UPDATE' then
    if new.station_id is distinct from old.station_id
       or new.organization_id is distinct from old.organization_id
       or new.created_by is distinct from old.created_by then
      raise exception using errcode = '42501', message = 'expense ownership fields are immutable';
    end if;
  end if;
  return new;
end;
$$;

drop trigger if exists expenses_protect_context on public.expenses;
create trigger expenses_protect_context
before insert or update on public.expenses
for each row execute function app_private.protect_expense_context();

revoke execute on function app_private.protect_expense_context() from public, anon, authenticated, service_role;
grant execute on function app_private.protect_expense_context() to postgres;

drop policy if exists expenses_manager_access on public.expenses;
create policy expenses_select_manager on public.expenses
for select to authenticated
using (exists (
  select 1 from public.stations s
  where s.id = expenses.station_id and app_private.can_manage_org(s.organization_id)
));

create policy expenses_insert_manager on public.expenses
for insert to authenticated
with check (exists (
  select 1 from public.stations s
  where s.id = expenses.station_id and app_private.can_manage_org(s.organization_id)
));

create policy expenses_update_manager on public.expenses
for update to authenticated
using (exists (
  select 1 from public.stations s
  where s.id = expenses.station_id and app_private.can_manage_org(s.organization_id)
))
with check (exists (
  select 1 from public.stations s
  where s.id = expenses.station_id and app_private.can_manage_org(s.organization_id)
));

-- The app archives expenses; direct DELETE is intentionally not part of the API.
revoke delete on public.expenses from authenticated;

create or replace function public.expense_summary(
  p_station_id uuid,
  p_start_at bigint,
  p_end_at bigint,
  p_category text default null
)
returns table(category text, total_centavos bigint, expense_count bigint)
language sql
stable
security invoker
set search_path = pg_catalog, public
as $$
  select e.category, coalesce(sum(e.amount_centavos), 0)::bigint, count(*)::bigint
  from public.expenses e
  where e.station_id = p_station_id
    and e.is_active
    and e.incurred_at >= p_start_at
    and e.incurred_at < p_end_at
    and (p_category is null or e.category = p_category)
  group by e.category
  order by e.category
$$;

revoke all on function public.expense_summary(uuid, bigint, bigint, text) from public, anon, authenticated, service_role;
grant execute on function public.expense_summary(uuid, bigint, bigint, text) to authenticated, service_role;

-- Public catalog assets; payment QR assets are authenticated station assets.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values
  ('product-images', 'product-images', true, 5242880, array['image/jpeg', 'image/png', 'image/webp']),
  ('station-assets', 'station-assets', true, 5242880, array['image/jpeg', 'image/png', 'image/webp']),
  ('payment-assets', 'payment-assets', false, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update set
  public = excluded.public,
  file_size_limit = excluded.file_size_limit,
  allowed_mime_types = excluded.allowed_mime_types;

create or replace function app_private.storage_station_manage(object_name text)
returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public, auth, app_private, storage
as $$
  select object_name ~ '^organizations/[0-9a-fA-F-]{36}/stations/[0-9a-fA-F-]{36}/'
     and exists (
       select 1 from public.stations s
       where s.id = split_part(object_name, '/', 4)::uuid
         and s.organization_id = split_part(object_name, '/', 2)::uuid
         and app_private.can_manage_org(s.organization_id)
     )
$$;

revoke execute on function app_private.storage_station_manage(text) from public, anon, authenticated, service_role;
grant execute on function app_private.storage_station_manage(text) to postgres, authenticated, service_role;

drop policy if exists storage_station_assets_insert on storage.objects;
drop policy if exists storage_station_assets_update on storage.objects;
drop policy if exists storage_station_assets_delete on storage.objects;
drop policy if exists storage_private_assets_select on storage.objects;

create policy storage_station_assets_insert on storage.objects
for insert to authenticated
with check (
  bucket_id in ('product-images', 'station-assets', 'payment-assets')
  and app_private.storage_station_manage(name)
);

create policy storage_station_assets_update on storage.objects
for update to authenticated
using (
  bucket_id in ('product-images', 'station-assets', 'payment-assets')
  and app_private.storage_station_manage(name)
)
with check (
  bucket_id in ('product-images', 'station-assets', 'payment-assets')
  and app_private.storage_station_manage(name)
);

create policy storage_station_assets_delete on storage.objects
for delete to authenticated
using (
  bucket_id in ('product-images', 'station-assets', 'payment-assets')
  and app_private.storage_station_manage(name)
);

create policy storage_private_assets_select on storage.objects
for select to authenticated
using (
  bucket_id in ('payment-assets')
  and app_private.storage_station_manage(name)
);
