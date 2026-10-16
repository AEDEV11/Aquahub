# Firebase dependency-removal manifest

Status: `FIREBASE_BUSINESS_RUNTIME_REMOVED_SOURCE_VERIFIED`  
Last reviewed: 2026-10-04

This manifest records the final source-level removal result. Firebase remains intentionally enabled only for FCM transport, Analytics, and Crashlytics. Google Play Billing is independent of Firebase. Historical Firestore data, Firebase Storage objects, rules, and exports were not deleted.

## Historical runtime dependency order

1. Finish authenticated/device validation and observation for each Supabase domain; this remains a product validation gate, not a reason to retain Firebase business SDKs in the source.
2. Retire migrated-domain Android writers and normal online reads; leave explicit historical import/read adapters only.
3. Retire non-order notification/business Functions after their Supabase replacements are live and observed.
4. Retire staff and subscription compatibility Functions after authenticated validation and caller observation.
5. Retire catalog/customer/payment/delivery/expense Firestore compatibility reads after import or scoped legacy-read evidence.
6. Retire the blocked order Functions and order listeners only after the order gate passes authenticated stock/idempotency/concurrency validation.
7. Remove Firebase Functions callable/client SDK usage from Android once no callable path remains.
8. Remove Firestore SDK/rules/indexes after no runtime or importer needs them.
9. Remove Firebase Storage SDK after the legacy `gs://` manifest/import and observation prove no active read remains.
10. Remove FirebaseAuth and the compatibility bridge after all Firebase business callers and token exchange paths are gone. This source-level step is complete as of 2026-10-04.

## Keep permanently for retained services

| Dependency | Keep reason | Boundary |
|---|---|---|
| `google-services.json` / Google services plugin | Firebase initialization for retained services | No business authority |
| Firebase Messaging | FCM transport | Tokens and recipient ownership are Supabase data |
| Firebase Analytics | telemetry | No customer/order payloads or credentials |
| Firebase Crashlytics | crash reporting | No tokens, OTPs, passwords, or secrets |

## Removal inventory

| Surface | Current files/area | Status | Prerequisite |
|---|---|---|---|
| FirebaseAuth | Android runtime | `REMOVED` | No active source dependency remains |
| Firestore | Android/runtime backend business path | `REMOVED_FROM_ACTIVE_SOURCE` | Historical data/rules retained; no active SDK or Firebase Functions package remains |
| Firebase Storage | Android runtime | `REMOVED_FROM_ACTIVE_SOURCE` | Historical objects retained; Supabase resolver/storage paths are active |
| Firebase Functions | Android/runtime backend business path | `REMOVED_FROM_ACTIVE_SOURCE` | Replaced by Supabase RPCs/Edge Functions; FCM sending remains Supabase-mediated |
| Firebase staff writes | Android runtime | `REMOVED` | Supabase `staff-admin` is the active writer |
| Firebase subscription writes | Android/runtime | `REMOVED` | Supabase subscription Edge Functions are active |

## Function responsibilities

- Order Functions remain deployed as legacy rollback/source compatibility after `ORDER_BACKEND_CUTOVER_VALIDATED`; `createCustomerOrder`, `cancelCustomerOrder`, and `mirrorCustomerOrderStatus` are classified `RUNTIME_UNUSED_OBSERVATION_PENDING`. They are removable only after runtime observation confirms zero normal callers and the legacy listener/device dependencies are retired.
- `staff-admin` owns new staff Auth/membership/station provisioning; old staff callables are source-only pending validation.
- `verify-play-purchase`, `subscription-trial`, and `play-rtdn` own the Supabase subscription path; old billing callables are compatibility-only.
- `dispatch-notification-outbox` and `send-fcm-notification` own the new server-side notification transport; FCM remains the transport, not the business store.

## Final source-removal evidence

Source verification completed: prohibited Firebase SDKs and the legacy Functions package are absent from both app/backend source trees; static scans and owner/customer builds passed. External authenticated/device validation gates remain documented separately and do not change the source audit result.
