-- PostgREST emits ON CONFLICT (operation_id) for expense upserts. PostgreSQL
-- cannot infer that target from the old partial index without a predicate.
-- A normal unique index still permits multiple NULL values, while allowing
-- the client's conflict target to match exactly.
drop index if exists public.expenses_operation_id_uidx;
create unique index expenses_operation_id_uidx
  on public.expenses (operation_id);
