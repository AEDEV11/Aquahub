import { parseMarketingDispatchOptions } from "./marketing-dispatch-options.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(
      "expected " + String(expected) + ", received " + String(actual),
    );
  }
}

Deno.test("dispatcher defaults to a bounded queue claim", () => {
  const result = parseMarketingDispatchOptions({});
  assertEquals(result.valid, true);
  if (result.valid) {
    assertEquals(result.value.batchSize, 10);
    assertEquals(result.value.subscriberId, null);
  }
});

Deno.test("dispatcher targets exactly one requested subscriber", () => {
  const id = "12345678-1234-4234-8234-123456789abc";
  const result = parseMarketingDispatchOptions({
    subscriberId: id,
    batchSize: 20,
  });
  assertEquals(result.valid, true);
  if (result.valid) {
    assertEquals(result.value.batchSize, 1);
    assertEquals(result.value.subscriberId, id);
  }
});

Deno.test("dispatcher rejects an invalid targeted subscriber id", () => {
  assertEquals(
    parseMarketingDispatchOptions({ subscriberId: "not-a-uuid" }).valid,
    false,
  );
  assertEquals(parseMarketingDispatchOptions([]).valid, false);
});
