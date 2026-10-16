-- Review-only assertions for migration 009. Run only after migration 009 is
-- deliberately deployed to the hosted project; this file does not create data.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(1);

do $$
declare
  has_trigger boolean;
  has_insert_policy boolean;
  has_delete_policy boolean;
  has_provision_function boolean;
begin
  select exists (
    select 1 from pg_trigger
    where tgrelid = 'auth.users'::regclass
      and tgname = 'on_auth_user_created'
      and not tgisinternal
  ) into has_trigger;
  if has_trigger then
    raise exception 'verification-first invariant failed: auth profile trigger still exists';
  end if;

  select exists (
    select 1 from pg_policies
    where schemaname = 'public' and tablename = 'profiles' and policyname = 'profiles_insert_own'
  ) into has_insert_policy;
  select exists (
    select 1 from pg_policies
    where schemaname = 'public' and tablename = 'profiles' and policyname = 'profiles_delete_own'
  ) into has_delete_policy;
  if has_insert_policy or has_delete_policy then
    raise exception 'verification-first invariant failed: client profile write policy remains';
  end if;

  select exists (
    select 1 from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'provision_verified_profile'
  ) into has_provision_function;
  if not has_provision_function then
    raise exception 'verification-first invariant failed: provisioning function missing';
  end if;
end;
$$;

select extensions.ok(true, 'verification-first profile provisioning constraints are present');
select * from extensions.finish();
rollback;
