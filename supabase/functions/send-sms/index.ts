import { Webhook } from "https://esm.sh/standardwebhooks@1.0.0";
import { buildOtpMessage, normalizePhilippinesPhone, toSemaphoreNumber, validateOtp } from "./phone.ts";
import { sendSemaphoreOtp } from "./semaphore.ts";

type HookPayload = {
  user?: { phone?: string };
  sms?: { otp?: string };
};

function jsonError(status: number, error: string): Response {
  return new Response(JSON.stringify({ error }), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function hookSecrets(): string[] {
  return (Deno.env.get("SEND_SMS_HOOK_SECRET") ?? "")
    .split("|")
    .map((secret) => secret.replace(/^v1,whsec_/, "").trim())
    .filter(Boolean);
}

function verifyPayload(rawPayload: string, headers: Headers): HookPayload {
  const secrets = hookSecrets();
  if (secrets.length === 0) throw new Error("hook_not_configured");

  const webhookHeaders = Object.fromEntries(headers.entries());
  for (const secret of secrets) {
    try {
      return new Webhook(secret).verify(rawPayload, webhookHeaders) as HookPayload;
    } catch {
      // A rotated secret may be configured. Try the next one without logging payload data.
    }
  }

  throw new Error("invalid_signature");
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return jsonError(405, "method_not_allowed");

  const semaphoreApiKey = Deno.env.get("SEMAPHORE_API_KEY")?.trim();
  const senderName = Deno.env.get("SEMAPHORE_SENDER_NAME")?.trim();
  if (!semaphoreApiKey || !senderName || hookSecrets().length === 0) {
    console.error("send_sms_not_configured");
    return jsonError(500, "sms_service_unavailable");
  }

  const rawPayload = await request.text();
  let payload: HookPayload;
  try {
    payload = verifyPayload(rawPayload, request.headers);
  } catch (error) {
    if (error instanceof Error && error.message === "hook_not_configured") {
      console.error("send_sms_hook_not_configured");
      return jsonError(500, "sms_service_unavailable");
    }
    return jsonError(401, "invalid_hook_signature");
  }

  let number: string;
  let otp: string;
  let message: string;
  try {
    number = toSemaphoreNumber(normalizePhilippinesPhone(payload.user?.phone ?? ""));
    otp = validateOtp(payload.sms?.otp ?? "");
    message = buildOtpMessage();
  } catch {
    return jsonError(400, "invalid_sms_request");
  }

  const providerBody = new URLSearchParams({
    apikey: semaphoreApiKey,
    number,
    message,
    code: otp,
    sendername: senderName,
  });

  const providerResult = await sendSemaphoreOtp(providerBody);
  if (!providerResult.ok) {
    if (providerResult.failure === "network") {
      console.error("semaphore_request_failed", { endpoint: providerResult.endpoint });
    } else if (providerResult.failure === "reported") {
      console.error("semaphore_reported_failure", {
        endpoint: providerResult.endpoint,
        status: providerResult.status,
      });
    } else {
      console.error("semaphore_rejected_request", {
        endpoint: providerResult.endpoint,
        status: providerResult.status,
      });
    }
    return jsonError(502, "sms_provider_unavailable");
  }

  if (providerResult.endpoint === "messages") {
    console.warn("semaphore_otp_fallback_sent");
  }

  // Return the explicit JSON success body expected by the Auth hook contract.
  // The SMS may already be queued even if Auth rejects an empty response, so
  // this response must be deliberate and machine-readable.
  return new Response(JSON.stringify({}), {
    status: 200,
    headers: { "content-type": "application/json" },
  });
});
