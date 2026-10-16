# Controlled authentication validation

Status: `DEVICE HARNESS READY — provider and compatibility flows pending controlled account testing` (2026-10-03).

Use one disposable controlled account at a time. Do not use a production customer or migrate business data.

## Preflight

1. Enable Supabase Phone, Email/password, and Google providers.
2. Add `aquahub://auth/recovery` and `aquahub://auth/confirm` to Supabase Auth redirect URLs.
3. Configure production SMTP and a supported SMS provider for the +63 region.
4. Create the Firebase secrets and deploy only `issueFirebaseCompatibilityToken`. The deployed binding uses the corrected hosted URL and the legacy JWT-form `service_role` key required by this project's privileged REST/Auth path.
5. Confirm the endpoint region is `asia-southeast1` and inspect logs for startup/configuration errors without printing secrets.
6. Build the debug APK with only `SUPABASE_URL`, `SUPABASE_ANON_KEY`, and the public bridge URL. Confirm no service-role key exists in source, Gradle configuration, or the APK. A release build currently fails closed because those two public Supabase build values are not configured in this environment.

## Physical-device checkpoint

Device: Samsung `SM-A366B`, Android 16. The debug APK was rebuilt after adding the official Firebase Crashlytics Gradle plugin (`com.google.firebase.crashlytics` version `3.0.8`), installed, and launched. The prior startup crash (`Crashlytics build ID is missing`) is resolved; the app reached the AquaHub sign-in screen and no new fatal exception was observed. No real phone number, email, password, OTP, Google token, or user identity was entered by automation.

## Server smoke matrix

Run against disposable verified identities only:

| Case | Expected result |
| --- | --- |
| Missing Authorization header | HTTP 401 |
| Malformed Bearer header | HTTP 401 |
| Invalid/expired Supabase JWT | HTTP 401 |
| Valid user without profile | HTTP 403 |
| Valid user with unverified/inactive profile | HTTP 403 |
| Valid active verified profile | HTTP 200, Firebase custom token and mapped UID |
| Repeated valid request | HTTP 200, same Firebase UID, no duplicate Firebase user |
| Existing UID linked elsewhere | HTTP 409 at ownership/uniqueness boundary; no reassignment |
| Unexpected internal failure | HTTP 500 with generic error only |

Delete temporary Auth/Firebase identities and profile rows after the test.

## Device matrix

- Phone OTP: test Philippine normalization, wrong OTP, correct OTP, post-verification provisioning, bridge state sequence, Firestore access, restart, and logout.
- Email: test signup, denial before confirmation, confirmation deep link, provisioning, bridge, restart, recovery at `aquahub://auth/recovery`, and logout.
- Google: test native Credential Manager, confirmed identity, one profile/UID mapping, restart, and logout/re-login.
- Account switching: verify Account A's Supabase session, Firebase UID, station/org cache, role, and FCM binding are absent before Account B becomes ready.
- Firebase features: Firestore, Storage where used, callable Functions, billing, FCM delivery, Analytics login events, and Crashlytics user-id clearing.

## Gate

The deployment verification passed for function name, `asia-southeast1` region, endpoint, both secret-version attachments, startup probe, and absence of startup/configuration errors. The complete server matrix passed, including custom-token exchange, deterministic UID mapping, repeat idempotency, cross-owner conflict rejection, exact Firebase account count, and cleanup. Android contract review confirmed it sends only the Supabase bearer token, never trusts or sends `firebase_uid`/role/org/station inputs, calls `signInWithCustomToken()`, transitions `AUTHENTICATED_SUPABASE → WAITING_FOR_FIREBASE_BRIDGE → READY`, and blocks business access on `FAILED_COMPATIBILITY`. The physical device now passes install/startup/sign-in-screen smoke after the Crashlytics plugin fix. Provider flows and all authenticated compatibility checks remain pending controlled user interaction; credentials and tokens must not be sent to the agent. Current classification is `READY_FOR_MORE_DEVICE_TESTING`; do not use `AUTH_FOUNDATION_READY` until the complete device matrix passes.
