# Staff and Settings Migration Audit

Status: `STAFF_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`

## Staff/member data

The owner app supports listing, creating, editing, and removing staff/rider accounts; roles found in the UI are `STAFF`, `MANAGER`, and `RIDER`, with owner authorization represented by the existing `OWNER` role. Staff accounts use phone OTP authentication; creation requires a phone number and station assignment, not a temporary password. No attendance or invitation entity was found. The existing Supabase model already has `profiles`, `organizations`, `organization_members`, and `member_stations`.

`public.staff_directory()` and `SupabaseStaffDataSource` provide manager-scoped Supabase staff reads. The deployed `staff-admin` Edge Function now owns create/update/deactivate operations; Android invokes it with the current Supabase session and never receives service credentials. `firebase_uid` is compatibility metadata only.

### Legacy staff callable contract (audited)

Before cutover, `createStaffAccount` accepted name, phone, password, role, and station IDs; created a synthetic Firebase email/password Auth account; wrote a Firebase user profile and organization member record; and returned the Firebase UID. It allowed `STAFF`, `MANAGER`, and `RIDER`, with MANAGER creation restricted to OWNER, and validated caller station access, paid subscription, and plan staff limits. It had no idempotency key and duplicate phone aliases were rejected by Firebase Auth.

`updateStaffAccount` changed Firebase display name/email alias and the Firestore profile/member role and station IDs, with OWNER/self and cross-station checks. `deleteStaffAccount` permanently deleted the Firebase Auth user and member/profile documents after blocking riders with active orders. These operations could leave an Auth orphan if post-delete Firestore cleanup failed, and neither callable was a safe reversible deactivation.

### Supabase staff write contract

`staff-admin` is deployed for `POST /functions/v1/staff-admin`. It requires a valid Supabase Bearer JWT, derives the caller and organization from active `organization_members`, and accepts `create`, `update`, and `delete` operations. OWNER may manage STAFF/MANAGER/RIDER; MANAGER may manage STAFF/RIDER but cannot create or promote MANAGER; no caller may target itself or an OWNER. Every station assignment is checked against the caller organization and, for MANAGER, the caller's assigned stations.

Create provisions a passwordless Supabase Auth phone user, writes `profiles`, `organization_members`, and `member_stations`, and lets the staff member authenticate through the normal `signInWithOtp`/`verifyOtp` flow. It compensates failed provisioning by deleting the newly-created Auth user. Repeated creation for an existing phone identity returns `STAFF_ALREADY_EXISTS`. Update changes only supported profile/member/station fields. Delete is now a reversible Supabase deactivation (`is_active=false` plus assignment removal) so historical records retain identity references. It returns safe application codes and never returns credentials or internal errors.

The Android path is now `StaffRepository → SupabaseStaffDataSource → staff-admin`; the Firebase create/update/delete callables are no longer invoked by the owner app. `listStaffAccounts` remains only as legacy source code until a repository audit confirms no compatibility consumer. `tools/supabase-migration/validate-staff-settings.mjs` provides the disposable hosted matrix and uses service-role credentials only for fixture setup/cleanup; all staff-admin and settings business calls use normal authenticated user-session JWTs. In the current environment the harness exits with `STAFF_AUTHENTICATED_VALIDATION_BLOCKED_BY_TEST_SETUP` because the required trusted setup variables are unavailable, so no authenticated success is claimed and the Staff/Settings gates remain validation-pending. Missing and malformed authorization contract checks return 401.

## Settings classification

- Business/station data: name, description, phone, email, address, coordinates, logo, operating hours, online ordering, delivery radius/fee/preparation time, payment activation/account details/QR paths, and business rules. These belong in Supabase station/workspace records and are already partly migrated through workspace/catalog sources.
- Local device preferences: onboarding flags, active station selection, appearance mode, cached sync state, and device-only UI choices. These remain DataStore/Room and should not be moved to Supabase.
- Subscription/entitlement data: remains a separate owner billing boundary; Google Play Functions stay retained.

## Firebase replacement matrix

| Function | Classification | Replacement/status | Safe removal condition |
|---|---|---|---|
| `issueFirebaseCompatibilityToken` | compatibility | KEEP temporarily | Firebase-backed legacy consumers removed |
| `list/create/update/deleteStaffAccount` | staff | `staff-admin` deployed; Android writes migrated; legacy source retained | Authenticated validation and post-observation removal gate |
| `ensureOwnerTrial`, `checkOwnerTrialEligibility` | subscription | RUNTIME_UNUSED_PENDING_VALIDATION | Supabase `subscription-trial` is active in Android; legacy callables remain source/compatibility only |
| `verifyPlaySubscription` | subscription | RUNTIME_UNUSED_PENDING_VALIDATION | Supabase `verify-play-purchase` is active in Android; real purchase validation remains pending |
| `playSubscriptionNotifications` | subscription | KEEP_FOR_SUBSCRIPTION_BILLING | RTDN receiver remains Firebase until a Supabase receiver is deployed and validated |
| `createCustomerOrder`, `cancelCustomerOrder`, `mirrorCustomerOrderStatus` | blocked order | ORDER_LEGACY_KEEP_UNTIL_VALIDATED | Authenticated order proof and device gate pass |
| `publishPublicStation`, `publishPublicProduct`, `syncPublicStationsForSubscription` | projections | MIGRATE_TO_TRIGGER/SQL projection | Supabase public projections and client reads validated |
| `deleteAquaHubAccount` | account deletion | MIGRATE_TO_AUTH/Supabase workflow | Supabase account deletion path validated |

## Importers and settings

`tools/supabase-migration/migrate-staff.mjs` and `migrate-settings.mjs` are dry-run-first mapping/reporting tools. They require explicit hosted-production confirmation for apply, preserve Firebase identity references, validate supported roles/typed station settings, and never delete Firebase data. No invitation flow was invented because the current app has no invitation state or UI.

`supabase/tests/staff_storage_invariants.sql` contains static hosted checks for manager directory exposure, the settings RPC, protected station update grants, and Storage bucket/privacy policy structure. Authenticated allow/deny cases remain pending trusted fixtures.

Business settings are the existing typed `organizations`/`stations` fields, including operating hours, delivery, payment configuration, and asset paths. `SupabaseSettingsDataSource` calls the deployed `update_station_settings(uuid,jsonb)` RPC, which whitelists owner-editable fields and rejects protected fields. Direct authenticated station-row UPDATE grants are revoked. Offline station-setting queue replay now calls the same Supabase RPC and completes without a new Firestore mirror write. Theme, printer, and other device-only preferences remain local. Subscription, entitlement, ownership, and security-role fields remain server-controlled. Payment QR and station-logo fields store Supabase object paths, not signed URLs.

FCM remains Firebase transport. The owner already has a Supabase `device_tokens` writer keyed by Supabase profile identity, while the legacy Firestore token mirror remains temporarily for retained notification compatibility.

FCM remains retained. The target notification path is Supabase/Postgres event → Edge Function → FCM HTTP v1; device tokens are already being moved toward Supabase identity, but no FCM removal is claimed.
# Runtime dependency note

Staff/settings Android writes are Supabase-only. Legacy staff Functions remain source/compatibility-only and are not removed because authenticated validation is still blocked. The remaining runtime callsites and removal prerequisites are tracked in `23-firebase-runtime-removal.md`.

Subscription billing is no longer part of the staff/settings Firebase write path. The owner runtime uses Supabase Auth identity, while legacy billing/staff Functions remain source-only until their authenticated validation and observation prerequisites are met.
