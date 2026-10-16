# Expenses Migration

Status: `EXPENSES_MIGRATION_IMPLEMENTED_VALIDATION_PENDING`

Expenses are owner/station-scoped records with integer-centavo amount, category, description, vendor, payment method, reference number, notes, incurred timestamp, active/archive state, and legacy Firebase ID. The owner UI supports create/edit/archive, station/date/category filtering, total aggregation, and Business/Pro entitlement gating. It has no receipt/image field, employee attribution UI, or expense-specific Function. Existing persistence is Room with a Firebase compatibility outbox.

The hosted expense foundation migration adds `organization_id`, `created_by`, `receipt_path`, and idempotent `operation_id`, preserves `incurred_at` as the existing expense-date field, protects tenant/creator fields with a trigger, and replaces the broad policy with owner/manager station-scoped RLS. `expense_summary` keeps aggregation in PostgreSQL. Amounts remain `bigint` centavos end to end.

`SupabaseExpenseDataSource` is now wired into the Room-backed `ExpenseRepository`: authenticated, provisioned new writes/update/archive through Supabase and cache the returned UUID; offline/unprovisioned writes retain the legacy outbox fallback until offline reconciliation is validated. This avoids two online writable authorities. Firebase expense data source/mapper/queue remains historical compatibility only for that fallback and import.

`tools/supabase-migration/migrate-expenses.mjs` is dry-run by default, requires explicit hosted-production confirmation for writes, validates station mapping, centavos, dates, legacy IDs, operation IDs, timestamps, receipt paths, and reports creator mappings/conflicts. It never deletes Firebase data or sends notifications.

Supabase already contains a station-scoped `expenses` table with the core fields. The next slice should add authenticated owner/staff RPCs for create/update/archive, tenant/station authorization, operation idempotency, and reporting views. Expense payment method is an accounting label and must not be conflated with order payment settlement or inventory movement. Historical import should preserve legacy IDs and centavos without invoking payment providers.
# Runtime dependency note

Expense writes remain Supabase-authoritative. Any Firebase compatibility is historical/import-only; the global runtime audit and remaining Firebase removal prerequisites are recorded in `23-firebase-runtime-removal.md`.
