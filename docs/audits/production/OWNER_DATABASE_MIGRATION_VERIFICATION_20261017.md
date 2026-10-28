# AquaHub Owner Database Migration Verification — 2026-10-17

## Result

**Migration parity: PASS. Production mutation: none. Migration deployment: not required by current repository diff. Prior disposable migration/integration tests passed.**

The linked production Supabase project is `pfkxrggexueltytxtqil`. A read-only migration inventory showed **69 local versions and 69 applied production versions**, with no local-only or remote-only versions. Relevant offline POS migrations already applied:

- `20261011150000_offline_pos_reservations`
- `20261012160000_offline_pos_immutable_payment_identity`
- `20261012160001_fix_inventory_quantity_conflict_target`

The database contains six `app_private.offline_pos_*` tables and seven related routines. Private offline tables have RLS enabled and no direct client/service-role grants in the inspected metadata. `register_offline_pos_device`, `issue_offline_pos_grant_and_lease`, and `reconcile_offline_pos_lease(s)` are `SECURITY DEFINER` routines executable by `authenticated`; `get_offline_pos_device_key` and `commit_offline_pos_sale` are service-role-only. Production access was read-only for this audit.

The offline POS migration was applied in the disposable project `/tmp/aquahub-off03-disposable` and the SQL suite passed 226 assertions across 22 files, including role/grant boundaries, tenant scope, stale stock revision, reserved stock floor, digest-bound idempotency, expired lease retention, and audited reconciliation. One competing reservation test rejected over-allocation; Android integration separately verified the core offline commit/replay path. These tests were isolated from production. The temporary database no longer exists in the current environment, so they were not rerun during this check.

## Not proven

- Schema behavior under a new clean install or migration rollback/recovery.
- Complete RPC authorization under adversarial role/tenant combinations. Prior SQL role/tenant tests pass, but the full production Data API surface has not been tested.
- Concurrent final-stock sale handling across all writers, stock lease conflict/expiration, modified payload rejection, clock rollback, full reboot, and two-Android-device races. Core response-loss idempotency and one lease-vs-guest race passed in disposable tests.
- Restore readiness. No current backup/restore point or successful restore drill was evidenced; Free plan does not provide downloadable backups/PITR.
- Current rerun of the migration test suite. Supabase CLI, PostgreSQL/`psql`, Docker and the prior isolated Supabase instance are unavailable in this environment.

## Safety disposition

No migration should be applied merely to force a count match; none is pending. Do not reset the database or rewrite migration history. Before the offline function deployment batch, run migration/RPC/tenant/RLS/concurrency tests in an isolated project, confirm relevant function secrets and signing key correspondence, and establish a recoverable production backup/restore point. If a future migration is needed, review exact SQL for destructive operations and apply only after explicit deployment approval. Stop dependent deployment batches after any migration failure.
