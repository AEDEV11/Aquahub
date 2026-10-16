create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(2);

do $$
declare
  v_message_id text := 'pgtap-play-rtdn-' || txid_current()::text;
  v_first jsonb;
  v_busy jsonb;
  v_reclaimed jsonb;
  v_processed jsonb;
  v_first_token uuid;
  v_new_token uuid;
  v_raw_rejected boolean := false;
  v_event_count integer;
begin
  perform set_config('request.jwt.claims', '{"role":"service_role"}', true);

  v_first := public.claim_play_rtdn_event(
    v_message_id,
    '{"packageName":"com.aesprt.aquahub","subscriptionNotification":{"purchaseTokenHash":"redacted"}}'::jsonb
  );
  if v_first ->> 'status' <> 'claimed' then
    raise exception 'first delivery was not claimed: %', v_first;
  end if;
  v_first_token := (v_first ->> 'lease_token')::uuid;
  if v_first_token is null then raise exception 'claim returned no lease token'; end if;

  v_busy := public.claim_play_rtdn_event(v_message_id, '{}'::jsonb);
  if v_busy ->> 'status' <> 'busy' then
    raise exception 'concurrent duplicate was not reported busy: %', v_busy;
  end if;

  update public.google_play_billing_events
  set processing_at = clock_timestamp() - interval '11 minutes'
  where message_id = v_message_id;
  v_reclaimed := public.claim_play_rtdn_event(v_message_id, '{}'::jsonb);
  if v_reclaimed ->> 'status' <> 'claimed' then
    raise exception 'expired lease was not reclaimed: %', v_reclaimed;
  end if;
  v_new_token := (v_reclaimed ->> 'lease_token')::uuid;
  if v_new_token is null or v_new_token = v_first_token then
    raise exception 'reclaim did not rotate the lease token';
  end if;

  -- Model an old worker attempting release and completion after reclaim. Both
  -- updates must affect zero rows and leave the newer lease intact.
  update public.google_play_billing_events
  set processing_at = null, processing_token = null
  where message_id = v_message_id and processing_token = v_first_token and processed_at is null;
  if found then raise exception 'stale worker released the new lease'; end if;

  update public.google_play_billing_events
  set processed_at = clock_timestamp(), processing_at = null, processing_token = null
  where message_id = v_message_id and processing_token = v_first_token and processed_at is null;
  if found then raise exception 'stale worker finalized the new lease'; end if;

  update public.google_play_billing_events
  set processed_at = clock_timestamp(), processing_at = null, processing_token = null
  where message_id = v_message_id and processing_token = v_new_token and processed_at is null;
  if not found then raise exception 'current lease could not finalize'; end if;

  v_processed := public.claim_play_rtdn_event(v_message_id, '{}'::jsonb);
  if v_processed ->> 'status' <> 'already_processed' then
    raise exception 'processed duplicate was not recognized: %', v_processed;
  end if;

  begin
    perform public.claim_play_rtdn_event(
      v_message_id || '-raw-token',
      '{"subscriptionNotification":{"purchaseToken":"must-not-persist"}}'::jsonb
    );
  exception when sqlstate '22023' then
    v_raw_rejected := true;
  end;
  if not v_raw_rejected then raise exception 'raw Play purchase token was accepted'; end if;
  select count(*) into v_event_count from public.google_play_billing_events
  where message_id = v_message_id || '-raw-token';
  if v_event_count <> 0 then raise exception 'raw-token rejection left an event row'; end if;
end;
$$;

select extensions.ok(true, 'atomic first claim, duplicate, lease recovery, stale worker fencing, completion, and token redaction');

select extensions.ok(
  has_function_privilege('service_role', 'public.claim_play_rtdn_event(text,jsonb)', 'execute')
  and not has_function_privilege('anon', 'public.claim_play_rtdn_event(text,jsonb)', 'execute')
  and not has_function_privilege('authenticated', 'public.claim_play_rtdn_event(text,jsonb)', 'execute')
  and not has_function_privilege('service_role', 'app_private.claim_play_rtdn_event(text,jsonb)', 'execute')
  and not exists (
    select 1 from information_schema.routine_privileges
    where routine_schema = 'public' and routine_name = 'claim_play_rtdn_event'
      and grantee = 'PUBLIC' and privilege_type = 'EXECUTE'
  ),
  'only service_role can execute the public wrapper; app_private is not exposed through the API'
);

select * from extensions.finish();
rollback;
