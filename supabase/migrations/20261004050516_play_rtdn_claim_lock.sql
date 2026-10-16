alter table public.google_play_billing_events
  alter column processed_at drop not null,
  add column if not exists processing_at timestamptz;

create or replace function app_private.claim_play_rtdn_event(
  p_message_id text,
  p_payload jsonb
)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public, app_private
as $$
declare
  claimed boolean := false;
begin
  if nullif(btrim(p_message_id), '') is null then
    return false;
  end if;

  insert into public.google_play_billing_events(message_id, payload, processed_at, processing_at)
  values (p_message_id, coalesce(p_payload, '{}'::jsonb), null, clock_timestamp())
  on conflict (message_id) do nothing;

  update public.google_play_billing_events
  set processing_at = clock_timestamp(), payload = coalesce(p_payload, payload)
  where message_id = p_message_id
    and processed_at is null
    and (processing_at is null or processing_at < clock_timestamp() - interval '10 minutes')
  returning true into claimed;

  return coalesce(claimed, false);
end;
$$;

revoke all on function app_private.claim_play_rtdn_event(text, jsonb) from public, anon, authenticated;
grant execute on function app_private.claim_play_rtdn_event(text, jsonb) to service_role;
