import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { readJsonBody } from "./request-body.ts";

Deno.test("bounded JSON reader accepts a request at its byte limit", async () => {
  const body = '{"ok":true}';
  const result = await readJsonBody(
    new Request("https://example.test", { method: "POST", body }),
    body.length,
  );
  assertEquals(result, { ok: true, value: { ok: true } });
});

Deno.test("bounded JSON reader rejects an oversized stream without Content-Length", async () => {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new TextEncoder().encode('{"value":"too large"}'));
      controller.close();
    },
  });
  const result = await readJsonBody(
    new Request("https://example.test", { method: "POST", body }),
    8,
  );
  assertEquals(result, { ok: false, reason: "too_large" });
});

Deno.test("bounded JSON reader rejects malformed JSON", async () => {
  const result = await readJsonBody(
    new Request("https://example.test", { method: "POST", body: "{" }),
    8,
  );
  assertEquals(result, { ok: false, reason: "invalid_json" });
});
