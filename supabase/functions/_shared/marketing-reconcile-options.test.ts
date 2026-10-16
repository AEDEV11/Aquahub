import { parseMarketingReconcileOptions } from "./marketing-reconcile-options.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(
      "expected " + String(expected) + ", received " + String(actual),
    );
  }
}

Deno.test("reconciler keeps empty scheduled requests on bulk mode", () => {
  const result = parseMarketingReconcileOptions({});
  assertEquals(result.valid, true);
  if (result.valid) assertEquals(result.value.mode, "bulk");
});

Deno.test("reconciler parses read-only diagnostics and one-contact scope", () => {
  const diagnostic = parseMarketingReconcileOptions({ mode: "diagnostic" });
  assertEquals(diagnostic.valid, true);
  if (diagnostic.valid) assertEquals(diagnostic.value.mode, "diagnostic");

  const target = parseMarketingReconcileOptions({
    mode: "single_contact",
    email: " Test@Example.com ",
  });
  assertEquals(target.valid, true);
  if (target.valid) {
    assertEquals(target.value.mode, "single_contact");
    if (target.value.mode === "single_contact") {
      assertEquals(target.value.email, "test@example.com");
    }
  }

  const contactStatus = parseMarketingReconcileOptions({
    mode: "contact_status",
    email: " Test@Example.com ",
  });
  assertEquals(contactStatus.valid, true);
  if (contactStatus.valid && contactStatus.value.mode === "contact_status") {
    assertEquals(contactStatus.value.email, "test@example.com");
  }
});

Deno.test("reconciler rejects malformed explicit scopes instead of falling back to bulk", () => {
  assertEquals(
    parseMarketingReconcileOptions({ mode: "single_contact", email: "bad" })
      .valid,
    false,
  );
  assertEquals(
    parseMarketingReconcileOptions({ mode: "unknown" }).valid,
    false,
  );
  assertEquals(parseMarketingReconcileOptions([]).valid, false);
});
