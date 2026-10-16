create schema if not exists app_private;

revoke all on schema app_private from public, anon, authenticated;
grant usage on schema app_private to postgres, authenticated, service_role;

create or replace function app_private.set_updated_at()
returns trigger language plpgsql set search_path = pg_catalog, public as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

revoke execute on function app_private.set_updated_at() from public, anon, authenticated, service_role;
grant execute on function app_private.set_updated_at() to postgres, authenticated, service_role;

do $$
declare table_name text;
begin
  foreach table_name in array array['profiles','organizations','stations','organization_members','customers','customer_addresses','products','riders','containers','orders','expenses','subscription_plans','subscriptions','device_tokens'] loop
    execute format('create trigger %I_updated_at before update on public.%I for each row execute function app_private.set_updated_at()', table_name, table_name);
  end loop;
end $$;

create or replace function app_private.handle_new_auth_user()
returns trigger language plpgsql security definer set search_path = pg_catalog, public, auth as $$
begin
  insert into public.profiles (id, email, display_name, email_verified)
  values (new.id, new.email, coalesce(new.raw_user_meta_data ->> 'display_name', ''), coalesce(new.email_confirmed_at is not null, false))
  on conflict (id) do update set email = excluded.email, updated_at = now();
  return new;
end;
$$;

revoke execute on function app_private.handle_new_auth_user() from public, anon, authenticated, service_role;
grant execute on function app_private.handle_new_auth_user() to postgres;

create trigger on_auth_user_created after insert on auth.users for each row execute function app_private.handle_new_auth_user();

create or replace function app_private.protect_profile_fields()
returns trigger language plpgsql set search_path = pg_catalog, public as $$
begin
  if current_user not in ('postgres', 'service_role') then
    if new.id is distinct from old.id
      or new.firebase_uid is distinct from old.firebase_uid
      or new.email is distinct from old.email
      or new.role is distinct from old.role
      or new.preferred_business_id is distinct from old.preferred_business_id
      or new.preferred_station_id is distinct from old.preferred_station_id
      or new.acquisition_source is distinct from old.acquisition_source
      or new.acquired_at is distinct from old.acquired_at
      or new.email_verified is distinct from old.email_verified
      or new.created_at is distinct from old.created_at then
      raise exception using errcode = '42501', message = 'protected profile field';
    end if;
  end if;
  return new;
end;
$$;

revoke execute on function app_private.protect_profile_fields() from public, anon, authenticated, service_role;
grant execute on function app_private.protect_profile_fields() to postgres, authenticated, service_role;

create trigger profiles_protect_fields before update on public.profiles
for each row execute function app_private.protect_profile_fields();

create or replace function app_private.is_org_member(target_org uuid)
returns boolean language sql stable security definer set search_path = pg_catalog, public, auth as $$
  select exists (select 1 from public.organization_members m where m.organization_id = target_org and m.profile_id = (select auth.uid()) and m.is_active);
$$;

create or replace function app_private.can_manage_org(target_org uuid)
returns boolean language sql stable security definer set search_path = pg_catalog, public, auth as $$
  select exists (select 1 from public.organization_members m where m.organization_id = target_org and m.profile_id = (select auth.uid()) and m.is_active and m.role in ('OWNER','MANAGER'));
$$;

create or replace function app_private.can_access_station(target_station uuid)
returns boolean language sql stable security definer set search_path = pg_catalog, public, auth as $$
  select exists (
    select 1 from public.stations s join public.organization_members m on m.organization_id = s.organization_id
    left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
    where s.id = target_station and m.profile_id = (select auth.uid()) and m.is_active
      and (m.role in ('OWNER','MANAGER') or ms.station_id is not null)
  );
$$;

create or replace function app_private.can_operate_station(target_station uuid)
returns boolean language sql stable security definer set search_path = pg_catalog, public, auth as $$
  select exists (
    select 1 from public.stations s join public.organization_members m on m.organization_id = s.organization_id
    left join public.member_stations ms on ms.member_id = m.id and ms.station_id = s.id
    where s.id = target_station and m.profile_id = (select auth.uid()) and m.is_active
      and (m.role in ('OWNER','MANAGER','STAFF','RIDER') and (m.role in ('OWNER','MANAGER') or ms.station_id is not null))
  );
$$;

revoke execute on function app_private.is_org_member(uuid) from public, anon, authenticated, service_role;
revoke execute on function app_private.can_manage_org(uuid) from public, anon, authenticated, service_role;
revoke execute on function app_private.can_access_station(uuid) from public, anon, authenticated, service_role;
revoke execute on function app_private.can_operate_station(uuid) from public, anon, authenticated, service_role;
grant execute on function app_private.is_org_member(uuid) to postgres;
grant execute on function app_private.can_manage_org(uuid) to postgres;
grant execute on function app_private.can_access_station(uuid) to postgres;
grant execute on function app_private.can_operate_station(uuid) to postgres;
grant execute on function app_private.is_org_member(uuid) to authenticated, service_role;
grant execute on function app_private.can_manage_org(uuid) to authenticated, service_role;
grant execute on function app_private.can_access_station(uuid) to authenticated, service_role;
grant execute on function app_private.can_operate_station(uuid) to authenticated, service_role;
