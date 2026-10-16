# AquaHub backend source of truth and lineage

Snapshot: 2026-10-09. This records the checked-out working tree and a read-only linked Supabase comparison. The linked project is not established as the backend of record for either Closed Testing app. No remote schema, migration history, function, or business record was changed.

## Decision

**Production of record: NOT VERIFIED.** In the current dirty working tree, both Android app implementations use Supabase for business identity/data flows; Firebase Messaging remains in the apps. This does not prove the released Owner or Customer binaries use the same Supabase project, or that Firebase users and historical records have been fully migrated. Firebase configuration/rules and workspace instructions still conflict with the working-tree source. Treat the release cutover as unverified and do not remove compatibility paths based on this document.

## Source-of-truth matrix

| Capability | Current checked-out Owner source | Current checked-out Customer source | Production authority / confidence |
|---|---|---|---|
| Authentication | Supabase Auth repository and session | Supabase Auth repository and session | Supabase in the working tree; Closed Testing backend and identity migration NOT VERIFIED. |
| Profiles | Supabase `profiles`, cached in Room | Supabase profile/customer repositories | Supabase in source; old Firebase UID ↔ Supabase UID mapping NOT VERIFIED. |
| Organizations/businesses | Supabase organizations, memberships, RPCs | Supabase business/station catalog | Supabase in source; ownership/history reconciliation NOT VERIFIED. |
| Stations | Supabase station queries/RPCs; Room cache | Supabase public station catalog | Supabase in source; exact Owner/Customer release target NOT VERIFIED. |
| Customers | Supabase catalog/RPCs, Room cache and outbox | Authenticated Customer profile and order snapshot | Supabase in source; duplicate/legacy identity merge completeness NOT VERIFIED. |
| Products/prices | Supabase data sources and server order snapshot | Supabase catalog | Supabase is authoritative in current checkout; historical price parity NOT VERIFIED. |
| Orders | Guarded Supabase RPCs; Room snapshot/outbox | `create_order`, direct authorized reads, legacy-signature `cancel_order` | Supabase in current source. Linked schema comparison exposes pre-Group-1 definitions; released client compatibility NOT VERIFIED. |
| Payments/refunds | `record_payment` and Group 2 refund RPCs; integer-centavo refund ledger; Room cache | Customer submits orders; no Customer-side refund mutation | Supabase in source. Group 1/2 schema is not linked; external refund provider completion is manual and NOT VERIFIED. |
| Inventory | Supabase transactional order/inventory RPCs; Room cache/outbox | Catalog availability only | Supabase in source; offline/multi-device reconciliation NOT VERIFIED. |
| Staff/riders | Membership/role checks, RPCs, Edge Functions | No station management | Supabase in source; deployed role/Edge Function versions NOT VERIFIED. |
| Subscription | Play purchase client + Supabase verification/entitlement reads | No owner subscription management | Google Play is intended purchase authority; Play Console and deployed verifier/RTDN configuration NOT VERIFIED. |
| Reports | Derived from orders, receipts and refund ledger; local cache | Not applicable | Derived source; history/report parity NOT VERIFIED. Cancelled/rejected receipts are now excluded from Owner sales aggregates. |
| Notifications | Firebase Cloud Messaging transport plus local Supabase notification outbox/dispatch sources | Firebase Messaging transport plus Supabase-backed order state | Delivery and trigger source in production NOT VERIFIED. Firebase deploy descriptors and current checkout do not agree. |

“Current source” means the dirty working tree, not the app currently installed by customers. `AquaHub/` and `AquaHubCustomer/` are separate Git roots. The workspace parent has no Git root; `supabase/` and `docs/production-readiness/` are not tracked by either Android repository. Existing local property files, service credentials, signing configuration, tokens and personal data were not copied into these notes.

## Environment and evidence

- Owner and Customer Gradle sources in this checkout have Supabase client configuration. Build-time endpoints/keys are external inputs and were not reproduced here.
- The Customer app still uses Firebase Messaging. Firebase rule files and Firebase deployment/config files remain in the workspace; Customer Firebase Function package files are deleted in the dirty working tree, while repository instructions still describe Firebase Functions as active. The deployed Firebase state is unknown.
- The latest read-only `supabase migration list --linked` on 2026-10-09 shows 54 local files and 45 linked versions. Nine local versions have no linked-history entry: `20261009030621`, `20261009051935`, `20261009060109`, `20261009080803`, `20261009093931`, `20261009104237`, `20261009112029`, `20261009124434`, and Group 4 `20261009135735`. `20261009043510` is present on both sides. No linked-only version appeared. `20261009042725` is absent from both current source and linked history; it appeared only in a separate local database's historical ledger.
- A read-only `supabase db diff --linked --schema public,app_private` returned a non-empty difference. The linked-side definitions include broad authenticated DML grants on orders/order_items/payments and legacy `create_order`/`cancel_order` bodies, while the local migration source contains Group 1 restrictions and Group 2 ledgers. The CLI generated diff includes destructive/drop statements in its comparison direction; it was inspected as diagnostic output only and never applied. This confirms substantial linked-vs-checkout schema divergence, but not which project serves released users.
- The diff was schema-only; no business rows were selected. Earlier Group 3 evidence records a clean disposable replay through `20261009124434`. Group 4's `20261009135735` was applied only to the existing named disposable refund-test database and exercised by the updated SQL suite. The later direct-to-production refresh below supersedes the historical note that a clean 54-file replay had not been performed: that replay now passes locally. No local replay used the linked database's exact starting schema.

