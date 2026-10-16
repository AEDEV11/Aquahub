# Customer/product device validation

Status: `PARTIAL — CUSTOMER LIFECYCLE, TENANT-CONTEXT REGRESSION, AND MIRROR RETRY VALIDATED; OFFLINE/PRODUCT VALIDATION REMAIN PENDING`

Validation target: `CUSTOMER_PRODUCT_CUTOVER_READY`

The debug build was installed on the connected Samsung `SM-A366B` using a chunked wireless-ADB transfer followed by `pm install`. The normal `adb install` transfer path still hangs for this 43 MB APK, while shell commands and small transfers work. The reconstructed APK SHA-256 matched the local artifact. The app launched, reached the authenticated dashboard, and showed no immediate crash.

The first disposable customer save exposed a blocking provisioning defect. This was repaired with the hosted legacy-ID-aware, idempotent workspace RPC and centralized session repair. The controlled owner workspace now has one organization, one station, one owner membership, and one member-station link; the Firebase business/station IDs are preserved. A second device build created a disposable customer through Supabase without a crash. The disposable Supabase customer was deleted after validation; no product fixture was created.

## Current test matrix

| Test | Result | Notes |
| --- | --- | --- |
| Customer Supabase read → Room | `PASSED` | Repaired workspace loaded on-device; customer list opened and refreshed without a crash |
| Customer create/update/archive | `PASSED` | Debug-only receiver used the real repository/use-case path: create, update, and archive reached hosted Supabase; archive set `is_active=false`/`deleted_at`; exact disposable rows were removed during cleanup. The production UI still does not expose edit/archive controls, so this is harness validation rather than a redesigned UI flow |
| Product Supabase catalog read → Room | `PARTIAL` | Product screen opened and showed `No Products Added Yet`; no fixture was available to verify remote-vs-Room hydration |
| Product create/update | `BLOCKED` | Current test subscription has no BASIC_INVENTORY access, so the UI cannot save a required bottle size; no product fixture was created |
| Inventory-enabled product account | `BLOCKED BY ENTITLEMENT` | Hosted subscription query exposed no authorized BASIC_INVENTORY-enabled owner account; entitlement checks were not bypassed and no product fixture was manufactured |
| Stock safety | `NOT RUN` | No product could be created; must be tested against a controlled Firebase order/inventory mutation before inventory migration |
| Offline Room reads | `BLOCKED_BY_TRANSPORT` | A fresh customer was verified in Supabase, visible in the UI, and present in Room. The only reliable offline simulation (disabling Wi-Fi and mobile data) drops wireless ADB; the ADB-safe global proxy did not block the native Supabase client. No offline pass is claimed |
| Offline writes | `IMPLEMENTED; DEVICE RECHECK PENDING` | Customer writes now commit locally and enqueue when network/DNS failures are detected; Supabase replay runs before the Firebase compatibility mirror. A physical no-network recheck still requires USB ADB or a controllable emulator/device network |
| Firestore mirror failure | `PASSED` | Debug-only mirror-boundary injection forced a CUSTOMER UPDATE mirror failure; Room recorded a retryable queue row while the Supabase write remained successful |
| Firestore mirror retry | `PASSED` | Disabling the hook and invoking the real coordinator drained the retry queue; hosted data matched the updated customer and Room queue was empty |
| Legacy order/function compatibility | `NOT RUN` | Requires a mirrored disposable customer/product and a safe legacy flow |
| Cross-tenant/station isolation | `PASSED — HOSTED` | Owner B→Station A customer insert was rejected by hosted RLS with `42501`; physical device/account evidence remains pending |
| Account-switch tenant context | `PASSED — HOSTED/SOURCE` | Two hosted owner identities resolve to different organizations/stations. Owner B→Station A insert was rejected by RLS; authentication now clears the shared Room tenant boundary when identity changes and blocks switching if unsynced mutations exist. Physical A→B→A interaction remains pending |
| Room tenant-cache safety | `PASSED — SOURCE` | Room is a single-tenant operational cache; logout clears active owner/station preferences, and account-boundary authentication clears tenant rows, metadata, and queue state before the new account is read |
| Online station resolution | `PASSED — SOURCE` | Customer writes re-resolve the station through the current authenticated Supabase session; cached station UUIDs are not trusted while online |
| Offline station resolution | `IMPLEMENTED; DEVICE RECHECK PENDING` | Cached station mapping is used only after connectivity failure. An online station-not-visible result fails safely instead of guessing |
| Customer error sanitization | `PASSED — SOURCE/BUILD` | Customer UI maps RLS/network/workspace failures to safe messages and does not render URLs, headers, tokens, SQL, or internal request details |
| Cleanup | `PASSED FOR HOSTED FIXTURES` | Hosted Supabase query returned zero disposable customer rows after exact cleanup; product fixtures remained zero. Before the transport dropped, Room queue inspection showed no pending/retry rows after retry. Final device-local zero-row verification requires USB ADB or restored wireless transport |

