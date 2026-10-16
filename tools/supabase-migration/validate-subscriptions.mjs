#!/usr/bin/env node
import { supabasePublishableKey, supabaseSecretKey } from "./supabase-env.mjs";

const required = ["SUPABASE_URL"];
const serviceKey = supabaseSecretKey();
const anonKey = supabasePublishableKey();
const missing = [...required.filter((name) => !process.env[name]), ...(serviceKey ? [] : ["SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY"]), ...(anonKey ? [] : ["SUPABASE_PUBLISHABLE_KEY or SUPABASE_ANON_KEY"])];
if (missing.length) {
  console.log(`SUBSCRIPTIONS_AUTHENTICATED_VALIDATION_BLOCKED_BY_TEST_SETUP: missing ${missing.join(", ")}`);
  if (process.argv.includes("--strict")) process.exitCode = 2;
  process.exit();
}

const baseUrl = process.env.SUPABASE_URL.replace(/\/$/, "");
const api = `${baseUrl}/rest/v1`;
const authApi = `${baseUrl}/auth/v1`;
const functionApi = `${baseUrl}/functions/v1`;
const runId = `subscription-validation-${Date.now()}`;
const fixtures = [];
const organizations = [];
const plans = [];

async function request(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: {
      apikey: options.apikey || serviceKey,
      authorization: `Bearer ${options.token || options.apikey || serviceKey}`,
      "content-type": "application/json",
      ...(options.headers || {}),
    },
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  return { response, body };
}

async function authCreate(email, password) {
  const result = await request(`${authApi}/admin/users`, { method: "POST", body: JSON.stringify({ email, password, email_confirm: true }) });
  if (!result.response.ok) throw new Error(`fixture auth create failed: ${result.response.status}`);
  fixtures.push(result.body.id);
  return result.body;
}

async function authDelete(id) {
  await request(`${authApi}/admin/users/${id}`, { method: "DELETE" });
}

async function ensureStarterPlan() {
  const existing = await rest("subscription_plans", "GET", undefined, serviceKey, "?code=eq.STARTER&is_active=eq.true&limit=1");
  if (existing.response.ok && existing.body?.[0]?.id) return existing.body[0].id;
  const created = await rest("subscription_plans", "POST", {
    code: "STARTER",
    name: `${runId} Starter`,
    description: "Disposable validation plan",
    monthly_price_centavos: 0,
    annual_price_centavos: 0,
    trial_days: 30,
    cta_text: "Validation",
    is_active: true,
    sort_order: 9999,
  });
  if (!created.response.ok || !created.body?.[0]?.id) throw new Error(`fixture plan create failed: ${created.response.status}`);
  const planId = created.body[0].id;
  plans.push(planId);
  const limit = await rest("plan_limits", "POST", { plan_id: planId, limit_type: "STAFF_ACCOUNTS", max_value: 100 });
  if (!limit.response.ok) throw new Error(`fixture plan limit create failed: ${limit.response.status}`);
  const feature = await rest("plan_features", "POST", { plan_id: planId, feature: "BASIC_INVENTORY", enabled: true });
  if (!feature.response.ok) throw new Error(`fixture plan feature create failed: ${feature.response.status}`);
  return planId;
}

