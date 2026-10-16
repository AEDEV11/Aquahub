# Owner Offline and Synchronization Audit

## Phase 3 Android-to-disposable-Supabase verification — 2026-10-12

**Overall OFF-03: PARTIAL E2E PASS, still a P1 release blocker.** The QA Android app used real Auth, Room, offline repository, Edge Function and disposable PostgreSQL. After issuing an 8-hour grant/lease, Android was disconnected and recorded an immutable cash-only walk-in sale (₱25.00 total, ₱50.00 received, ₱25.00 change). The local receipt retained its original items/price/quantity, timestamp, stable operation UUID, payload digest, device signature and payment. A force-stop/recovery preserved it. On reconnect, the real repository/worker committed it. The proxy held/dropped the successful server response; the app was force-stopped and retried; duplicate operation replay returned the canonical order. The server had exactly one order, one PAID cash payment and one inventory movement; Room became COMMITTED; dashboard/report included the sale exactly once and removed pending cash. A remote refresh race was also covered and now maps the canonical order to the existing local row rather than inserting a duplicate.

### Phase 3 scenario register

| Scenario | Result | Evidence boundary |
|---|---|---|
| Actual offline cash POS | PASS | QA Android + airplane mode + Room + disposable Edge/Postgres. No electronic tenders/refunds used. |
| Process termination before sync / after successful server commit but lost response | PASS | Force-stop and durable Room recovery; after commit retry reused stable operation ID/digest. |
| Duplicate operation replay / timeout-after-commit | PASS | Exactly-one server effects and canonical response. |
| Duplicate worker retry | PASS for duplicate replay; simultaneous worker stress race NOT VERIFIED | Stable operation ID plus server idempotency. |
| Repeated POS button taps | NOT VERIFIED | Did not automate fast repeated UI taps. |
| Device reboot | NOT VERIFIED | Emulator force-stop used; no OS reboot. |
| HTTP 429/503 and mid-upload network interruption | NOT VERIFIED | Retry classes have JVM coverage; live gateway fault injection not performed. |
| Expired lease/grant, server revocation, wall clock rollback | NOT VERIFIED end-to-end | SQL/cryptographic policy tests do not prove the whole Android-to-Edge path. |
| Price change/product deletion while offline | NOT VERIFIED end-to-end | Local immutable snapshot is kept; server conflict and recovery UI sequence not run. |
| Organization/station switch and hostile cross-tenant client | NOT VERIFIED through live app/Edge | SQL RLS tests pass, but no two-tenant Android adversarial request in this phase. |
| Lost/unresponsive device and physical reconciliation | SQL contract pass; operating workflow NOT VERIFIED | No operator authorization/device revocation E2E. |
| Partial lease consumption | SQL contract PASS | No live Android multiple-sale/partial-use cycle. |
| Browser guest order against reservation floor | PASS | Actual disposable guest-order Edge→Postgres request created one order from one unreserved unit; same operation retry returned the canonical order. With only reserved stock, the database rolled back the attempt; after the Edge classification fix it returned HTTP 409 and left inventory/order rows unchanged. |
| Browser order versus offline lease contention | PASS for one server/API race | Two-thread barrier released the authenticated lease RPC and guest-order Edge endpoint against one available unit. Exactly one lease won; guest order returned 409; invariant remained intact. Audited physical reconciliation and CAS stock restore cleaned the disposable fixture. This did not use two Android clients. |
| Inventory writer concurrency | PARTIAL | One online Owner POS race, setter/revision checks, reservation-floor SQL tests, sequential and concurrent guest Edge paths are covered. Stock-edit, refund/cancel, transfer/container and two-Android-device races remain open. |

The additive local migration `20261012160001_fix_inventory_quantity_conflict_target.sql` repairs the real `42702` conflict-target bug in `set_inventory_quantity`; it now names the unique constraint. Stale revision and reservation-floor checks pass. The new SQL behavior tests raised the disposable suite from 221 to **226/226**.

The approved lease rules remain: 8h default/24h max inventory lease; 8h default/12h max grant; expiration stops new sales but never automatically releases unresolved stock; partial consumption and reconciliation remain audited; physical counts require authorized reconciliation. This reduces overselling risk but can leave uncertain inventory unavailable until a device reconnects or an authorized physical reconciliation is completed.

