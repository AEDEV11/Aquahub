export type MarketingReconcileOptions =
  | { mode: "bulk" }
  | { mode: "diagnostic" }
  | { mode: "contact_status"; email: string }
  | { mode: "single_contact"; email: string };

export type MarketingReconcileOptionsResult =
  | { valid: true; value: MarketingReconcileOptions }
  | { valid: false };

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function parseMarketingReconcileOptions(
  input: unknown,
): MarketingReconcileOptionsResult {
  if (input === undefined || input === null) {
    return { valid: true, value: { mode: "bulk" } };
  }
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    return { valid: false };
  }
  const body = input as Record<string, unknown>;
  if (body.mode === undefined) return { valid: true, value: { mode: "bulk" } };
  if (body.mode === "diagnostic") {
    return { valid: true, value: { mode: "diagnostic" } };
  }
  if (body.mode === "contact_status") {
    const email = typeof body.email === "string"
      ? body.email.trim().toLowerCase()
      : "";
    if (email.length > 254 || !emailPattern.test(email)) {
      return { valid: false };
    }
    return { valid: true, value: { mode: "contact_status", email } };
  }
  if (body.mode === "single_contact") {
    const email = typeof body.email === "string"
      ? body.email.trim().toLowerCase()
      : "";
    if (email.length > 254 || !emailPattern.test(email)) {
      return { valid: false };
    }
    return { valid: true, value: { mode: "single_contact", email } };
  }
  return { valid: false };
}
