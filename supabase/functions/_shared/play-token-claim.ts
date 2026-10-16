export type PurchaseOrganizationDecision =
  | { result: "CLAIM" | "IDEMPOTENT"; organizationId: string }
  | {
    result:
      | "ACCOUNT_MISMATCH"
      | "ORGANIZATION_MISMATCH"
      | "ORGANIZATION_REQUIRED"
      | "OWNER_REQUIRED"
      | "CONFLICT";
    organizationId?: never;
  };

export type PurchaseOrganizationClaim = {
  ownerUid: string;
  ownerOrganizationIds: string[];
  existingOrganizationId?: string | null;
  linkedOrganizationId?: string | null;
  requestedOrganizationId?: string | null;
  purchaseObfuscatedAccountId?: string;
  purchaseObfuscatedProfileId?: string;
  verifiedOutOfAppPredecessor?: boolean;
};

export function canReplaceBoundPlayToken(
  requestedOrganizationId: string,
  activeOwner: boolean,
  boundOrganizationId: string | null | undefined,
  supersededAt: string | null | undefined,
  isCurrentPlaySubscription: boolean,
): boolean {
  return activeOwner && boundOrganizationId === requestedOrganizationId &&
    !supersededAt && isCurrentPlaySubscription;
}

export async function decidePurchaseOrganizationClaim(
  claim: PurchaseOrganizationClaim,
): Promise<PurchaseOrganizationDecision> {
  const ownerOrganizations = new Set(claim.ownerOrganizationIds);
  const existingOrganizationId = claim.existingOrganizationId;
  const linkedOrganizationId = claim.linkedOrganizationId;
  const requestedOrganizationId = claim.requestedOrganizationId;

  // A previously committed binding is authoritative when restoring a token.
  // Older purchases did not carry a Play profile identifier.
  if (existingOrganizationId) {
    if (
      linkedOrganizationId && linkedOrganizationId !== existingOrganizationId
    ) return { result: "CONFLICT" };
    if (!ownerOrganizations.has(existingOrganizationId)) {
      return { result: "OWNER_REQUIRED" };
    }
    if (
      requestedOrganizationId &&
      requestedOrganizationId !== existingOrganizationId
    ) {
      return { result: "ORGANIZATION_MISMATCH" };
    }
    return { result: "IDEMPOTENT", organizationId: existingOrganizationId };
  }

  if (!ownerOrganizations.size) return { result: "OWNER_REQUIRED" };
  const trustedExpiredPredecessor = claim.verifiedOutOfAppPredecessor === true &&
    Boolean(linkedOrganizationId);
  if (
    (!claim.purchaseObfuscatedAccountId && !trustedExpiredPredecessor) ||
    (claim.purchaseObfuscatedAccountId && claim.purchaseObfuscatedAccountId !==
      await ownerObfuscatedAccountId(claim.ownerUid))
  ) {
    return { result: "ACCOUNT_MISMATCH" };
  }

  // Play's linked token establishes the organization for a new replacement
  // token even after the owner has switched their preferred business.
  if (linkedOrganizationId) {
    if (!ownerOrganizations.has(linkedOrganizationId)) {
      return { result: "OWNER_REQUIRED" };
    }
    if (
      requestedOrganizationId &&
      requestedOrganizationId !== linkedOrganizationId
    ) {
      return { result: "ORGANIZATION_MISMATCH" };
    }
    if (
      claim.purchaseObfuscatedProfileId &&
      claim.purchaseObfuscatedProfileId !==
        await ownerObfuscatedProfileId(claim.ownerUid, linkedOrganizationId)
    ) {
      return { result: "ORGANIZATION_MISMATCH" };
    }
    return { result: "CLAIM", organizationId: linkedOrganizationId };
  }

  if (
    requestedOrganizationId && !ownerOrganizations.has(requestedOrganizationId)
  ) return { result: "OWNER_REQUIRED" };
  if (claim.purchaseObfuscatedProfileId) {
    const matches: string[] = [];
    for (const organizationId of ownerOrganizations) {
      if (
        claim.purchaseObfuscatedProfileId ===
          await ownerObfuscatedProfileId(claim.ownerUid, organizationId)
      ) {
        matches.push(organizationId);
      }
    }
    if (
      matches.length !== 1 ||
      (requestedOrganizationId && requestedOrganizationId !== matches[0])
    ) {
      return { result: "ORGANIZATION_MISMATCH" };
    }
    return { result: "CLAIM", organizationId: matches[0] };
  }

  // Legacy tokens without an organization-scoped profile can be claimed only
  // when the authenticated owner has exactly one possible destination.
  if (ownerOrganizations.size !== 1) return { result: "ORGANIZATION_REQUIRED" };
  const [organizationId] = ownerOrganizations;
  if (requestedOrganizationId && requestedOrganizationId !== organizationId) {
    return { result: "ORGANIZATION_MISMATCH" };
  }
  return { result: "CLAIM", organizationId };
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return [...new Uint8Array(digest)].map((byte) =>
    byte.toString(16).padStart(2, "0")
  ).join("");
}

export function ownerObfuscatedAccountId(ownerUid: string): Promise<string> {
  return sha256Hex(`aquahub-owner-v1:${ownerUid}`);
}

export function ownerObfuscatedProfileId(
  ownerUid: string,
  organizationId: string,
): Promise<string> {
  return sha256Hex(`aquahub-org-v1:${ownerUid}:${organizationId}`);
}
