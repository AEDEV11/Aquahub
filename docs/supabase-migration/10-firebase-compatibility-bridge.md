# Firebase compatibility bridge

Status: `DEPLOYED AND SERVER-SMOKE-VALIDATED — CONTROLLED DEVICE TEST PENDING` (2026-10-03).

The Android app authenticates with hosted Supabase first, then sends the current Supabase access token to the HTTPS Firebase Function `issueFirebaseCompatibilityToken`. The trusted function validates that token with Supabase Auth, loads the matching `public.profiles` row using its server-only service-role credential, requires `account_state = ACTIVE` and a verified phone or email, and issues a Firebase Admin custom token.

Android signs into Firebase only with that returned custom token. No Firebase Admin key, Supabase service-role key, arbitrary UID, role, organization, or station identifier is shipped to Android or accepted from the request body.

## Security review

The HTTPS endpoint is intentionally callable without a Firebase session because the Supabase bearer token is its authentication input. It rejects non-POST requests and missing/invalid tokens. The server derives the Supabase UID from `/auth/v1/user`, never from request data, and performs all profile and UID ownership checks with the server credential. Only active, verified profiles proceed. Existing mappings are reverse-checked; new compatibility UIDs are deterministic and collision-checked before persistence. The custom token is created only after the mapping is validated. Responses contain only generic error codes, and the implementation has no token/password/secret logging.

The endpoint is deployed in `asia-southeast1` at `https://asia-southeast1-aquahub-506411.cloudfunctions.net/issueFirebaseCompatibilityToken` (Cloud Run URL: `https://issuefirebasecompatibilitytoken-5clsgz3z2q-as.a.run.app`). Both `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` are bound through Firebase Secret Manager; the corrected URL is `https://pfkxrggexueltytxtqil.supabase.co` and the service-role secret is bound by version, without documenting its value. This hosted project requires the legacy JWT-form `service_role` key for privileged Auth/PostgREST calls; it is sent only by the function runtime as the backend credential. The caller's Supabase JWT remains separate and is sent only in the user `Authorization: Bearer` header. The Gen 2 runtime service account also has the narrowly scoped self `Service Account Token Creator` grant required by Firebase Admin custom-token signing. No startup/configuration error was observed. Rate limiting/abuse protection should be monitored because the endpoint is public at the HTTP layer.

## UID mapping

- Existing mapped profile: reuse `profiles.firebase_uid` after reverse-ownership validation.
- New verified profile: create deterministic Firebase UID `supabase_<Supabase UUID without punctuation>` with Firebase Admin, persist it server-side in `profiles.firebase_uid`, and issue the token.
- If the UID is already linked to another profile, the bridge rejects the request.
- Repeated requests reuse the same profile mapping and Firebase UID.

## Disposable bridge smoke result

The 2026-10-03 disposable matrix passed: missing/malformed/invalid bearer input returned `401`; unprovisioned and unverified profiles returned `403`; an active verified profile returned a Firebase custom token; the repeated request returned `200` with the exact same `profiles.firebase_uid`; the cross-owner mapping attempt was rejected by the profile uniqueness constraint with `409`; Firebase custom-token exchange and account lookup succeeded with exactly one compatibility account per disposable user. Cleanup verified zero disposable Supabase Auth users, zero disposable profiles, and zero `supabase_` Firebase compatibility users.

## Required configuration

Configure these as Firebase Functions secrets; never commit their values:

- `SUPABASE_URL`: hosted project URL.
- `SUPABASE_SERVICE_ROLE_KEY`: hosted project service-role key, used only by the function runtime.

Deploy the active `AquaHubCustomer/functions` package after setting the secrets. Android uses the public function URL through `FIREBASE_COMPATIBILITY_BRIDGE_URL`; it contains no credential.

## Session and device validation

The app treats a login as usable only after both sessions exist. Restore attempts the bridge if Firebase has not restored locally. Logout signs out both providers and clears the local account/session cache. After provider configuration and function deployment, test verified phone OTP, verified email/password, Google with verified email, invalid/expired token, unverified/disabled denial, first-time UID creation, repeated exchange idempotency, account switching, logout, offline bridge failure, Firestore access gating, and FCM registration.
