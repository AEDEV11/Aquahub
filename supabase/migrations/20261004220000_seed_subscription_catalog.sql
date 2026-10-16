-- Keep the server-side subscription catalog in sync with the owner app's
-- canonical plan definitions.  The Android Room seeder is only a local cache;
-- trial creation and entitlement resolution must have the same plans in
-- Supabase.

insert into public.subscription_plans (
  code,
  name,
  description,
  monthly_price_centavos,
  annual_price_centavos,
  is_popular,
  trial_days,
  cta_text,
  is_active,
  sort_order
)
values
  ('FREE', 'Free', 'Core station, customer, product, and basic reporting tools for one water station', 0, 0, false, 0, 'Continue with Free', true, 0),
  ('STARTER', 'Starter', 'Inventory, mobile ordering, and rider tools for one water station', 29900, 287040, false, 30, 'Start 30-Day Free Trial', true, 1),
  ('BUSINESS', 'Business', 'Delivery, advanced reporting, and up to three water stations', 69900, 671040, true, 30, 'Start 30-Day Free Trial', true, 2),
  ('PRO', 'Pro', 'Full operations, analytics, and up to ten water stations', 149900, 1439040, false, 30, 'Start 30-Day Free Trial', true, 3)
on conflict (code) do update set
  name = excluded.name,
  description = excluded.description,
  monthly_price_centavos = excluded.monthly_price_centavos,
  annual_price_centavos = excluded.annual_price_centavos,
  is_popular = excluded.is_popular,
  trial_days = excluded.trial_days,
  cta_text = excluded.cta_text,
  is_active = excluded.is_active,
  sort_order = excluded.sort_order,
  updated_at = now();

-- The feature and limit rows are catalog metadata, not customer state. Replace
-- them for these canonical plans so a retry remains deterministic and the
-- entitlement RPC never serves a partially seeded plan.
delete from public.plan_features
where plan_id in (select id from public.subscription_plans where code in ('FREE', 'STARTER', 'BUSINESS', 'PRO'));

delete from public.plan_limits
where plan_id in (select id from public.subscription_plans where code in ('FREE', 'STARTER', 'BUSINESS', 'PRO'));

