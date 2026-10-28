const SEMAPHORE_OTP_URL = "https://api.semaphore.co/api/v4/otp";
const SEMAPHORE_MESSAGES_URL = "https://api.semaphore.co/api/v4/messages";

type SemaphoreEndpoint = "otp" | "messages";

export type SemaphoreSendResult =
  | { ok: true; endpoint: SemaphoreEndpoint }
  | { ok: false; failure: "network"; endpoint: SemaphoreEndpoint }
  | { ok: false; failure: "http"; endpoint: SemaphoreEndpoint; status: number }
  | {
    ok: false;
    failure: "reported";
    endpoint: SemaphoreEndpoint;
    status: number;
  };

const formHeaders = { "content-type": "application/x-www-form-urlencoded" };

function formBody(
  body: URLSearchParams,
  endpoint: SemaphoreEndpoint,
): URLSearchParams {
  const copy = new URLSearchParams(body);
  if (endpoint === "messages") {
    const otp = copy.get("code") ?? "";
    copy.set("message", (copy.get("message") ?? "").replace("{otp}", otp));
    // The regular messages endpoint does not need Semaphore to generate a code;
    // Supabase Auth remains the sole OTP authority.
    copy.delete("code");
  }
  return copy;
}

async function isAccepted(response: Response): Promise<boolean> {
  if (!response.ok) return false;
  try {
    const body = await response.text();
    if (!body.trim()) return true;
    const result: unknown = JSON.parse(body);
    const messages = Array.isArray(result) ? result : [result];
    return !messages.some((entry) => {
      if (!entry || typeof entry !== "object") return false;
      const status = (entry as { status?: unknown }).status;
      return typeof status === "string" &&
        ["failed", "refunded"].includes(status.toLowerCase());
    });
  } catch {
    // A 2xx provider response may already have queued the SMS; do not reject it
    // because its response body is empty or not JSON.
    return true;
  }
}

async function post(
  endpoint: SemaphoreEndpoint,
  body: URLSearchParams,
  fetcher: typeof fetch,
): Promise<Response | null> {
  try {
    return await fetcher(
      endpoint === "otp" ? SEMAPHORE_OTP_URL : SEMAPHORE_MESSAGES_URL,
      {
        method: "POST",
        headers: formHeaders,
        body: formBody(body, endpoint),
      },
    );
  } catch {
    return null;
  }
}

/** Sends Supabase's OTP, falling back to the regular SMS route only on OTP-route 5xx. */
export async function sendSemaphoreOtp(
  body: URLSearchParams,
  fetcher: typeof fetch = fetch,
): Promise<SemaphoreSendResult> {
  const otpResponse = await post("otp", body, fetcher);
  if (!otpResponse) return { ok: false, failure: "network", endpoint: "otp" };

  let response = otpResponse;
  let endpoint: SemaphoreEndpoint = "otp";
  if (otpResponse.status >= 500) {
    endpoint = "messages";
    const fallbackResponse = await post("messages", body, fetcher);
    if (!fallbackResponse) return { ok: false, failure: "network", endpoint };
    response = fallbackResponse;
  }

  if (!response.ok) {
    return { ok: false, failure: "http", endpoint, status: response.status };
  }
  if (!await isAccepted(response)) {
    return {
      ok: false,
      failure: "reported",
      endpoint,
      status: response.status,
    };
  }
  return { ok: true, endpoint };
}
