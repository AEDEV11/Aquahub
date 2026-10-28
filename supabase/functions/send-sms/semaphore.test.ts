import { sendSemaphoreOtp } from "./semaphore.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(`Expected ${String(expected)}, got ${String(actual)}`);
  }
}

function requestBody(): URLSearchParams {
  return new URLSearchParams({
    apikey: "test-key",
    number: "639171234567",
    message: "AquaHub verification code: {otp}.",
    code: "123456",
    sendername: "AquaHub",
  });
}

Deno.test("falls back to the regular messages route after an OTP endpoint 5xx", async () => {
  const requests: Array<{ url: string; body: URLSearchParams }> = [];
  const fetcher: typeof fetch = async (input, init) => {
    requests.push({
      url: String(input),
      body: new URLSearchParams(String(init?.body ?? "")),
    });
    return requests.length === 1
      ? new Response("provider error", { status: 500 })
      : new Response('[{"status":"Queued"}]', { status: 200 });
  };

  const result = await sendSemaphoreOtp(requestBody(), fetcher);

  assertEquals(result.ok, true);
  if (!result.ok) throw new Error("Expected fallback send to succeed");
  assertEquals(result.endpoint, "messages");
  assertEquals(requests.length, 2);
  assertEquals(requests[0].url, "https://api.semaphore.co/api/v4/otp");
  assertEquals(requests[1].url, "https://api.semaphore.co/api/v4/messages");
  assertEquals(
    requests[1].body.get("message"),
    "AquaHub verification code: 123456.",
  );
  assertEquals(requests[1].body.has("code"), false);
});

Deno.test("does not use fallback after a client error", async () => {
  let requestCount = 0;
  const fetcher: typeof fetch = async () => {
    requestCount++;
    return new Response("provider error", { status: 400 });
  };

  const result = await sendSemaphoreOtp(requestBody(), fetcher);

  assertEquals(requestCount, 1);
  assertEquals(result.ok, false);
  if (result.ok) throw new Error("Expected the provider error to be returned");
  assertEquals(result.failure, "http");
  assertEquals(result.endpoint, "otp");
  if (result.failure !== "http") throw new Error("Expected an HTTP failure");
  assertEquals(result.status, 400);
});

Deno.test("returns a sanitized failure when both provider routes fail", async () => {
  let requestCount = 0;
  const fetcher: typeof fetch = async () => {
    requestCount++;
    return new Response("provider error", { status: 500 });
  };

  const result = await sendSemaphoreOtp(requestBody(), fetcher);

  assertEquals(requestCount, 2);
  assertEquals(result.ok, false);
  if (result.ok) throw new Error("Expected the provider fallback to fail");
  assertEquals(result.failure, "http");
  assertEquals(result.endpoint, "messages");
  if (result.failure !== "http") throw new Error("Expected an HTTP failure");
  assertEquals(result.status, 500);
});
