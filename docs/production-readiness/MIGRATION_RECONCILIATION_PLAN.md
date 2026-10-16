# Supabase migration reconciliation plan

Snapshot: 2026-10-09. Read-only linked-history check refreshed after Group 4. No linked migration was repaired, applied, reset, or backfilled.

## Verified migration-history comparison

Read-only command: `supabase migration list --linked`.

| Version | Local source | Linked history | Purpose / dependency | Status |
|---|---:|---:|---|---|
| `20261009030621` | Yes | No | Owner sync, cancellation notification and related RPC changes; earlier than the shared container migration. | One of five pre-existing local-only migrations; do not replay blindly. |
| `20261009043510` | Yes | Yes | Container movement synchronization. | Shared history entry. |
| `20261009051935` | Yes | No | Walk-in delivery/payment-proof guards and Storage policy support. Depends on payment/order/Storage foundations. | One of five pre-existing local-only migrations. |
| `20261009060109` | Yes | No | Preserves client-account payment method during order updates. | One of five pre-existing local-only migrations. |
| `20261009080803` | Yes | No | Restricts direct order/payment table writes and adds guarded account charge behavior. | One of five pre-existing local-only migrations. |
| `20261009093931` | Yes | No | Group 1 authorization, payment integrity, idempotency, and server-side subscription limit guards. | One of five pre-existing local-only migrations. |
| `20261009104237` | Yes | No | Group 2 refund/cancellation/return ledgers and RPCs. | New local candidate migration; not included in the five historical divergences. Not deployed. |
| `20261009112029` | Yes | No | Guest web-ordering RPCs, tracking/rate-limit support, and related policies. | Additional local candidate discovered in the latest history query; it is not one of the five historical divergences and is not deployed. |
| `20261009124434` | Yes | No | Group 3 Play subscription token encryption, atomic purchase/trial RPCs and intended price catalog. | Refreshed linked list confirms local-only; current clean 54-migration disposable replay includes it; not deployed. |
| `20261009135735` | Yes | No | Group 4 `refund_obligation_report` aggregate RPC and role/plan checks. | Refreshed linked list confirms local-only; manually applied only to the named disposable test DB; not in linked schema/history. |
| `20261009042725` | No | No | Previously named in an older audit snapshot. | Not present in the current read-only linked list or this checkout; stale claim corrected. |

The refreshed read-only query reports **54 local migration files and 45 linked history entries**. Nine current local versions are absent from linked history: `09030621`, `09051935`, `09060109`, `09080803`, `09093931`, `09104237`, `09112029`, `09124434`, and `09135735`. `09043510` is shared; the refreshed result has no linked-only version. Historical references to five pre-existing local-only migrations were accurate at an earlier query but are superseded by this comparison. `09042725` is absent from current source and linked history; it was present only in a separate local database history. The linked project's production role remains unknown.

## Definition comparison

Read-only command: `supabase db diff --linked --schema public,app_private`. It rebuilt the checked-out migration chain in a disposable shadow and produced a non-empty schema difference against the linked database. The comparison output includes the linked-side legacy `create_order` definition that returns an existing operation before authorization and marks Owner walk-in orders paid, an older `cancel_order` body that restores stock unconditionally, and broad `authenticated` DML grants on orders, order_items and payments. The local migration chain contains the Group 1 protections and Group 2 additions absent from that linked-side schema view.

The CLI’s generated comparison included drops of local-only objects in its selected diff direction. This output was treated as evidence only; no generated SQL was applied or saved as a migration. It is not an acceptable deployment patch. It does not establish that this linked project is production.

The disposable local project was clean-reset and its 51-file migration snapshot replayed through Group 2 `20261009104237`; the SQL suite passed nine files and 43 pgTAP assertions. The latest workspace also contains `20261009112029_guest_web_ordering.sql`, which was not present in that isolated snapshot and was not replay-tested in this Group 2 run. The tested bootstrap proves only the Group 2 chain against the local baseline; it does not validate replay over the linked project's schema or data. A concurrent refund test was also run against the isolated PostgreSQL instance (same operation returned one ledger row; competing requests did not exceed the refundable cap).

