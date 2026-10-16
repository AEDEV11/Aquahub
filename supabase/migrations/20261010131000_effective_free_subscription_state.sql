-- The entitlement response describes current access. Keep the historical paid
-- subscription row intact, but report FREE when its entitlement has expired.
create or replace function public.get_effective_entitlements()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  with selected_membership as (
    select m.organization_id
    from public.organization_members m
    join public.profiles p on p.id = m.profile_id
    where m.profile_id = (select auth.uid()) and m.is_active
    order by (p.preferred_business_id = m.organization_id) desc, m.created_at
    limit 1
  ),
  selected_subscription as (
    select s.id, s.organization_id, s.plan_id, s.updated_at, s.status::text as status,
           s.billing_cycle, s.current_period_end, s.trial_ends_at, s.expires_at,
           s.grace_period_ends_at, s.auto_renew, s.cancel_at_period_end
    from public.subscriptions s
    join selected_membership m on m.organization_id = s.organization_id
    where s.deleted_at is null
    order by s.updated_at desc
    limit 1
  ),
  effective_plan as (
    select app_private.effective_plan_id(m.organization_id) as id
    from selected_membership m
    union all
    select (select p.id from public.subscription_plans p where p.code = 'FREE' and p.is_active limit 1)
    where not exists (select 1 from selected_membership)
  ),
  selected_plan as (
    select p.id, p.code, p.name
    from public.subscription_plans p
    join effective_plan e on e.id = p.id
  ),
  output_status as (
    select case
      when (select code from selected_plan) = 'FREE' then 'FREE'
      when s.id is null then 'FREE'
      when s.plan_id = (select id from selected_plan) then s.status
      else 'EXPIRED'
    end as status
    from (select 1) singleton
    left join selected_subscription s on true
  )
  select pg_catalog.jsonb_build_object(
    'organization_id', (select organization_id from selected_membership),
    'subscription_id', (select id from selected_subscription),
    'subscription_plan_id', (select plan_id from selected_subscription),
    'subscription_updated_at', (select updated_at from selected_subscription),
    'subscription_status', (select status from selected_subscription),
    'subscription_billing_cycle', (select billing_cycle from selected_subscription),
    'plan_code', coalesce((select code from selected_plan), 'FREE'),
    'plan_name', coalesce((select name from selected_plan), 'Free'),
    'status', coalesce((select status from output_status), 'FREE'),
    'billing_cycle', (select billing_cycle from selected_subscription),
    'current_period_end', (select current_period_end from selected_subscription),
    'trial_ends_at', (select trial_ends_at from selected_subscription),
    'expires_at', (select expires_at from selected_subscription),
    'grace_period_ends_at', (select grace_period_ends_at from selected_subscription),
    'auto_renew', coalesce((select auto_renew from selected_subscription), false),
    'cancel_at_period_end', coalesce((select cancel_at_period_end from selected_subscription), false),
    'features', coalesce((
      select pg_catalog.jsonb_object_agg(pf.feature, pf.enabled)
      from public.plan_features pf join selected_plan sp on sp.id = pf.plan_id
    ), '{}'::jsonb),
    'limits', coalesce((
      select pg_catalog.jsonb_object_agg(pl.limit_type, pl.max_value)
      from public.plan_limits pl join selected_plan sp on sp.id = pl.plan_id
    ), '{}'::jsonb)
  );
$$;

revoke all on function public.get_effective_entitlements() from public, anon;
grant execute on function public.get_effective_entitlements() to authenticated;
