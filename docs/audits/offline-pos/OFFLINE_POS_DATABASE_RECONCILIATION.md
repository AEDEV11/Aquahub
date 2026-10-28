# Offline POS Database Reconciliation — 2026-10-17

## Scope

The requested `productionDebug` build was subsequently validated, but no app or production database transactions were performed with it. Database counts below remain the disposable local baseline and prior read-only production inventory snapshot; the productionDebug build produced no backend writes. See `evidence/production-debug-build-summary.txt`.

Queries and tests used the disposable local database. Production was only inspected read-only and was not used for transactions. The isolated local database remained empty after the run.

## Results

| Measure | Expected for the five specified sales | Actual this run | Result |
|---|---:|---:|---|
| Orders | 5 | 0 | BLOCKED — sales were not entered |
| Payments | 5 | 0 | BLOCKED — no payment attempts were made |
| Confirmed revenue | ₱425 | ₱0 in local DB | BLOCKED — no control/offline sales were made |
| Purified refill stock | 95 after seed/sales | No product/balance row | NOT VERIFIED |
| Alkaline refill stock | 48 after seed/sales | No product/balance row | NOT VERIFIED |
| Mineral 500 mL stock | 198 after seed/sales | No product/balance row | NOT VERIFIED |
| Empty container stock | 19 after seed/sales | No product/balance row | NOT VERIFIED |
| Duplicate offline operation IDs | 0 | 0 rows exist | NOT VERIFIED — uniqueness was not exercised end-to-end |
| Pending Room operations | 0 after complete sync | Not queried; no transaction created | NOT VERIFIED |
| Failed reconciliation records | 0 expected for the five successful sales | 0 local records exist | NOT VERIFIED — no reconciliation attempt occurred |

The zero actual counts are not a passing financial assertion: they reflect that the test stopped before creating the test owner and any transactions. The 22-file/226-assertion SQL suite passed independently, but it does not substitute for reconciling real Android Room operations against function/RPC results.

## Initial data inventory

The disposable local baseline was empty. A single transient synthetic local Auth user was created for the direct function auth/signer check and then deleted; its admin lookup returned 404 and the matching profile query returned 0 rows. No app owner profile, organization, station, product, inventory, sale, order, payment, grant, lease, or pending operation was created. The invalid-station registration RPC rejected before the device insert, as confirmed by the function result and the migration’s authorization-before-insert order.

Production snapshot counts were recorded separately in `OFFLINE_POS_E2E_TEST_REPORT.md`. They are unrelated to this local baseline and were neither deleted nor modified.

## Database test evidence

`supabase test db --local`: 22 test files, 226 assertions, PASS. The suite covers schema-level invariants and tenant/security rules. Actual Android-to-Edge-to-RPC transaction reconciliation remains blocked by the missing local function route and tablet interaction failure.

Security-test limitation: local `public.order_number_counters` has RLS disabled; production has RLS enabled with direct grants limited to `service_role`. This drift was not changed. The local suite’s passing result must not be taken as parity evidence for that relation.
