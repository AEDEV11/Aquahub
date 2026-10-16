export type PlayPurchaseRejectionCategory =
  | "AUTH_MISSING"
  | "AUTH_INVALID"
  | "PENDING_CANCELED"
  | "TOKEN_SUPERSEDED"
  | "TOKEN_BOUND_ELSEWHERE"
  | "ACCOUNT_MISMATCH"
  | "ORGANIZATION_MISMATCH"
  | "ORGANIZATION_REQUIRED"
  | "REPLACEMENT_NOT_ALLOWED"
  | "BINDING_CONFLICT"
  | "STALE_TOKEN"
  | "STALE_LINKED_TOKEN"
  | "STALE_EVENT"
  | "STALE_OR_UNBOUND_TOKEN"
  | "UNEXPECTED_APPLY_RESULT";

const rejection: Record<
  PlayPurchaseRejectionCategory,
  { status: 401 | 409; error: string }
> = {
  AUTH_MISSING: { status: 401, error: "UNAUTHORIZED" },
  AUTH_INVALID: { status: 401, error: "UNAUTHORIZED" },
  PENDING_CANCELED: { status: 409, error: "PENDING_PURCHASE_CANCELED" },
  TOKEN_SUPERSEDED: { status: 409, error: "PURCHASE_TOKEN_SUPERSEDED" },
  TOKEN_BOUND_ELSEWHERE: { status: 409, error: "PURCHASE_ALREADY_BOUND" },
  ACCOUNT_MISMATCH: { status: 409, error: "PURCHASE_ACCOUNT_MISMATCH" },
  ORGANIZATION_MISMATCH: {
    status: 409,
    error: "PURCHASE_ORGANIZATION_MISMATCH",
  },
  ORGANIZATION_REQUIRED: {
    status: 409,
    error: "PURCHASE_ORGANIZATION_REQUIRED",
  },
  REPLACEMENT_NOT_ALLOWED: {
    status: 409,
    error: "PURCHASE_REPLACEMENT_NOT_ALLOWED",
  },
  BINDING_CONFLICT: { status: 409, error: "PURCHASE_BINDING_CONFLICT" },
  STALE_TOKEN: { status: 409, error: "PURCHASE_REPLACED_OR_STALE" },
  STALE_LINKED_TOKEN: { status: 409, error: "PURCHASE_REPLACED_OR_STALE" },
  STALE_EVENT: { status: 409, error: "PURCHASE_REPLACED_OR_STALE" },
  STALE_OR_UNBOUND_TOKEN: { status: 409, error: "PURCHASE_REPLACED_OR_STALE" },
  UNEXPECTED_APPLY_RESULT: { status: 409, error: "PURCHASE_REPLACED_OR_STALE" },
};

export function playApplyResultCategory(
  result: unknown,
): PlayPurchaseRejectionCategory {
  switch (result) {
    case "STALE_TOKEN":
      return "STALE_TOKEN";
    case "STALE_LINKED_TOKEN":
      return "STALE_LINKED_TOKEN";
    case "STALE_EVENT":
      return "STALE_EVENT";
    case "STALE_OR_UNBOUND_TOKEN":
      return "STALE_OR_UNBOUND_TOKEN";
    default:
      return "UNEXPECTED_APPLY_RESULT";
  }
}

export function playPurchaseRejectionResponse(
  category: PlayPurchaseRejectionCategory,
  headers: HeadersInit,
  log: (
    message: string,
    details: { status: number; category: PlayPurchaseRejectionCategory },
  ) => void = (message, details) => console.warn(message, details),
): Response {
  const { status, error } = rejection[category];
  log("Play purchase verification rejected", { status, category });
  return new Response(JSON.stringify({ error }), { status, headers });
}
