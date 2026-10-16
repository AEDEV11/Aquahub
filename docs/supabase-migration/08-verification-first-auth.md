# Verification-first authentication

Status: `IMPLEMENTED IN SCHEMA AND CLIENT; PROVIDER CONFIGURATION, BRIDGE DEPLOYMENT, AND DEVICE VALIDATION PENDING`.

## Three separate methods

- Phone: normalize to Philippine E.164, send Supabase SMS OTP, verify the OTP, then call trusted profile provisioning.
- Email/password: register a real email and password, require Supabase email confirmation, block unverified login, and use the existing PKCE recovery deep link for password reset.
- Google: authenticate through the configured Supabase Google provider and accept only the trusted provider session with confirmed email; never forge identities from an email string or Firebase `rawId`.

New users do not use the legacy phone-email alias. The alias remains only for JIT migration of legacy Firebase accounts.

## Environment readiness

Phone requires an enabled SMS provider and production rate-limit/CAPTCHA configuration. Email confirmation and recovery require the two Android redirects `aquahub://auth/confirm` and `aquahub://auth/recovery`; production email requires custom SMTP. Google uses the existing native Credential Manager ID-token path and therefore has no Android browser callback in this implementation. Exact Dashboard steps and non-secret deployment commands are recorded in `04-auth-runtime-validation.md`.

## Verification-first provisioning

The prepared migration `20261002043559_verification_first_auth.sql` removes the immediate `auth.users` insert trigger, removes client profile INSERT/DELETE policies, and adds `public.provision_verified_profile()`.

The trusted function derives state from `auth.users` and `auth.identities`:

- email requires `email_confirmed_at`;
- phone requires `phone_confirmed_at`;
- Google requires a Google identity plus confirmed email;
- role is always `CUSTOMER` for a new profile;
- `firebase_uid`, organization, station, membership, and subscription fields are never accepted from the client.

The function is `SECURITY DEFINER`, has a fixed search path, checks `auth.uid()`, and is executable only by authenticated/service roles. It is not yet deployed.

## Account states

The client model distinguishes `AUTH_PENDING_VERIFICATION`, `VERIFIED_NOT_PROVISIONED`, `ACTIVE`, `MIGRATION_REQUIRED`, `MANUAL_REVIEW`, and `DISABLED`. A Supabase `auth.users` row alone is not an active AquaHub account.

## Legacy Firebase compatibility

Existing Firebase users retain JIT migration. A legacy phone alias must first prove the Firebase credential and, where required, verify the real phone through Supabase OTP before linking `profiles.firebase_uid`. Legacy email users must prove the Firebase credential and complete Supabase email verification. Legacy Google users must authenticate through Supabase Google OAuth and be matched using trusted provider identity; email-only automatic merges are not allowed when ambiguous.

New Supabase phone OTP sessions do not yet have a Firebase custom-token bridge, so they must not enter Firebase-backed business screens until that compatibility boundary is implemented and device-tested.

## RLS behavior

Before migration 009 is deployed, the current trigger/RLS behavior remains the old model. After deployment, an unverified Auth user has no profile and therefore cannot read protected profile or tenant rows, create privileged state, self-assign roles, or join an organization. The hosted behavioral fixture suite must be extended with unverified-user cases after 009 is reviewed.

## Manual configuration and device testing

Configure Supabase Phone provider/SMS delivery, email provider/templates, Google provider, and the exact `aquahub://auth/recovery` redirect manually. Validate OTP expiry/resend/rate limits, verification gating, recovery, Google account matching, session restore, logout/account switching, FCM, Analytics, and Crashlytics on a physical device. No such device validation has been claimed yet.
