# Client version compatibility

Snapshot: 2026-10-09. Released binary provenance and current production backend are **NOT VERIFIED**. Do not declare a released client compatible based on current source.

## Version/source comparison

| App | Current dirty checkout | Device evidence | Play Closed Testing provenance |
|---|---|---|---|
| Owner | `com.aesprt.aquahub`, code 12, `aq-1.0.7`; current source uses Supabase business/auth and Firebase messaging/analytics/crash reporting | Owner package is installed in Secure Folder user 150; shell could not inspect it | BLOCKED — artifact hash/version/source endpoint not independently established |
| Customer | `com.aesprt.aquahub_customer`, code 3, `aqc-1.0.2`; current source uses Supabase business/auth and Firebase messaging | user 0 reports code 3, `aqc-1.0.2`, installer `null`, same-day install | BLOCKED — local install is not proof of Play Closed Testing provenance |

The checked-out apps are separate Android Git repositories with extensive dirty changes. The backend folder/docs have no Git history in either repository. Do not use those dirty sources as a release manifest until commits and artifact hashes exist.

## Backend contract differences and risk

- Current Owner uses guarded order/payment/refund RPCs, server-authoritative purchase entitlement, Room cache/outbox, and new report RPC. Group 1–4 migrations are not in linked migration history.
- Current Customer uses Supabase order creation, order reads, cancellation, and masked refund status. Previous released Firebase-auth/rules/Functions paths remain a compatibility question; the Customer Firebase deployment source declared by configuration is absent from this dirty checkout.
- Group 1 removes broad direct DML on order/payment tables. Existing app versions that directly update these tables can fail after privilege tightening. Do not restore direct grants. First capture released APKs and trace every old-client call, then either stage versioned secure RPC compatibility or define a minimum supported version and controlled rollout.
- New RPCs, including refund workflows, refund obligation reporting, guest order APIs, plan-entitlement guards and subscription service RPCs, may not exist in the linked environment. Verify exact signatures, grants and function bodies in a restorable staging clone before app rollout.
- Firebase Messaging may remain a deliberate transport even if Supabase owns business state. Firebase Hosting, FCM, Firebase Auth/Firestore and Functions are separate authority questions; do not infer one from another.

## Required compatibility test

For each provenance-confirmed released Owner and Customer version plus the candidate builds, use synthetic staging users and test sign-in/session restoration, list/read, order creation, status updates, delivery/rider proof, receipt/payment, cancellation/refund visibility, inventory, notifications, realtime refresh, offline queue replay, and account switching. Capture supported server version ranges and retire old write paths only after telemetry/usage and rollout policy are reviewed. Hosted test: **BLOCKED**.
