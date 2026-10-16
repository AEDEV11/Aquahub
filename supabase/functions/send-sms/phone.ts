const PHILIPPINES_PHONE = /^\+639\d{9}$/;

/** Converts supported Philippine inputs to the E.164 form expected by Semaphore. */
export function normalizePhilippinesPhone(input: string): string {
  const value = input.trim().replace(/[\s().-]/g, "");
  const digits = value.startsWith("+") ? value.slice(1) : value;

  const normalized = digits.startsWith("09")
    ? `+63${digits.slice(1)}`
    : `+${digits}`;

  if (!PHILIPPINES_PHONE.test(normalized)) {
    throw new Error("invalid_phone");
  }

  return normalized;
}

/** Semaphore's API examples use the same country code without a leading '+'. */
export function toSemaphoreNumber(e164Phone: string): string {
  return normalizePhilippinesPhone(e164Phone).slice(1);
}

export function validateOtp(otp: string): string {
  if (!/^\d{6}$/.test(otp)) {
    throw new Error("invalid_otp");
  }

  return otp;
}

export function buildOtpMessage(): string {
  return "AquaHub verification code: {otp}. It expires soon. Do not share it.";
}
