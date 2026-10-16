# Production data cutover and verification runbook

Status: `IMPLEMENTED — PRODUCTION APPLY NOT AUTHORIZED`  
Last reviewed: 2026-10-04

This runbook describes the order of operations for importing retained Firebase history into Supabase. It does not authorize production writes, Firebase deletion, or Storage deletion. Every importer remains dry-run by default and requires its explicit apply and hosted-production confirmation flags.

## Checkpoints

Each domain must record these checkpoints in its report before moving to the next one:

`DRY_RUN_COMPLETE` → `CONFLICTS_RESOLVED` → `APPROVED_FOR_APPLY` → `APPLY_COMPLETE` → `VERIFIED` → `LEGACY_READ_REMOVABLE`

An importer that cannot prove a mapping or ownership invariant remains at `CONFLICTS_RESOLVED` or earlier. No importer may silently create a new organization, station, owner, role, or entitlement to resolve an ambiguity.

## Import order

Run the existing tools in dependency order, using the actual organization/station and Auth mappings from the report:

1. `migrate-staff.mjs` — profiles, memberships, and station assignments.
2. `migrate-settings.mjs` — organizations/stations and typed cloud settings.
3. `migrate-customers-products.mjs` — customer/product/catalog identity mappings.
4. `migrate-inventory.mjs` — opening/current ledger state only; do not invent movement history.
5. `migrate-orders.mjs` — order and item relationships after customer/product mappings.
6. `migrate-payments.mjs` — payment receipts linked to imported orders; preserve integer centavos.
7. `migrate-deliveries.mjs` — delivery/order/customer/staff references and event timestamps.
8. `migrate-expenses.mjs` — expense/station/creator references and centavo amounts.
9. `migrate-subscriptions.mjs` — organization subscriptions and historical status only; never treat an old purchase token as newly verified.
10. `migrate-storage.mjs` — asset objects and object-path references after entity UUID mappings exist; do not delete Firebase objects.

The exact command syntax and source requirements for each tool are documented in its `--help` output and domain migration document. No production command is run by this runbook.

## Verification requirements

Every report must include source count, imported count, skipped count, unresolved mappings, duplicate/conflict count, and the checkpoint. Verify at minimum:

- organization and station ownership is preserved;
- Supabase UUIDs are the new relationship keys and legacy Firebase IDs are metadata only;
- tenant and station references cannot cross organizations;
- timestamps and active/deleted semantics are preserved;
- money round-trips through integer centavos with no floating-point authority;
- order totals, payment status, delivery status, and COD settlement remain separate;
- subscription entitlements are server-derived and not granted by imported client data;
- Storage MIME type, size, checksum where available, and object-path mapping agree;
- imported rows are idempotent on a second dry-run/apply;
- no notification, FCM, Play, refund, or customer message side effects are emitted by historical imports.

## Post-import observation

Keep Firebase history and legacy objects read-only during the observation window. Compare Firebase-only reads and Supabase reads by entity mapping, monitor callable/Firestore/Storage access logs, and require zero unexplained business writes before marking a legacy path removable. A failed verification returns the domain to dry-run/conflict resolution; it does not trigger destructive rollback of Firebase data.

## Current state

Trusted disposable fixture credentials and external provider credentials are unavailable in this environment. Therefore no authenticated production-like proof or production import is claimed. The current gates remain unchanged.

