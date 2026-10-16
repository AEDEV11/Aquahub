-- Static hosted invariants. Authenticated success cases require trusted fixtures.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(2);

do $$
declare
  bucket_is_public boolean;
begin
  select public into bucket_is_public from storage.buckets where id = 'product-images';
  if bucket_is_public is distinct from true then raise exception 'product-images must be public'; end if;
  select public into bucket_is_public from storage.buckets where id = 'station-assets';
  if bucket_is_public is distinct from true then raise exception 'station-assets must be public'; end if;
  select public into bucket_is_public from storage.buckets where id = 'payment-assets';
  if bucket_is_public is distinct from false then raise exception 'payment-assets must be private'; end if;

  if not exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public' and p.proname = 'staff_directory') then
    raise exception 'staff_directory RPC missing';
  end if;
  if exists (select 1 from information_schema.routine_privileges where routine_schema = 'public' and routine_name = 'staff_directory' and grantee in ('PUBLIC', 'anon') and privilege_type = 'EXECUTE') then
    raise exception 'staff_directory must not be executable by anon/public';
  end if;
  if not exists (select 1 from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'public' and p.proname = 'update_station_settings') then
    raise exception 'update_station_settings RPC missing';
  end if;
  if exists (select 1 from information_schema.routine_privileges where routine_schema = 'public' and routine_name = 'update_station_settings' and grantee in ('PUBLIC', 'anon') and privilege_type = 'EXECUTE') then
    raise exception 'update_station_settings must not be executable by anon/public';
  end if;
  if exists (select 1 from information_schema.column_privileges where table_schema = 'public' and table_name = 'stations' and grantee = 'authenticated' and privilege_type = 'UPDATE') then
    raise exception 'authenticated must not directly update station rows';
  end if;
end $$;
select extensions.ok(true, 'storage bucket visibility and staff mutation privileges are configured safely');

do $$
begin
  if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = 'storage_private_assets_select') then
    raise exception 'payment asset read policy missing';
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = 'storage_station_assets_insert') then
    raise exception 'payment asset insert policy missing';
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = 'storage_station_assets_delete') then
    raise exception 'payment asset delete policy missing';
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = 'storage_rider_delivery_payment_proof_insert') then
    raise exception 'assigned rider payment proof upload policy missing';
  end if;
  if not exists (select 1 from pg_policies where schemaname = 'storage' and tablename = 'objects' and policyname = 'storage_rider_delivery_payment_proof_select') then
    raise exception 'assigned rider payment proof read policy missing';
  end if;
  if not exists (
    select 1 from pg_trigger
    where tgrelid = 'public.payments'::regclass and tgname = 'payments_apply_customer_paid_spend'
  ) then
    raise exception 'customer paid receipt aggregate trigger missing';
  end if;
end $$;
select extensions.ok(true, 'private storage policies and payment aggregate trigger are present');

select * from extensions.finish();
rollback;