**SYNC-02 remains NOT VERIFIED and release blocking.** See `OWNER_BACKEND_SECURITY_AUDIT.md` writer table. The reservation-floor trigger protects the balance invariant, and the real guest Edge path now proves sequential rejection of reserved-only stock, but all requested simultaneous races are not yet run.

### FIN-01 and EXP-01

FIN-01 is now **FIXED AND VERIFIED LOCALLY**: authoritative receipt/refund hydration unit test passes, and the real Android offline sale transitions from pending to confirmed once in reports/dashboard. EXP-01 is **PARTIALLY VERIFIED**: real Android expense refresh hydrated a server-only synthetic row, maintained the total exactly once on repeat, and converged a server edit. It did not use a second Android device or create a pending local expense conflict.


## Approved OFF-03 implementation update — 2026-10-11

**Release status: NO-GO / OFF-03 NOT VERIFIED end-to-end.** Local implementation and disposable verification are complete for the approved policy; production deployment is not performed.

| Workstream | Local result | Evidence / remaining gate |
|---|---|---|
| Policy | 8h default/24h max inventory lease; 8h default/12h max auth grant. Leases and grants bind user, org, station, device; leases also bind product/allocation. | Config validated by SQL constraints/RPC tests. No live config change. |
| POS | Cash-only offline walk-in pickup. Electronic tender, delivery, account charge, refund/cancel and container movements require online authority. | Use-case regression + phone UI; real offline sale/receipt lifecycle not run end-to-end. |
| Durable receipt | Room v22 operations with stable UUID, canonical payload digest, signature, immutable item/price/quantity, cash received/change, timestamps and event history; no local delete on failure. | v21 migration instrumented test and unit test pass on Samsung. Abrupt process kill/reboot with a real pending sale remains NOT VERIFIED. |
| Authorization | Server-issued ES256 grants and leases; Android pins/verifies server signatures, checks scope, device signature, monotonic/boot-bound age and snapshot. Sync rechecks auth and tenant scope; invalid transactions remain local for review. | Type check, unit and SQL policy tests pass. Cryptographic issuance→offline→Edge verification, rollback/revocation and lost-device flows are not fully E2E verified. |
| Reservation | Atomic server allocation; no automatic release at expiry; consumed/released quantities tracked; physical-count single/bulk reconciliation records actor/reason and can revoke device/grant. | 221 SQL tests across 22 files pass. Two-device reservation race test rejects the second oversubscription. Lost-device physical reconciliation and partial consumption tested at SQL contract level, not live operational workflow. |
| Stock updates | Absolute target plus expected revision CAS replaces stale absolute-to-relative delta; reservation floor prevents available stock crossing below held allocations. | Disposable stale revision and reservation checks pass. Must still prove all browser order, online POS, refund/cancel, transfer and container writers invoke/obey the guarded transactional stock paths. SYNC-02 remains a P1 integration gate. |
| Reconciliation | Stable operation id/digest; transactionally writes order, paid cash payment, item rows and inventory ledger; duplicate returns prior result; expired/reconciled lease blocks late unreported operations. | Disposable SQL tests pass; mobile Edge timeout-after-commit/retry path not E2E tested. |
| Cash reports | Pending local cash collected value is separate from confirmed revenue; successful sync marks it confirmed once. | Unit and connected dashboard/report tests pass; report hydration against production remains unverified. |
| UI | Saved receipts show original details and conflict state. Permanent failures require owner review; only retry-limit records can be requeued. | 29/29 Samsung QA instrumentation tests; focused Compose receipt test on phone and Pixel Tablet. Full tablet POS transaction layout/accessibility sweep remains NOT VERIFIED. |

### Expiration, release and recovery rules

- Expiry prevents new offline sales. It does not return reserved inventory to allocatable stock automatically; the lease remains unresolved.
- A previously recorded sale can reconcile after expiry only while its lease is unresolved and within the signed time window. Once an authorized physical reconciliation closes/revokes the lease, later unreported sale operations are rejected into local owner review and are never silently treated as confirmed revenue.
- `quantity_consumed` is recorded per product. Reconciliation calculates remaining lease stock and requires audited physical counts before release. If physical count conflicts with other allocations, the RPC rejects partial release and requires all affected leases to be reconciled together.
- Lost/unresponsive devices require authorized physical-stock count, reason, actor and audit event; optional device/grant revocation is in the same database transaction.
- Catalog/price snapshots are immutable. A changed/missing product or price results in conflict; original cash receipt remains stored and is not repriced. Owner review is required; no historical-price acceptance or silent adjustment is implemented.

