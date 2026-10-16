-- Keep profile contact metadata in sync when email confirmation completed
-- before the client had an authenticated session to write public.profiles.
create or replace function public.provision_verified_profile()
returns public.profiles
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  current_auth_user auth.users;
  provisioned public.profiles;
begin
  select * into current_auth_user
  from auth.users
  where id = (select auth.uid());

  if current_auth_user.id is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;

  insert into public.profiles (
    id,
    email,
    phone,
    display_name,
    email_verified,
    phone_verified,
    account_state,
    role
  ) values (
    current_auth_user.id,
    current_auth_user.email,
    coalesce(
      nullif(btrim(current_auth_user.phone), ''),
      nullif(btrim(current_auth_user.raw_user_meta_data ->> 'phone'), ''),
      ''
    ),
    coalesce(current_auth_user.raw_user_meta_data ->> 'display_name', ''),
    current_auth_user.email_confirmed_at is not null,
    current_auth_user.phone_confirmed_at is not null,
    'ACTIVE',
    'CUSTOMER'
  )
  on conflict (id) do update set
    email = excluded.email,
    phone = case
      when nullif(btrim(excluded.phone), '') is not null then excluded.phone
      else public.profiles.phone
    end,
    display_name = case
      when nullif(btrim(excluded.display_name), '') is not null
        then excluded.display_name
      else public.profiles.display_name
    end,
    email_verified = excluded.email_verified,
    phone_verified = excluded.phone_verified,
    account_state = case
      when public.profiles.account_state = 'DISABLED' then 'DISABLED'
      else 'ACTIVE'
    end,
    updated_at = now()
  returning * into provisioned;

  return provisioned;
end;
$$;

revoke all on function public.provision_verified_profile() from public, anon;
grant execute on function public.provision_verified_profile() to authenticated, service_role;
