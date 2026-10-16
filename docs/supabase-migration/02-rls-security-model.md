# Supabase RLS and security model

Status: `HARDENED — CURRENT SCHEMA DEPLOYED; VERIFICATION-FIRST 009 PREPARED, NOT DEPLOYED`.

## Function and profile hardening

Migration `007_triggers_and_authorization_helpers.sql` places internal functions in the non-exposed `app_private` schema. `app_private` has no anonymous access and is not included in the Data API schema list.

| Function | `SECURITY DEFINER` | Purpose | Execution grants |
|---|---:|---|---|
| `app_private.handle_new_auth_user()` | Yes | Auth trigger inserts/updates the matching profile despite profile RLS | `postgres` only |
| `app_private.is_org_member(uuid)` | Yes | RLS membership predicate; reads membership rows without recursive RLS failure | `postgres`, `authenticated`, `service_role`; not RPC/client-callable because schema is private |
| `app_private.can_manage_org(uuid)` | Yes | RLS manager predicate | `postgres`, `authenticated`, `service_role`; private schema only |
| `app_private.can_access_station(uuid)` | Yes | RLS station-access predicate | `postgres`, `authenticated`, `service_role`; private schema only |
| `app_private.can_operate_station(uuid)` | Yes | RLS station-operation predicate | `postgres`, `authenticated`, `service_role`; private schema only |
| `app_private.set_updated_at()` | No | Timestamp trigger | `postgres`, `authenticated`, `service_role`; private schema only |
| `app_private.protect_profile_fields()` | No | Defense-in-depth trigger for protected profile columns | `postgres`, `authenticated`, `service_role`; private schema only |

All functions use an explicit safe `search_path`; all `SECURITY DEFINER` functions are revoked from `PUBLIC` and `anon`. The RLS helper grants to `authenticated` are required for policy evaluation, not client RPC access; the containing schema is private and unexposed.

Ordinary users can update only these profile columns: `display_name`, `phone`, `photo_url`, `address`, `latitude`, `longitude`, and `setup_complete`. They cannot update `id`, `firebase_uid`, `email`, `role`, `preferred_business_id`, `preferred_station_id`, `acquisition_source`, `acquired_at`, `email_verified`, `created_at`, or `updated_at`. The trigger also rejects protected-field changes for non-server roles.

Membership rows and station assignments are read-only to authenticated clients. Organization owners/managers may change organization `name` and `is_active`, but not `owner_profile_id`; membership, role, and station-assignment changes are server-only. New auth users receive the default `CUSTOMER` profile role, and signup metadata is used only for display name; it cannot set roles or `firebase_uid`.

## Authorization invariants

1. A request must be authenticated for all private data.
2. A user can access an organization only through an active `organization_members` row.
3. Station-scoped access requires an active organization membership and either owner/manager authority or an active `member_stations` assignment.
4. `OWNER` and `MANAGER` manage catalog, staff assignments, riders, payments, expenses, and station configuration. `STAFF` can operate assigned stations. `RIDER` is limited to assigned delivery operations. Customer access is limited to the profile, devices, public projections, own order mirrors, and server-mediated order mutations.
5. Client roles and profile metadata are not trusted for authorization. RLS derives access from normalized membership tables and `auth.uid()`.
6. Customer order creation/cancellation, billing, trial claims, audit events, public projections, and notification fan-out are server-only operations. Edge Functions/Postgres functions must validate business rules explicitly.
7. Every exposed table has RLS enabled, explicit grants, and separate policies for SELECT/INSERT/UPDATE/DELETE. Views must use `security_invoker` or remain unexposed.

## Policy matrix

| Table family | Customer | Rider | Staff | Manager/Owner | Server function |
|---|---|---|---|---|---|
| `profiles` | own row | own row | own row | own row + server member administration | full server path |
| `organizations`, `organization_members`, `stations` | public projection only | assigned station reads | assigned station reads/operations | full tenant management | full |
| `customers`, `customer_addresses`, `products`, `riders`, `containers` | no private access | assigned operational subset | assigned station | full station scope | full |
| `orders`, `order_items` | own mirror; no station-order writes | assigned delivery reads/status subset | assigned station operational reads/updates | full station scope | full, including customer intake |
| `payments`, `expenses` | none | no payments/expenses | payments only where explicitly required; no expenses | full station scope | full |
| `public_stations`, `public_products` | read active/sanitized rows | authenticated read | authenticated read | server-maintained | write only |
| `device_tokens` | own rows | own rows | own rows | own rows | fan-out read where authorized |
| request/audit/billing/trial metadata | none | none | none | no direct client writes | server only |

## Important implementation constraints

- Do not use `raw_user_meta_data` or editable user metadata in an RLS predicate. If custom JWT claims are later used, they must be server-managed `app_metadata` and refreshed deliberately; normalized membership remains the source of truth.
- UPDATE policies need both `USING` and `WITH CHECK` so a user cannot move a row to another station/tenant.
- Storage policies must validate bucket/path ownership, MIME type, and size; upsert requires SELECT/INSERT/UPDATE grants as appropriate.
- The `service_role` key is never shipped in either Android app. Edge Functions use server secrets and return only validated results.
- Supabase Realtime publication should include only tables and filtered channels required by the migrated module; never subscribe to an entire tenant table from a client.

## Verification plan

The SQL policies are accompanied by `supabase/tests/rls_invariants.sql`. Hosted privilege checks and the disposable cross-organization/cross-station runner in `supabase/scripts/hosted_rls_fixtures.py` passed for the currently deployed schema. The runner also verified customer membership/subscription denial and protected profile/ownership fields. Add table-specific tests for each migrated module before that module is exposed; required future assertions include customer direct-order denial, rider status boundaries, and server-only table denial. The fixture runner must clean up its disposable users and organizations and must never be pointed at a local Supabase instance for this hosted validation workflow.

Migration 009 additionally prepares verification-first invariants: no `auth.users` profile trigger, no client profile INSERT/DELETE policies, and a trusted `provision_verified_profile()` function deriving email/phone/Google verification from Supabase Auth state. Its review assertions are in `supabase/tests/verification_first_auth.sql`; the migration is pending and has not been pushed.
