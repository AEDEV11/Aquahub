-- Keep Supabase catalog metadata aligned with the live Google Play price.
-- Google Play remains the runtime billing authority; this value is used for
-- catalog display and fallback calculations only.
update public.subscription_plans
set monthly_price_centavos = 129900,
    annual_price_centavos = 1247040,
    updated_at = now()
where code = 'PRO';
