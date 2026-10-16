# Functions migration inventory

Status: `AUDITED — REPLACEMENTS NOT IMPLEMENTED`.

| Firebase function | Trigger/purpose | Proposed replacement |
|---|---|---|
| `listStaffAccounts` | callable staff listing | Edge Function or authenticated RPC; membership/plan checks server-side |
| `createStaffAccount` | callable Auth user + member creation | Edge Function; Supabase Auth admin operation and transactional membership setup |
| `updateStaffAccount` | callable staff/member update | Edge Function/RPC with role and station checks |
| `deleteStaffAccount` | callable staff deactivation | Edge Function; revoke sessions and deactivate member/rider |
| `ensureOwnerTrial` | callable trial provisioning and identity claim | PostgreSQL transaction + Edge Function for identity claim hashing |
| `checkOwnerTrialEligibility` | callable eligibility lookup | RLS-safe RPC or Edge Function |
| `verifyPlaySubscription` | callable Google Play verification | Edge Function; external Android Publisher API + database transaction |
| `playSubscriptionNotifications` | Pub/Sub billing event | Edge Function/webhook or retained server integration; idempotent event table |
| `createCustomerOrder` | callable authoritative order, pricing, stock, idempotency, notifications | PostgreSQL transaction/RPC for data invariants plus Edge Function for FCM notification |
| `setCustomerPreferredStation` | callable profile/station acquisition link | authenticated RPC with public-station validation |
| `cancelCustomerOrder` | callable status transition/stock restoration | PostgreSQL transaction/RPC |
| `mirrorCustomerOrderStatus` | Firestore document-write trigger + FCM | Postgres trigger/webhook + Edge Function; verify Realtime/customer projection strategy |
| `publishPublicStation` | document-write sanitized projection | Postgres trigger/view or server projection job |
| `publishPublicProduct` | document-write sanitized product projection | Postgres trigger/view or server projection job |
| `syncPublicStationsForSubscription` | subscription change fan-out projection | Postgres trigger/job |
| `deleteAquaHubAccount` | callable account/business cleanup and retention rules | Edge Function + explicit deletion/retention transaction |

No Functions are deleted or replaced in this phase. Firebase Admin/FCM remains required until the notification path is intentionally moved.