This intentionally favors preventing silent overselling over immediate availability: unresolved lease stock may remain blocked until reconciliation. The database exposes controlled release rather than timer-based release.

### Remaining scenario gates

Offline→online Edge E2E, duplicate-tap UI/persistence race, process termination at each transaction boundary, phone/tablet full workflow, two-device sale contention through Android clients, actual clock rollback/reboot, auth revocation, timeout after committed server response, station/account switch, retry-after-5xx/429, stale catalog conflict resolution, and cross-organization hostile requests from real disposable identities remain **NOT VERIFIED**. No production data was touched.

## Separate production migration, deployment and rollback plan — requires separate approval

1. **Preflight:** review the exact additive migration and edge code against the final approved source revision; back up production; run read-only checks for inventory quantities below reserved quantity (expected zero before rollout), schema/function grants, active migration version and current Edge contract. Do not run broad logs or mutate token/financial rows.
2. **Stage in disposable/staging:** apply the migration before deploying the `offline-pos` Edge Function. Verify exposed public wrapper is service-role-only and `app_private` remains non-exposed; run SQL/RLS tests and test accounts for organization/station/device ownership, lease contention and reconciliation. Exercise old app versions to confirm ordinary online POS remains compatible.
3. **Production sequence after explicit authorization:** apply the additive SQL migration; verify schema and reservation-floor trigger; deploy the matching Edge Function version; configure required server signing key and pinned public key only through approved secret handling; run read-only health checks and a controlled non-financial issuance check. Then distribute app gradually only after owner approves release. Do not change live stock, sales, payments, purchase tokens or entitlements as test data.
4. **Rollback/containment:** if migration fails before commit, let the transaction/migration runner roll it back and do not deploy Edge/app. If the additive migration succeeds but Edge/app rollout fails, keep schema/table/history in place; disable new offline grant/lease issuance through the approved server-side flag/Edge rollback, preserve existing reservations and operations, and continue online-only POS. Never drop reservation columns/tables or auto-release leases while any are unresolved. For a committed offline sale defect, stop new issuance, preserve all local/server receipts and audit events, reconcile by operation UUID/digest and physical count, and apply a forward fix. App rollback uses Play staged rollout halt/previous compatible version only with separate Play authorization.
5. **Approval boundary:** production migration, function deploy, secrets/settings, staged release, reservation reconciliation and any financial correction each require separate explicit approval. This plan authorizes none of them.

## Initial findings before approved remediation (historical; current status is above)

| ID | Severity | Evidence | Impact / remediation |
|---|---|---|---|
| OFF-01 | P1 | `SupabaseAuthRepository.kt:123-135,284-286,303-309`; `SplashViewModel.kt:32-44` | Restore asks remote account data before using cached owner. Valid local user can land at login without internet. Preserve local session shell; test offline launch, expired token and refresh. |
| OFF-02 | P1 | `AquaHubApplication.kt:35-45`; `SyncDao.kt:29-70,184-185`; `ExistingLocalDataMigrationPlanner.kt:28-92` | Startup quarantines outstanding legacy queue rows. Normal replay excludes QUARANTINED and manual retry targets FAILED. Existing-data planner has no caller. Upgrade can strand local operations. Add an explicit, idempotent migration and old-version database fixture. |
| OFF-03 | P1 / NOT VERIFIED end-to-end | `OrderUseCases.kt`, `RoomOfflinePosRepository.kt`, `OfflinePosSyncCoordinator.kt`, `20261011150000_offline_pos_reservations.sql` | Local approved cash-only offline sale and atomic reconciliation contract is present. Full Android→Edge transaction/recovery and adversarial security scenarios remain open. |
| SYNC-01 | P2 / LOCALLY IMPLEMENTED, INTEGRATION NOT VERIFIED | `SyncRetryPolicy.kt`, `SupabaseSyncCoordinator.kt`, `SupabaseSyncWorker.kt`, `SyncScheduler.kt` | Transport errors and HTTP 408/425/429/5xx are retried; permanent 4xx and 409 are failed for explicit resolution. Attempts are capped at 8, WorkManager uses exponential backoff, and manual retry resets the window without changing operation UUID. Unit + Room regression pass; backend failure injection and worker timing remain NOT VERIFIED. |
| SYNC-02 | P1 / BLOCKED for complete writer-path verification | `RoomProductRepository.kt`, `SupabaseInventoryDataSource.kt`, `SupabasePendingMutationWriter.kt`, `set_inventory_quantity` RPC | Local absolute target + revision CAS replaces unsafe relative delta. Disposable stale-write tests pass; prove all stock mutation paths use the guarded transaction and pass end-to-end concurrency tests. |
| SYNC-03 | P2 | `SupabaseCatalogDataSource.kt:215-221` and order/inventory list sources | No visible pagination on full-list reads; row caps and large catalogs/history may yield incomplete or slow results. Add keyset pagination and large fixture test. |
| SYNC-04 | P1 / FAIL — order freshness recovery gap | `OrdersViewModel.kt:64,76-98`; `OrdersScreen.kt`; `SyncScheduler.kt:51-64`; `SupabaseSyncWorker.kt:20-24` | Orders refresh on initial flow setup, filter/station change or manual request. There is no lifecycle-resume refresh or Realtime subscription, and periodic WorkManager requests carry no station ID so the worker cannot refresh the order queue after a missed FCM. A browser order may remain absent while the Orders ViewModel stays alive. Implement a station-scoped recovery path and prove missed-push recovery through real QA Android + disposable Edge/Postgres. Blocks production. |
| SYNC-05 | P2 / OPEN | `RoomProductRepository.kt:308-315`; `RoomCustomerRepository.kt:438-446` | Refresh errors preserve cached records but are swallowed; product/customer screens do not expose authoritative refresh timestamp or retry/error state. Add a user-visible stale/freshness signal and retry action, then test offline and server recovery. |
| EXP-01 | P2 / IMPLEMENTED LOCALLY, CROSS-DEVICE NOT VERIFIED | `RoomExpenseRepository.kt:43-113`; sync queue and expense datasource | Expense writes now persist with a stable operation ID and durable outbox inside a Room transaction; remote hydration is implemented and skips rows with outstanding mutations. No dedicated expense repository/outbox/reconciliation or second-device integration regression was run; retain as a verification item. |

