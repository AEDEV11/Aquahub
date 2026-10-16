# Hosted security test matrix

Snapshot: 2026-10-09. No staging project or representative hosted JWT identities were available/authorized; hosted REST/RPC tests are **BLOCKED**, not passed. SQL tests below ran only against an isolated local disposable database.

| Identity / scenario | Expected REST/RPC behavior | Persisted-state assertion | Status / evidence |
|---|---|---|---|
| Owner A reads/writes own organization and station | Allowed only through intended read policies and guarded RPCs | Only A tenant rows change | BLOCKED — hosted JWT not available; local RLS/invariant tests cover selected contracts |
| Owner A accesses Owner B | Deny/no data | No B rows exposed or mutated | BLOCKED hosted; local tenant/role synthetic tests in `refund_obligation_report.sql`, `refund_workflow.sql`, `guest_web_ordering.sql` |
| Manager assigned station | Allowed within assigned station and permitted operation | No unassigned station change | BLOCKED hosted; local refund-report test covers assigned vs unassigned report access |
| Staff attempts owner-only report/refund/plan operation | Deny | No financial or subscription state change | BLOCKED hosted; local report and refund SQL fixtures exercise role denial |
| Rider reads payment details or records collection without assignment/proof | Deny except the narrow assigned delivery proof/collection contract | No unauthorized payment/refund state change | BLOCKED hosted; current SQL contracts cover selected rider authorization, not HTTP end-to-end |
| Customer reads own order/refund status | Allow limited own data | No owner financial mutation | BLOCKED hosted; local guest-ordering and refund SQL tests cover selected customer-read isolation |
| Anonymous/guest invokes protected table/RPC | Deny | No rows created or disclosed | BLOCKED hosted; SQL tests verify selected anonymous grants and guest RPC isolation |
| Authenticated REST direct INSERT/UPDATE/DELETE on orders/items/payments/refunds | Deny where RPC required | No changes | BLOCKED hosted. Linked metadata previously showed broad authenticated DML on orders/items/payments; local restrictions are not deployed |
| Forged organization/station IDs or idempotency key probing | Deny without cross-tenant disclosure | No foreign row read/change | BLOCKED hosted; local RPC tests cover synthetic cross-tenant denial |
| Expired/revoked access token | 401/authorization denial | No state change | BLOCKED hosted; Supabase Auth hosted session behavior not exercised |
| Plan/quota bypass by direct API call | Deny at trusted database boundary | No over-limit state | BLOCKED hosted; local SQL tests exercise selected Free/expired-plan constraints |
| Duplicate/replayed payment/refund operation | Same idempotent result, no duplicate ledger effect | One financial operation | BLOCKED hosted; local transaction tests cover idempotency, concurrency, and exact centavos |
| Invalid order transition or refund above available amount | Reject | Order, receipt, stock, and refund ledger unchanged | BLOCKED hosted; local SQL constraints/RPC tests cover these cases |

## Local evidence, not hosted evidence

`supabase/tests/*.sql`: 12 files, 84 pgTAP assertions passed on `supabase_db_aquahub-group2-refund-test`; fixtures rollback. Deno: 34 tests passed and all 22 TypeScript files checked. These results do not exercise PostgREST JWT authentication, hosted RLS, deployed function versions, CDN/network behavior, or a production release client.

## Required hosted test capture

For each matrix row, record synthetic identity ID (not token), request method/path/RPC and sanitized body, HTTP status/error category, expected vs actual, before/after row counts and centavo sums, correlation/operation ID, schema/function revision, and tester/date. Never save raw JWTs or purchase tokens. Test both allowed and denied paths. After tests, verify no fixtures remain or that all are isolated under the staging retention policy.