async function rest(table, method, body, token = serviceKey, query = "") {
  return request(`${api}/${table}${query}`, {
    method,
    token,
    headers: { Prefer: method === "POST" || method === "PATCH" ? "return=representation" : "" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function signIn(email, password) {
  const result = await request(`${authApi}/token?grant_type=password`, {
    method: "POST", apikey: anonKey, token: anonKey, body: JSON.stringify({ email, password }),
  });
  if (!result.response.ok || !result.body?.access_token) throw new Error(`fixture sign-in failed: ${result.response.status}`);
  return result.body.access_token;
}

async function invoke(name, token, body) {
  return request(`${functionApi}/${name}`, { method: "POST", token, apikey: anonKey, body: JSON.stringify(body) });
}

function assert(condition, message) {
  if (!condition) throw new Error(`ASSERTION_FAILED: ${message}`);
}

async function main() {
  const password = `AquaHub-${crypto.randomUUID()}-Safe!`;
  const email = `${runId}@invalid.example`;
  const user = await authCreate(email, password);
  // Auth Admin user creation does not guarantee a public profile row, while
  // organizations.owner_profile_id has a foreign key to profiles.id.
  const profile = await rest("profiles", "POST", { id: user.id, display_name: "Subscription Fixture", email, phone: "+639000000000", role: "OWNER", setup_complete: false, email_verified: true, phone_verified: true, account_state: "ACTIVE" });
  assert(profile.response.ok, `fixture profile create returned ${profile.response.status}`);
  const organization = (await rest("organizations", "POST", { owner_profile_id: user.id, name: runId, is_active: true })).body?.[0];
  assert(organization?.id, "organization fixture created");
  organizations.push(organization.id);
  await rest("profiles", "POST", { id: user.id, display_name: "Subscription Fixture", email, phone: "+639000000000", role: "OWNER", setup_complete: true, email_verified: true, phone_verified: true, account_state: "ACTIVE", preferred_business_id: organization.id });
  await rest("organization_members", "POST", { organization_id: organization.id, profile_id: user.id, role: "OWNER", is_active: true });
  const token = await signIn(email, password);
  await ensureStarterPlan();

  const trial = await invoke("subscription-trial", token, { plan_code: "STARTER", billing_cycle: "MONTHLY" });
  assert(trial.response.ok, `trial activation returned ${trial.response.status}: ${JSON.stringify(trial.body)}`);
  const duplicate = await invoke("subscription-trial", token, { plan_code: "STARTER", billing_cycle: "MONTHLY" });
  assert(duplicate.response.ok, `duplicate trial request returned ${duplicate.response.status}`);
  const entitlements = await request(`${api}/rpc/get_effective_entitlements`, { method: "POST", token, apikey: anonKey, body: "{}" });
  assert(entitlements.response.ok, `entitlement resolver returned ${entitlements.response.status}`);
  assert(entitlements.body?.organization_id === organization.id, "entitlement organization is tenant-scoped");
  assert(typeof entitlements.body?.features?.BASIC_INVENTORY === "boolean", "BASIC_INVENTORY is server-resolved");
  assert(entitlements.body?.limits && typeof entitlements.body.limits === "object", "plan limits are server-resolved");

  const invalidProduct = await invoke("verify-play-purchase", token, { product_id: "invalid", purchase_token: "too-short" });
  assert(invalidProduct.response.status === 400, "invalid product/token rejected before provider access");

  for (const table of ["subscriptions", "subscription_plans", "plan_features", "plan_limits"]) {
    const denied = await rest(table, "POST", {}, token);
    assert([401, 403, 405, 409, 422].includes(denied.response.status), `${table} direct write denied (${denied.response.status})`);
  }
  console.log(JSON.stringify({ status: "PASS", authenticated: true, fixture: runId, external_play: "BLOCKED_BY_GOOGLE_PLAY_TEST_CONFIGURATION", ownership_conflict: "REQUIRES_REAL_PURCHASE_TOKEN" }, null, 2));
}

try {
  await main();
} catch (error) {
  console.error(error instanceof Error ? error.message : "subscription validation failed");
  process.exitCode = 1;
} finally {
  for (const organizationId of organizations) await rest("organizations", "DELETE", undefined, serviceKey, `?id=eq.${encodeURIComponent(organizationId)}`);
  for (const planId of plans) {
    await rest("plan_limits", "DELETE", undefined, serviceKey, `?plan_id=eq.${encodeURIComponent(planId)}`);
    await rest("plan_features", "DELETE", undefined, serviceKey, `?plan_id=eq.${encodeURIComponent(planId)}`);
    await rest("subscription_plans", "DELETE", undefined, serviceKey, `?id=eq.${encodeURIComponent(planId)}`);
  }
  for (const id of fixtures) await authDelete(id);
}
