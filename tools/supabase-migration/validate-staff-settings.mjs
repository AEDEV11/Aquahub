#!/usr/bin/env node

/**
 * Disposable hosted staff/settings validation.
 *
 * Required environment (never hardcode or print these values):
 *   SUPABASE_URL
 *   SUPABASE_SERVICE_ROLE_KEY  (fixture setup/cleanup only)
 *   SUPABASE_ANON_KEY or SUPABASE_PUBLISHABLE_KEY
 *
 * Business calls use normal password sign-in access tokens. The service role
 * is limited to creating/deleting disposable Auth users and fixture rows.
 */

import { randomUUID } from "node:crypto";
import { supabasePublishableKey, supabaseSecretKey } from "./supabase-env.mjs";

const baseUrl = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const anonKey = supabasePublishableKey();
const serviceRole = supabaseSecretKey();
if (!baseUrl || !anonKey || !serviceRole) {
  console.error("STAFF_AUTHENTICATED_VALIDATION_BLOCKED_BY_TEST_SETUP");
  console.error("Requires SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY or SUPABASE_ANON_KEY, and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");
  process.exit(2);
}

const marker = `staff-settings-${randomUUID()}`;
const users = [];
const organizations = [];
const plans = [];
const report = { passed: [], denied: [], failed: [], cleanup: "not-run" };

async function request(path, init = {}, key = anonKey, expected = null) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: { apikey: key, "content-type": "application/json", ...(init.headers ?? {}) },
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  if (expected !== null && response.status !== expected) {
    throw new Error(`Expected HTTP ${expected}, received HTTP ${response.status}`);
  }
  return { response, body };
}

const admin = (path, init = {}) => request(path, {
  ...init,
  headers: { authorization: `Bearer ${serviceRole}`, ...(init.headers ?? {}) },
}, serviceRole);

const bearer = (token, path, init = {}) => request(path, {
  ...init,
  headers: { authorization: `Bearer ${token}`, ...(init.headers ?? {}) },
});

const password = () => `AqH-${randomUUID()}-safe-test!`;
const authPath = "/auth/v1/admin/users";

async function createUser(label, phone) {
  const userPassword = password();
  const email = `aquahub-${label}-${randomUUID()}@example.com`;
  const created = await admin(authPath, {
    method: "POST",
    body: JSON.stringify({ email, password: userPassword, email_confirm: true, user_metadata: { test_fixture: marker } }),
  });
  if (!created.response.ok || !created.body?.id) throw new Error(`Could not create disposable ${label}`);
  users.push(created.body.id);
  const signedIn = await request("/auth/v1/token?grant_type=password", {
    method: "POST",
    body: JSON.stringify({ email, password: userPassword }),
  });
  if (!signedIn.response.ok || !signedIn.body?.access_token) throw new Error(`Could not sign in disposable ${label}`);
  return { id: created.body.id, email, password: userPassword, accessToken: signedIn.body.access_token };
}