## Automated checks

Passed from the owner app:

```text
./gradlew testDebugUnitTest assembleDebug
./gradlew compileDebugKotlin
```

Passed against hosted Supabase project `pfkxrggexueltytxtqil`:

```text
supabase db lint --linked
supabase migration list
```

Hosted migrations `20261003103615_provision_owner_workspace_legacy_ids.sql` and `20261003105510_preserve_auth_verification_on_workspace_repair.sql` passed dry-run/lint and were applied. Inventory migration remains out of scope.

## Blocking evidence

The original customer failure was a real workspace-readiness defect, not a mirror failure. Existing Firebase owners could reach the dashboard with only a Supabase profile; onboarding-only provisioning was skipped for that path. Session restoration now resolves the authoritative Firebase business/station IDs and repairs the organization, station, membership, and assignment before business features. Missing workspace errors are now recoverable UI failures. Product testing remains blocked because the device is on the `FREE` plan, whose `BASIC_INVENTORY` entitlement is explicitly disabled; entitlement checks were not bypassed.

The device validation also found a cache-reconciliation defect: a customer archived/deleted from Supabase could remain visible in Room because refresh only upserted active remote rows. The customer refresh now removes only mapped Room customer rows absent from a successful Supabase read (and their addresses/metadata); failed remote reads retain the cache for offline reads. The fix passed owner-app unit tests, debug build validation, chunked-device installation, and a fresh device refresh showing no customers after the disposable hosted row was removed.

The current debug build contains a disposable validation receiver with actions for customer create/update/archive, immediate sync, and customer mirror-failure injection. The injection is placed immediately before the Firestore compatibility write, is restricted to `CUSTOMER` resources, and is compiled as a no-op in release. It produced a retryable Room queue entry without changing the Supabase-primary result; after disabling injection, the real coordinator retried and cleared the queue. Customer offline writes now use the same durable queue, with Supabase replay before the compatibility mirror. No credentials or token values are logged. A fresh UI-created customer was also verified in hosted Supabase and Room before cleanup; the hosted row and local cache were reconciled and cleaned afterward.

## Deployment diagnosis and workaround

The exact device serial is `adb-RRCY5099DHB-xEKG59._adb-tls-connect._tcp` (`SM-A366B`, Android 16). Shell commands, storage checks, package queries, and small transfers succeeded. The standard large-file install transfer timed out; the package installer itself was healthy. The successful workaround was:

```text
split -b 1m app-debug.apk chunk-
adb -s <serial> push each chunk /data/local/tmp/
adb -s <serial> shell 'cat /data/local/tmp/chunk-* > /data/local/tmp/aquahub-debug-chunked.apk'
adb -s <serial> shell pm install -r -t -d /data/local/tmp/aquahub-debug-chunked.apk
```

The final install returned `Success`, and no app data was cleared. The follow-up build also passed debug/release Kotlin compilation after adding the account-boundary cache guard.

## Required next execution

Use the successful chunked install workaround or switch to USB ADB, then execute the remaining customer matrix with disposable records only. A plan with `BASIC_INVENTORY` is required for product CRUD; do not bypass the entitlement. Record masked local/remote IDs, queue state, and status transitions. The current build does not expose customer update/archive actions on the device screen, so the lifecycle result above uses the debug harness and is not a claim that production UI controls were added. The gate remains `CUSTOMER_PRODUCT_CUTOVER_PENDING_DEVICE_VALIDATION` until offline cache/write, legacy compatibility, physical account-switch evidence, and an authorized product validation pass. Product validation is additionally `PRODUCT_DEVICE_VALIDATION_BLOCKED_BY_ENTITLEMENT` until a legitimate `BASIC_INVENTORY` account is available. Inventory migration is not started. USB ADB or a controllable emulator/device network is required to complete offline validation without changing production networking code.