## Required scenario status

| Scenario | Status | Evidence / gap |
|---|---|---|
| Cold launch without internet | NOT VERIFIED | QA no-session worker is isolated; no signed-in airplane-mode launch was tested. |
| Expired session offline | NOT VERIFIED | Restore source depends on online profile read. |
| Existing signed-in work offline | PARTIAL | Room/cache and queue exist; workflow behavior unproven. |
| Create POS sale offline | PARTIAL / NOT VERIFIED | Local cash sale path uses active signed grant and reservation; Android-to-Edge end-to-end/reboot/revocation coverage remains open. |
| Edit local data offline | PARTIAL | Some queue-backed mutations; conflicts not tested. |
| Kill/restart before sync | PARTIAL | Queue persistence tests exist; old-work quarantine migration concern remains. |
| Reconnect after prolonged outage | NOT VERIFIED | No retry/backoff end-to-end test. |
| Concurrent second-device update | PARTIAL | Disposable SQL reservation contention rejects oversubscription; end-to-end POS/update race across all server writers remains NOT VERIFIED. |
| Switch station with queued work | NOT VERIFIED | Mandatory no cross-station replay test missing. |
| Logout with pending work | NOT VERIFIED | Quarantine/account switch path needs tested preservation and no cross-account leakage. |
| Reinstall/restore | NOT VERIFIED | Backup/session behavior and remote data restoration not tested. |
| Duplicate worker execution | PARTIAL | Idempotency/queue tests exist; whole transaction path not proven. |
| Missed FCM while Orders screen/ViewModel remains active | FAIL by source inspection; runtime injection NOT VERIFIED | FCM requests a station-scoped worker when delivered, but periodic work has no station context and there is no resume pull or Realtime subscription. The order may not appear until manual refresh or ViewModel recreation. This was promoted to P1 SYNC-04; test by suppressing push and submitting a real disposable browser order. |
| Backend maintenance | NOT VERIFIED | Error classification and clear recovery UX missing evidence. |

## Release acceptance tests

Use disposable organizations and known test accounts only. Seed a previous-version Room DB with PENDING, RETRY, FAILED, IN_PROGRESS and already-synced rows; upgrade, restart, change station, logout, reconnect and assert each operation is either durably synchronized once or clearly presented for action. Test repeated taps and process death around server commit/local acknowledgement. Assert ledger totals, no duplicate payment/order, no negative/incorrect stock, no cross-tenant replay, bounded retry, and user-visible blocked state. Do not run destructive or financial tests against production.
