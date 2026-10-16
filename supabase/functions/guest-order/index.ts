import { createClient } from "https://esm.sh/@supabase/supabase-js@2.117.2";
import { createAllowedOrigins, normalizeClientNetwork } from "./security-utils.js";
import { GooglePlaceUnavailableError, InvalidGooglePlaceError, resolveGooglePlace } from "./google-places.js";
import {
  classifyGuestOrderDatabaseError,
  safeGuestOrderDatabaseMessage,
} from "../_shared/guest-order-errors.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
const isLocalRuntime = /^(https?:\/\/)?(127\.0\.0\.1|localhost|kong|supabase_kong)(:\d+)?$/i.test(supabaseUrl || "");
const allowedOrigins = createAllowedOrigins(Deno.env.get("GUEST_ORDER_ALLOWED_ORIGINS"), isLocalRuntime);
const corsHeaders = {
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-headers": "authorization, apikey, content-type, x-client-info",
  "access-control-max-age": "86400",
  "vary": "Origin",
};
const json = (status: number, body: Record<string, unknown>, origin?: string) => {
  const headers = new Headers({ ...corsHeaders, "content-type": "application/json; charset=utf-8" });
  if (origin && allowedOrigins.has(origin)) headers.set("access-control-allow-origin", origin);
  return new Response(JSON.stringify(body), { status, headers });
};

function normalizePhilippineMobile(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const input = value.trim();
  if (!input || /[^\d+\s().-]/.test(input) || (input.match(/\+/g) ?? []).length > 1) return null;
  let mobile = input.replace(/[\s().-]/g, "");
  const hasCountryCode = mobile.startsWith("+63") || mobile.startsWith("63");
  if (mobile.startsWith("+63")) mobile = mobile.slice(3);
  if (mobile.startsWith("63")) mobile = mobile.slice(2);
  if (!hasCountryCode && mobile.startsWith("0")) mobile = mobile.slice(1);
  if (!/^9\d{9}$/.test(mobile)) return null;
  return `+63${mobile}`;
}

function bytesToHex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

function stableJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)
      .map(([key, entry]) => `${JSON.stringify(key)}:${stableJson(entry)}`);
    return `{${entries.join(",")}}`;
  }
  return JSON.stringify(value) ?? "null";
}

async function requestFingerprint(stationCode: string, request: Record<string, unknown>): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(stableJson({ station_code: stationCode, request })),
  );
  return bytesToHex(new Uint8Array(digest));
}

