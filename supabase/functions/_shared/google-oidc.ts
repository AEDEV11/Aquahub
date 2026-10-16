export type GoogleOidcFailure = "NOT_CONFIGURED" | "UNAUTHORIZED" | "UNAVAILABLE";

export class GoogleOidcVerificationError extends Error {
  constructor(readonly reason: GoogleOidcFailure) {
    super(reason);
    this.name = "GoogleOidcVerificationError";
  }
}

type GoogleOidcClaims = {
  iss?: unknown;
  aud?: unknown;
  exp?: unknown;
  iat?: unknown;
  nbf?: unknown;
  sub?: unknown;
  email?: unknown;
  email_verified?: unknown;
};

type GoogleJwk = JsonWebKey & { kid?: string };

let cachedKeys: GoogleJwk[] | null = null;
let cachedUntil = 0;

function unauthorized(): never {
  throw new GoogleOidcVerificationError("UNAUTHORIZED");
}

function decodeBase64Url(value: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) return unauthorized();
  try {
    const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
    const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
    const binary = atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) {
      bytes[index] = binary.charCodeAt(index);
    }
    return bytes;
  } catch {
    return unauthorized();
  }
}

function parsePart<T>(value: string): T {
  try {
    return JSON.parse(new TextDecoder().decode(decodeBase64Url(value))) as T;
  } catch (error) {
    if (error instanceof GoogleOidcVerificationError) throw error;
    return unauthorized();
  }
}

async function fetchGoogleKeys(
  fetcher: typeof fetch,
  nowMs: number,
  forceRefresh: boolean,
): Promise<GoogleJwk[]> {
  const usingPlatformFetch = fetcher === fetch;
  if (usingPlatformFetch && !forceRefresh && cachedKeys && nowMs < cachedUntil) return cachedKeys;

  let response: Response;
  try {
    response = await fetcher("https://www.googleapis.com/oauth2/v3/certs", {
      signal: AbortSignal.timeout(5_000),
    });
  } catch {
    throw new GoogleOidcVerificationError("UNAVAILABLE");
  }
  if (!response.ok) throw new GoogleOidcVerificationError("UNAVAILABLE");

  let body: { keys?: unknown };
  try {
    body = await response.json() as { keys?: unknown };
  } catch {
    throw new GoogleOidcVerificationError("UNAVAILABLE");
  }
  if (!Array.isArray(body.keys)) throw new GoogleOidcVerificationError("UNAVAILABLE");
  const keys = body.keys.filter((candidate): candidate is GoogleJwk =>
    candidate !== null && typeof candidate === "object" && typeof (candidate as GoogleJwk).kid === "string"
  );
  if (keys.length === 0) throw new GoogleOidcVerificationError("UNAVAILABLE");

  if (usingPlatformFetch) {
    const cacheControl = response.headers.get("cache-control") ?? "";
    const maxAge = Number(cacheControl.match(/(?:^|,)\s*max-age=(\d+)/i)?.[1] ?? 300);
    const ttlSeconds = Number.isFinite(maxAge) ? Math.max(0, Math.min(maxAge, 3_600)) : 300;
    cachedKeys = keys;
    cachedUntil = nowMs + ttlSeconds * 1_000;
  }
  return keys;
}

/** Verify a Google-signed OIDC bearer token used by an authenticated Pub/Sub push or scheduler. */
export async function verifyGoogleOidcBearer(
  request: Request,
  expectedAudience: string | undefined,
  expectedServiceAccount: string | undefined,
  options: { fetcher?: typeof fetch; nowMs?: number } = {},
): Promise<void> {
  const audience = expectedAudience?.trim();
  const serviceAccount = expectedServiceAccount?.trim().toLowerCase();
  if (!audience || !serviceAccount) throw new GoogleOidcVerificationError("NOT_CONFIGURED");

  const authorization = request.headers.get("authorization") ?? "";
  const match = /^Bearer ([^\s]+)$/i.exec(authorization);
  if (!match) unauthorized();
  const token = match[1];
  const parts = token.split(".");
  if (parts.length !== 3) unauthorized();

  const header = parsePart<{ alg?: unknown; kid?: unknown }>(parts[0]);
  const claims = parsePart<GoogleOidcClaims>(parts[1]);
  const nowSeconds = Math.floor((options.nowMs ?? Date.now()) / 1_000);
  if (
    header.alg !== "RS256" || typeof header.kid !== "string" || header.kid.length === 0 ||
    !["https://accounts.google.com", "accounts.google.com"].includes(String(claims.iss)) ||
    claims.aud !== audience ||
    !Number.isSafeInteger(claims.exp) || (claims.exp as number) <= nowSeconds ||
    !Number.isSafeInteger(claims.iat) || (claims.iat as number) > nowSeconds + 60 ||
    (claims.exp as number) <= (claims.iat as number) ||
    (claims.nbf !== undefined && (!Number.isSafeInteger(claims.nbf) || (claims.nbf as number) > nowSeconds + 60)) ||
    typeof claims.sub !== "string" || claims.sub.length === 0 ||
    typeof claims.email !== "string" || claims.email.toLowerCase() !== serviceAccount ||
    claims.email_verified !== true
  ) unauthorized();

  const fetcher = options.fetcher ?? fetch;
  let keys = await fetchGoogleKeys(fetcher, options.nowMs ?? Date.now(), false);
  let jwk = keys.find((candidate) => candidate.kid === header.kid);
  if (!jwk) {
    keys = await fetchGoogleKeys(fetcher, options.nowMs ?? Date.now(), true);
    jwk = keys.find((candidate) => candidate.kid === header.kid);
  }
  // A successful key-set refresh without this kid means the bearer is not signed by
  // Google's currently published keys; treating it as unavailable would trigger retries.
  if (!jwk) unauthorized();

  try {
    const key = await crypto.subtle.importKey(
      "jwk",
      jwk,
      { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
      false,
      ["verify"],
    );
    const signatureValid = await crypto.subtle.verify(
      "RSASSA-PKCS1-v1_5",
      key,
      decodeBase64Url(parts[2]),
      new TextEncoder().encode(`${parts[0]}.${parts[1]}`),
    );
    if (!signatureValid) unauthorized();
  } catch (error) {
    if (error instanceof GoogleOidcVerificationError) throw error;
    unauthorized();
  }
}
