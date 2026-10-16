# Customers migration

Status: `IMPLEMENTED — Supabase-primary repository; Room cache; Firestore compatibility mirror; workspace repair verified on device`

## Current Firestore model

Owner-app customer records are stored under:

`businesses/{businessId}/stations/{stationId}/customers/{customerId}`

The owner app also stores delivery addresses under:

`businesses/{businessId}/stations/{stationId}/customerAddresses/{addressId}`

The exact customer fields are `id`, `stationId`, `name`, `phone`, `email`, `notes`, `totalOrders`, `totalSpentCentavos`, `createdAt`, `clientUpdatedAt`, `isActive`, and `deletedAt`. Address fields are `id`, `stationId`, `customerId`, `label`, `addressLine`, `barangay`, `city`, `province`, `latitude`, `longitude`, `isDefault`, `clientUpdatedAt`, and `deletedAt`.

Room remains the UI cache, but remote ownership has changed: `RoomCustomerRepository` refreshes reads from Supabase before exposing the Room flow, performs Supabase-first create/update/archive operations, then updates Room and enqueues `CUSTOMER`/`CUSTOMER_ADDRESS` mutations for the existing Firestore compatibility mirror. Customer search remains local name/phone matching. No application-level duplicate-phone check exists today. The customer order Function also creates or updates a customer aggregate at the same station path, so Firestore cannot be removed until orders are migrated.

## Supabase mapping

`public.customers` already maps the model with `station_id`, `name`, `phone`, `email`, `notes`, integer `total_orders`, integer-centavo `total_spent_centavos`, `is_active`, timestamps, and `firebase_id` for the legacy document ID. `public.customer_addresses` maps the nested address records and also preserves `firebase_id`. No schema change is required for the audited fields.

RLS uses `app_private.can_operate_station(station_id)`. Owners/managers can operate all stations in their organization; staff/riders require an explicit `member_stations` assignment. CUSTOMER profiles are excluded from this operator predicate, so they cannot browse owner customer lists. The client must not use organization IDs as authorization; the station row predicate is enforced server-side.

## Transition plan

Customer mutations are now offline-first. When the cached station-to-Supabase mapping is available, the app attempts the Supabase write; if the network/DNS is unavailable, it commits the customer/address to Room and the durable outbox instead of rejecting the save. The sync coordinator replays the Supabase mutation first after connectivity returns, then sends the Firebase compatibility mirror. Non-connectivity errors still fail normally, and no remote-success state is shown for a queued write.

Latest validation note: a fresh UI-created customer was confirmed in hosted Supabase and Room before cleanup. Offline cache/write remains unverified because device-level network blocking drops wireless ADB and the ADB-safe proxy did not block the native Supabase client. Legacy Firebase consumer validation remains pending; hosted disposable rows and device-local fixture rows/queue entries are now zero.

The implemented path is Supabase reads and writes with a temporary Firestore compatibility mirror. The mirror is queued only after the Supabase write succeeds and reuses the preserved `firebase_id`; the existing sync queue owns retries and exposes pending/error state through the existing sync-status UI. Supabase failures leave the local cache unchanged for writes, while read failures fall back to the last Room snapshot. Address archive currently deletes the Supabase row because the deployed address table has no soft-delete columns; the Firestore mirror retains its existing archive semantics.

`supabase/scripts/migrate_customers_products.py` is dry-run by default, preserves IDs, validates station ownership from the supplied export, and reports `READY`, `ALREADY_MIGRATED`, `DUPLICATE`, `INVALID_TENANT`, `CONFLICT`, or `FAILED`. No authorized Firestore export was present in this workspace, so no production records were imported or modified.

Validation: Android `compileDebugKotlin`, `testDebugUnitTest`, `assembleDebug`, and `compileReleaseKotlin` pass. The hosted legacy workspace repair was verified as idempotent, and the debug-only validation receiver exercised disposable customer create/update/archive through the real repository/use-case path on Samsung `SM-A366B`. A forced CUSTOMER mirror failure produced a retryable Room queue row while the Supabase write succeeded; disabling the hook and invoking the real coordinator drained the queue. Device validation also exposed stale Room resurrection after the hosted disposable row was removed; refresh now removes only mapped Room customers absent from a successful Supabase read, including their addresses and metadata, while failed reads retain cache for offline reads. Hosted disposable rows are zero after cleanup. Offline cache/write and legacy compatibility remain pending because the wireless transport became unreachable when both device network transports were disabled; the current customer screen does not expose update/archive actions. The debug injection is compiled as a no-op in release.
