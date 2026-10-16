# Semaphore SMS transport hook

Status: `IMPLEMENTED LOCALLY — HOSTED CONFIGURATION PENDING`

## Design

Supabase Auth remains the only authentication system. It creates and verifies the OTP, owns the session and JWT, and applies Auth rate limits and expiry. The Edge Function only transports the signed Send SMS Hook payload to Semaphore.

Flow:

`Android signInWith(OTP)` → `Supabase Auth` → signed `send-sms` hook → `Semaphore /api/v4/otp` → `Android verifyPhoneOtp`

The transport uses Semaphore's dedicated OTP endpoint. The hook passes Supabase's generated OTP through Semaphore's `code` parameter and uses `{otp}` in the message template, so Semaphore does not generate a second, mismatched code.

The function accepts only POST requests, verifies `SEND_SMS_HOOK_SECRET` using Standard Webhooks, validates the supplied Philippine phone number and six-digit OTP, converts E.164 to Semaphore's documented `639...` transport format, and sends a short single-purpose message. It checks an explicitly reported Semaphore `failed`/`refunded` status, but treats an accepted 2xx response with an empty or non-JSON body as success because the provider may already have queued the SMS. On success it returns an explicit JSON `{}` response to Supabase Auth. It never logs the request body, phone number, OTP, Semaphore API key, or webhook secret. Provider response bodies are not logged.

## Files

- `supabase/functions/send-sms/index.ts`: signed hook verification and Semaphore transport.
- `supabase/functions/send-sms/phone.ts`: Philippine E.164 normalization and OTP message validation.
- `supabase/functions/send-sms/index.test.ts`: normalization/message unit tests.
- `supabase/config.toml`: `send-sms` is configured with `verify_jwt = false`; the hook secret is the authentication mechanism.
- `AquaHub/app/src/main/.../OnboardingViewModel.kt`: 120-second client resend cooldown and duplicate-send guard, matching delayed carrier delivery and preventing late older codes from being used.

## Hosted setup (run only with real values in a secure shell)

```bash
supabase secrets set \
  SEMAPHORE_API_KEY='[set securely; do not commit]' \
  SEMAPHORE_SENDER_NAME='[approved sender name]' \
  SEND_SMS_HOOK_SECRET='v1,whsec_[generated secret]'

supabase functions deploy send-sms --no-verify-jwt
```

Generate the hook secret without committing it:

```bash
echo "v1,whsec_$(openssl rand -base64 32)"
```

In Supabase Dashboard → Authentication → Hooks, enable **Send SMS**, choose the deployed `send-sms` Edge Function, and configure the same webhook secret. Keep phone authentication enabled and automatic phone confirmation disabled so the hook runs for OTP delivery.

The Android app must continue to pass only the normalized phone to `signInWith(OTP)` and only the user-entered OTP to `verifyPhoneOtp`. It must not call Semaphore, hold provider credentials, manually verify OTPs, or create a second OTP table/system.

## Verification matrix

The disposable verification run must cover:

1. missing/invalid Standard Webhooks signature → rejected;
2. malformed or non-Philippine phone / non-six-digit OTP → rejected;
3. valid signed payload → one form-urlencoded Semaphore request;
4. provider failure or timeout → safe 5xx response without provider details;
5. repeated Auth OTP requests → Supabase Auth cooldown/rate limits plus the Android 30-second resend guard;
6. OTP verification → normal Supabase `verifyPhoneOtp`, session, profile and RLS path.

Before marking this transport complete, verify from hosted logs and a disposable phone/Auth identity that no secret/OTP values are logged, the Dashboard hook is active, and the SMS arrives. No local Supabase instance is required or used.

## Known blocker

The repository does not contain the Semaphore API key, sender name, or webhook secret, and the hosted Dashboard hook configuration cannot be inferred from source. Therefore the function is implemented and deployable, but hosted deployment and real SMS smoke testing remain pending until those values are configured securely.
