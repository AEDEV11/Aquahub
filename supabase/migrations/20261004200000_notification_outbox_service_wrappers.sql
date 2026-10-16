-- Edge Functions call PostgREST with the service role. The implementation
-- functions remain in app_private; these wrappers expose only the three
-- server-only operations needed by the notification workers.
create or replace function public.claim_notification_outbox(p_limit integer default 20)
returns table(
  id uuid,
  recipient_profile_id uuid,
  event_type text,
  entity_type text,
  entity_id uuid,
  payload jsonb,
  attempt_count integer
)
language sql
security definer
set search_path = pg_catalog, public, app_private
as $$
  select * from app_private.claim_notification_outbox(p_limit);
$$;

create or replace function public.requeue_stale_notification_outbox(
  p_stale_after interval default interval '10 minutes'
)
returns integer
language sql
security definer
set search_path = pg_catalog, public, app_private
as $$
  select app_private.requeue_stale_notification_outbox(p_stale_after);
$$;

create or replace function public.claim_notification_outbox_item(p_outbox_id uuid)
returns table(
  id uuid,
  recipient_profile_id uuid,
  event_type text,
  entity_type text,
  entity_id uuid,
  payload jsonb,
  attempt_count integer
)
language sql
security definer
set search_path = pg_catalog, public, app_private
as $$
  select * from app_private.claim_notification_outbox_item(p_outbox_id);
$$;

revoke all on function public.claim_notification_outbox(integer) from public, anon, authenticated;
revoke all on function public.requeue_stale_notification_outbox(interval) from public, anon, authenticated;
revoke all on function public.claim_notification_outbox_item(uuid) from public, anon, authenticated;
grant execute on function public.claim_notification_outbox(integer) to service_role;
grant execute on function public.requeue_stale_notification_outbox(interval) to service_role;
grant execute on function public.claim_notification_outbox_item(uuid) to service_role;
