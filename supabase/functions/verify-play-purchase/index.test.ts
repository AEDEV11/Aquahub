import {
  canReplaceBoundPlayToken,
  decidePurchaseOrganizationClaim,
  ownerObfuscatedAccountId,
  ownerObfuscatedProfileId,
} from "../_shared/play-token-claim.ts";
import {
  playApplyResultCategory,
  playPurchaseRejectionResponse,
} from "../_shared/play-purchase-rejection.ts";

Deno.test("a fresh token binds to the Play account and organization profile", async () => {
  const ownerUid = "owner-uid";
  const ownerOrganizationIds = ["org-a", "org-b"];
  const purchaseObfuscatedAccountId = await ownerObfuscatedAccountId(ownerUid);
  const purchaseObfuscatedProfileId = await ownerObfuscatedProfileId(
    ownerUid,
    "org-b",
  );
  const claim = {
    ownerUid,
    ownerOrganizationIds,
    purchaseObfuscatedAccountId,
    purchaseObfuscatedProfileId,
  };
  assertEquals(
    (await decidePurchaseOrganizationClaim(claim)).organizationId,
    "org-b",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      requestedOrganizationId: "org-b",
    })).result,
    "CLAIM",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      requestedOrganizationId: "org-a",
    })).result,
    "ORGANIZATION_MISMATCH",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      purchaseObfuscatedAccountId: "different-account",
    })).result,
    "ACCOUNT_MISMATCH",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      purchaseObfuscatedProfileId: "different-profile",
    })).result,
    "ORGANIZATION_MISMATCH",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      requestedOrganizationId: "org-c",
    })).result,
    "OWNER_REQUIRED",
  );
});

Deno.test("bound token restore follows its original organization across preference changes", async () => {
  const claim = {
    ownerUid: "owner-uid",
    ownerOrganizationIds: ["org-a", "org-b"],
    existingOrganizationId: "org-a",
  };
  const result = await decidePurchaseOrganizationClaim(claim);
  assertEquals(result.result, "IDEMPOTENT");
  assertEquals(result.organizationId, "org-a");
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      requestedOrganizationId: "org-b",
    })).result,
    "ORGANIZATION_MISMATCH",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      ownerOrganizationIds: ["org-b"],
    })).result,
    "OWNER_REQUIRED",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      linkedOrganizationId: "org-b",
    })).result,
    "CONFLICT",
  );
});

Deno.test("linked replacement stays with its prior organization", async () => {
  const ownerUid = "owner-uid";
  const claim = {
    ownerUid,
    ownerOrganizationIds: ["org-a", "org-b"],
    linkedOrganizationId: "org-a",
    purchaseObfuscatedAccountId: await ownerObfuscatedAccountId(ownerUid),
  };
  assertEquals(
    (await decidePurchaseOrganizationClaim(claim)).organizationId,
    "org-a",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      requestedOrganizationId: "org-b",
    })).result,
    "ORGANIZATION_MISMATCH",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      purchaseObfuscatedProfileId: await ownerObfuscatedProfileId(
        ownerUid,
        "org-b",
      ),
    })).result,
    "ORGANIZATION_MISMATCH",
  );
});

Deno.test("verified Play Store renewal can restore a bound expired predecessor without legacy IDs", async () => {
  const ownerUid = "owner-uid";
  const claim = {
    ownerUid,
    ownerOrganizationIds: ["org-a", "org-b"],
    linkedOrganizationId: "org-a",
    verifiedOutOfAppPredecessor: true,
  };
  assertEquals((await decidePurchaseOrganizationClaim(claim)).organizationId, "org-a");
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      verifiedOutOfAppPredecessor: false,
    })).result,
    "ACCOUNT_MISMATCH",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      linkedOrganizationId: null,
    })).result,
    "ACCOUNT_MISMATCH",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      purchaseObfuscatedAccountId: "different-account",
    })).result,
    "ACCOUNT_MISMATCH",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      requestedOrganizationId: "org-b",
    })).result,
    "ORGANIZATION_MISMATCH",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      ownerOrganizationIds: ["org-b"],
    })).result,
    "OWNER_REQUIRED",
  );
});

