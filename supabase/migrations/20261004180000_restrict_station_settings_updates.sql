-- Station business settings must go through the whitelisted RPC. Keep
-- read/insert/delete grants unchanged for existing workspace flows, but do
-- not allow the Android client to submit arbitrary station-row updates.
revoke update on table public.stations from authenticated;
grant execute on function public.update_station_settings(uuid, jsonb) to authenticated;