## Safe reconciliation strategy

1. Confirm the actual production-of-record and exact Supabase project reference using release records and Play/Firebase/Supabase console evidence. Until then the linked target is `UNKNOWN`.
2. For a designated nonproduction clone, capture a schema-only dump, migration history, object-owner/grant/RLS inventory, function definitions, Storage policies, and a data-safe backup/restore proof. No customer rows should enter local artifacts.
3. Compare each absent migration’s actual effects with the clone. For each changed table/function/trigger/storage policy, label it `present and equivalent`, `absent`, `partial`, or `conflicting`; inspect dependencies and old-client RPC signatures. Do not equate a matching timestamp with matching effects.
4. Reconstruct provenance for any out-of-band SQL. If a migration’s effects are already present, do not replay it; if missing, test the original SQL against a clone. If partially applied or incompatible, prepare a new forward-only, idempotent reconciliation migration with explicit preconditions and recovery SQL.
5. Apply the proposed order to a disposable clone created from the confirmed target schema, not only to a clean local bootstrap. Verify grants, RLS, RPC signatures/bodies, indexes, triggers, Storage access, Owner/Customer compatibility, and preservation of synthetic historical rows.
6. Prepare an operator-approved backup, staged rollout, smoke tests, and forward-recovery plan. Only then request separate authorization for linked/production mutation. Never use remote `db push`, `migration repair`, linked reset, or data backfill as a shortcut.

### Candidate dependency order (not deployment authorization)

`20261009030621` → shared `20261009043510` → `20261009051935` → `20261009060109` → `20261009080803` → `20261009093931` → `20261009104237` → `20261009112029` → `20261009124434` → `20261009135735` is the local chronology and a candidate dependency direction. All 54 current local migrations now replay from empty in a disposable local Supabase project, and the 12-file/84-assertion SQL suite passes. This is **not** a final remote apply order until every effect is reconciled against the actual target. Nine missing linked-history entries make blind replay unsafe.

## Risk and recovery

- Group 1 removes direct mutation privileges. Old clients that still write orders/payments directly may fail; do not restore broad grants. Stage exact currently released client builds against the proposed contract.
- Group 2 adds immutable receipt/refund-event rules, payment-refund/cancellation/return tables and guarded RPCs. Existing rows are not rewritten, but old binaries cannot call new Owner refund RPCs. The old Customer `cancel_order(uuid,text,text)` signature is retained for unpaid/eligible customer cancellation.
- A failed migration should be recovered by a reviewed forward fix or a verified restore of the disposable/staging clone. No automatic production rollback is safe if real refund/order rows have been appended.
- Production migration, history repair, schema reset, backfill, Firebase deployment/deletion, and Edge Function deployment remain prohibited under this local approval.

## Current verification states

| Gate | Result |
|---|---|
| Read-only migration history | PASS for refreshed query: 54 local files, 45 linked versions, nine local-only entries, shared `09043510`, no linked-only entry. |
| Read-only linked schema comparison | PASS with significant drift and dangerous generated reverse/drop statements; diagnostic only. |
| Clean isolated migration bootstrap | PASS: fresh empty disposable project replayed all 54 current migrations; 12 SQL files / 84 assertions pass. This does not validate replay against linked schema or data. |
| Group 4 migration `09135735` | Applied only to the named disposable database; SQL/report regression tests passed. Not deployed or entered into linked history. |
| Replay against schema clone of linked target | NOT RUN. |
| Actual production-of-record | NOT VERIFIED; see `RELEASE_ENVIRONMENT_VERIFICATION.md`. |
| Remote migration repair/deploy | NOT RUN and not authorized. |

