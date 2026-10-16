# Just-in-time Firebase authentication migration

Status: `DESIGNED — not device-validated`.

Legacy users remain on Firebase until they prove ownership through the existing credential flow. The migration then establishes the corresponding Supabase identity and requires trusted Supabase verification before profile provisioning and `profiles.firebase_uid` linking.

- Legacy phone aliases are temporary compatibility identifiers only. New phone users use real E.164 phone OTP.
- Legacy email users prove the Firebase credential, complete Supabase email verification, then receive a trusted profile link.
- Legacy Google users authenticate through Supabase Google OAuth. Provider identity is matched server-side; email-only ambiguous matches stop for manual review.
- The Android client never writes `firebase_uid`, role, provider ownership, organization, station, or subscription state.
- No Firebase user is deleted, and no Firestore document IDs or ownership fields are rewritten.

The current owner app still needs Firebase credentials for Firestore, Storage, callable Functions, FCM, and billing. A new Supabase-only OTP session therefore remains outside Firebase-backed business screens until a server-issued Firebase custom-token bridge is implemented.