async function fingerprint(secret: string, purpose: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${purpose}:${value}`));
  return bytesToHex(new Uint8Array(signature));
}

function firstRow<T>(value: T | T[] | null): T | null {
  return Array.isArray(value) ? value[0] ?? null : value;
}

async function readLimitedJson(request: Request): Promise<{ body: Record<string, unknown> | null; tooLarge: boolean }> {
  const reader = request.body?.getReader();
  if (!reader) return { body: null, tooLarge: false };
  const chunks: Uint8Array[] = [];
  let byteLength = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    byteLength += value.byteLength;
    if (byteLength > 32_000) {
      await reader.cancel();
      return { body: null, tooLarge: true };
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(byteLength);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes));
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return { body: null, tooLarge: false };
  return { body: parsed as Record<string, unknown>, tooLarge: false };
}

Deno.serve(async (request) => {
  const origin = request.headers.get("origin") ?? undefined;
  if (request.method === "OPTIONS") {
    if (origin && !allowedOrigins.has(origin)) return json(403, { error: "Origin is not allowed." });
    return new Response(null, {
      status: 204,
      headers: {
        ...corsHeaders,
        ...(origin ? { "access-control-allow-origin": origin } : {}),
      },
    });
  }
  if (request.method !== "POST") return json(405, { error: "Use POST for guest ordering." }, origin);
  if (origin && !allowedOrigins.has(origin)) return json(403, { error: "Origin is not allowed." });
  const contentLength = Number(request.headers.get("content-length") || 0);
  if (contentLength > 32_000) return json(413, { error: "The order request is too large." }, origin);

  let body: Record<string, unknown> | null;
  try {
    const result = await readLimitedJson(request);
    if (result.tooLarge) return json(413, { error: "The order request is too large." }, origin);
    body = result.body;
    if (!body) return json(400, { error: "The order request is invalid." }, origin);
  } catch {
    return json(400, { error: "The order request is invalid." }, origin);
  }

  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const googleMapsServerKey = Deno.env.get("GOOGLE_MAPS_SERVER_API_KEY");
  if (!supabaseUrl || !serviceKey) return json(503, { error: "Guest ordering is temporarily unavailable." }, origin);
  const ip = normalizeClientNetwork(request.headers.get("cf-connecting-ip"));
  if (!ip && !isLocalRuntime) return json(503, { error: "Guest ordering is temporarily unavailable." }, origin);
  const trustedIp = ip || "local-development";
  const admin = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
  });

  if (body.action === "create") {
    const stationCode = typeof body.stationCode === "string" ? body.stationCode : "";
    const token = typeof body.trackingToken === "string" ? body.trackingToken : "";
    const submitted = body.request as Record<string, unknown> | undefined;
    if (!/^[A-Za-z0-9_-]{6,64}$/.test(stationCode) || !/^[A-Za-z0-9_-]{40,60}$/.test(token)
      || !submitted || typeof submitted !== "object" || Array.isArray(submitted)) {
      return json(400, { error: "The order request is incomplete." }, origin);
    }
    const phone = normalizePhilippineMobile(submitted.customer_phone);
    const name = typeof submitted.customer_name === "string" ? submitted.customer_name.trim() : "";
    const items = submitted.items;
    if (!phone) return json(422, { error: "Enter a valid Philippine mobile number, such as 09XX XXX XXXX." }, origin);
    if (name.length < 2 || name.length > 100 || !Array.isArray(items) || items.length < 1 || items.length > 20) {
      return json(422, { error: "Check your name and cart, then try again." }, origin);
    }
    if (items.some((item) => !item || typeof item !== "object"
      || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(String(item.product_id))
      || !Number.isInteger(item.quantity) || item.quantity < 1 || item.quantity > 99
      || (item.container_source != null && !["CUSTOMER_OWNED", "STATION_EXCHANGE", "STATION_BORROW"].includes(item.container_source)))) {
      return json(422, { error: "One or more products in your cart are invalid." }, origin);
    }

    const deliveryMode = String(submitted.delivery_mode || "").toUpperCase();
    const addressDetails = typeof submitted.delivery_address_details === "string" ? submitted.delivery_address_details.trim() : "";
    const operationId = typeof submitted.operation_id === "string" ? submitted.operation_id : "";
    if (!/^[A-Za-z0-9_-]{6,64}$/.test(stationCode) || !/^guest:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(operationId)
      || !["PICKUP", "DELIVERY"].includes(deliveryMode) || Object.hasOwn(submitted, "request_fingerprint")
      || (submitted.delivery_address_details != null && typeof submitted.delivery_address_details !== "string") || addressDetails.length > 270) {
      return json(422, { error: "Check your delivery address details and try again." }, origin);
    }
    const placeId = typeof submitted.delivery_place_id === "string" ? submitted.delivery_place_id.trim() : "";
    const normalizedSubmission = {
      ...submitted,
      customer_name: name,
      customer_phone: phone,
      delivery_mode: deliveryMode,
      delivery_address: typeof submitted.delivery_address === "string" ? submitted.delivery_address.trim() : "",
      delivery_address_details: addressDetails,
      delivery_place_id: placeId || null,
    };
    const submissionFingerprint = await requestFingerprint(stationCode, normalizedSubmission);
    const normalizedRequest: Record<string, unknown> = { ...normalizedSubmission, request_fingerprint: submissionFingerprint };
    const [ipFingerprint, phoneFingerprint] = await Promise.all([
      fingerprint(serviceKey, "create-ip", trustedIp),
      fingerprint(serviceKey, "create-phone", phone),
    ]);
    const { data: rateData, error: rateError } = await admin.rpc("consume_guest_order_rate_limit", {
      p_ip_fingerprint: ipFingerprint,
      p_phone_fingerprint: phoneFingerprint,
    });
    if (rateError) return json(503, { error: "Guest ordering is temporarily unavailable." }, origin);
    const rate = firstRow(rateData);
    if (!rate?.allowed) {
      return json(429, { error: "Too many order attempts. Please wait a few minutes and try again.", retryAfter: rate?.retry_after_seconds ?? 900 }, origin);
    }

    const { data: resumedData, error: resumeError } = await admin.rpc("resume_guest_order", {
      p_operation_id: operationId,
      p_request_fingerprint: submissionFingerprint,
      p_tracking_token: token,
    });
    if (resumeError) {
      const isRetryMismatch = resumeError.message?.includes("this retry key was already used");
      return json(isRetryMismatch ? 422 : 503, {
        error: isRetryMismatch ? safeGuestOrderDatabaseMessage(resumeError.message) : "Guest ordering is temporarily unavailable.",
      }, origin);
    }
    const resumed = firstRow(resumedData);
    if (resumed && typeof resumed === "object") return json(200, { order: resumed }, origin);

    delete normalizedRequest.delivery_address_details;
    if (deliveryMode === "PICKUP") {
      normalizedRequest.delivery_address = "";
      normalizedRequest.delivery_place_id = null;
      normalizedRequest.latitude = null;
      normalizedRequest.longitude = null;
    } else if (deliveryMode === "DELIVERY") {
      const hasCoordinates = submitted.latitude != null || submitted.longitude != null;
      if (hasCoordinates && !placeId) {
        return json(422, { error: "Choose your delivery address using address search or the map pin." }, origin);
      }
      if (placeId) {
        try {
          const place = await resolveGooglePlace(placeId, googleMapsServerKey);
          const deliveryAddress = [place.address, addressDetails].filter(Boolean).join(", ");
          if (deliveryAddress.length > 400) return json(422, { error: "Your delivery address is too long." }, origin);
          normalizedRequest.delivery_address = deliveryAddress;
          normalizedRequest.delivery_place_id = place.id;
          normalizedRequest.latitude = place.latitude;
          normalizedRequest.longitude = place.longitude;
        } catch (error) {
          if (error instanceof InvalidGooglePlaceError) {
            return json(422, { error: "Choose a valid delivery address or map pin." }, origin);
          }
          if (error instanceof GooglePlaceUnavailableError) {
            console.error("guest-order Google Places verification failed", {
              category: error.reason,
              upstream_status: error.status,
            });
            return json(503, { error: "Delivery location verification is temporarily unavailable. Your details are still here; please try again." }, origin);
          }
          console.error("guest-order Google Places verification failed", { category: "unexpected_error" });
          return json(503, { error: "Delivery location verification is temporarily unavailable. Your details are still here; please try again." }, origin);
        }
      } else {
        const deliveryAddress = [String(submitted.delivery_address || "").trim(), addressDetails].filter(Boolean).join(", ");
        if (deliveryAddress.length > 400) return json(422, { error: "Your delivery address is too long." }, origin);
        normalizedRequest.delivery_address = deliveryAddress;
        normalizedRequest.delivery_place_id = null;
        normalizedRequest.latitude = null;
        normalizedRequest.longitude = null;
      }
    }

    const { data, error } = await admin.rpc("create_guest_order_with_container_sources", {
      p_station_code: stationCode,
      p_request: normalizedRequest,
      p_tracking_token: token,
    });
    if (error || !data || typeof data !== "object") {
      const failure = classifyGuestOrderDatabaseError(error?.message);
      return json(failure.status, { error: failure.message }, origin);
    }
    return json(200, { order: data }, origin);
  }

  if (body.action === "status") {
    const orderId = typeof body.orderId === "string" ? body.orderId : "";
    const token = typeof body.trackingToken === "string" ? body.trackingToken : "";
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(orderId)
      || !/^[A-Za-z0-9_-]{40,60}$/.test(token)) {
      return json(404, { error: "Order tracking is unavailable." }, origin);
    }
    const ipFingerprint = await fingerprint(serviceKey, "track-ip", trustedIp);
    const { data: rateData, error: rateError } = await admin.rpc("consume_guest_tracking_rate_limit", {
      p_ip_fingerprint: ipFingerprint,
    });
    if (rateError) return json(503, { error: "Order tracking is temporarily unavailable." }, origin);
    const rate = firstRow(rateData);
    if (!rate?.allowed) {
      return json(429, { error: "Tracking is refreshing too often. Please wait a moment.", retryAfter: rate?.retry_after_seconds ?? 900 }, origin);
    }
    const { data, error } = await admin.rpc("get_guest_order_status", {
      p_order_id: orderId,
      p_tracking_token: token,
    });
    if (error || !data) return json(404, { error: "Order tracking is unavailable." }, origin);
    return json(200, { order: data }, origin);
  }

  return json(400, { error: "Choose a valid ordering action." }, origin);
});
