import {
  marketingFingerprint,
  retryDelaySeconds,
  validateNewsletterPayload,
} from "./marketing.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(
      "expected " + String(expected) + ", received " + String(actual),
    );
  }
}
function assertMatches(actual: string, pattern: RegExp): void {
  if (!pattern.test(actual)) {
    throw new Error("value did not match " + String(pattern));
  }
}

Deno.test("newsletter validation requires valid email and explicit consent", () => {
  assertEquals(
    validateNewsletterPayload({ email: "bad", consent: true }).valid,
    false,
  );
  assertEquals(
    validateNewsletterPayload({ email: "owner@example.com" }).valid,
    false,
  );
  const valid = validateNewsletterPayload({
    email: "  Owner@Example.com ",
    firstName: "  Juan ",
    consent: true,
    website: "",
  });
  assertEquals(valid.valid, true);
  if (valid.valid) {
    assertEquals(valid.value.email, "owner@example.com");
    assertEquals(valid.value.firstName, "Juan");
  }
});

Deno.test("marketing rate-limit fingerprints are keyed and contain no email text", async () => {
  const fingerprint = await marketingFingerprint(
    "test-secret",
    "email",
    "Owner@example.com",
  );
  assertMatches(fingerprint, /^[a-f0-9]{64}$/);
  assertEquals(fingerprint.includes("owner"), false);
});

Deno.test("retry delay applies exponential backoff and honors Retry-After", () => {
  assertEquals(retryDelaySeconds(1, null, () => 0.5), 60);
  assertEquals(retryDelaySeconds(2, 300, () => 0.5), 300);
  assertEquals(retryDelaySeconds(20, 99_999, () => 0.5), 86_400);
});
