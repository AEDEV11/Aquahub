# Firebase runtime removal checkpoint

Checkpoint: 2026-10-04. This is a source/configuration audit; it does not
claim that pending physical-device or authenticated validation gates passed.

## Final runtime boundary

Firebase runtime usage in the Android apps is limited to Firebase Cloud
Messaging transport/token refresh, Firebase Analytics, and Firebase
Crashlytics. Firebase Auth, Firestore, callable Functions, Storage, and App
Check were removed from both Android dependency graphs and source callers.

Legacy `firebase_id`/`firebase_uid` database columns and Room fields remain as
historical mapping metadata. They are not authentication, authorization, or
runtime backend authorities.

## Supabase replacements

| Former Firebase surface | Supabase replacement |
|---|---|
| Auth and profiles | Supabase Auth + `profiles` + provisioning RPCs |
| Staff administration | `staff-admin` Edge Function |
| Station/business settings | `update_station_settings` RPC |
| Account deletion | `delete-account` Edge Function |
| Catalog and projections | Supabase tables/views/triggers + repositories |
| Orders and stock | Supabase RPCs; legacy source data retained |
| Payments/subscriptions | Supabase tables/RPCs/Edge Functions |
| Delivery assignment/status | `assign_delivery` and `transition_delivery_status` |
| Expenses | Supabase datasource/table/RLS |
| Product/logo/QR assets | Supabase Storage + `StorageAssetResolver` |
| Offline reconciliation | `SupabaseSyncWorker` and Room outbox |
| Notifications | Supabase outbox → Edge Function → FCM HTTP v1 |

Every new business write is Supabase-only. Supabase authorization failures
(401/403, SQLSTATE 42501, tenant mismatch, invalid role/station) never trigger
a Firebase retry. Room is a cache/outbox, not a second cloud authority.

## Remaining allowed Firebase files

- `AquaHub/.../data/firebase/messaging/AquaHubMessagingService.kt`
- `AquaHubCustomer/.../data/CustomerMessagingService.kt`
- Owner Firebase Analytics/Crashlytics providers.
- Google Services configuration required by retained platform SDKs.

The Firebase Functions package and inactive scaffold are no longer active or
configured by `AquaHubCustomer/firebase.json`. No Firebase source data,
Firestore rows, or Storage objects were deleted.

## Validation status

Static scans are clean for prohibited SDK/API patterns in both Android app
source trees. Gradle validation is run independently for both apps. Pending
authenticated/device gates remain unchanged until their required evidence is
available.
