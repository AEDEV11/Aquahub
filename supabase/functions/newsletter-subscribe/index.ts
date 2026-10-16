import { createClient } from "https://esm.sh/@supabase/supabase-js@2.117.2";
import {
  marketingFingerprint,
  validateNewsletterPayload,
} from "../_shared/marketing.ts";
import { readJsonBody } from "../_shared/request-body.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const allowedOrigins = new Set(
  (Deno.env.get("NEWSLETTER_ALLOWED_ORIGINS") ?? "")
    .split(",")
    .map((origin) => origin.trim())
    .filter(Boolean),
);
const corsHeaders = {
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "content-type, apikey, x-client-info",
  "access-control-max-age": "86400",
  "vary": "Origin",
};

function json(
  status: number,
  body: Record<string, unknown>,
  origin?: string,
): Response {
  const headers = new Headers({
    ...corsHeaders,
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff",
  });
  if (origin && allowedOrigins.has(origin)) {
    headers.set("access-control-allow-origin", origin);
  }
  return new Response(JSON.stringify(body), { status, headers });
}

function clientIp(request: Request): string {
  // Supabase's gateway supplies the first forwarded address. It is only used
  // as an HMAC rate-limit key and is never persisted or logged.
  return request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
    request.headers.get("cf-connecting-ip")?.trim() ||
    "unknown";
}

function serviceClient() {
  return createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

Deno.serve(async (request) => {
  const origin = request.headers.get("origin") ?? "";
  if (request.method === "OPTIONS") {
    if (!allowedOrigins.has(origin)) {
      return json(403, { error: "origin_not_allowed" });
    }
    return new Response(null, {
      status: 204,
      headers: { ...corsHeaders, "access-control-allow-origin": origin },
    });
  }
  if (request.method !== "POST") {
    return json(405, { error: "method_not_allowed" });
  }
  if (!origin || !allowedOrigins.has(origin)) {
    return json(403, { error: "origin_not_allowed" });
  }
  if (
    !supabaseUrl || !serviceRoleKey ||
    !Deno.env.get("NEWSLETTER_RATE_LIMIT_HMAC_KEY")
  ) {
    return json(503, { error: "subscription_unavailable" }, origin);
  }
  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(contentLength) && contentLength > 12_000) {
    return json(413, { error: "request_too_large" }, origin);
  }
  const parsed = await readJsonBody(request, 12_000);
  if (!parsed.ok && parsed.reason === "too_large") {
    return json(413, { error: "request_too_large" }, origin);
  }
  if (!parsed.ok) {
    return json(400, { error: "invalid_request" }, origin);
  }
  const validation = validateNewsletterPayload(parsed.value);
  if (!validation.valid) return json(422, { error: validation.code }, origin);
  if (validation.value.honeypot) {
    return json(202, {
      accepted: true,
      confirmationRequired:
        Deno.env.get("HOSTINGER_REACH_DOUBLE_OPT_IN") === "true",
    }, origin);
  }

  const secret = Deno.env.get("NEWSLETTER_RATE_LIMIT_HMAC_KEY")!;
  const [ipFingerprint, emailFingerprint] = await Promise.all([
    marketingFingerprint(secret, "ip", clientIp(request)),
    marketingFingerprint(secret, "email", validation.value.email),
  ]);
  const supabase = serviceClient();
  const rate = await supabase.rpc("consume_marketing_rate_limit", {
    p_ip_fingerprint: ipFingerprint,
    p_email_fingerprint: emailFingerprint,
  });
  if (rate.error) {
    console.warn(
      JSON.stringify({ event: "newsletter_rate_limit_unavailable" }),
    );
    return json(503, { error: "subscription_unavailable" }, origin);
  }
  const rateRow = Array.isArray(rate.data) ? rate.data[0] : rate.data;
  if (!rateRow?.allowed) {
    const retryAfter = Math.min(
      3600,
      Math.max(1, Number(rateRow?.retry_after_seconds) || 60),
    );
    const response = json(429, { error: "rate_limited" }, origin);
    response.headers.set("retry-after", String(retryAfter));
    return response;
  }

  const saved = await supabase.rpc("record_marketing_consent", {
    p_email: validation.value.email,
    p_first_name: validation.value.firstName,
    p_source: "website",
    p_policy_version: Deno.env.get("NEWSLETTER_CONSENT_POLICY_VERSION") ||
      "2026-10-v1",
    p_owner_user_id: null,
  });
  if (saved.error) {
    console.warn(JSON.stringify({ event: "newsletter_consent_store_failed" }));
    return json(503, { error: "subscription_unavailable" }, origin);
  }
  return json(202, {
    accepted: true,
    confirmationRequired: Deno.env.get("HOSTINGER_REACH_ENABLED") === "true" &&
      Deno.env.get("HOSTINGER_REACH_DOUBLE_OPT_IN") === "true",
    processing: "queued",
  }, origin);
});
