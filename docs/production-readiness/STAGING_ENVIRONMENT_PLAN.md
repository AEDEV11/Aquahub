# Staging environment plan

Status: **Local isolated verification available; hosted staging intentionally not selected.** The user selected local disposable Supabase/PostgreSQL for pre-deployment testing, followed by direct-to-production only after a separately approved exact preflight; do not require a hosted staging project or provision a branch. Do not use the linked project `pfkxrggexueltytxtqil` as staging; its environment is unknown and it contains nonempty business tables. The latest fresh local replay now applies all 54 migrations and passes 12 SQL files / 84 assertions; hosted REST/JWT integration remains unverified.

## Recommended target

Prefer a separately provisioned Supabase staging project owned by the organization, in a region chosen to match the verified production deployment. Give it its own Auth users, database, storage, service-role secret, Google API test credentials, FCM project/test devices, and explicit `NONPRODUCTION` label. Do not clone production rows. If paid hosted staging is not approved, continue with a dedicated local Supabase project and synthetic fixtures; do not treat that as hosted REST verification.

Persistent branches are a second choice only if their isolation, branch database reset behavior, secret inheritance, extension support, and CI/CD automation are reviewed. No automatic branch or merge-to-production behavior is authorized.

## Isolation controls

- Synthetic-only users, businesses, stations, products, customers, orders, payments, refunds, and subscription fixtures.
- Dedicated test email/SMS configuration; local mail sink or provider sandbox. Disable real SMS delivery, external payment/refund execution, and real-user push destinations.
- Use distinct, least-privilege test credentials per Owner, Manager, Staff, Rider, Customer, and anonymous/guest role.
- Keep audit events enabled. Redact access tokens, OTPs, purchase tokens, payment credentials, full addresses, and private customer data from logs.
- Configure test Play package/service account only when licensed sandbox testing is explicitly authorized. Never reuse production subscription tokens.
- Tag test artifacts and exported diagnostics `NONPRODUCTION`; prohibit restoration into production.

## Current local test fixture and scope

The existing disposable database container is explicitly named `supabase_db_aquahub-group2-refund-test`, project label `aquahub-group2-refund-test`. It was used for rollback-only SQL tests and synthetic data. The Group 4 reporting migration was applied directly to this disposable schema; it was not registered as a deployed/linked migration. The local tests never accessed the linked database. This proves local SQL behavior for that schema snapshot, not clean full-chain replay after Group 4 or HTTP behavior.

Before calling a future local project reproducible, create it with a unique project name and nonconflicting ports; replay the exact reviewed migration commit; run SQL and Deno suites; record sanitized logs and checksums. Never reset the existing `WaterStationApp` local database or any database without verifying its identity first.

## Hosted staging setup gates (not executed)

1. Obtain explicit hosted-project provisioning approval and cost/region owner.
2. Create a separate project with no production data; confirm project reference/environment in the authorized dashboard.
3. Apply a clean reviewed backend revision through staging-only credentials; no remote migration repair.
4. Configure test Auth, Storage, Realtime, Edge Function secrets, Pub/Sub test transport, and isolated notification providers.
5. Restore only a synthetic fixture or a separately approved, irreversibly anonymized dataset; test backup restoration first.
6. Run actual REST/RPC JWT identity matrix plus Owner and Customer end-to-end tests.
7. Verify clean teardown/retention and credential rotation without touching production.

## Backup/restore rehearsal plan

For the hosted test project, take an encrypted schema/data backup containing only synthetic test data. Restore it into a second isolated disposable project/database, then verify migration ledger, constraints, functions, RLS, grants, Storage bucket policies, Edge Function configuration inventory, and app workflows. Keep secret values in the approved secret manager, not the backup or Git. Record checksums and restore timestamps, then securely remove temporary test copies under the staging retention policy. No backup/restore rehearsal against a hosted project has been run; this is **BLOCKED** pending authorized staging provisioning.
