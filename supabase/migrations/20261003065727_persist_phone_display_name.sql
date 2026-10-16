-- Keep the registration name captured in Supabase Auth metadata in sync with
-- an existing profile when phone verification is retried.
create or replace function public.provision_verified_profile()
returns public.profiles
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
  current_auth_user auth.users;
  has_google_identity boolean;
  provisioned public.profiles;
begin
  select * into current_auth_user
  from auth.users
  where id = (select auth.uid());

  if current_auth_user.id is null then
    raise exception using errcode = '28000', message = 'authenticated user is required';
  end if;

  select exists (
    select 1
    from auth.identities identity_row
    where identity_row.user_id = current_auth_user.id
      and identity_row.provider = 'google'
  ) into has_google_identity;

  if current_auth_user.email_confirmed_at is null
     and current_auth_user.phone_confirmed_at is null
     and not has_google_identity then
    raise exception using errcode = '42501', message = 'identity verification is required before profile provisioning';
  end if;

  if has_google_identity and current_auth_user.email_confirmed_at is null then
    raise exception using errcode = '42501', message = 'verified Google email is required before profile provisioning';
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
    coalesce(current_auth_user.phone, ''),
    coalesce(current_auth_user.raw_user_meta_data ->> 'display_name', ''),
    current_auth_user.email_confirmed_at is not null,
    current_auth_user.phone_confirmed_at is not null,
    'ACTIVE',
    'CUSTOMER'
  )
  on conflict (id) do update set
    email = excluded.email,
    phone = excluded.phone,
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
