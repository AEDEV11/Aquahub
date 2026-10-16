import { GoogleOidcVerificationError, verifyGoogleOidcBearer } from "./google-oidc.ts";

const AUDIENCE = "https://aquahub-staging.example.test/functions/v1/play-rtdn";
const SERVICE_ACCOUNT = "rtdn-push@aquahub-staging.iam.gserviceaccount.com";
const NOW_MS = Date.parse("2026-10-09T00:00:00.000Z");
const NOW_SECONDS = NOW_MS / 1_000;
const KEY_ID = "synthetic-test-key";

const keyPairPromise = crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"],
);

async function testJwk(): Promise<JsonWebKey & { kid: string }> {
  const pair = await keyPairPromise;
  return { ...await crypto.subtle.exportKey("jwk", pair.publicKey), kid: KEY_ID };
}

function encodeBase64Url(value: string | Uint8Array): string {
  const bytes = typeof value === "string" ? new TextEncoder().encode(value) : value;
  const binary = Array.from(bytes, (byte) => String.fromCharCode(byte)).join("");
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

async function token(
  claimOverrides: Record<string, unknown> = {},
  headerOverrides: Record<string, unknown> = {},
): Promise<string> {
  const pair = await keyPairPromise;
  const header = encodeBase64Url(JSON.stringify({ alg: "RS256", kid: KEY_ID, ...headerOverrides }));
  const claims = encodeBase64Url(JSON.stringify({
    iss: "https://accounts.google.com",
    aud: AUDIENCE,
    exp: NOW_SECONDS + 3_600,
    iat: NOW_SECONDS - 30,
    nbf: NOW_SECONDS - 30,
    sub: "synthetic-service-account-subject",
    email: SERVICE_ACCOUNT,
    email_verified: true,
    ...claimOverrides,
  }));
  const message = `${header}.${claims}`;
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", pair.privateKey, new TextEncoder().encode(message));
  return `${message}.${encodeBase64Url(new Uint8Array(signature))}`;
}

function requestWithBearer(value: string): Request {
  return new Request("https://aquahub-staging.example.test/push", {
    method: "POST",
    headers: { authorization: `Bearer ${value}` },
  });
}

const testFetcher: typeof fetch = async () => new Response(JSON.stringify({ keys: [await testJwk()] }), {
  status: 200,
  headers: { "content-type": "application/json", "cache-control": "public, max-age=300" },
});

async function assertRejected(
  request: Request,
  reason: GoogleOidcVerificationError["reason"],
  fetcher: typeof fetch = testFetcher,
): Promise<void> {
  try {
    await verifyGoogleOidcBearer(request, AUDIENCE, SERVICE_ACCOUNT, { fetcher, nowMs: NOW_MS });
  } catch (error) {
    if (error instanceof GoogleOidcVerificationError && error.reason === reason) return;
    throw new Error(`Expected ${reason}; received ${error instanceof Error ? error.message : String(error)}`);
  }
  throw new Error(`Expected ${reason}, but the request was accepted`);
}

Deno.test("accepts a valid Google OIDC token for the configured push service account and audience", async () => {
  await verifyGoogleOidcBearer(requestWithBearer(await token()), AUDIENCE, SERVICE_ACCOUNT, {
    fetcher: testFetcher,
    nowMs: NOW_MS,
  });
});

Deno.test("rejects a multi-audience claim even when it contains the configured audience", async () => {
  await assertRejected(requestWithBearer(await token({ aud: ["another-client", AUDIENCE] })), "UNAUTHORIZED");
});

Deno.test("rejects wrong issuer, audience, service account, and unverified email claims", async () => {
  for (const claim of [
    { iss: "https://attacker.example" },
    { aud: "https://other.example.test" },
    { email: "attacker@example.test" },
    { email_verified: false },
  ]) {
    await assertRejected(requestWithBearer(await token(claim)), "UNAUTHORIZED");
  }
});

Deno.test("rejects expired and not-yet-valid tokens", async () => {
  await assertRejected(requestWithBearer(await token({ exp: NOW_SECONDS - 1 })), "UNAUTHORIZED");
  await assertRejected(requestWithBearer(await token({ nbf: NOW_SECONDS + 120 })), "UNAUTHORIZED");
});

Deno.test("rejects a forged signature and non-RS256 algorithms", async () => {
  const validParts = (await token()).split(".");
  const forgedSignature = `${validParts[0]}.${validParts[1]}.${encodeBase64Url(new Uint8Array(256).fill(1))}`;
  await assertRejected(requestWithBearer(forgedSignature), "UNAUTHORIZED");
  await assertRejected(requestWithBearer(await token({}, { alg: "none" })), "UNAUTHORIZED");
});

Deno.test("rejects an unknown signing key after refreshing Google's key set", async () => {
  let keySetRequests = 0;
  const fetcher: typeof fetch = async () => {
    keySetRequests++;
    return new Response(JSON.stringify({ keys: [await testJwk()] }), {
      status: 200,
      headers: { "content-type": "application/json", "cache-control": "public, max-age=300" },
    });
  };
  await assertRejected(requestWithBearer(await token({}, { kid: "unknown-key" })), "UNAUTHORIZED", fetcher);
  if (keySetRequests !== 2) throw new Error(`Expected initial and refreshed key lookup; got ${keySetRequests}`);
});

Deno.test("rejects missing identity configuration and reports Google cert-service failures as unavailable", async () => {
  try {
    await verifyGoogleOidcBearer(requestWithBearer(await token()), undefined, SERVICE_ACCOUNT, { nowMs: NOW_MS });
    throw new Error("Missing verifier configuration was accepted");
  } catch (error) {
    if (!(error instanceof GoogleOidcVerificationError) || error.reason !== "NOT_CONFIGURED") throw error;
  }
  await assertRejected(requestWithBearer(await token()), "UNAVAILABLE", async () => new Response("", { status: 503 }));
});
