# Backend deployment and recovery runbook

## Group 4 latest status — 2026-10-09

This older runbook is superseded by `BACKEND_DEPLOYMENT_RUNBOOK.md` for current preconditions and ordering. Refreshed migration list: 54 local / 45 linked, nine local-only, no linked-only entry; `09042725` is absent from current source and linked history. The exact target remains unknown. No remote schema, history, function, schedule, account or business data changed.

Status: prepared for a future separately authorized staging/production operation. This document does not authorize deployment. No migration repair, SQL deployment, Edge Function deployment, Firebase deployment, data backfill, Play Console change, or release publication occurred during Group 2 or Group 3.

## Gate 0 — identify the target

1. Obtain authorized evidence for the exact backend of record used by the currently released Owner and Customer binaries: package/version/hash, endpoint/project reference, Auth provider, database name, Storage, Realtime, Functions, and Firebase/FCM configuration. The linked Supabase project is currently **not verified as production**.
2. Confirm project reference and environment with the release owner using protected dashboard metadata; never print tokens or credentials. Establish whether Owner and Customer share the same backend and identity namespace.
3. Freeze and record the reviewed backend Git SHA, migration checksums, Edge Function SHA, Android SHAs/version codes, and environment label. The repository proposal is in `BACKEND_REPOSITORY_STRUCTURE.md`; current backend/docs files are not tracked by the Android Git roots.
4. Stop if project identity, backup/restore, data lineage, or current-client compatibility is uncertain.

## Gate 1 — reconcile and rehearse

1. Capture an authorized encrypted backup and prove restore into a nonproduction isolated database. Do not put dumps in this repository or local audit files.
2. Export the migration history and schema-only definitions (objects, grants, policies, function bodies, triggers, indexes, Storage policies) from a confirmed nonproduction clone.
3. Reconcile the five pre-existing local-only migrations individually. The last linked-history query showed additional local candidates `20261009104237` (refunds) and `20261009112029` (guest ordering). Group 3 migration `20261009124434` was created afterward; its presence in linked history was not rechecked and this work did not deploy it. `20261009042725` was absent from the last linked query and current local source. The full checked-out 53-migration chain now replays in a disposable local database, but this is not a replay over the linked target's schema/data. See `MIGRATION_RECONCILIATION_PLAN.md`.
4. Test only an explicit, reviewed forward-reconciliation sequence against the restored clone. Verify Group 1 least-privilege protections, Group 2 refund contracts, guest-ordering function/policies, historical rows, both app versions, and rollback/forward-recovery.
5. Require isolated direct REST/RPC JWT tests for Owner, Manager, Staff, Rider, Customer, foreign organization/station and anonymous clients; prove direct protected DML fails and legitimate guarded RPCs work.

## Gate 2 — compatibility and release choreography

1. Verify currently distributed Owner and Customer builds before applying grant removals. Existing binaries that directly mutate protected tables may fail; do not restore broad grants as an exception. Define staged rollout/minimum-supported-version or an explicitly safe compatibility path using guarded RPCs.
2. Deploy backend before dependent new clients only after independent authorization. Apply reviewed SQL migration changes in the exact clone-validated order, then deploy version-pinned Edge Functions. Never combine a migration-history repair with an unreviewed `db push`.
3. Verify refund behavior in staging using synthetic centavo values: pending reservation, cash/external confirmation, failure/retry, concurrent over-refund, cancellation, physical return, reports and customer-visible masked status. No live payment/provider refund is simulated as complete.
4. Verify subscription verification, token ownership, RTDN idempotency/reconciliation and entitlements in a nonproduction project. Google Play license testing is separately required; do not change live subscriptions.
5. Before enabling Group 3 billing, provision reviewed server secrets, verify Play package/product/base-plan/service-account/RTDN configuration, deploy the approved schema before compatible Edge Function revisions, and configure a monitored schedule for `reconcile-play-subscriptions`. Choose and test a safe restore/reverification path for legacy token rows that lack ciphertext. None of these operational billing settings is configured or verified by this local work.
6. Release Owner and Customer app builds only through their approved tracks after backend compatibility checks. No app upload or publication is authorized by this runbook.

## Gate 3 — smoke, monitor, recover

Run synthetic workflows for sign-in, station selection, Customer order creation, Owner receipt, delivery assignment/collection proof, unpaid and paid cancellation, refund request/confirmation/failure, physical return, subscription entitlement, and account-scoped offline retry. Check database state and audit ledgers, not only UI messages. Monitor authorization failures, refund backlog/liabilities, duplicate operation IDs, inventory movement, sync outbox, notification queue, function failures, and subscription drift.

For failure, stop rollout and preserve logs/identifiers. Prefer a reviewed forward fix. Restore a backup only under an approved recovery decision with explicit assessment of transactions committed after the backup. Never delete ledger rows or rewrite migration history to make monitoring appear clean.

## Approval and stop conditions

Every linked or production mutation requires separate explicit authorization naming the exact environment and action. Stop on unverified target identity, failed restore proof, unexplained schema drift, unknown older-client write patterns, failed tenant isolation, financial mismatch, unresolved billing verification, or missing monitoring. Group 2 local approval does not authorize any operation described as a deploy step.
