import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

type FcmFailure = "RETRYABLE" | "PERMANENT_TOKEN_FAILURE" | "PERMANENT_PAYLOAD_FAILURE";

function classifyFcmFailure(httpStatus: number, responseText: string): FcmFailure {
  // Only FCM's explicit UNREGISTERED token error authorizes deleting a token.
  // INVALID_ARGUMENT commonly means malformed notification data and must never
  // delete every device token for an otherwise valid profile.
  if (/\"errorCode\"\s*:\s*\"UNREGISTERED\"|\bUNREGISTERED\b/.test(responseText)) return "PERMANENT_TOKEN_FAILURE";
  if (httpStatus === 400 && /INVALID_ARGUMENT|BadRequest|fieldViolations/.test(responseText)) return "PERMANENT_PAYLOAD_FAILURE";
  return "RETRYABLE";
}

function base64Url(value: Uint8Array | string): string {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  let binary = "";
  bytes.forEach((byte) => binary += String.fromCharCode(byte));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function pemToBytes(pem: string): Uint8Array {
  const raw = atob(pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, ""));
  return Uint8Array.from(raw, (char) => char.charCodeAt(0));
}

function ownedArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}

let cachedAccessToken: { value: string; expiresAt: number } | null = null;

async function googleAccessToken(projectId: string, clientEmail: string, privateKey: string): Promise<string> {
  if (cachedAccessToken && cachedAccessToken.expiresAt > Date.now() + 60_000) return cachedAccessToken.value;
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64Url(JSON.stringify({
    iss: clientEmail,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    ownedArrayBuffer(pemToBytes(privateKey)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(`${header}.${claim}`),
  );
  const assertion = `${header}.${claim}.${base64Url(new Uint8Array(signature))}`;
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!response.ok) throw new Error("FCM_AUTH_FAILED");
  const body = await response.json() as { access_token?: string; expires_in?: number };
  if (!body.access_token) throw new Error("FCM_AUTH_FAILED");
  cachedAccessToken = { value: body.access_token, expiresAt: Date.now() + (body.expires_in ?? 3600) * 1000 };
  return body.access_token;
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json(405, { error: "METHOD_NOT_ALLOWED" });
  const dispatchSecret = Deno.env.get("NOTIFICATION_DISPATCH_SECRET")?.trim();
  if (!dispatchSecret || request.headers.get("x-notification-dispatch-secret") !== dispatchSecret) {
    return json(401, { error: "UNAUTHORIZED" });
  }
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.trim();
  const projectId = Deno.env.get("FCM_PROJECT_ID")?.trim();
  const clientEmail = Deno.env.get("FCM_CLIENT_EMAIL")?.trim();
  const privateKey = Deno.env.get("FCM_PRIVATE_KEY")?.replace(/\\n/g, "\n");
  if (!serviceKey || !supabaseUrl || !projectId || !clientEmail || !privateKey) return json(503, { error: "NOTIFICATION_SERVICE_UNAVAILABLE" });

  let outboxId: string;
  let dispatcherClaimed = false;
  try {
    const body = await request.json() as { outbox_id?: unknown; dispatcher_claimed?: unknown };
    if (typeof body.outbox_id !== "string" || !body.outbox_id) return json(400, { error: "INVALID_OUTBOX_ID" });
    outboxId = body.outbox_id;
    dispatcherClaimed = body.dispatcher_claimed === true;
  } catch { return json(400, { error: "INVALID_REQUEST" }); }

  const admin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: existing, error: existingError } = await admin.from("notification_outbox")
    .select("id, status, terminal_failure, processed_at")
    .eq("id", outboxId).maybeSingle();
  if (existingError || !existing) return json(404, { error: "OUTBOX_NOT_FOUND" });
  if (existing.status === "SENT") return json(200, { delivered: true, duplicate: true });
  if (existing.terminal_failure || existing.status === "FAILED") return json(409, { error: "OUTBOX_TERMINAL_FAILURE" });

  let entry;
  if (dispatcherClaimed) {
    const { data: processing, error: processingError } = await admin.from("notification_outbox")
      .select("id, recipient_profile_id, event_type, payload, status, attempt_count")
      .eq("id", outboxId).eq("status", "PROCESSING").maybeSingle();
    if (processingError || !processing) return json(202, { delivered: false, reason: "CLAIM_NOT_FOUND" });
    entry = processing;
  } else {
    const { data: claimed, error: claimError } = await admin.rpc("claim_notification_outbox_item", { p_outbox_id: outboxId });
    if (claimError) return json(503, { error: "OUTBOX_CLAIM_FAILED" });
    entry = Array.isArray(claimed) ? claimed[0] : claimed;
    if (!entry) return json(202, { delivered: false, reason: "ALREADY_PROCESSING_OR_NOT_DUE" });
  }

  const { data: devices } = await admin.from("device_tokens").select("id, token")
    .eq("profile_id", entry.recipient_profile_id);
  if (!devices?.length) {
    await admin.from("notification_outbox").update({ status: "SENT", processed_at: new Date().toISOString(), next_attempt_at: null, last_error: "NO_DEVICE" }).eq("id", outboxId);
    return json(200, { delivered: false, reason: "NO_DEVICE" });
  }

  try {
    const accessToken = await googleAccessToken(projectId, clientEmail, privateKey);
    let sent = 0;
    let transientFailure = false;
    let permanentPayloadFailure = false;
    for (const device of devices) {
      const payload = entry.payload as Record<string, unknown>;
      const isCancellation = entry.event_type === "ORDER_CANCELLED";
      const orderNumber = typeof payload.order_number === "string" ? payload.order_number : "your order";
      const reason = typeof payload.cancellation_reason === "string" ? payload.cancellation_reason : "Cancelled by the station";
      const title = isCancellation ? "Order cancelled" : entry.event_type;
      const notificationBody = isCancellation
        ? `Order ${orderNumber} was cancelled: ${reason}`
        : String(payload.status ?? "AquaHub update");
      const response = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
        method: "POST",
        headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
        body: JSON.stringify({ message: { token: device.token, notification: { title, body: notificationBody }, data: Object.fromEntries(Object.entries(payload).map(([key, value]) => [key, String(value)])) } }),
      });
      if (response.ok) { sent++; continue; }
      const text = await response.text();
      const failure = classifyFcmFailure(response.status, text);
      if (failure === "PERMANENT_TOKEN_FAILURE") {
        await admin.from("device_tokens").delete().eq("id", device.id);
      } else if (failure === "PERMANENT_PAYLOAD_FAILURE") {
        permanentPayloadFailure = true;
      } else {
        transientFailure = true;
      }
    }
    if (sent > 0) {
      await admin.from("notification_outbox").update({ status: "SENT", processed_at: new Date().toISOString(), next_attempt_at: null, last_error: transientFailure ? "PARTIAL_DELIVERY" : null }).eq("id", outboxId);
    } else if (permanentPayloadFailure && !transientFailure) {
      await admin.from("notification_outbox").update({
        status: "FAILED", terminal_failure: true, next_attempt_at: null,
        last_error: "FCM_PERMANENT_PAYLOAD_FAILURE", processed_at: new Date().toISOString(),
      }).eq("id", outboxId);
    } else if (!transientFailure) {
      await admin.from("notification_outbox").update({ status: "SENT", processed_at: new Date().toISOString(), next_attempt_at: null, last_error: "NO_DELIVERY" }).eq("id", outboxId);
    } else {
      const attempt = Number(entry.attempt_count ?? 1);
      const terminal = attempt >= 5;
      const backoffSeconds = Math.min(900, 15 * (2 ** Math.max(attempt - 1, 0)));
      await admin.from("notification_outbox").update({
        status: terminal ? "FAILED" : "PENDING",
        terminal_failure: terminal,
        next_attempt_at: terminal ? null : new Date(Date.now() + backoffSeconds * 1000).toISOString(),
        last_error: "FCM_TRANSIENT_FAILURE",
        processed_at: terminal ? new Date().toISOString() : null,
      }).eq("id", outboxId);
    }
    return json(200, { delivered: sent > 0 });
  } catch {
    const attempt = Number(entry.attempt_count ?? 1);
    const terminal = attempt >= 5;
    const backoffSeconds = Math.min(900, 15 * (2 ** Math.max(attempt - 1, 0)));
    await admin.from("notification_outbox").update({
      status: terminal ? "FAILED" : "PENDING",
      terminal_failure: terminal,
      next_attempt_at: terminal ? null : new Date(Date.now() + backoffSeconds * 1000).toISOString(),
      last_error: "DELIVERY_FAILED",
      processed_at: terminal ? new Date().toISOString() : null,
    }).eq("id", outboxId);
    return json(502, { error: "NOTIFICATION_DELIVERY_FAILED" });
  }
});
