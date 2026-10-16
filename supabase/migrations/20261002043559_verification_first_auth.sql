-- Verification-first auth preparation.
-- This migration is intentionally not deployed in this change.

alter table public.profiles
  add column if not exists phone_verified boolean not null default false,
  add column if not exists account_state text not null default 'ACTIVE';

alter table public.profiles
  drop constraint if exists profiles_account_state_check;

alter table public.profiles
  add constraint profiles_account_state_check
  check (account_state in (
    'AUTH_NOT_CREATED',
    'AUTH_PENDING_VERIFICATION',
    'VERIFIED_NOT_PROVISIONED',
    'ACTIVE',
    'MIGRATION_REQUIRED',
    'MANUAL_REVIEW',
    'DISABLED'
  ));

-- An auth.users row may exist before verification. It must not create an
-- application profile or grant a client a profile INSERT/DELETE escape hatch.
drop trigger if exists on_auth_user_created on auth.users;
drop policy if exists profiles_insert_own on public.profiles;
drop policy if exists profiles_delete_own on public.profiles;

create or replace function app_private.protect_profile_fields()
returns trigger
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
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
      or new.phone_verified is distinct from old.phone_verified
      or new.account_state is distinct from old.account_state
      or new.created_at is distinct from old.created_at then
      raise exception using errcode = '42501', message = 'protected profile field';
    end if;
  end if;
  return new;
end;
$$;

revoke execute on function app_private.protect_profile_fields() from public, anon, authenticated, service_role;
grant execute on function app_private.protect_profile_fields() to postgres, authenticated, service_role;

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