## Group 3 clean isolated replay addendum — 2026-10-09

The previous test snapshot was stale: its database function definition for `create_guest_order` did not match the checked-out `20261009112029_guest_web_ordering.sql` (it lacked the `request_fingerprint` allowlist). This was discovered by a failing isolated guest-ordering assertion. After verifying that the target was the disposable localhost container `aquahub-group2-refund-test` on port 55322, the local test database was reset and replayed from the checked-out migration sources. The replay completed through the new `20261009124434_group3_subscription_security_and_catalog.sql`; a read-only check confirmed the current `create_guest_order` definition now contains the fingerprint handling. This reset affected only that disposable local database, not any linked or production target.

The fresh replay is the correct base for the latest 11-file/81-assertion SQL suite result in `TEST_MATRIX.md`. The exact linked schema remains unreconciled, no historical financial/user data was migrated, and migration history was not repaired remotely. The dependency chain in this report is still only a local source chronology, not authorization or proof of a safe remote replay order.

## Group 4 latest linked comparison and local verification — 2026-10-09

The linked migration list was refreshed after the Group 4 migration was created. Current count is 54 local migration files versus 45 linked versions; nine files lack linked-history rows (listed above), with no linked-only version. `20261009042725` is not in linked history or source; earlier “linked-only” wording was stale. The 45-row history is not evidence this project is production.

At that Group 4 checkpoint, the report migration had been applied to the pre-existing disposable `aquahub-group2-refund-test` database, not a fresh 54-file replay. All 12 SQL files passed, 84 assertions total. The clean-replay requirement in this historical paragraph was subsequently satisfied locally as recorded in the direct-to-production preflight refresh below; comparison against the linked schema/data remains outstanding. Do not repair history or apply SQL to the linked environment.

## Direct-to-production preflight refresh — 2026-10-09

The clean-replay gate is now **PASS for local disposable bootstrap only**. A newly created isolated Supabase project under `/tmp/aquahub-clean-replay-20261009-bk6es9` applied all 54 current migration files in order from an empty database; the local migration ledger contains all 54. `supabase test db --local --workdir /tmp/aquahub-clean-replay-20261009-bk6es9` passed 12 files / 84 pgTAP assertions. `supabase db lint --local --workdir /tmp/aquahub-clean-replay-20261009-bk6es9` exited successfully with one warning: unused parameter `p_payment_status` in `public.import_legacy_order`.

The read-only linked history remains 54 local / 45 linked, with these nine local-only versions: `20261009030621`, `20261009051935`, `20261009060109`, `20261009080803`, `20261009093931`, `20261009104237`, `20261009112029`, `20261009124434`, `20261009135735`; there is no linked-only version in the current history result. `20261009042725` is absent from current source and linked history (it was observed only in a separate local DB ledger). The clean local replay does not reconcile what any of those migrations would do to the linked schema/data.

The latest read-only schema comparison is nonempty and contains broad authenticated DML/grant and legacy function differences; its generated SQL contains DROP statements in the selected direction. It is diagnostic only and must not be applied. The local disposable schema/data/roles dumps are small, mode-0600 rehearsal outputs from a synthetic empty DB. No restore rehearsal was completed; they omit managed Auth/Storage and Storage API objects, are not encrypted off-site and are not a production backup.

**Decision: direct production migration deployment is NO-GO.** Production-of-record remains unverified despite local AABs and current build inputs pointing to `pfkxrggexueltytxtqil`: Play Console confirms Owner Closed testing Alpha `11 (aq-1.0.6)` and inactive Production, while local Owner AAB is `aq-1.0.7`; Customer's exact served bundle was not obtained. No migration should be pushed, repaired, or force-included. Before a revised preflight, obtain exact release artifact provenance and reconcile each migration effect against a restorable, privacy-safe copy/metadata of the confirmed target; validate old-client contract compatibility and complete a recovery-grade backup/restore plan.
