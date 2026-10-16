import { buildOtpMessage, normalizePhilippinesPhone, toSemaphoreNumber, validateOtp } from "./phone.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) throw new Error(`Expected ${String(expected)}, got ${String(actual)}`);
}

function assertThrows(operation: () => unknown): void {
  try {
    operation();
  } catch {
    return;
  }
  throw new Error("Expected operation to throw");
}

Deno.test("normalizes local Philippine mobile numbers", () => {
  assertEquals(normalizePhilippinesPhone("0917 123 4567"), "+639171234567");
});

Deno.test("keeps E.164 Philippine numbers stable", () => {
  assertEquals(normalizePhilippinesPhone("+639171234567"), "+639171234567");
  assertEquals(normalizePhilippinesPhone("639171234567"), "+639171234567");
});

Deno.test("converts E.164 numbers to Semaphore's documented format", () => {
  assertEquals(toSemaphoreNumber("+639171234567"), "639171234567");
});

Deno.test("rejects non-Philippine or malformed numbers", () => {
  assertThrows(() => normalizePhilippinesPhone("0917123456"));
  assertThrows(() => normalizePhilippinesPhone("+14155552671"));
});

Deno.test("builds only six-digit OTP messages", () => {
  assertEquals(validateOtp("123456"), "123456");
  assertEquals(buildOtpMessage(), "AquaHub verification code: {otp}. It expires soon. Do not share it.");
  assertThrows(() => validateOtp("12345"));
});
