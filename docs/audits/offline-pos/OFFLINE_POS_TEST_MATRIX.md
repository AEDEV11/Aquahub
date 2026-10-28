# Offline POS Test Matrix — 2026-10-17

Variant correction: the latest build validation used `productionDebug` (not `qaDebug`): 190 JVM tests passed and production-flavor Lint completed with 87 warnings / 0 errors. The APK targets the live backend, so it was not used for account or transaction workflows. E2E scenario statuses below remain based on the absence of a safe isolated runtime and completed owner/station setup. See `evidence/production-debug-build-summary.txt`.

| Test ID | Scenario | Status | Evidence / reason |
|---|---|---|---|
| OFF-001 | Device authorization, signed grant, and stock lease | BLOCKED | Function-level QA signer/auth check passed through direct loopback Deno: invalid fingerprint rejected before RPC; matching fingerprint reached expected 403 for a random nonexistent station. App gateway route returned 404; no owner/station/grant/lease was provisioned. |
| OFF-002 | Offline cash sale of 3 Purified refills (₱105) | BLOCKED | No authorized grant/catalog and onboarding was blocked by Android system ANR. |
| OFF-003 | Three further offline sales (₱200) | BLOCKED | No transaction was entered. |
| OFF-004 | Force-stop recovery of pending receipts | BLOCKED | No Room transaction was created. |
| OFF-005 | Reconnection and one-time synchronization | BLOCKED | Local function route unavailable; no pending operation existed. |
| OFF-006 | Duplicate upload/idempotent replay | BLOCKED | No canonical offline operation was created. Historical disposable tests are not counted as current evidence. |
| OFF-007 | Commit succeeds but response is lost | BLOCKED | Could not invoke offline-pos against the isolated runtime. |
| OFF-008 | Sale beyond authorized offline stock | BLOCKED | No lease, inventory, or sale was created. |
| OFF-009 | Expired grant | BLOCKED | No QA grant or controlled clock scenario was available. |
| OFF-010 | Logout/account switch with pending sale | BLOCKED | No owner session or pending sale was created. |
| OFF-011 | Reboot, grant survival, and clock rollback/forward | BLOCKED | Emulator did not remain ADB-responsive; no cached grant existed. |
| OFF-012 | Concurrent inventory conflict | BLOCKED | Local function route unavailable and no second isolated device/tenant was provisioned. |
| OFF-013 | Backend unavailable during synchronization | BLOCKED | No locally persisted sale existed to exercise bounded retry. |
| OFF-014 | 50 authorized offline sales | BLOCKED | No isolated grant/catalog; no production load was generated. |
| OFF-015 | Cross-tenant authorization using two QA owners | BLOCKED | SQL RLS suite passed separately (226 assertions), but no end-to-end owner/function request was run. |

All OFF-001–OFF-015 scenarios remain BLOCKED. The `productionDebug` build was not used for runtime transactions because it is configured for the production backend; no isolated test owner credentials were available, and the tablet AVD attempt did not register with ADB. The connected physical phone was not used.

No listed scenario is marked PASS based on mocks, source inspection, or historical runs.
