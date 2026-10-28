# AquaHub Owner Production Rollback and Incident Response — 2026-10-17

This is a prepared runbook, not evidence of an existing tested recovery system. Confirm actual access, backups, and prior hosting/function versions before approving any deployment. No production changes were made.

## Stop conditions

Stop dependent batches immediately if a migration/function/website deployment fails, project identity differs, authentication or RLS behavior changes unexpectedly, a transaction duplicates or loses an order/payment/stock movement, or the rollback/recovery point cannot be verified. Do not reset production, edit migration history, disable authorization, or delete pending offline receipts.

## Before every production batch

1. Record project/site identity, function versions, current release/host revision, migration versions, and Android/backend compatibility.
2. For a database change, verify a recoverable backup/restore point and rehearse the recovery action in isolation. The linked Supabase Free plan has no downloadable backup/PITR guarantee; there is no verified recovery point in this audit. Do not proceed until an acceptable recovery plan exists.
3. Save the exact reviewed source/configuration and test evidence. Store signing material and service secrets only in approved secret storage; never in this document, app, or logs.
4. Deploy one approved batch at a time. Verify its real behavior before proceeding to a dependent batch.

## Offline POS incident actions

- If grant authorization/signature verification fails, block new offline grant issuance. The local `OFFLINE_POS_ENABLED=false` switch is designed to stop grants while still accepting commits backed by valid previous grants; test this before deployment.
- Preserve encrypted/local pending sale records and transaction UUIDs. Do not clear Room, reinstall, or ask the user to recreate a sale until the server's idempotent canonical result has been checked.
- If stock lease or ledger inconsistency occurs, pause affected station offline selling, capture operation IDs and non-sensitive diagnostics, reconcile each pending operation idempotently, and resolve stock through audited server operations.
- Do not redeploy an older function until compatibility with grants already issued has been checked. If uncertain, keep the service safely unavailable and preserve pending receipts for recovery.

## Function rollback

After an approved function deployment, record the prior version/revision and new version. On regression, restore the known-good function revision only if its RPC/schema and pending-operation semantics remain compatible. For `delete-account`, do not simply roll back a partially completed cleanup without first identifying durable deletion state and affected records; the current local sequence has not passed failure-injection testing.

## Website rollback

Before website deployment, capture the hosting release identifier and confirm the Hosting console can restore it. Verify the public custom URL and canonical deletion URL anonymously after deployment. If content or routing fails, restore the prior hosting revision, verify the URL, and reapply the corrected deletion instructions through a tested forward deploy. The current environment could not verify the custom domain, so a production rollout is not ready.

## Android / Play response

- A signed AAB is available locally at `AquaHub/app/build/outputs/bundle/productionRelease/app-production-release.aab`; its signer matches the configured keystore alias. Compare its certificate fingerprint with the Play Console upload certificate before any owner-controlled upload. Do not replace the existing Play upload key or submit an unsigned bundle.
- After an approved staged release, monitor crash/ANR, auth failures, offline sync queue age, duplicate/rejected transactions, stock ledger reconciliation, purchase verification, RTDN backlog, and support reports.
- If a release causes data integrity or authorization issues, halt rollout expansion; use Play Console halt/rollback options available for that track and deploy a forward fix. A previous Play version may not restore server compatibility or repair persisted data.
- Do not make a public rollout or percentage increase without explicit approval.

## Evidence required to close runbook readiness

Demonstrate restore from a verified backup; prove function and website rollback; rehearse an offline sale response-loss and replay incident; validate customer/station data isolation; and record named operators, alert routing, thresholds, and recovery time objectives. Current backup, monitoring, rollback, and incident rehearsal evidence is NOT VERIFIED.
