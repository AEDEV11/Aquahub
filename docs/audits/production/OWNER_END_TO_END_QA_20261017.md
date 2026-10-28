# AquaHub Owner QA Results — 2026-10-17

## Automated checks executed

| Suite / command | Environment | Result |
|---|---|---|
| `./gradlew :app:testQaDebugUnitTest :app:assembleQaDebug --no-daemon --max-workers=2` | Local QA debug | **190 passed, 0 failed, 0 skipped**; QA APK assembled. |
| `./gradlew :app:lintQaDebug --no-daemon --max-workers=1` | Local QA debug | **PASS**, zero errors and 87 warning-level findings on final source/resources. |
| `./gradlew :app:lintProductionRelease --no-daemon --max-workers=1` | Local production release | **PASS**, zero errors and 85 warning-level findings on final source/resources. |
| `/tmp/aquahub-deno/bin/deno test --allow-env --allow-net --allow-read=supabase/functions supabase/functions` | Local function tests | **87 passed, 0 failed**. Not offline POS database integration coverage. |
| `/tmp/aquahub-deno/bin/deno check supabase/functions/*/index.ts` | Local Deno | **14 entrypoints passed typecheck**. |
| `node --test test/*.test.js` | Local static website tests | **14 passed, 0 failed**. Not live hosting verification. |
| `git diff --check` | Workspace | **PASS**. |

## Prior disposable integration evidence

Existing audit artifacts record these tests from 2026-10-12; they were isolated from production and were not rerun today:

- API 35 Medium Phone emulator instrumentation: **30 passed, 0 failed**.
- Offline POS Android→Edge→PostgreSQL path: durable local transaction, airplane mode, force-stop recovery, successful server commit with response dropped, stable duplicate retry, and one canonical order/payment/inventory movement.
- Disposable Supabase SQL suite: **226 assertions across 22 files passed**, covering role/grant and tenant rules, stock floors, stale revisions, digest-bound idempotency, lease expiry retention, and audited reconciliation.
- One simultaneous offline lease RPC vs guest order race: one lease won, guest returned 409, no duplicate stock movement.

Source evidence: [OWNER_TEST_RESULTS.md](OWNER_TEST_RESULTS.md) and [OWNER_BACKEND_SECURITY_AUDIT.md](OWNER_BACKEND_SECURITY_AUDIT.md). The disposable Supabase directory and emulator are not available in the current environment; the broader fault matrix remains open.

## Required critical flows not executed

| Area | Result | Why / acceptance test |
|---|---|---|
| Registration, OTP, email verification, Google sign-in, session expiry, reauthentication | NOT VERIFIED | No runtime device/provider test accounts; exercise full Owner onboarding and returning-user flow on a test tenant. |
| Product and inventory create/edit, adjustment, low stock, concurrent updates | NOT VERIFIED | Run on isolated backend and devices; verify stock ledger, quantities and tenant boundaries. |
| Online and offline POS, cash, receipt, discount, return/refund/cancel | PARTIAL | Core offline cash sale and idempotent response-loss recovery passed in disposable integration; discount, return/refund/cancel and complete online POS matrix remain open. |
| Offline lease/grant, replay, modified payload, duplicate, response loss, process death | PARTIAL | Grant/lease, durable receipt, force-stop recovery, dropped response, and canonical retry passed; modified payload, expiry, revocation, clock rollback, reboot, and two Android devices remain open. |
| Online order reception/acceptance/delivery/payment/cancel/notifications | NOT VERIFIED | Screen resume refresh is implemented; no live test order or notification test. Verify station scoped event recovery and duplicate prevention. |
| Dashboard and financial reporting / expense synchronization | NOT VERIFIED | No end-to-end synchronized ledger assertions. Verify after offline/online sync, refund, expense and restart. |
| Billing purchase, pending, restore, cancellation, grace, hold, refund/revoke, expiry | NOT VERIFIED | Play Console/license testers unavailable. Use disposable testers; never create real paid transaction. |
| Account deletion, retention, marketing opt-out, reauth, retry after partial failure | NOT VERIFIED | Source changed locally; function not deployed and no failure-injection test. Use isolated disposable Owner and verify only target data changes. |
| Force stop, reboot, network loss/recovery, app update, Room migration | PARTIAL | Force-stop recovery and Room v21→v22 migration passed in prior emulator/disposable testing; no device is attached now for reboot/network/app-update and broader device matrix. |
| Accessibility/adaptive layout/dark mode/large font/keyboard/landscape | BLOCKED | No runtime device. Verify compact phone and medium/expanded tablet/foldable layouts and TalkBack. |

`adb devices -l` currently returns no usable device. Prior emulator/disposable runtime tests provide evidence for the specific cases above, not a substitute for the missing current phone/tablet, Play Billing, reboot, and adversarial runtime matrix. The full end-to-end gate remains open.
