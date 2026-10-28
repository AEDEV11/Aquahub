import { createClient } from "https://esm.sh/@supabase/supabase-js@2.117.2";
import { createAllowedOrigins, normalizeClientNetwork } from "../guest-order/security-utils.js";

const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
const isLocalRuntime = /^(https?:\/\/)?(127\.0\.0\.1|localhost|kong|supabase_kong)(:\d+)?$/i.test(supabaseUrl || "");
const allowedOrigins = createAllowedOrigins(Deno.env.get("GUEST_ORDER_ALLOWED_ORIGINS"), isLocalRuntime);
const MAX_PROOF_BYTES = 5 * 1024 * 1024;
const corsHeaders = {
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
  "access-control-max-age": "86400",
  "vary": "Origin",
};

function json(status: number, body: Record<string, unknown>, origin?: string): Response {
  const headers = new Headers({ ...corsHeaders, "content-type": "application/json; charset=utf-8" });
  if (origin && allowedOrigins.has(origin)) headers.set("access-control-allow-origin", origin);
  return new Response(JSON.stringify(body), { status, headers });
}

function bytesToHex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function fingerprint(secret: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`payment-proof-ip:${value}`));
  return bytesToHex(new Uint8Array(signature));
}

function imageType(bytes: Uint8Array, contentType: string): boolean {
  if (contentType === "image/jpeg") return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (contentType === "image/png") return bytes.length >= 8
    && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47
    && bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a;
  if (contentType === "image/webp") return bytes.length >= 12
    && new TextDecoder().decode(bytes.slice(0, 4)) === "RIFF"
    && new TextDecoder().decode(bytes.slice(8, 12)) === "WEBP";
  return false;
}

function extension(contentType: string): string {
  return contentType === "image/jpeg" ? "jpg" : contentType === "image/png" ? "png" : "webp";
}

async function readBoundedBody(request: Request, maximumBytes: number): Promise<Uint8Array | null> {
  const reader = request.body?.getReader();
  if (!reader) return null;
  const chunks: Uint8Array[] = [];
  let length = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > maximumBytes) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const body = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body;
}

