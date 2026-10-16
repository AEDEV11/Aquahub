import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const json = (status: number, body: Record<string, unknown>) => new Response(
  JSON.stringify(body),
  { status, headers: { "content-type": "application/json", "cache-control": "no-store" } },
);

function bearer(request: Request): string | null {
  const value = request.headers.get("authorization") ?? "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : null;
}

function decodeBase64Url(value: string): Uint8Array {
  const normalized = value.replaceAll("-", "+").replaceAll("_", "/");
  const binary = atob(normalized + "=".repeat((4 - normalized.length % 4) % 4));
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function asBufferSource(value: Uint8Array): ArrayBuffer {
  return value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength) as ArrayBuffer;
}

function encodeBase64Url(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

async function importSigningKey(): Promise<CryptoKey> {
  const value = Deno.env.get("OFFLINE_POS_SIGNING_PRIVATE_JWK");
  if (!value) throw new Error("offline signing key unavailable");
  return await crypto.subtle.importKey(
    "jwk",
    JSON.parse(value),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["sign"],
  );
}

async function importVerificationKey(): Promise<CryptoKey> {
  const value = Deno.env.get("OFFLINE_POS_SIGNING_PUBLIC_JWK");
  if (!value) throw new Error("offline verification key unavailable");
  return await crypto.subtle.importKey(
    "jwk",
    JSON.parse(value),
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["verify"],
  );
}

async function verifySigningConfiguration(expectedFingerprint: unknown): Promise<boolean> {
  if (typeof expectedFingerprint !== "string" || !/^[0-9a-f]{64}$/.test(expectedFingerprint)) return false;
  const privateKey = await importSigningKey();
  const publicKey = await importVerificationKey();
  const challenge = crypto.getRandomValues(new Uint8Array(32));
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, privateKey, challenge);
  if (!await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, publicKey, signature, challenge)) return false;
  const der = new Uint8Array(await crypto.subtle.exportKey("spki", publicKey));
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", der));
  const fingerprint = Array.from(hash, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return fingerprint === expectedFingerprint;
}

async function signClaims(claims: Record<string, unknown>): Promise<string> {
  const key = await importSigningKey();
  const publicJwk = JSON.parse(Deno.env.get("OFFLINE_POS_SIGNING_PUBLIC_JWK") ?? "{}");
  const header = { alg: "ES256", typ: "JWT", ...(publicJwk.kid ? { kid: publicJwk.kid } : {}) };
  const encodedHeader = encodeBase64Url(new TextEncoder().encode(JSON.stringify(header)));
  const encodedPayload = encodeBase64Url(new TextEncoder().encode(JSON.stringify(claims)));
  const signingInput = `${encodedHeader}.${encodedPayload}`;
  const signature = new Uint8Array(await crypto.subtle.sign(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    new TextEncoder().encode(signingInput),
  ));
  if (signature.length !== 64) throw new Error("unexpected ES256 signature format");
  return `${signingInput}.${encodeBase64Url(signature)}`;
}

async function verifyClaims(token: string): Promise<Record<string, unknown>> {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("invalid token");
  const header = JSON.parse(new TextDecoder().decode(decodeBase64Url(parts[0])));
  if (header.alg !== "ES256") throw new Error("unsupported token algorithm");
  const verified = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    await importVerificationKey(),
    asBufferSource(decodeBase64Url(parts[2])),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
  );
  if (!verified) throw new Error("invalid token signature");
  return JSON.parse(new TextDecoder().decode(decodeBase64Url(parts[1])));
}

function derEcdsaToRaw(value: Uint8Array): Uint8Array {
  if (value.length < 8 || value[0] !== 0x30) throw new Error("invalid device signature");
  let offset = 2;
  if (value[1] & 0x80) offset = 2 + (value[1] & 0x7f);
  if (value[offset++] !== 0x02) throw new Error("invalid device signature");
  const rLength = value[offset++];
  let r = value.slice(offset, offset + rLength);
  offset += rLength;
  if (value[offset++] !== 0x02) throw new Error("invalid device signature");
  const sLength = value[offset++];
  let s = value.slice(offset, offset + sLength);
  while (r.length > 32 && r[0] === 0) r = r.slice(1);
  while (s.length > 32 && s[0] === 0) s = s.slice(1);
  if (r.length > 32 || s.length > 32) throw new Error("invalid device signature width");
  const result = new Uint8Array(64);
  result.set(r, 32 - r.length);
  result.set(s, 64 - s.length);
  return result;
}

