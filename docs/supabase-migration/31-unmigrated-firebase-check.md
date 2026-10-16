# Unmigrated Firebase check

Audit date: 2026-10-04.

## Prohibited runtime surface

No production Android matches remain for `FirebaseAuth`, `FirebaseFirestore`,
`FirebaseFunctions`, `FirebaseStorage`, `FirebaseAppCheck`, or their Gradle
artifacts. App Check initialization is removed from both applications.

## Allowed runtime surface

The remaining Firebase references are intentional: the two
`FirebaseMessagingService` implementations, the owner Analytics and
Crashlytics providers, the FCM manifest event action, and Google Services
configuration required by those platform SDKs.

## Backend replacements

| Former responsibility | Supabase target |
|---|---|
| Staff create/update/delete | `staff-admin` |
| Account deletion | `delete-account` |
| Trial/entitlements | `subscription-trial` and SQL entitlement functions |
| Play purchase verification/RTDN | `verify-play-purchase` and `play-rtdn` |
| Delivery notifications | notification outbox + dispatcher |
| FCM sending | `send-fcm-notification` |
| Customer/order operations | Supabase order RPCs and repositories |
| Public station/product projections | Supabase projection triggers/views |

The Firebase Functions package and inactive scaffold are removed from the
workspace runtime configuration. Firestore data and Storage objects remain
untouched for historical export/import purposes.

`firebase_id` and `firebase_uid` columns remain only for historical mappings;
they do not represent an active Firebase session or writable Firebase backend.
This source audit does not advance any authenticated/device gate.
