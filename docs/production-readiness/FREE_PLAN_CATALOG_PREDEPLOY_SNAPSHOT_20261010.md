# FREE Catalog Pre-deployment Snapshot

Captured from Supabase project `pfkxrggexueltytxtqil` using read-only SQL on
2026-10-10 before applying `20261010100000_free_plan_basic_inventory.sql`.
This is a configuration-only snapshot; it contains no credentials or owner
records.

## Affected configuration

- FREE plan row count: `1`
- FREE plan id: `6be0798d-6105-46a6-8338-941ac7937155`
- Description: `Core station, customer, product, and basic reporting tools for one water station`
- Monthly / annual price: `0 / 0` centavos
- Active: `true`; trial days: `0`; sort order: `0`
- `BASIC_INVENTORY`: one row, `enabled=false`, `limit_value=NULL`
- `BASIC_CONTAINER_TRACKING`: one row, `enabled=false`, `limit_value=NULL`
- FREE limits: station `1`, products `20`, monthly orders `100`, customers `50`
- Paid catalog digest (plans, features, limits): `52eb49c5efe74fd162de744b4e97f991`

## Data safety check

Read-only exact counts before deployment: Auth users `0`, profiles `0`,
organizations `0`, stations `0`, customers `0`, products `0`, orders `0`,
payments `0`, subscriptions `0`, and inventory movements `0`.

## Reversal procedure

The migration only changes the FREE plan description and the FREE
`BASIC_INVENTORY` flag. To reverse its business effect, after verifying the
same project reference and the expected single rows, restore the description
and flag to the values above. Do not change any paid-tier rows.

```sql
begin;

update public.subscription_plans
set description = 'Core station, customer, product, and basic reporting tools for one water station'
where code = 'FREE';

update public.plan_features pf
set enabled = false
from public.subscription_plans p
where p.id = pf.plan_id
  and p.code = 'FREE'
  and pf.feature = 'BASIC_INVENTORY';

commit;
```

The reversal statements were exercised against the disposable local Supabase
database inside a transaction and then rolled back. The description and flag
were restored exactly. The `subscription_plans` update trigger advances
`updated_at`, so the timestamp is intentionally not restored; it is audit
metadata, not an entitlement value. No reversal was executed against the
linked project.

## Deployment preconditions verified

- Supabase project metadata and API URL resolve to `pfkxrggexueltytxtqil`.
- Hosted migration history matches local history through
  `20261009135735`; the FREE catalog migration is the only pending local file.
- Required catalog tables/columns and the one-row FREE feature entries exist.
- No business or transaction records were found by the exact aggregate counts.
- The migration updates only `FREE` and the `BASIC_INVENTORY` feature row;
  paid catalog digest and FREE limits were captured for post-deployment
  comparison.

## Deployment and post-deployment verification — 2026-10-10

- Applied only `20261010100000_free_plan_basic_inventory.sql` to project
  `pfkxrggexueltytxtqil` with the exact project reference supplied by the user.
- Hosted migration history now records version `20261010100000`, name
  `free_plan_basic_inventory`.
- Read-only post-deploy query confirmed exactly one FREE row for each relevant
  feature: `BASIC_INVENTORY=true` and `BASIC_CONTAINER_TRACKING=false`.
- The FREE limits remain station `1`, products `20`, monthly orders `100`,
  and customers `50`; the paid-plan catalog digest remains
  `52eb49c5efe74fd162de744b4e97f991`.
- Post-test aggregate counts remain zero for Auth users, profiles,
  organizations, stations, products, orders, payments, subscriptions, and
  inventory movements. No test identity or transaction was created.
- No unrelated migration, schema backfill, Edge Function, Storage object,
  subscription, customer/business record, or Play Console setting was changed.
- This proves hosted catalog configuration only. It does not prove an
  authenticated Free owner's product/POS workflow.
