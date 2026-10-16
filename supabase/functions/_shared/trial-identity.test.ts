import { legacyOwnerTrialUidHash, ownerTrialIdentityHashes } from "./trial-identity.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) throw new Error(`Expected ${String(expected)}; received ${String(actual)}`);
}

Deno.test("verified trial identities are normalized, keyed, and stable", async () => {
  const secret = "test-only-trial-claim-secret-at-least-32-characters";
  const first = await ownerTrialIdentityHashes({
    userId: "owner-1", email: "Owner@Example.com ", emailVerified: true,
    phone: "+63 (917) 123-4567", phoneVerified: true,
  }, secret);
  const normalized = await ownerTrialIdentityHashes({
    userId: "owner-2", email: "owner@example.com", emailVerified: true,
    phone: "+639171234567", phoneVerified: true,
  }, secret);
  assertEquals(first.length, 2);
  assertEquals(first.join(","), normalized.join(","));
  assertEquals(first.some((hash) => hash.includes("owner@example.com")), false);
});

Deno.test("unverified contacts do not become trial identity keys", async () => {
  const hashes = await ownerTrialIdentityHashes({
    userId: "owner-1", email: "owner@example.com", emailVerified: false,
    phone: "+639171234567", phoneVerified: false,
  }, "test-only-trial-claim-secret-at-least-32-characters");
  assertEquals(hashes.length, 0);
});

Deno.test("legacy UID hash remains deterministic for rollout compatibility", async () => {
  assertEquals(await legacyOwnerTrialUidHash("owner-1"), await legacyOwnerTrialUidHash("owner-1"));
});
