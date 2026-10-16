export type NewsletterPayload = {
  email: string;
  firstName: string | null;
  consent: true;
  honeypot: string;
};

export type NewsletterValidation =
  | { valid: true; value: NewsletterPayload }
  | {
    valid: false;
    code:
      | "invalid_email"
      | "missing_consent"
      | "invalid_name"
      | "invalid_payload";
  };

export function validateNewsletterPayload(
  input: unknown,
): NewsletterValidation {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    return { valid: false, code: "invalid_payload" };
  }
  const body = input as Record<string, unknown>;
  const email = typeof body.email === "string"
    ? body.email.trim().toLowerCase()
    : "";
  const firstName = typeof body.firstName === "string"
    ? body.firstName.trim()
    : "";
  const honeypot = typeof body.website === "string" ? body.website.trim() : "";

  if (
    email.length < 3 || email.length > 254 ||
    !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
  ) {
    return { valid: false, code: "invalid_email" };
  }
  // Reject ASCII control characters in user-provided names.
  // deno-lint-ignore no-control-regex
  if (firstName.length > 80 || /[\u0000-\u001f\u007f]/.test(firstName)) {
    return { valid: false, code: "invalid_name" };
  }
  if (body.consent !== true) return { valid: false, code: "missing_consent" };
  return {
    valid: true,
    value: { email, firstName: firstName || null, consent: true, honeypot },
  };
}

function toHex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function marketingFingerprint(
  secret: string,
  purpose: "ip" | "email",
  value: string,
): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signed = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(`${purpose}:${value.trim().toLowerCase()}`),
  );
  return toHex(new Uint8Array(signed));
}

export function retryDelaySeconds(
  attempt: number,
  retryAfterSeconds: number | null,
  random = Math.random,
): number {
  const exponential = Math.min(
    86_400,
    30 * 2 ** Math.min(Math.max(attempt, 1), 10),
  );
  const jittered = Math.ceil(exponential * (0.8 + random() * 0.4));
  return Math.min(86_400, Math.max(jittered, retryAfterSeconds ?? 0));
}
