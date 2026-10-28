-- Regression: PostgREST uses ON CONFLICT (operation_id) for expense replay.
-- All fixture data is rolled back at the end of this local-only test.
create extension if not exists pgtap with schema extensions;
begin;
select extensions.plan(3);

create temporary table expense_upsert_fixture (
  organization_id uuid not null,
  station_id uuid not null,
  operation_id text not null,
  incurred_at bigint not null
) on commit drop;

insert into expense_upsert_fixture
values (
  gen_random_uuid(),
  gen_random_uuid(),
  'expense-upsert-regression:' || gen_random_uuid()::text,
  floor(extract(epoch from clock_timestamp()) * 1000)::bigint
);

insert into public.organizations (id, name)
select organization_id, 'Expense upsert regression fixture'
from expense_upsert_fixture;

insert into public.stations (id, organization_id, name, address)
select station_id, organization_id, 'Expense upsert regression fixture', 'Local test only'
from expense_upsert_fixture;

insert into public.expenses (
  station_id, amount_centavos, category, description, payment_method,
  incurred_at, operation_id
)
select station_id, 10000, 'SUPPLIES', 'First retry', 'CASH', incurred_at, operation_id
from expense_upsert_fixture
on conflict (operation_id) do update
set amount_centavos = excluded.amount_centavos,
    description = excluded.description;

insert into public.expenses (
  station_id, amount_centavos, category, description, payment_method,
  incurred_at, operation_id
)
select station_id, 12500, 'SUPPLIES', 'Replayed expense', 'CASH', incurred_at, operation_id
from expense_upsert_fixture
on conflict (operation_id) do update
set amount_centavos = excluded.amount_centavos,
    description = excluded.description;

select extensions.ok(
  exists (
    select 1
    from pg_index i
    join pg_class index_relation on index_relation.oid = i.indexrelid
    join pg_class table_relation on table_relation.oid = i.indrelid
    join pg_namespace n on n.oid = table_relation.relnamespace
    where n.nspname = 'public'
      and table_relation.relname = 'expenses'
      and index_relation.relname = 'expenses_operation_id_uidx'
      and i.indisunique
      and i.indpred is null
  ),
  'operation_id has a full unique index that matches ON CONFLICT (operation_id)'
);

select extensions.is(
  (select count(*)::bigint
   from public.expenses e
   join expense_upsert_fixture f using (station_id, operation_id)),
  1::bigint,
  'replaying an expense operation keeps one remote row'
);

select extensions.is(
  (select e.amount_centavos
   from public.expenses e
   join expense_upsert_fixture f using (station_id, operation_id)),
  12500::bigint,
  'the matching upsert updates the existing expense row'
);

select * from extensions.finish();
rollback;