function validUuid(value: unknown): value is string {
  return typeof value === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, apikey, content-type", "access-control-allow-methods": "POST, OPTIONS" } });
  }
  if (request.method !== "POST") return json(405, { error: "METHOD_NOT_ALLOWED" });
  const accessToken = bearer(request);
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY")?.trim();
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.trim();
  if (!accessToken) return json(401, { error: "UNAUTHORIZED" });
  if (!serviceKey || !anonKey || !supabaseUrl) return json(503, { error: "OFFLINE_POS_UNAVAILABLE" });

  const admin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: authData, error: authError } = await admin.auth.getUser(accessToken);
  const profile = authData.user;
  if (authError || !profile) return json(401, { error: "UNAUTHORIZED" });

  let body: Record<string, unknown>;
  try {
    body = await request.json();
  } catch {
    return json(400, { error: "INVALID_REQUEST" });
  }

  if (body.action === "authorize") {
    const organizationId = body.organization_id;
    const stationId = body.station_id;
    const deviceId = body.device_id;
    const publicKeyDer = body.public_key_der;
    const verificationFingerprint = body.verification_public_key_sha256;
    const grantId = body.grant_id;
    const leaseId = body.lease_id;
    const items = body.items;
    const grantHours: unknown = body.grant_duration_hours ?? 8;
    const leaseHours: unknown = body.lease_duration_hours ?? 8;
    const elapsedRealtimeAnchorMs = body.elapsed_realtime_anchor_ms;
    const bootCount = body.boot_count;
    if (typeof organizationId !== "string" || typeof stationId !== "string"
        || typeof deviceId !== "string" || typeof publicKeyDer !== "string"
        || typeof verificationFingerprint !== "string"
        || !validUuid(grantId) || !validUuid(leaseId) || !Array.isArray(items)
        || typeof grantHours !== "number" || !Number.isInteger(grantHours) || grantHours < 1 || grantHours > 12
        || typeof leaseHours !== "number" || !Number.isInteger(leaseHours) || leaseHours < 1 || leaseHours > 24
        || typeof elapsedRealtimeAnchorMs !== "number" || !Number.isSafeInteger(elapsedRealtimeAnchorMs)
        || typeof bootCount !== "number" || !Number.isSafeInteger(bootCount)) {
      return json(400, { error: "INVALID_RESERVATION_REQUEST" });
    }
    try {
      // Check both signing-key configuration and the APK's verifier before
      // reserving any stock. A mismatched key must not strand inventory.
      if (!await verifySigningConfiguration(verificationFingerprint)) {
        return json(503, { error: "OFFLINE_SIGNING_CONFIGURATION_INVALID" });
      }
      const userClient = createClient(supabaseUrl, anonKey, {
        global: { headers: { Authorization: `Bearer ${accessToken}` } },
        auth: { persistSession: false, autoRefreshToken: false },
      });
      const { error: registerError } = await userClient.rpc("register_offline_pos_device", {
        p_device_id: deviceId,
        p_organization_id: organizationId,
        p_station_id: stationId,
        p_public_key_der: publicKeyDer,
      });
      if (registerError) return json(403, { error: "DEVICE_REGISTRATION_REJECTED" });
      const { data, error } = await userClient.rpc("issue_offline_pos_grant_and_lease", {
        p_device_id: deviceId,
        p_organization_id: organizationId,
        p_station_id: stationId,
        p_grant_id: grantId,
        p_lease_id: leaseId,
        p_requested_items: items,
        p_grant_duration_hours: grantHours,
        p_lease_duration_hours: leaseHours,
      });
      if (error || !data || typeof data !== "object") return json(409, { error: "RESERVATION_REJECTED" });
      const claims = data as { grant?: Record<string, unknown>; lease?: Record<string, unknown> };
      if (!claims.grant || !claims.lease) return json(503, { error: "RESERVATION_RESPONSE_INVALID" });
      // The database anchor is stable across an idempotent retry. Replacing it
      // with the retry's current wall time while retaining the original device
      // monotonic anchor would make the client count elapsed time twice.
      const serverTimeAnchorMs = Number(claims.grant.server_time_anchor_ms);
      if (!Number.isSafeInteger(serverTimeAnchorMs)
          || Number(claims.lease.server_time_anchor_ms) !== serverTimeAnchorMs) {
        return json(503, { error: "RESERVATION_TIME_ANCHOR_INVALID" });
      }
      const grantClaims = {
        ...claims.grant,
        elapsed_realtime_anchor_ms: elapsedRealtimeAnchorMs,
        boot_count: bootCount,
      };
      const leaseClaims = {
        ...claims.lease,
        elapsed_realtime_anchor_ms: elapsedRealtimeAnchorMs,
        boot_count: bootCount,
      };
      const [grantToken, leaseToken] = await Promise.all([signClaims(grantClaims), signClaims(leaseClaims)]);
      return json(200, { grant_token: grantToken, lease_token: leaseToken, server_time_ms: serverTimeAnchorMs });
    } catch {
      return json(503, { error: "RESERVATION_SERVICE_UNAVAILABLE" });
    }
  }

  if (body.action !== "commit") return json(400, { error: "UNSUPPORTED_ACTION" });
  const deviceId = body.device_id;
  const grantToken = body.grant_token;
  const leaseToken = body.lease_token;
  const canonicalPayload = body.canonical_payload;
  const payloadDigest = body.payload_digest;
  const deviceSignature = body.device_signature;
  if (typeof deviceId !== "string" || typeof grantToken !== "string"
      || typeof leaseToken !== "string" || typeof canonicalPayload !== "string"
      || typeof payloadDigest !== "string" || !/^[0-9a-f]{64}$/.test(payloadDigest)
      || typeof deviceSignature !== "string" || canonicalPayload.length > 32768) {
    return json(400, { error: "INVALID_OFFLINE_OPERATION" });
  }

  try {
    const [grant, lease, payload] = await Promise.all([
      verifyClaims(grantToken),
      verifyClaims(leaseToken),
      Promise.resolve(JSON.parse(canonicalPayload) as Record<string, unknown>),
    ]);
    if (grant.iss !== "aquahub-offline-pos" || lease.iss !== "aquahub-offline-pos"
        || grant.sub !== profile.id || lease.sub !== profile.id
        || grant.device !== deviceId || lease.device !== deviceId
        || grant.org !== lease.org || grant.station !== lease.station
        || lease.grant_id !== grant.jti
        || !Array.isArray(grant.operations) || !grant.operations.includes("OFFLINE_WALKIN_CASH")) {
      return json(403, { error: "OFFLINE_AUTHORIZATION_SCOPE_MISMATCH" });
    }
    const transactionAt = payload.transaction_at_ms;
    const elapsedAt = payload.elapsed_realtime_at_ms;
    const bootCount = payload.boot_count;
    const grantElapsedAnchor = Number(grant.elapsed_realtime_anchor_ms);
    const grantServerAnchor = Number(grant.server_time_anchor_ms);
    const monotonicTransactionAt = grantServerAnchor + (Number(elapsedAt) - grantElapsedAnchor);
    if (payload.profile_id !== profile.id || payload.device_id !== deviceId
        || payload.grant_id !== grant.jti || payload.lease_id !== lease.jti
        || payload.organization_id !== grant.org || payload.station_id !== grant.station
        || payload.payment_method !== "CASH" || payload.delivery_mode !== "PICKUP"
        || typeof transactionAt !== "number"
        || typeof elapsedAt !== "number" || !Number.isSafeInteger(elapsedAt)
        || bootCount !== grant.boot_count || bootCount !== lease.boot_count
        || elapsedAt < grantElapsedAnchor
        || !Number.isFinite(monotonicTransactionAt)
        || Math.abs(transactionAt - monotonicTransactionAt) > 5000
        || transactionAt < Number(grant.iat) * 1000
        || transactionAt > Number(grant.exp) * 1000
        || transactionAt < Number(lease.iat) * 1000
        || transactionAt > Number(lease.exp) * 1000
        || transactionAt > Date.now() + 2 * 60 * 1000) {
      return json(409, { error: "OFFLINE_OPERATION_OUTSIDE_AUTHORIZATION_WINDOW" });
    }
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(canonicalPayload)));
    if (encodeBase64Url(digest) === "" || Array.from(digest, (byte) => byte.toString(16).padStart(2, "0")).join("") !== payloadDigest) {
      return json(400, { error: "OFFLINE_PAYLOAD_DIGEST_MISMATCH" });
    }
    const { data: keyRow, error: keyError } = await admin.rpc("get_offline_pos_device_key", {
      p_device_id: deviceId,
      p_profile_id: profile.id,
    });
    if (keyError || typeof keyRow !== "string") return json(403, { error: "OFFLINE_DEVICE_NOT_REGISTERED" });
    const publicKey = await crypto.subtle.importKey(
      "spki", asBufferSource(Uint8Array.from(atob(keyRow), (character) => character.charCodeAt(0))),
      { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"],
    );
    const deviceVerified = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" }, publicKey,
      asBufferSource(derEcdsaToRaw(decodeBase64Url(deviceSignature))), new TextEncoder().encode(payloadDigest),
    );
    if (!deviceVerified) return json(403, { error: "OFFLINE_DEVICE_SIGNATURE_INVALID" });
    const result = await admin.rpc("commit_offline_pos_sale", {
      p_profile_id: profile.id,
      p_device_id: deviceId,
      p_grant_id: String(grant.jti),
      p_lease_id: String(lease.jti),
      p_operation_id: String(payload.operation_id),
      p_payload_digest: payloadDigest,
      p_canonical_payload: canonicalPayload,
    });
    if (result.error || !result.data) {
      const code = result.error?.code ?? "SYNC_REJECTED";
      const conflict = ["23514", "23505", "22023", "55000", "42501", "40001"].includes(code);
      return json(conflict ? 409 : 503, { error: conflict ? "RECONCILIATION_REQUIRED" : "SYNC_TEMPORARILY_UNAVAILABLE", code });
    }
    return json(200, result.data as Record<string, unknown>);
  } catch {
    return json(409, { error: "OFFLINE_OPERATION_REQUIRES_RECONCILIATION" });
  }
});
