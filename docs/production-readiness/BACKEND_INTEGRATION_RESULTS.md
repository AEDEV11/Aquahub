# Backend integration results

Snapshot: 2026-10-09. This report distinguishes local/disposable results from hosted integration. **Hosted Owner/Customer ↔ API ↔ database integration is BLOCKED** because no exact production-of-record or safely isolated test target is established. The user selected local disposable verification followed by direct-to-production only after an exact, approved preflight; no hosted staging project is required by that strategy.

| Layer | Result | Evidence / limit |
|---|---|---|
| PostgreSQL refund report | PASS locally | New `refund_obligation_report` RPC returns tenant/station/plan-authorized centavo aggregates and current pending aging. Synthetic owner/manager/staff/cross-tenant and period/status fixture passed in disposable DB. |
| SQL regression suite and clean replay | PASS locally | Fresh empty disposable project replayed all 54 current migrations; 12 SQL files / 84 pgTAP assertions passed, rollback-only synthetic fixtures. This does not prove linked-schema replay compatibility. |
| Supabase Deno tests | PASS locally | 34 tests, including seven Google OIDC verifier tests. |
| Supabase Edge Function typecheck | PASS locally | All 22 `functions/**/*.ts` source/test files checked using Deno 2.9.7. |
| Owner JVM/build | PASS locally | `./gradlew testDebugUnitTest assembleDebug`: 121 JVM tests, 0 failures/errors; `BUILD SUCCESSFUL`. Only debug output was produced. |
| Customer JVM/build/lint | PASS locally, lint with warnings | `./gradlew testDebugUnitTest assembleDebug lintDebug`: 21 JVM tests, 0 failures/errors; `BUILD SUCCESSFUL`; 0 lint errors and 75 warnings. Small scanner opt-in and optional-camera manifest correction; no Customer business/API contract change. |
| Owner/Customer Android lint | PASS WITH WARNINGS locally | Owner `./gradlew lintDebug` succeeded. Customer `lintDebug` succeeded with 75 warnings and no errors. Release-variant lint and warning triage remain outstanding. |
| Hosted PostgREST/RPC | BLOCKED | No verified production-of-record or safe hosted test identities. Read-only linked schema/history shows current Group 1–4 drift, but the target remains environment-unknown. No hosted REST/JWT mutation was attempted. |
| Owner/Customer full network workflows | BLOCKED | No staging contract deployed; no real/test customer records were changed. |
| Play Billing/RTDN | PARTIAL locally; hosted/sandbox blocked | Local OIDC verifier, token verification, and state machine tests only. No Google Pub/Sub push, Play license purchase, Console product, or deployed handler was verified. |
| Device E2E | NOT RUN in Group 4 | Existing device data preserved. Prior Group 3 Owner instrumentation results do not cover Group 4; Customer device end-to-end remains unverified. |

## Direct production preflight status

The local migration bootstrap and SQL gate now pass. The remote deployment gate does not: Play Console confirms Closed testing (Owner Production inactive), but exact release artifacts/endpoints for both apps do not match to the local source; the linked project's production role is not established. The latest linked schema diff is nonempty and contains destructive statements in its generated direction. No validated backup/restore of database plus Auth/Storage is available. Do not run `supabase db push`, migration repair, or `functions deploy`; no exact safe set of migrations/functions is approved by evidence.

## Implemented local contract

`refund_obligation_report(p_station_id, p_start_at, p_end_at)` is a read-only `SECURITY DEFINER` RPC with empty `search_path`. It requires an authenticated OWNER or station-assigned MANAGER and `BASIC_REPORTS`. It returns dimensions by organization/station/refund payment method/status, period-bounded request/completed/failed events, current pending/reserved value and disjoint age bands, plus current partial-refund totals. Values remain exact integer centavos. Authenticated clients receive execute permission only; anonymous execute and direct ledger access are not granted by this migration.

The Owner Reports screen calls the RPC independently of the existing sales analytics so refund-report errors do not turn successful sales data into an overall screen failure. It presents pending, completed, failed, partial totals, oldest unresolved age, status/method breakdown, loading/error/empty states and retry. This is a narrow report addition, not the tablet redesign.

## Reproduce local checks

From `supabase/`:

```sh
deno test --allow-env --allow-net functions
deno check $(find functions -type f -name '*.ts' | sort)
```

Run the SQL suite only against a separately verified disposable database after applying the exact matching migration snapshot. The Group 4 run used the named disposable Docker DB, not `supabase db push --linked`.
