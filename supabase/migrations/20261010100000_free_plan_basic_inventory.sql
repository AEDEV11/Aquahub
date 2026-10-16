-- Free includes saleable product inventory (opening stock and manual
-- adjustments). Physical station-owned bottle circulation remains premium.
-- Forward-only catalog correction; do not rewrite the original seed migration.
begin;

update public.subscription_plans
set description = 'Core station, customer, product, basic inventory, and basic reporting tools for one water station',
    updated_at = now()
where code = 'FREE';

do $$
declare
  feature_rows integer;
begin
  update public.plan_features pf
  set enabled = true
  from public.subscription_plans p
  where p.id = pf.plan_id
    and p.code = 'FREE'
    and pf.feature = 'BASIC_INVENTORY';

  get diagnostics feature_rows = row_count;
  if feature_rows <> 1 then
    raise exception 'Expected exactly one FREE/BASIC_INVENTORY catalog row; updated %', feature_rows;
  end if;
end;
$$;

commit;
