-- Subscription state is provider-verified on the server.  These states mirror
-- the existing Android domain model without making the client the authority.
alter type public.subscription_status add value if not exists 'GRACE_PERIOD';
alter type public.subscription_status add value if not exists 'SUSPENDED';

alter table public.subscriptions
  add column if not exists last_verified_at bigint;

create or replace function public.get_effective_entitlements()
returns jsonb
language sql
stable
security invoker
set search_path = pg_catalog, public, auth
as $$
  with selected_membership as (
    select m.organization_id
    from public.organization_members m
    join public.profiles p on p.id = m.profile_id
    where m.profile_id = (select auth.uid())
      and m.is_active
    order by (p.preferred_business_id = m.organization_id) desc, m.created_at
    limit 1
  ),
  selected_subscription as (
    select s.id, s.organization_id, s.status::text as status,
           s.billing_cycle, s.current_period_end, s.trial_ends_at,
           s.expires_at, s.auto_renew, s.cancel_at_period_end,
           p.code as plan_code, p.name as plan_name
    from public.subscriptions s
    join selected_membership m on m.organization_id = s.organization_id
    left join public.subscription_plans p on p.id = s.plan_id
    where s.deleted_at is null
    order by s.updated_at desc
    limit 1
  ),
  selected_plan as (
    select p.id, p.code, p.name
    from public.subscription_plans p
    where p.code = coalesce((select plan_code from selected_subscription), 'FREE')
    limit 1
  )
  select jsonb_build_object(
    'organization_id', (select organization_id from selected_membership),
    'subscription_id', (select id from selected_subscription),
    'plan_code', coalesce((select plan_code from selected_subscription), (select code from selected_plan), 'FREE'),
    'plan_name', coalesce((select plan_name from selected_subscription), (select name from selected_plan), 'Free'),
    'status', coalesce((select status from selected_subscription), 'FREE'),
    'billing_cycle', (select billing_cycle from selected_subscription),
    'current_period_end', (select current_period_end from selected_subscription),
    'trial_ends_at', (select trial_ends_at from selected_subscription),
    'expires_at', (select expires_at from selected_subscription),
    'auto_renew', coalesce((select auto_renew from selected_subscription), false),
    'cancel_at_period_end', coalesce((select cancel_at_period_end from selected_subscription), false),
    'features', coalesce((
      select jsonb_object_agg(pf.feature, pf.enabled)
      from public.plan_features pf
      join selected_plan sp on sp.id = pf.plan_id
    ), '{}'::jsonb),
    'limits', coalesce((
      select jsonb_object_agg(pl.limit_type, pl.max_value)
      from public.plan_limits pl
      join selected_plan sp on sp.id = pl.plan_id
    ), '{}'::jsonb)
  );
$$;

revoke all on function public.get_effective_entitlements() from public, anon;
grant execute on function public.get_effective_entitlements() to authenticated;
