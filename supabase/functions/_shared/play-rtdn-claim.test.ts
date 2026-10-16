import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { rtdnOrganizationForVerifiedToken } from "./play-rtdn-claim.ts";

Deno.test("bound RTDN token keeps its original organization", () => {
  assertEquals(rtdnOrganizationForVerifiedToken(
    { organization_id: "organization-a", superseded_at: null },
    { organization_id: "organization-b", superseded_at: null },
  ), "organization-a");
});

Deno.test("verified unbound replacement inherits only a live linked token", () => {
  assertEquals(rtdnOrganizationForVerifiedToken(null, {
    organization_id: "organization-a", superseded_at: null,
  }), "organization-a");
  assertEquals(rtdnOrganizationForVerifiedToken(
    { organization_id: null, superseded_at: null },
    { organization_id: "organization-a", superseded_at: null },
  ), "organization-a");
  assertEquals(rtdnOrganizationForVerifiedToken(null, null), null);
  assertEquals(rtdnOrganizationForVerifiedToken(null, {
    organization_id: null, superseded_at: null,
  }), null);
  assertEquals(rtdnOrganizationForVerifiedToken(null, {
    organization_id: "organization-a", superseded_at: "2026-10-10T00:00:00Z",
  }), null);
  assertEquals(rtdnOrganizationForVerifiedToken(
    { organization_id: "organization-a", superseded_at: "2026-10-10T00:00:00Z" },
    { organization_id: "organization-b", superseded_at: null },
  ), null);
});
