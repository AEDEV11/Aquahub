# Backend deployment runbook (future use only)

**No deployment occurred.** This runbook is not deployment authorization. The user-selected path is local disposable validation followed by direct-to-production only after identity and recovery gates pass; a hosted staging project is not required. The linked project is still not confirmed as the production-of-record, and the local backend source has no Git commit/revision provenance.

## Preconditions

1. Verify production-of-record and both released client versions using `RELEASE_ENVIRONMENT_VERIFICATION.md` and `CLIENT_VERSION_COMPATIBILITY.md`.
2. Use only a fresh disposable local Supabase/PostgreSQL project for migration and SQL tests until production target identity is established. A production-clone/hosted staging project is not part of the user's selected plan; do not create one without separate authorization.
3. Create/configure the dedicated backend Git repository without copying credentials. Record reviewed Owner, Customer, backend commits, artifact hashes, migration head/checksums, Edge Function source revisions, and environment label using `supabase/RELEASE_MANIFEST.example.json`.
4. Reconcile all 54 current local migration files against the confirmed target's actual schema, migration ledger, function signatures/bodies, triggers, grants, RLS, indexes, and Storage policies. Resolve each of the nine local-only history entries individually; do not repair history by timestamp alone. The linked diff currently contains drops and is diagnostic only.
5. Clean-replay migrations in a fresh disposable local database and run all local SQL/Deno/Android checks. Local replay now passes all 54 migrations and 12 SQL files / 84 assertions. Before production approval, separately prove REST/RPC/JWT roles and Owner/Customer compatibility using authorized synthetic production test identities; do not use real customer/payment records.
6. Prove compatibility with the exact currently released clients or approve a minimum-version transition. Complete a recovery-grade backup and restore rehearsal, including explicit Auth/Storage preservation. Current local dump rehearsal is synthetic/incomplete and does not satisfy this gate.

## Direct-to-production preflight — current decision: NO-GO

Read-only Play Console evidence confirms Owner and Customer Closed testing; Owner Production is inactive. The Owner Alpha summary displays `11 (aq-1.0.6)`, while the local signed Owner artifact is `aq-1.0.7`. Local Owner/Customer artifacts and current build inputs reference Supabase project `pfkxrggexueltytxtqil`, but there is no artifact-provenance match to either Play upload. Customer's exact served release is unknown. This does not verify the true backend target.

The 54-migration local clean replay and 84 SQL assertions pass, but the linked history has 45 entries and nine local-only migration versions. The read-only schema diff is nonempty and includes DROP statements in its direction. There is no verified production backup and restore covering required Auth/Storage state; the temporary local dumps are synthetic and incomplete. The released client API contract, hosted role tests, and deployed Function/source revisions also remain unmatched. Therefore the exact migration/function list, user impact/downtime and safe recovery plan cannot yet be responsibly fixed. **Stop before remote mutation.**

### Exact preflight blockers to clear

1. Obtain artifact/version/hash/signing and endpoint evidence for the currently served Owner and Customer Closed Testing releases; match both to their source revisions and backend project.
2. Confirm the linked Supabase project is that backend using authorized project/deployment records, not the CLI link or local endpoint alone.
3. Inventory each of the nine local-only migrations against the target's actual object definitions/data and identify a non-destructive per-change apply set. Never apply the generated diff or bulk-push divergent history.
4. Complete a permission-restricted, encrypted backup/export and successful isolated restoration rehearsal for database plus required Auth/Storage identities, metadata and objects; document Free-plan limitations and external configuration/secrets inventory.
5. Verify backward compatibility for the exact released app versions and deployed Edge Function revisions; run required synthetic role and financial checks through the target API.
6. Then publish a new exact migration/function manifest with expected impact, maintenance window, pre/post checks, monitoring, and forward-recovery steps, and obtain separate approval for those named live changes.

No `db push`, migration repair/reset, production export, Function deployment, scheduler activation, Firebase operation, Play setting change, purchase, or remote Git push was performed.

## Future production rollout (separate authorization required)

Only after the exact direct-production preflight passes and the release owner separately approves the named live changes: secure backup/restore readiness; agreed maintenance/compatibility window; migration with explicit forward-recovery plan; deploy server functions; server-side smoke tests using authorized synthetic identities; monitor both app clients; then close old-client paths only after adoption is proven. Hosted staging sign-off is not a prerequisite under the selected plan, but direct production verification must be carefully bounded and approved. Never deploy Functions ahead of required SQL, or tighten a contract while currently installed clients depend on an unsafe direct write. Do not upload AABs or change Play Console as part of this backend runbook.

## Stop/incident procedure

Stop rollout for any 401/403 spike affecting legitimate paths, cross-tenant visibility, duplicate operation, incorrect paid/refund totals, inventory divergence, missing notification/order, entitlement drift, or failed backup restore. Preserve sanitized operation/correlation IDs and migration/function revision. Prefer a reviewed forward fix; restore only after assessing transactions committed since backup. Never delete financial history or rewrite migration history to quiet errors.
