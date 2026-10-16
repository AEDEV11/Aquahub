begin;

-- RTDN claims are durable audit rows. A lease token distinguishes a current
-- worker from an expired worker that finishes after a newer claim.
alter table public.google_play_billing_events
  add column if not exists processing_token uuid;

-- Replace the broken first-delivery claim implementation. The insert creates
-- an unclaimed row; the row lock serializes duplicate deliveries before the
-- function decides whether to claim, report busy, or report completion.
drop function app_private.claim_play_rtdn_event(text, jsonb);

create function app_private.claim_play_rtdn_event(
  p_message_id text,
  p_payload jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
  v_processed_at timestamptz;
  v_processing_at timestamptz;
  v_lease_token uuid;
begin
  if nullif(btrim(p_message_id), '') is null then
    raise exception using errcode = '22023', message = 'PLAY_INVALID_RTDN_MESSAGE_ID';
  end if;
  if coalesce(p_payload, '{}'::jsonb) #> '{subscriptionNotification,purchaseToken}' is not null then
    raise exception using errcode = '22023', message = 'PLAY_RAW_TOKEN_NOT_ALLOWED';
  end if;

  insert into public.google_play_billing_events(
    message_id, payload, processed_at, processing_at, processing_token
  ) values (
    p_message_id, coalesce(p_payload, '{}'::jsonb), null, null, null
  )
  on conflict (message_id) do nothing;

  select e.processed_at, e.processing_at
    into v_processed_at, v_processing_at
  from public.google_play_billing_events e
  where e.message_id = p_message_id
  for update;

  if v_processed_at is not null then
    return pg_catalog.jsonb_build_object('status', 'already_processed');
  end if;

  if v_processing_at is not null
     and v_processing_at >= pg_catalog.clock_timestamp() - interval '10 minutes' then
    return pg_catalog.jsonb_build_object('status', 'busy');
  end if;

  v_lease_token := pg_catalog.gen_random_uuid();
  update public.google_play_billing_events e
  set payload = coalesce(p_payload, e.payload),
      processing_at = pg_catalog.clock_timestamp(),
      processing_token = v_lease_token
  where e.message_id = p_message_id
    and e.processed_at is null;

  return pg_catalog.jsonb_build_object(
    'status', 'claimed',
    'lease_token', v_lease_token
  );
end;
$$;

alter function app_private.claim_play_rtdn_event(text, jsonb) owner to postgres;

-- The internal implementation is callable only by its postgres-owned public
-- wrapper. Keep app_private hidden; do not change schema exposure or USAGE.
revoke all on function app_private.claim_play_rtdn_event(text, jsonb)
  from public, anon, authenticated, service_role;
grant execute on function app_private.claim_play_rtdn_event(text, jsonb) to postgres;

create function public.claim_play_rtdn_event(
  p_message_id text,
  p_payload jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog
as $$
begin
  if coalesce(auth.jwt() ->> 'role', '') <> 'service_role' then
    raise exception using errcode = '42501', message = 'PLAY_SERVICE_ROLE_REQUIRED';
  end if;
  return app_private.claim_play_rtdn_event(p_message_id, p_payload);
end;
$$;

alter function public.claim_play_rtdn_event(text, jsonb) owner to postgres;
revoke all on function public.claim_play_rtdn_event(text, jsonb)
  from public, anon, authenticated, service_role;
grant execute on function public.claim_play_rtdn_event(text, jsonb) to service_role;

notify pgrst, 'reload schema';
commit;
