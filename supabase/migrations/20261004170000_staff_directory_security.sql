-- Manager-scoped staff directory read. Mutations remain server-only until the
-- Auth Admin provisioning Edge Function is deployed and validated.
create or replace function public.staff_directory()
returns table (
  uid uuid,
  name text,
  phone text,
  role public.member_role,
  is_active boolean,
  station_ids uuid[]
)
language sql
security definer
set search_path = pg_catalog, public, auth
as $$
  select
    m.profile_id,
    coalesce(p.display_name, ''),
    coalesce(p.phone, ''),
    m.role,
    m.is_active,
    coalesce(array_agg(ms.station_id) filter (where ms.station_id is not null), '{}'::uuid[])
  from public.organization_members m
  join public.profiles p on p.id = m.profile_id
  left join public.member_stations ms on ms.member_id = m.id
  where exists (
    select 1
    from public.organization_members caller
    where caller.organization_id = m.organization_id
      and caller.profile_id = (select auth.uid())
      and caller.is_active
      and caller.role in ('OWNER', 'MANAGER')
  )
  group by m.profile_id, p.display_name, p.phone, m.role, m.is_active;
$$;

revoke all on function public.staff_directory() from public, anon;
grant execute on function public.staff_directory() to authenticated, service_role;
