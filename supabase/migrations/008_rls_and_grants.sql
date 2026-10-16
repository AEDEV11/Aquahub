alter table public.profiles enable row level security;
alter table public.organizations enable row level security;
alter table public.stations enable row level security;
alter table public.organization_members enable row level security;
alter table public.member_stations enable row level security;
alter table public.customers enable row level security;
alter table public.customer_addresses enable row level security;
alter table public.products enable row level security;
alter table public.riders enable row level security;
alter table public.containers enable row level security;
alter table public.container_movements enable row level security;
alter table public.orders enable row level security;
alter table public.order_items enable row level security;
alter table public.payments enable row level security;
alter table public.expenses enable row level security;
alter table public.subscription_plans enable row level security;
alter table public.plan_features enable row level security;
alter table public.plan_limits enable row level security;
alter table public.subscriptions enable row level security;
alter table public.subscription_history enable row level security;
alter table public.device_tokens enable row level security;
alter table public.public_stations enable row level security;
alter table public.public_products enable row level security;
alter table public.customer_order_requests enable row level security;
alter table public.audit_events enable row level security;
alter table public.owner_trial_claims enable row level security;
alter table public.google_play_subscription_tokens enable row level security;
alter table public.google_play_billing_events enable row level security;
alter table public.inventory_compensations enable row level security;

revoke all on all tables in schema public from anon, authenticated;
grant select on public.profiles to authenticated;
grant update (display_name, phone, photo_url, address, latitude, longitude, setup_complete) on public.profiles to authenticated;
grant select, insert, update, delete on public.device_tokens to authenticated;
grant select on public.public_stations, public.public_products to anon, authenticated;
grant select on public.subscription_plans, public.plan_features, public.plan_limits to authenticated;
grant select on public.organizations, public.organization_members, public.member_stations, public.stations,
  public.customers, public.customer_addresses, public.products, public.riders, public.containers,
  public.container_movements, public.orders, public.order_items, public.payments, public.expenses,
  public.subscriptions, public.subscription_history to authenticated;
grant insert, delete on public.organizations to authenticated;
grant insert, update, delete on public.stations, public.customers, public.customer_addresses, public.products, public.riders,
  public.containers, public.container_movements, public.orders, public.order_items, public.payments,
  public.expenses to authenticated;
grant update (name, is_active) on public.organizations to authenticated;
grant select on public.organization_members, public.member_stations to authenticated;

create policy profiles_select_own on public.profiles for select to authenticated using (id = (select auth.uid()));
create policy profiles_insert_own on public.profiles for insert to authenticated with check (id = (select auth.uid()));
create policy profiles_update_own on public.profiles for update to authenticated using (id = (select auth.uid())) with check (id = (select auth.uid()));
create policy profiles_delete_own on public.profiles for delete to authenticated using (id = (select auth.uid()));

create policy organizations_select_member on public.organizations for select to authenticated using (app_private.is_org_member(id));
create policy organizations_update_manager on public.organizations for update to authenticated using (app_private.can_manage_org(id)) with check (app_private.can_manage_org(id));
create policy organizations_insert_owner on public.organizations for insert to authenticated with check (owner_profile_id = (select auth.uid()));

create policy members_select_member on public.organization_members for select to authenticated using (app_private.is_org_member(organization_id));
create policy member_stations_select_member on public.member_stations for select to authenticated using (exists (select 1 from public.organization_members m where m.id = member_id and app_private.is_org_member(m.organization_id)));

create policy stations_select_access on public.stations for select to authenticated using (app_private.can_access_station(id));
create policy stations_manage on public.stations for all to authenticated using (app_private.can_manage_org(organization_id)) with check (app_private.can_manage_org(organization_id));

create policy customers_station_access on public.customers for all to authenticated using (app_private.can_operate_station(station_id)) with check (app_private.can_operate_station(station_id));
create policy addresses_station_access on public.customer_addresses for all to authenticated using (app_private.can_operate_station(station_id)) with check (app_private.can_operate_station(station_id));
create policy products_station_access on public.products for all to authenticated using (app_private.can_operate_station(station_id)) with check (app_private.can_operate_station(station_id));
create policy riders_station_access on public.riders for all to authenticated using (app_private.can_operate_station(station_id)) with check (app_private.can_operate_station(station_id));
create policy containers_station_access on public.containers for all to authenticated using (app_private.can_operate_station(station_id)) with check (app_private.can_operate_station(station_id));
create policy movements_station_access on public.container_movements for all to authenticated using (app_private.can_operate_station(station_id)) with check (app_private.can_operate_station(station_id));
create policy payments_station_access on public.payments for all to authenticated using (app_private.can_operate_station(station_id)) with check (app_private.can_operate_station(station_id));
create policy expenses_manager_access on public.expenses for all to authenticated using (exists (select 1 from public.stations s where s.id = station_id and app_private.can_manage_org(s.organization_id))) with check (exists (select 1 from public.stations s where s.id = station_id and app_private.can_manage_org(s.organization_id)));

create policy orders_select_access on public.orders for select to authenticated using (app_private.can_operate_station(station_id) or customer_uid = (select auth.uid()));
create policy orders_update_operator on public.orders for update to authenticated using (app_private.can_operate_station(station_id)) with check (app_private.can_operate_station(station_id));
create policy order_items_select_access on public.order_items for select to authenticated using (exists (select 1 from public.orders o where o.id = order_id and (app_private.can_operate_station(o.station_id) or o.customer_uid = (select auth.uid()))));
create policy order_items_update_operator on public.order_items for update to authenticated using (exists (select 1 from public.orders o where o.id = order_id and app_private.can_operate_station(o.station_id))) with check (exists (select 1 from public.orders o where o.id = order_id and app_private.can_operate_station(o.station_id)));

create policy device_tokens_own on public.device_tokens for all to authenticated using (profile_id = (select auth.uid())) with check (profile_id = (select auth.uid()));
create policy public_stations_read on public.public_stations for select to anon, authenticated using (true);
create policy public_products_read on public.public_products for select to anon, authenticated using (true);
create policy plans_read on public.subscription_plans for select to authenticated using (is_active);
create policy plan_features_read on public.plan_features for select to authenticated using (exists (select 1 from public.subscription_plans p where p.id = plan_id and p.is_active));
create policy plan_limits_read on public.plan_limits for select to authenticated using (exists (select 1 from public.subscription_plans p where p.id = plan_id and p.is_active));
create policy subscriptions_member_read on public.subscriptions for select to authenticated using (app_private.is_org_member(organization_id));

-- Server-only tables intentionally receive no anon/authenticated grants or policies.
