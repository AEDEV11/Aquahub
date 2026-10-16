import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

Deno.serve(async (request) => {
  const startedAt = Date.now();
  const runId = crypto.randomUUID();
  if (request.method !== "POST") return json(405, { error: "METHOD_NOT_ALLOWED" });
  const secret = Deno.env.get("NOTIFICATION_DISPATCH_SECRET")?.trim();
  if (!secret || request.headers.get("x-notification-dispatch-secret") !== secret) {
    return json(401, { error: "UNAUTHORIZED" });
  }

  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  if (!serviceKey || !supabaseUrl) return json(503, { error: "NOTIFICATION_SERVICE_UNAVAILABLE" });

  let limit = 20;
  try {
    const body = await request.json() as { limit?: unknown };
    if (body.limit !== undefined) limit = Number(body.limit);
  } catch {
    // Empty POST bodies are valid and use the default batch size.
  }
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) return json(400, { error: "INVALID_BATCH_SIZE" });

  const admin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { error: requeueError } = await admin.rpc("requeue_stale_notification_outbox", { p_stale_after: "00:10:00" });
  if (requeueError) return json(503, { error: "OUTBOX_REQUEUE_FAILED" });
  const { data: entries, error } = await admin.rpc("claim_notification_outbox", { p_limit: limit });
  if (error) return json(503, { error: "OUTBOX_CLAIM_FAILED" });

  let sent = 0;
  let retried = 0;
  let terminal = 0;
  let skipped = 0;
  for (const entry of entries ?? []) {
    const response = await fetch(`${supabaseUrl}/functions/v1/send-fcm-notification`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-notification-dispatch-secret": secret,
        "x-notification-dispatch-run-id": runId,
      },
      body: JSON.stringify({ outbox_id: entry.id, dispatcher_claimed: true }),
    });
    if (response.ok) sent++;
    else if (response.status === 409) terminal++;
    else if (response.status === 202) skipped++;
    else retried++;
  }
  return json(200, {
    run_id: runId,
    duration_ms: Date.now() - startedAt,
    claimed: (entries ?? []).length,
    sent,
    retried,
    terminal,
    skipped,
  });
});
