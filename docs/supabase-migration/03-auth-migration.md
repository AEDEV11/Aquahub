# Authentication migration

Status: `PARTIAL — verification-first schema deployed; provider configuration, bridge deployment, and physical-device validation pending`.

Current providers are Firebase email/password and Google sign-in for owner/customer flows. Owner staff accounts are provisioned through Firebase callable Functions using phone-alias credentials. No password hashes are copied in this phase.

## Implemented owner-app architecture

- `SupabaseAuthRepository` now owns the application login, signup, Google ID-token login, password reset, logout, session restoration, and reauthentication calls.
- The pinned `supabase-kt` `3.8.0` client uses `SUPABASE_URL` and `SUPABASE_ANON_KEY` from Gradle build configuration. No service-role key is read by Android.
- Supabase Auth and PostgREST are included. Owner onboarding now calls the hosted `provision_owner_workspace` RPC for the profile, organization, membership, station, and member-station boundary; Realtime, Storage, and Supabase Functions are not yet used by the Android business modules.
- The existing `AuthRepository`, ViewModels, onboarding flow, Room session state, legacy role lookup, and Firestore profile loading remain in place. Business Firestore modules have not been migrated.
- Supabase profiles are loaded after authentication. The client validates an existing `profiles.firebase_uid` mapping but does not write this protected field.
- The production Firebase export currently contains 20 `PHONE_ALIAS_PASSWORD` users and 4 `GOOGLE` users. The staging dry run classified all 24 as `MANUAL_REVIEW`; no Supabase Auth user was created. See `06-auth-user-staging.md`.
- New-account authentication is now designed as three separate methods: phone + OTP, email + password, and Google. New users do not use phone aliases.
- The verification-first database migration removes immediate profile creation and adds trusted provisioning; it is deployed and verified. The Firebase compatibility bridge is implemented in source; see `10-firebase-compatibility-bridge.md`.

## Existing Firebase-user transition

Firebase password hashes are not copied or assumed compatible. If a phone/password user has no Supabase account, the app first proves the existing Firebase credential, then claims the same deterministic phone alias in Supabase. The Firebase user is retained and is never deleted or duplicated. Supabase email confirmation must be disabled for these internal aliases, or an operator-managed recovery flow must be used.

Google users use the existing Google ID token with Supabase Auth and then establish the Firebase compatibility session. The Supabase Google provider must use the same web OAuth client ID as the Android Credential Manager flow.

## Hybrid Firebase compatibility session

Firestore, Firebase Storage, existing callable Functions, and the current FCM fan-out still require Firebase credentials. After a successful Supabase password or Google login, the app establishes a matching Firebase session solely for those unchanged paths. The Firebase UID remains the identifier used by old Firestore documents and is not replaced by the Supabase UUID. A populated `profiles.firebase_uid` must match the Firebase session; a null value falls back to the paired compatibility session during the backfill window.

Session restoration requires both the persisted Supabase session and Firebase's restored compatibility session. The current Supabase profile is loaded and its non-null mapping is checked before Firestore is touched. If Firebase cannot restore, the app clears the Supabase session instead of touching Firestore with an unpaired identity. This is intentionally temporary and must be replaced by a server-issued Firebase custom-token bridge before Firebase Auth can be removed entirely.

FCM tokens continue to be written to Firestore for current Functions and are also best-effort upserted to the designed Supabase `device_tokens` table. Logout removes both associations where available.

## Safe transition

1. Export Firebase Auth metadata only through an authorized Firebase export process; do not export or commit secrets/password hashes locally.
2. Confirm whether the export format and Supabase Auth import support the exact Firebase password-hash algorithm/parameters before considering hash import. The current export lacks the required project-level SCRYPT parameters, so no hash import is approved. Use credential proof/claim instead and preserve the Firebase UID in `profiles.firebase_uid`.
3. Build an explicit mapping report with `firebase_uid`, `supabase_uid`, normalized email, provider, and migration status. Deduplicate by verified provider identity, not display name or phone alone.
4. Keep Firebase Auth and FCM registration alive until all app sessions and notification paths have moved and rollback is no longer needed.

## Analytics, Crashlytics, and FCM

- Firebase Analytics remains enabled; Supabase user IDs identify login/signup events without logging credentials or tokens.
- Firebase Crashlytics remains enabled; the Supabase user ID is used as the non-sensitive Crashlytics identifier and cleared on logout.
- Firebase Cloud Messaging remains the notification transport. Existing Firestore token registration remains active, with a best-effort Supabase `device_tokens` upsert keyed by the Supabase profile ID.

## Manual hosted configuration

- Provide only the hosted URL and anon/publishable key through `AquaHub/local.properties` or the build environment.
- Release Gradle builds fail closed when either public value is missing; the current local checkout intentionally has neither value configured.
- Configure the Supabase Google provider with the existing web OAuth client ID and permitted redirect URLs.
- Configure password recovery redirect URLs.
- Configure the Supabase Phone provider/SMS delivery and email verification templates before enabling the new flows.
- Disable email confirmation for internal phone aliases, or hold the legacy-password claim path until staged recovery is available.
- Provision/backfill `profiles.firebase_uid` and authorization data through an authorized server/data-migration process; Android cannot update this protected field.

## Current unresolved migration prerequisite

Existing Firebase accounts cannot be assumed to have compatible passwords in Supabase. Firebase Auth export/import support for the exact deployed hash parameters has not been verified, so the safe production plan remains staged credential proof/claim or operator-managed recovery. Phone-alias password reset remains a hosted-configuration blocker because the alias is not a user mailbox.

The Android recovery path now uses the fixed redirect `aquahub://auth/recovery`, exchanges the hosted Supabase PKCE code, updates only the authenticated Supabase session, then signs out both Supabase and Firebase compatibility state. The next normal phone/password login re-establishes the Firebase compatibility session. The hosted dashboard URL allowlist and email provider remain manual configuration.

## Required checkpoint

Build validation passed for the registration/login code paths, logout/session wiring, profile loading, Analytics/Crashlytics retention, and FCM token wiring. Runtime provider configuration, password recovery, session refresh, and device-token checks remain required before Firestore feature migration.

The phone OTP/email verification flows and Firebase custom-token compatibility bridge are deployed. The owner-workspace provisioning migration has also been reviewed, dry-run, and applied to the hosted project. New registrations now write the owner/workspace boundary to Supabase before the temporary Firestore compatibility mirror; business screens remain gated by the still-Firebase-backed data/sync paths until those modules are migrated.
