-- Owners and active organization members need to restore billing history on a
-- fresh device. RLS was enabled for this table without a SELECT policy, which
-- made authenticated history reads return no rows.
create policy subscription_history_member_read
on public.subscription_history
for select
to authenticated
using (app_private.is_org_member(organization_id));