Deno.serve(async (request) => {
  const origin = request.headers.get("origin") ?? undefined;
  if (request.method === "OPTIONS") {
    if (origin && !allowedOrigins.has(origin)) return json(403, { error: "Origin is not allowed." });
    return new Response(null, {
      status: 204,
      headers: { ...corsHeaders, ...(origin ? { "access-control-allow-origin": origin } : {}) },
    });
  }
  if (request.method !== "POST") return json(405, { error: "Use POST to submit payment proof." }, origin);
  if (origin && !allowedOrigins.has(origin)) return json(403, { error: "Origin is not allowed." }, origin);
  if (!request.headers.get("content-type")?.toLowerCase().startsWith("multipart/form-data")) {
    return json(415, { error: "Attach a payment screenshot to continue." }, origin);
  }
  const contentLength = Number(request.headers.get("content-length") || 0);
  if (contentLength > MAX_PROOF_BYTES + 256_000) return json(413, { error: "The payment screenshot must be 5 MB or smaller." }, origin);

  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!supabaseUrl || !serviceKey) return json(503, { error: "Payment proof submission is temporarily unavailable." }, origin);
  const ip = normalizeClientNetwork(request.headers.get("cf-connecting-ip"));
  if (!ip && !isLocalRuntime) return json(503, { error: "Payment proof submission is temporarily unavailable." }, origin);
  const admin = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
  });

  const ipFingerprint = await fingerprint(serviceKey, ip || "local-development");
  const { data: rateData, error: rateError } = await admin.rpc("consume_guest_tracking_rate_limit", {
    p_ip_fingerprint: ipFingerprint,
  });
  const rate = Array.isArray(rateData) ? rateData[0] : rateData;
  if (rateError) return json(503, { error: "Payment proof submission is temporarily unavailable." }, origin);
  if (!rate?.allowed) return json(429, { error: "Too many payment proof attempts. Please wait a few minutes and try again." }, origin);

  const boundedBody = await readBoundedBody(request, MAX_PROOF_BYTES + 256_000);
  if (!boundedBody) return json(413, { error: "The payment screenshot must be 5 MB or smaller." }, origin);
  let form: FormData;
  try {
    form = await new Request(request.url, {
      method: "POST",
      headers: request.headers,
      body: boundedBody,
    }).formData();
  } catch {
    return json(400, { error: "The payment proof request is invalid." }, origin);
  }
  const allowedFields = new Set(["orderId", "trackingToken", "paymentMethod", "referenceNumber", "proof"]);
  const submittedFields = [...form.keys()];
  if (submittedFields.length !== allowedFields.size || submittedFields.some((key) => !allowedFields.has(key))) {
    return json(400, { error: "The payment proof request is invalid." }, origin);
  }
  const orderId = String(form.get("orderId") || "");
  const trackingToken = String(form.get("trackingToken") || "");
  const paymentMethod = String(form.get("paymentMethod") || "").toUpperCase();
  const referenceNumber = String(form.get("referenceNumber") || "").trim();
  const proof = form.get("proof");
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(orderId)
    || !/^[A-Za-z0-9_-]{40,60}$/.test(trackingToken)
    || !["GCASH", "MAYA"].includes(paymentMethod)
    || !referenceNumber || referenceNumber.length > 100 || /[\u0000-\u001f\u007f]/.test(referenceNumber)
    || !(proof instanceof File) || proof.size < 1 || proof.size > MAX_PROOF_BYTES
    || !["image/jpeg", "image/png", "image/webp"].includes(proof.type)) {
    return json(422, { error: "Enter the payment reference and attach a JPG, PNG or WebP screenshot up to 5 MB." }, origin);
  }

  const { data: trackingData, error: trackingError } = await admin.rpc("get_guest_order_status", {
    p_order_id: orderId,
    p_tracking_token: trackingToken,
  });
  if (trackingError || !trackingData) return json(404, { error: "Order payment is unavailable." }, origin);

  const { data: order, error: orderError } = await admin.from("orders")
    .select("id,station_id,order_source,payment_method,status")
    .eq("id", orderId)
    .maybeSingle();
  if (orderError || !order || order.order_source !== "CUSTOMER_WEB"
    || order.payment_method !== paymentMethod || ["CANCELLED", "REJECTED"].includes(order.status)) {
    return json(409, { error: "Online payment is unavailable for this order." }, origin);
  }
  const { data: station, error: stationError } = await admin.from("stations")
    .select("organization_id")
    .eq("id", order.station_id)
    .maybeSingle();
  if (stationError || !station?.organization_id) return json(503, { error: "Payment proof submission is temporarily unavailable." }, origin);

  const bytes = new Uint8Array(await proof.arrayBuffer());
  if (!imageType(bytes, proof.type)) return json(422, { error: "The selected file is not a valid payment image." }, origin);
  const objectPath = `organizations/${station.organization_id}/stations/${order.station_id}/customer-payment-proofs/${orderId}/${crypto.randomUUID()}.${extension(proof.type)}`;
  const { error: uploadError } = await admin.storage.from("payment-assets").upload(objectPath, bytes, {
    contentType: proof.type,
    cacheControl: "3600",
    upsert: false,
  });
  if (uploadError) {
    console.error("guest-payment-proof upload failed", { category: "storage_upload" });
    return json(503, { error: "Your order is saved, but the payment screenshot could not be uploaded. Keep this page open and try again." }, origin);
  }

  const { data: paymentData, error: paymentError } = await admin.rpc("record_guest_online_payment", {
    p_order_id: orderId,
    p_tracking_token: trackingToken,
    p_payment_method: paymentMethod,
    p_reference_number: referenceNumber,
    p_proof_object_path: objectPath,
  });
  if (paymentError) {
    await admin.storage.from("payment-assets").remove([objectPath]);
    const conflict = paymentError.message?.includes("different payment proof")
      || paymentError.message?.includes("no outstanding online payment");
    return json(conflict ? 409 : 422, {
      error: conflict
        ? "This order already has a different payment record. Contact the station before submitting another payment."
        : paymentError.message?.includes("order is unavailable")
          ? "Order payment is unavailable."
          : "The payment screenshot could not be recorded. Keep this page open and try again.",
    }, origin);
  }
  const payment = Array.isArray(paymentData) ? paymentData[0] : paymentData;
  if (!payment?.payment_id || payment.status !== "PAID") {
    await admin.storage.from("payment-assets").remove([objectPath]);
    return json(503, { error: "The payment screenshot could not be confirmed. Keep this page open and try again." }, origin);
  }
  if (payment.created === false) await admin.storage.from("payment-assets").remove([objectPath]);
  return json(200, { payment: { status: "PAID", referenceNumber, created: payment.created === true } }, origin);
});