insert into public.plan_features (plan_id, feature, enabled, limit_value)
select p.id, v.feature, v.enabled, v.limit_value
from (
  values
    ('FREE', 'STATION_MANAGEMENT', true, null::bigint),
    ('FREE', 'DASHBOARD', true, null::bigint),
    ('FREE', 'ORDER_MANAGEMENT', true, null::bigint),
    ('FREE', 'CUSTOMER_MANAGEMENT', true, null::bigint),
    ('FREE', 'PRODUCT_MANAGEMENT', true, null::bigint),
    ('FREE', 'BASIC_INVENTORY', false, null::bigint),
    ('FREE', 'BASIC_CONTAINER_TRACKING', false, null::bigint),
    ('FREE', 'BASIC_REPORTS', true, null::bigint),
    ('FREE', 'DATA_EXPORT', false, null::bigint),
    ('FREE', 'MOBILE_APP_ORDERING', false, null::bigint),
    ('FREE', 'DELIVERY_MANAGEMENT', false, null::bigint),
    ('FREE', 'ADVANCED_REPORTS', false, null::bigint),
    ('FREE', 'ADVANCED_ANALYTICS', false, null::bigint),
    ('FREE', 'CUSTOMER_INSIGHTS', false, null::bigint),
    ('FREE', 'ACCOUNTS_RECEIVABLE', false, null::bigint),
    ('FREE', 'PROMOTIONS', false, null::bigint),
    ('FREE', 'EXPENSE_TRACKING', false, null::bigint),
    ('FREE', 'STAFF_ACCOUNTS', false, null::bigint),
    ('FREE', 'MULTI_STATION', false, null::bigint),
    ('FREE', 'CONSOLIDATED_REPORTS', false, null::bigint),

    ('STARTER', 'STATION_MANAGEMENT', true, null::bigint),
    ('STARTER', 'DASHBOARD', true, null::bigint),
    ('STARTER', 'ORDER_MANAGEMENT', true, null::bigint),
    ('STARTER', 'CUSTOMER_MANAGEMENT', true, null::bigint),
    ('STARTER', 'PRODUCT_MANAGEMENT', true, null::bigint),
    ('STARTER', 'BASIC_INVENTORY', true, null::bigint),
    ('STARTER', 'BASIC_CONTAINER_TRACKING', true, null::bigint),
    ('STARTER', 'BASIC_REPORTS', true, null::bigint),
    ('STARTER', 'DATA_EXPORT', true, null::bigint),
    ('STARTER', 'EXPORT_CSV', true, null::bigint),
    ('STARTER', 'EXPORT_EXCEL', false, null::bigint),
    ('STARTER', 'EXPORT_PDF', false, null::bigint),
    ('STARTER', 'ADVANCED_REPORTS', false, null::bigint),
    ('STARTER', 'MULTI_BRANCH', false, null::bigint),
    ('STARTER', 'CONSOLIDATED_REPORTS', false, null::bigint),
    ('STARTER', 'MOBILE_APP_ORDERING', true, null::bigint),
    ('STARTER', 'RIDER_MANAGEMENT', true, 2::bigint),
    ('STARTER', 'DELIVERY_MANAGEMENT', true, null::bigint),
    ('STARTER', 'STAFF_ACCOUNTS', true, 2::bigint),
    ('STARTER', 'ACCOUNTS_RECEIVABLE', false, null::bigint),
    ('STARTER', 'PROMOTIONS', false, null::bigint),
    ('STARTER', 'EXPENSE_TRACKING', false, null::bigint),

    ('BUSINESS', 'STATION_MANAGEMENT', true, null::bigint),
    ('BUSINESS', 'DASHBOARD', true, null::bigint),
    ('BUSINESS', 'ORDER_MANAGEMENT', true, null::bigint),
    ('BUSINESS', 'CUSTOMER_MANAGEMENT', true, null::bigint),
    ('BUSINESS', 'PRODUCT_MANAGEMENT', true, null::bigint),
    ('BUSINESS', 'BASIC_INVENTORY', true, null::bigint),
    ('BUSINESS', 'BASIC_CONTAINER_TRACKING', true, null::bigint),
    ('BUSINESS', 'RIDER_MANAGEMENT', true, 10::bigint),
    ('BUSINESS', 'DELIVERY_MANAGEMENT', true, null::bigint),
    ('BUSINESS', 'MOBILE_APP_ORDERING', true, null::bigint),
    ('BUSINESS', 'BASIC_REPORTS', true, null::bigint),
    ('BUSINESS', 'ADVANCED_REPORTS', true, null::bigint),
    ('BUSINESS', 'ADVANCED_ANALYTICS', true, null::bigint),
    ('BUSINESS', 'DATA_EXPORT', true, null::bigint),
    ('BUSINESS', 'EXPORT_CSV', true, null::bigint),
    ('BUSINESS', 'EXPORT_EXCEL', true, null::bigint),
    ('BUSINESS', 'EXPORT_PDF', true, null::bigint),
    ('BUSINESS', 'MULTI_BRANCH', true, 3::bigint),
    ('BUSINESS', 'CONSOLIDATED_REPORTS', false, null::bigint),
    ('BUSINESS', 'STAFF_ACCOUNTS', true, 10::bigint),
    ('BUSINESS', 'ACCOUNTS_RECEIVABLE', true, null::bigint),
    ('BUSINESS', 'PROMOTIONS', true, null::bigint),
    ('BUSINESS', 'EXPENSE_TRACKING', true, null::bigint),

    ('PRO', 'STATION_MANAGEMENT', true, null::bigint),
    ('PRO', 'DASHBOARD', true, null::bigint),
    ('PRO', 'ORDER_MANAGEMENT', true, null::bigint),
    ('PRO', 'CUSTOMER_MANAGEMENT', true, null::bigint),
    ('PRO', 'PRODUCT_MANAGEMENT', true, null::bigint),
    ('PRO', 'BASIC_INVENTORY', true, null::bigint),
    ('PRO', 'BASIC_CONTAINER_TRACKING', true, null::bigint),
    ('PRO', 'RIDER_MANAGEMENT', true, null::bigint),
    ('PRO', 'DELIVERY_MANAGEMENT', true, null::bigint),
    ('PRO', 'MOBILE_APP_ORDERING', true, null::bigint),
    ('PRO', 'BASIC_REPORTS', true, null::bigint),
    ('PRO', 'ADVANCED_REPORTS', true, null::bigint),
    ('PRO', 'MULTI_BRANCH', true, 10::bigint),
    ('PRO', 'DATA_EXPORT', true, null::bigint),
    ('PRO', 'EXPORT_CSV', true, null::bigint),
    ('PRO', 'EXPORT_EXCEL', true, null::bigint),
    ('PRO', 'EXPORT_PDF', true, null::bigint),
    ('PRO', 'CONSOLIDATED_REPORTS', true, null::bigint),
    ('PRO', 'DELIVERY_ANALYTICS', true, null::bigint),
    ('PRO', 'CUSTOMER_INSIGHTS', true, null::bigint),
    ('PRO', 'ACCOUNTS_RECEIVABLE', true, null::bigint),
    ('PRO', 'PROMOTIONS', true, null::bigint),
    ('PRO', 'EXPENSE_TRACKING', true, null::bigint),
    ('PRO', 'STAFF_ACCOUNTS', true, 100::bigint)
) as v(code, feature, enabled, limit_value)
join public.subscription_plans p on p.code = v.code;

insert into public.plan_limits (plan_id, limit_type, max_value)
select p.id, v.limit_type, v.max_value
from (
  values
    ('FREE', 'STATION_COUNT', 1::bigint),
    ('FREE', 'PRODUCT_COUNT', 20::bigint),
    ('FREE', 'MONTHLY_ORDER_COUNT', 100::bigint),
    ('FREE', 'CUSTOMER_COUNT', 50::bigint),
    ('STARTER', 'STATION_COUNT', 1::bigint),
    ('STARTER', 'RIDER_COUNT', 2::bigint),
    ('STARTER', 'PRODUCT_COUNT', 30::bigint),
    ('STARTER', 'MONTHLY_ORDER_COUNT', 300::bigint),
    ('STARTER', 'CUSTOMER_COUNT', 100::bigint),
    ('STARTER', 'STAFF_COUNT', 2::bigint),
    ('BUSINESS', 'STATION_COUNT', 3::bigint),
    ('BUSINESS', 'RIDER_COUNT', 10::bigint),
    ('BUSINESS', 'PRODUCT_COUNT', 500::bigint),
    ('BUSINESS', 'MONTHLY_ORDER_COUNT', 10000::bigint),
    ('BUSINESS', 'CUSTOMER_COUNT', 2000::bigint),
    ('BUSINESS', 'STAFF_COUNT', 10::bigint),
    ('PRO', 'STATION_COUNT', 10::bigint),
    ('PRO', 'RIDER_COUNT', 100::bigint),
    ('PRO', 'PRODUCT_COUNT', 10000::bigint),
    ('PRO', 'MONTHLY_ORDER_COUNT', 100000::bigint),
    ('PRO', 'CUSTOMER_COUNT', 50000::bigint),
    ('PRO', 'STAFF_COUNT', 100::bigint)
) as v(code, limit_type, max_value)
join public.subscription_plans p on p.code = v.code;
