# Firebase class and dependency audit

Audit date: 2026-10-04.

The Android source trees contain no imports or dependencies for Firebase Auth,
Firestore, Functions, Storage, or App Check. The only Firebase Android SDK
surface left is Messaging, Analytics, and Crashlytics as explicitly allowed.

| Area | Current implementation | Result |
|---|---|---|
| Owner/customer auth/profile | Supabase Auth repositories and provisioning RPCs | Supabase-only |
| Catalog/customers/products/expenses | Supabase datasources + Room cache | Supabase-only writes |
| Staff administration | `staff-admin` Edge Function | Supabase-only writes |
| Settings | `update_station_settings` RPC | Supabase-only writes |
| Orders/delivery/payments | Supabase RPCs/datasources | Supabase runtime path |
| Offline sync | `SupabaseSyncCoordinator`/`SupabaseSyncWorker` | Supabase-only |
| Product/logo/QR storage | Supabase Storage + resolver | Supabase-only new writes |
| Account deletion | `delete-account` Edge Function | Supabase-only |
| Notifications | Supabase token registry/outbox + FCM transport | Allowed Firebase transport only |

Removed source/dependencies include the owner Firebase auth/Firestore/Storage
adapters, Firebase sync/listener classes, customer Firebase repositories,
Firebase Functions/App Check SDKs, and the active/inactive Firebase Functions
source packages.

Fields such as `firebase_id`, `firebase_uid`, and historical Room column names
are retained solely for non-destructive mapping. They do not authorize users,
create sessions, or provide a writable Firebase authority.

| SDK | Status | Purpose |
|---|---|---|
| `firebase-messaging` | retained | FCM transport/token refresh |
| `firebase-analytics` | retained | Analytics |
| `firebase-crashlytics` | retained | Crash reporting |
| Auth/Firestore/Functions/Storage/App Check | removed | No runtime use |

Static prohibited-SDK scans are clean. Authenticated/device migration gates are
not advanced by this source audit.
