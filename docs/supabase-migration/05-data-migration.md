# Data migration plan

Status: `PLANNED`.

Migration scripts must read Firestore through an authorized server process, preserve Firestore document IDs in `firebase_id`, preserve numeric timestamps, convert financial fields to integer centavos, validate coordinate pairs, retain order item snapshots, and be restartable/idempotent. Production data will not be copied manually in this phase.

Each module requires a count, duplicate count, validation-error count, failed-row report, and financial/referential comparison before reads or writes switch. Initial candidates are profiles/organizations/stations, then customers/catalog, then inventory/orders/payments/expenses.