Deno.test("legacy account-only token has exactly one unambiguous owner organization", async () => {
  const ownerUid = "owner-uid";
  const claim = {
    ownerUid,
    ownerOrganizationIds: ["org-a"],
    purchaseObfuscatedAccountId: await ownerObfuscatedAccountId(ownerUid),
  };
  assertEquals(
    (await decidePurchaseOrganizationClaim(claim)).organizationId,
    "org-a",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      ownerOrganizationIds: ["org-a", "org-b"],
    })).result,
    "ORGANIZATION_REQUIRED",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      ownerOrganizationIds: ["org-a", "org-b"],
      requestedOrganizationId: "org-a",
    })).result,
    "ORGANIZATION_REQUIRED",
  );
  assertEquals(
    (await decidePurchaseOrganizationClaim({
      ...claim,
      purchaseObfuscatedAccountId: undefined,
    })).result,
    "ACCOUNT_MISMATCH",
  );
});

Deno.test("replacement preflight permits only the current unsuperseded token of the selected owner organization", () => {
  const requestedOrganizationId = "org-a";
  assertEquals(
    canReplaceBoundPlayToken(
      requestedOrganizationId,
      true,
      "org-a",
      null,
      true,
    ),
    true,
  );
  assertEquals(
    canReplaceBoundPlayToken(
      requestedOrganizationId,
      false,
      "org-a",
      null,
      true,
    ),
    false,
  );
  assertEquals(
    canReplaceBoundPlayToken(
      requestedOrganizationId,
      true,
      "org-b",
      null,
      true,
    ),
    false,
  );
  assertEquals(
    canReplaceBoundPlayToken(requestedOrganizationId, true, null, null, true),
    false,
  );
  assertEquals(
    canReplaceBoundPlayToken(
      requestedOrganizationId,
      true,
      "org-a",
      "2026-10-10T00:00:00Z",
      true,
    ),
    false,
  );
  assertEquals(
    canReplaceBoundPlayToken(
      requestedOrganizationId,
      true,
      "org-a",
      null,
      false,
    ),
    false,
  );
});

Deno.test("owner and organization hashes match Android BillingFlowParams identifiers", async () => {
  assertEquals(
    await ownerObfuscatedAccountId("owner-uid"),
    "ba370780deb7a7b43fce188212310e814fd1dda767dcc443ec08e2019abd3032",
  );
  assertEquals(
    await ownerObfuscatedProfileId(
      "owner-uid",
      "11111111-1111-4111-8111-111111111111",
    ),
    "f2bd1086cea34b96656bfa49f45e40cd02786beb07944d441800c73d4d066ecf",
  );
});

Deno.test("authentication rejection returns 401 and logs only a safe result category", async () => {
  const entries: Array<{ message: string; details: Record<string, unknown> }> =
    [];
  const response = playPurchaseRejectionResponse(
    "AUTH_INVALID",
    { "content-type": "application/json" },
    (message, details) => entries.push({ message, details }),
  );
  assertEquals(response.status, 401);
  assertEquals((await response.json()).error, "UNAUTHORIZED");
  assertEquals(
    JSON.stringify(entries),
    JSON.stringify([
      {
        message: "Play purchase verification rejected",
        details: { status: 401, category: "AUTH_INVALID" },
      },
    ]),
  );
});

Deno.test("replacement preflight rejection exposes no token or binding details", async () => {
  const logged: Array<Record<string, unknown>> = [];
  const response = playPurchaseRejectionResponse(
    "REPLACEMENT_NOT_ALLOWED",
    { "content-type": "application/json" },
    (_message, details) => logged.push(details),
  );
  assertEquals(response.status, 409);
  assertEquals(
    (await response.json()).error,
    "PURCHASE_REPLACEMENT_NOT_ALLOWED",
  );
  assertEquals(
    JSON.stringify(logged),
    JSON.stringify([{ status: 409, category: "REPLACEMENT_NOT_ALLOWED" }]),
  );
});

Deno.test("stale and unexpected RPC results return sanitized 409 responses", async () => {
  const logged: Array<Record<string, unknown>> = [];
  const logger = (_message: string, details: Record<string, unknown>) =>
    logged.push(details);
  const stale = playPurchaseRejectionResponse(
    playApplyResultCategory("STALE_TOKEN"),
    {},
    logger,
  );
  assertEquals(stale.status, 409);
  assertEquals((await stale.json()).error, "PURCHASE_REPLACED_OR_STALE");

  const unexpected = playPurchaseRejectionResponse(
    playApplyResultCategory("untrusted-provider-body-with-synthetic-token"),
    {},
    logger,
  );
  assertEquals(unexpected.status, 409);
  assertEquals((await unexpected.json()).error, "PURCHASE_REPLACED_OR_STALE");
  assertEquals(
    JSON.stringify(logged),
    JSON.stringify([
      { status: 409, category: "STALE_TOKEN" },
      { status: 409, category: "UNEXPECTED_APPLY_RESULT" },
    ]),
  );
});

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(`Expected ${String(expected)}, got ${String(actual)}`);
  }
}