async function patchProfile(id, values) {
  const result = await admin("/rest/v1/profiles", {
    method: "POST",
    headers: { prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify({ id, ...values, account_state: "ACTIVE" }),
  });
  if (!result.response.ok) throw new Error("Could not provision disposable profile");
}

async function ensureStarterPlan() {
  const existing = await admin("/rest/v1/subscription_plans?code=eq.STARTER&is_active=eq.true&limit=1");
  if (existing.response.ok && existing.body?.[0]?.id) return existing.body[0].id;
  const created = await admin("/rest/v1/subscription_plans", {
    method: "POST",
    headers: { prefer: "return=representation" },
    body: JSON.stringify({
      code: "STARTER",
      name: `${marker} Starter`,
      description: "Disposable validation plan",
      monthly_price_centavos: 0,
      annual_price_centavos: 0,
      trial_days: 30,
      cta_text: "Validation",
      is_active: true,
      sort_order: 9999,
    }),
  });
  const planId = created.body?.[0]?.id;
  if (!created.response.ok || !planId) throw new Error("Could not provision disposable subscription plan");
  plans.push(planId);
  const limit = await admin("/rest/v1/plan_limits", {
    method: "POST",
    headers: { prefer: "return=minimal" },
    body: JSON.stringify({ plan_id: planId, limit_type: "STAFF_ACCOUNTS", max_value: 100 }),
  });
  if (!limit.response.ok) throw new Error("Could not provision disposable staff plan limit");
  return planId;
}

async function setupTenant(label, owner, planId) {
  // The hosted schema requires organizations.owner_profile_id to reference an
  // existing profile. Auth Admin user creation alone does not guarantee that
  // profile row exists, so provision the disposable profile first.
  await patchProfile(owner.id, { display_name: `Disposable ${label}`, role: "OWNER", setup_complete: false, email_verified: true });
  const organization = await admin("/rest/v1/organizations", {
    method: "POST",
    headers: { prefer: "return=representation" },
    body: JSON.stringify({ name: `${marker}-${label}`, owner_profile_id: owner.id, is_active: true }),
  });
  const organizationId = organization.body?.[0]?.id;
  if (!organization.response.ok || !organizationId) throw new Error(`Could not create ${label} organization (${organization.response.status}: ${organization.body?.message ?? organization.body?.code ?? "no details"})`);
  organizations.push(organizationId);

  const stations = [];
  for (const suffix of ["1", "2"]) {
    const station = await admin("/rest/v1/stations", {
      method: "POST",
      headers: { prefer: "return=representation" },
      body: JSON.stringify({ organization_id: organizationId, name: `${marker}-${label}-station-${suffix}`, address: "Disposable validation", is_active: true }),
    });
    const stationId = station.body?.[0]?.id;
    if (!station.response.ok || !stationId) throw new Error(`Could not create ${label} station`);
    stations.push(stationId);
  }
  await patchProfile(owner.id, { display_name: `Disposable ${label}`, role: "OWNER", setup_complete: true, email_verified: true, preferred_business_id: organizationId, preferred_station_id: stations[0] });
  const membership = await admin("/rest/v1/organization_members", {
    method: "POST",
    headers: { prefer: "return=representation" },
    body: JSON.stringify({ organization_id: organizationId, profile_id: owner.id, role: "OWNER", is_active: true }),
  });
  const memberId = membership.body?.[0]?.id;
  if (!membership.response.ok || !memberId) throw new Error(`Could not create ${label} membership`);
  const links = await admin("/rest/v1/member_stations", {
    method: "POST",
    headers: { prefer: "return=minimal" },
    body: JSON.stringify(stations.map((station_id) => ({ member_id: memberId, station_id }))),
  });
  if (!links.response.ok) throw new Error(`Could not assign ${label} stations`);

  const subscription = await admin("/rest/v1/subscriptions", {
    method: "POST",
    headers: { prefer: "return=minimal" },
    body: JSON.stringify({ organization_id: organizationId, plan_id: planId, status: "ACTIVE", billing_cycle: "MONTHLY" }),
  });
  if (!subscription.response.ok) throw new Error(`Could not provision ${label} subscription`);
  return { organizationId, stations };
}

async function staffCall(token, payload) {
  return bearer(token, "/functions/v1/staff-admin", { method: "POST", body: JSON.stringify(payload) });
}

async function settingsCall(token, stationId, patch) {
  return bearer(token, "/rest/v1/rpc/update_station_settings", {
    method: "POST",
    body: JSON.stringify({ p_station_id: stationId, p_settings: patch }),
  });
}

function assertOk(result, label) {
  if (!result.response.ok) throw new Error(`${label} rejected with HTTP ${result.response.status}: ${JSON.stringify({ error: result.body?.error, code: result.body?.code, message: result.body?.message })}`);
}

function assertDenied(result, label) {
  if (result.response.ok) throw new Error(`${label} unexpectedly succeeded`);
  report.denied.push(`${label}: HTTP ${result.response.status}`);
}

async function deleteOrganizationFixture(organizationId) {
  const stations = await admin(`/rest/v1/stations?organization_id=eq.${encodeURIComponent(organizationId)}&select=id`);
  for (const station of stations.body ?? []) {
    await admin(`/rest/v1/stations?id=eq.${encodeURIComponent(station.id)}`, { method: "DELETE" }).catch(() => {});
  }
  await admin(`/rest/v1/organizations?id=eq.${encodeURIComponent(organizationId)}`, { method: "DELETE" }).catch(() => {});
}

async function run() {
  const ownerA = await createUser("owner-a", "09000000011");
  const ownerB = await createUser("owner-b", "09000000012");
  const starterPlanId = await ensureStarterPlan();
  const tenantA = await setupTenant("org-a", ownerA, starterPlanId);
  const tenantB = await setupTenant("org-b", ownerB, starterPlanId);
  report.passed.push("disposable tenant and normal password sessions");

  const create = async (phone, role, stationIds = tenantA.stations) => {
    const staffPassword = password();
    const result = await staffCall(ownerA.accessToken, { operation: "create", name: `Disposable ${role}`, phone, password: staffPassword, role, station_ids: stationIds });
    assertOk(result, `OWNER create ${role}`);
    const uid = result.body?.uid;
    if (!uid) throw new Error(`OWNER create ${role} returned no uid`);
    users.push(uid);
    const signedIn = await request("/auth/v1/token?grant_type=password", {
      method: "POST",
      body: JSON.stringify({ email: phoneEmail(phone), password: staffPassword }),
    });
    if (!signedIn.response.ok || !signedIn.body?.access_token) throw new Error(`Could not sign in disposable ${role}`);
    return { ...result.body, accessToken: signedIn.body.access_token };
  };
  const manager = await create("09000000021", "MANAGER");
  const staff = await create("09000000022", "STAFF");
  const rider = await create("09000000023", "RIDER");
  report.passed.push("OWNER create MANAGER/STAFF/RIDER with role and station assignments");

  const duplicate = await staffCall(ownerA.accessToken, { operation: "create", name: "Duplicate", phone: "09000000022", password: password(), role: "STAFF", station_ids: tenantA.stations });
  if (duplicate.response.status !== 409) throw new Error("duplicate staff create was not rejected safely");
  report.passed.push("duplicate staff create does not create another Auth or membership row");

  const managerCreatedStaff = await staffCall(manager.accessToken, { operation: "create", name: "Manager-created staff", phone: "09000000041", password: password(), role: "STAFF", station_ids: [tenantA.stations[0]] });
  assertOk(managerCreatedStaff, "MANAGER create STAFF");
  users.push(managerCreatedStaff.body.uid);
  report.passed.push("MANAGER create STAFF");
  const managerForeign = await staffCall(manager.accessToken, { operation: "update", uid: staff.uid, name: "Cross tenant attempt", phone: "09000000022", role: "STAFF", station_ids: [tenantB.stations[0]] });
  assertDenied(managerForeign, "MANAGER assign foreign station");
  const managerOwner = await staffCall(manager.accessToken, { operation: "update", uid: ownerA.id, name: "Owner target", phone: "09000000011", role: "OWNER", station_ids: tenantA.stations });
  assertDenied(managerOwner, "MANAGER update OWNER");
  const managerPromote = await staffCall(manager.accessToken, { operation: "update", uid: staff.uid, name: "Promotion attempt", phone: "09000000022", role: "MANAGER", station_ids: tenantA.stations });
  assertDenied(managerPromote, "MANAGER promote STAFF to MANAGER");

  const ordinarySession = await createUser("ordinary", "09000000051");
  await patchProfile(ordinarySession.id, { display_name: "Disposable ordinary", role: "STAFF", setup_complete: true });
  const ordinaryMember = await admin("/rest/v1/organization_members", { method: "POST", headers: { prefer: "return=representation" }, body: JSON.stringify({ organization_id: tenantA.organizationId, profile_id: ordinarySession.id, role: "STAFF", is_active: true }) });
  if (!ordinaryMember.response.ok) throw new Error("Could not provision ordinary staff membership");
  const ordinaryAttempt = await staffCall(ordinarySession.accessToken, { operation: "create", name: "Denied", phone: "09000000061", password: password(), role: "STAFF", station_ids: [tenantA.stations[0]] });
  assertDenied(ordinaryAttempt, "ordinary STAFF administration");

  const riderSession = await createUser("rider", "09000000052");
  await patchProfile(riderSession.id, { display_name: "Disposable rider", role: "RIDER", setup_complete: true });
  const riderMember = await admin("/rest/v1/organization_members", { method: "POST", headers: { prefer: "return=representation" }, body: JSON.stringify({ organization_id: tenantA.organizationId, profile_id: riderSession.id, role: "RIDER", is_active: true }) });
  if (!riderMember.response.ok) throw new Error("Could not provision rider membership");
  const riderAttempt = await staffCall(riderSession.accessToken, { operation: "delete", uid: staff.uid });
  assertDenied(riderAttempt, "ordinary RIDER administration");

  const settings = await settingsCall(ownerA.accessToken, tenantA.stations[0], { name: `${marker}-updated`, address: "Updated disposable address", logo_path: "supabase://station-assets/organizations/test/stations/test/logo.png", gcash_qr_image_path: "supabase://payment-assets/organizations/test/stations/test/gcash.png" });
  assertOk(settings, "OWNER station settings");
  report.passed.push("OWNER settings RPC allowed fields persisted");
  const protectedSettings = await settingsCall(ownerA.accessToken, tenantA.stations[0], { organization_id: tenantB.organizationId, created_at: "2000-01-01T00:00:00Z", role: "OWNER", entitlement: "PRO" });
  assertDenied(protectedSettings, "protected settings fields");
  const directStation = await bearer(ownerA.accessToken, `/rest/v1/stations?id=eq.${encodeURIComponent(tenantA.stations[0])}`, { method: "PATCH", body: JSON.stringify({ name: "direct escalation" }) });
  assertDenied(directStation, "direct station UPDATE");

  const deactivate = await staffCall(ownerA.accessToken, { operation: "delete", uid: staff.uid });
  assertOk(deactivate, "OWNER deactivate STAFF");
  const afterDeactivation = await staffCall(staff.accessToken, { operation: "create", name: "Denied after deactivation", phone: "09000000071", password: password(), role: "STAFF", station_ids: [tenantA.stations[0]] });
  assertDenied(afterDeactivation, "deactivated STAFF administration");
  report.passed.push("STAFF reversible deactivation");

  const directMembership = await bearer(ownerA.accessToken, "/rest/v1/organization_members", { method: "PATCH", headers: { prefer: "return=minimal" }, body: JSON.stringify({ role: "OWNER", organization_id: tenantB.organizationId }) });
  assertDenied(directMembership, "direct membership escalation");
  const directProfile = await bearer(ordinarySession.accessToken, `/rest/v1/profiles?id=eq.${encodeURIComponent(ordinarySession.id)}`, { method: "PATCH", headers: { prefer: "return=minimal" }, body: JSON.stringify({ role: "OWNER" }) });
  assertDenied(directProfile, "direct profile role escalation");
  report.passed.push("direct profile/membership escalation rejected");
}

try {
  await run();
  console.log(JSON.stringify({ ...report, fixtureUsers: users.length, fixtureOrganizations: organizations.length }, null, 2));
} catch (error) {
  report.failed.push(error instanceof Error ? error.message : "validation failed");
  console.error(JSON.stringify(report, null, 2));
  process.exitCode = 1;
} finally {
  for (const organizationId of organizations) await deleteOrganizationFixture(organizationId);
  for (const planId of plans) {
    await admin(`/rest/v1/plan_limits?plan_id=eq.${encodeURIComponent(planId)}`, { method: "DELETE" }).catch(() => {});
    await admin(`/rest/v1/plan_features?plan_id=eq.${encodeURIComponent(planId)}`, { method: "DELETE" }).catch(() => {});
    await admin(`/rest/v1/subscription_plans?id=eq.${encodeURIComponent(planId)}`, { method: "DELETE" }).catch(() => {});
  }
  for (const userId of users) await admin(`/auth/v1/admin/users/${encodeURIComponent(userId)}`, { method: "DELETE" }).catch(() => {});
  report.cleanup = "disposable organizations and Auth users cleanup requested";
  if (process.exitCode !== 1) console.error(JSON.stringify({ cleanup: report.cleanup }, null, 2));
}