## Required verification before any cutover

1. Identify the exact Owner and Customer Closed Testing version codes, APK/AAB hashes, release endpoints, Firebase project/database, Supabase project reference, and deployment revisions through authorized release records or Play/Firebase/Supabase consoles. Do not infer from the current CLI link or from local artifacts.
2. Reconcile Firebase and Supabase Auth identities, organization/station/product/customer/order/payment/stock history and pending outbox with synthetic or approved staging identities. Never rewrite live financial history as part of verification.
3. After target identity is proven, obtain the required pre-change backup and migration-history export into a restricted encrypted location and compare exact RPC signatures/bodies, constraints, indexes, grants, RLS, triggers, storage policies and Edge Function versions. Never export ambiguous production records.
4. Validate both currently released app versions against the confirmed server contract and safe synthetic identities before changing it. A hosted staging project is not required by the selected plan; preserve the old `cancel_order(uuid,text,text)` contract unless compatibility evidence establishes a safe transition.
5. Keep Firebase compatibility and release rollback paths until the actual production-of-record and data lineage are independently established.

Until all five steps are evidenced, source-of-truth for deployed users and release cutover remain **NOT VERIFIED** and release remains **NO-GO**.

## Group 3 local verification addendum — 2026-10-09

This addendum supersedes the earlier isolated-replay endpoint, not the production lineage decision. The newly added local migration `20261009124434_group3_subscription_security_and_catalog.sql` was replayed after `20261009112029_guest_web_ordering.sql` and `20261009104237_group2_refunds_and_order_integrity.sql` against the disposable localhost Supabase database. The clean replay and current SQL test results are in `TEST_MATRIX.md`. No linked database history/schema/rows were changed; linked migration history was not rechecked after the new migration was created, and no production endpoint was inferred from the local test project.

Group 3 adds the local Owner purchase-verification contract through `verify-play-purchase`, RTDN/reconciliation handlers, service-role RPCs, subscription history, and encrypted token persistence. This is source and isolated-schema evidence only. It does not establish deployed function versions, Google Play project/package alignment, subscription records, or the release binary's environment. Production-of-record remains **NOT VERIFIED**.

## Group 4 hosted verification addendum — 2026-10-09

The linked migration list was refreshed after the Group 4 migration was added. Current count is **54 local migration files / 45 linked versions**, with nine local-only entries listed above, shared `09043510`, and no linked-only entry. The older “five pre-existing local-only” statement was accurate at an earlier checkpoint and is superseded. The linked migration result does not establish production identity.

Read-only linked metadata continues to show broad authenticated DML grants on `orders`, `order_items`, and `payments`, and the linked project's migration history does not contain the current local security/refund/subscription/report contracts. Eight active hosted Edge Function names were observed, but deployed source revisions were not matched to this checkout. The project has nonempty business tables and remains environment-unknown; no staging or production writes were made.

The refund-obligation RPC and Owner report card are implemented locally. The migration was manually applied only to the named disposable DB for tests, not to the linked DB and not to its migration ledger. Twelve SQL suites / 84 pgTAP assertions and 34 Deno tests pass; all 22 function TypeScript files typecheck. A fresh clean replay of all 54 files is now verified locally; hosted REST/JWT tests, Owner/Customer hosted E2E, and recovery-grade backup/restore remain unverified. See `RELEASE_ENVIRONMENT_VERIFICATION.md`, `HOSTED_SECURITY_TEST_MATRIX.md`, and `BACKEND_INTEGRATION_RESULTS.md`.

## Group 4 direct-to-production verification refresh — 2026-10-09

The user-selected deployment model is local disposable replay followed by direct production deployment only after target verification; a hosted staging project is not a prerequisite. Play Console read-only inspection confirms both named apps are on Closed testing, and Owner Production is inactive. The visible Owner Alpha label is `11 (aq-1.0.6)`, while the locally available signed Owner AAB is versionCode 12 / `aq-1.0.7`; artifact hash/provenance cannot be matched to Play from available evidence. Customer's exact served track version is also unverified. Both local AABs and current local build inputs point to Supabase ref `pfkxrggexueltytxtqil`, but this establishes local-build intent only, not release backend authority.

A new disposable Supabase project replayed all 54 current migrations from an empty local database and passed 12 SQL test files / 84 assertions. This supersedes the earlier statement that a fresh post-Group-4 replay had not run. The linked schema remains materially divergent; its diagnostic diff includes drops and is not deployable. No production-linked export/restore was attempted because identity is unconfirmed and such a dump would contain real private data. The actual released backend, linked-schema clone compatibility, secure backup/restore, deployed Function source lineage and REST/JWT behavior remain **NOT VERIFIED**. Accordingly, source-of-truth for released users remains **NOT VERIFIED** and direct production deployment is **NO-GO**.
