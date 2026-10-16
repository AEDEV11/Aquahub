#!/usr/bin/env node

/**
 * Hosted order validation harness.
 *
 * The service-role key is used only for disposable Auth-user preparation and
 * cleanup. Every business RPC request uses an access token obtained through a
 * normal password sign-in. No JWTs are forged and no auth.uid() is injected.
 *
 * Fixture input is prepared by trusted setup tooling. Example shape:
 * {
 *   "stationId": "...", "foreignStationId": "...",
 *   "customerId": "...", "foreignCustomerId": "...",
 *   "productAId": "...", "productBId": "...",
 *   "stockOneProductId": "..."
 * }
 */

import fs from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { supabasePublishableKey, supabaseSecretKey } from "./supabase-env.mjs";

const args = process.argv.slice(2);
const fixtureIndex = args.indexOf("--fixtures");
const fixturePath = fixtureIndex >= 0 ? args[fixtureIndex + 1] : null;
const concurrencyOnly = args.includes("--concurrency-only");
if (args.includes("--help") || !fixturePath) {
  console.error("Usage: validate-orders.mjs --fixtures <fixture.json> [--concurrency-only]");
  process.exit(fixturePath ? 0 : 2);
}

const baseUrl = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const anonKey = supabasePublishableKey();
const serviceRole = supabaseSecretKey();
if (!baseUrl || !anonKey || !serviceRole) {
  console.error("Requires SUPABASE_URL, SUPABASE_PUBLISHABLE_KEY or SUPABASE_ANON_KEY, and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");
  process.exit(2);
}

const fixture = JSON.parse(await fs.readFile(fixturePath, "utf8"));
const required = ["stationId", "organizationId", "customerId", "foreignCustomerId", "productAId", "productBId", "stockOneProductId"];
for (const field of required) {
  if (!fixture[field]) throw new Error(`Fixture is missing ${field}`);
}

const request = async (path, init = {}, key = anonKey, expected = null) => {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      apikey: key,
      "Content-Type": "application/json",
      ...(init.headers ?? {}),
    },
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = { message: "non-json response" }; }
  if (expected !== null && response.status !== expected) {
    throw new Error(`Expected HTTP ${expected}, received ${response.status}`);
  }
  return { response, body };
};

const admin = async (path, init = {}) => request(path, {
  ...init,
  headers: { Authorization: `Bearer ${serviceRole}`, ...(init.headers ?? {}) },
}, serviceRole);

const rpc = async (accessToken, payload) => {
  const result = await request("/rest/v1/rpc/create_order", {
    method: "POST",
    headers: { Authorization: `Bearer ${accessToken}` },
    body: JSON.stringify(payload),
  });
  if (!result.response.ok) throw new Error(`create_order rejected with HTTP ${result.response.status}: ${JSON.stringify({ code: result.body?.code, message: result.body?.message, hint: result.body?.hint, details: result.body?.details })}`);
  return Array.isArray(result.body) ? result.body[0] : result.body;
};

const rawRpc = (accessToken, payload, name = "create_order") => request(`/rest/v1/rpc/${name}`, {
  method: "POST",
  headers: { Authorization: `Bearer ${accessToken}` },
  body: JSON.stringify(payload),
});

const password = () => `AqH-${randomUUID()}-safe-test!`;
const users = [];
const report = { passed: [], failed: [], cleanup: "not-run" };

const createAndSignIn = async (label) => {
  const email = `aquahub-order-${label}-${randomUUID()}@example.com`;
  const userPassword = password();
  const created = await admin("/auth/v1/admin/users", {
    method: "POST",
    body: JSON.stringify({ email, password: userPassword, email_confirm: true, user_metadata: { test_fixture: true } }),
  });
  if (!created.response.ok || !created.body?.id) throw new Error(`Could not create disposable ${label} Auth user`);
  users.push(created.body.id);
  const signedIn = await request("/auth/v1/token?grant_type=password", {
    method: "POST",
    body: JSON.stringify({ email, password: userPassword }),
  });
  if (!signedIn.response.ok || !signedIn.body?.access_token) throw new Error(`Could not sign in disposable ${label} Auth user`);
  const profile = label === "owner"
    ? { display_name: "Disposable Order Owner", role: "OWNER", setup_complete: true, email_verified: true, preferred_business_id: fixture.organizationId, preferred_station_id: fixture.stationId }
    : { display_name: "Disposable Order Customer", role: "CUSTOMER", setup_complete: true, phone_verified: true, preferred_station_id: fixture.stationId };
  const patched = await admin("/rest/v1/profiles", {
    method: "POST",
    headers: { Prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify({ id: created.body.id, ...profile, email: created.body.email ?? null, phone: "09000000000", account_state: "ACTIVE" }),
  });
  if (!patched.response.ok) throw new Error(`Could not provision disposable ${label} profile`);
  if (label === "owner") {
    const member = await admin("/rest/v1/organization_members", {
      method: "POST",
      headers: { Prefer: "return=representation" },
      body: JSON.stringify({ organization_id: fixture.organizationId, profile_id: created.body.id, role: "OWNER", is_active: true }),
    });
    const memberId = member.body?.[0]?.id;
    if (!member.response.ok || !memberId) throw new Error(`Could not provision disposable owner membership (${member.response.status}: ${member.body?.message ?? member.body?.code ?? "no details"})`);
    const link = await admin("/rest/v1/member_stations", {
      method: "POST",
      headers: { Prefer: "return=minimal" },
      body: JSON.stringify({ member_id: memberId, station_id: fixture.stationId }),
    });
    if (!link.response.ok) throw new Error("Could not provision disposable station membership");
  }
  return { id: created.body.id, accessToken: signedIn.body.access_token };
};

const common = (source, operationId, productId, customerId = null, stationId = fixture.stationId) => ({
  p_station_id: stationId,
  p_client_operation_id: operationId,
  p_source: source,
  p_items: [{ product_id: productId, quantity: 1 }],
  p_customer_id: customerId,
  p_customer_name: "Disposable order fixture",
  p_customer_phone: "09000000000",
  p_delivery_mode: "PICKUP",
  p_delivery_address: "Station pickup",
  p_delivery_fee_centavos: 0,
  p_payment_method: "CASH",
});

const run = async () => {
  const owner = await createAndSignIn("owner");
  if (concurrencyOnly) {
    const parallelPayload = () => common("OWNER_WALK_IN", `test:race:${randomUUID()}`, fixture.stockOneProductId);
    const race = await Promise.allSettled([rpc(owner.accessToken, parallelPayload()), rpc(owner.accessToken, parallelPayload())]);
    if (race.filter((item) => item.status === "fulfilled").length !== 1) throw new Error("stock-one concurrency did not produce exactly one success");

    const balance = await request(`/rest/v1/inventory_balances?select=quantity&station_id=eq.${encodeURIComponent(fixture.stationId)}&product_id=eq.${encodeURIComponent(fixture.stockOneProductId)}`, {
      headers: { Authorization: `Bearer ${owner.accessToken}` },
    });
    const movements = await request(`/rest/v1/inventory_movements?select=id,operation_id,delta,quantity_after,movement_type&station_id=eq.${encodeURIComponent(fixture.stationId)}&product_id=eq.${encodeURIComponent(fixture.stockOneProductId)}&movement_type=eq.ORDER_DEDUCTION`, {
      headers: { Authorization: `Bearer ${owner.accessToken}` },
    });
    const orderItems = await request(`/rest/v1/order_items?select=order_id,quantity&product_id=eq.${encodeURIComponent(fixture.stockOneProductId)}`, {
      headers: { Authorization: `Bearer ${owner.accessToken}` },
    });
    const balanceRows = Array.isArray(balance.body) ? balance.body : [];
    const movementRows = Array.isArray(movements.body) ? movements.body : [];
    const orderItemRows = Array.isArray(orderItems.body) ? orderItems.body : [];
    const uniqueOrderIds = new Set(orderItemRows.map((row) => row.order_id));
    if (balance.response.status !== 200 || balanceRows.length !== 1 || Number(balanceRows[0]?.quantity) !== 0) throw new Error(`stock-one final balance proof failed (HTTP ${balance.response.status})`);
    if (movements.response.status !== 200 || movementRows.length !== 1 || movementRows[0]?.movement_type !== "ORDER_DEDUCTION" || Number(movementRows[0]?.delta) !== -1 || Number(movementRows[0]?.quantity_after) !== 0) throw new Error(`stock-one deduction movement proof failed (HTTP ${movements.response.status})`);
    if (orderItems.response.status !== 200 || orderItemRows.length !== 1 || uniqueOrderIds.size !== 1 || Number(orderItemRows[0]?.quantity) !== 1) throw new Error(`stock-one order uniqueness proof failed (HTTP ${orderItems.response.status})`);
    report.passed.push("stock-one concurrency final balance, movement, and order proof");
    console.log(JSON.stringify({ ...report, disposableUserCount: users.length }, null, 2));
    return;
  }
  const customer = await createAndSignIn("customer");
  report.passed.push("normal Auth user creation and password sign-in");

  const customerOrder = await rpc(customer.accessToken, common("CUSTOMER_APP", `test:customer:${randomUUID()}`, fixture.productAId, fixture.customerId));
  if (!customerOrder?.order_id || customerOrder.status === "CANCELLED") throw new Error("customer order did not commit");
  report.passed.push("authenticated customer create_order");

  const cancelled = await rawRpc(customer.accessToken, {
    p_order_id: customerOrder.order_id,
    p_operation_id: `order:cancel:${customerOrder.order_id}`,
    p_reason: "Disposable validation",
  }, "cancel_order");
  if (!cancelled.response.ok) throw new Error("cancel_order rejected a valid customer order");
  const cancelledAgain = await rawRpc(customer.accessToken, {
    p_order_id: customerOrder.order_id,
    p_operation_id: `order:cancel:${customerOrder.order_id}`,
    p_reason: "Disposable validation retry",
  }, "cancel_order");
  if (!cancelledAgain.response.ok) throw new Error("cancel_order retry was not idempotent");
  report.passed.push("cancel_order and cancellation retry");

  const ownerOrder = await rpc(owner.accessToken, common("OWNER_WALK_IN", `test:owner:${randomUUID()}`, fixture.productAId));
  if (!ownerOrder?.order_id) throw new Error("owner order did not commit");
  report.passed.push("authenticated owner create_order");

  const operationId = `test:idempotency:${randomUUID()}`;
  const idempotentPayload = common("OWNER_WALK_IN", operationId, fixture.productBId);
  const [first, second] = await Promise.all([rpc(owner.accessToken, idempotentPayload), rpc(owner.accessToken, idempotentPayload)]);
  if (first.order_id !== second.order_id) throw new Error("create_order idempotency returned different orders");
  report.passed.push("create_order idempotency");

  const rollback = await rawRpc(owner.accessToken, {
    ...common("OWNER_WALK_IN", `test:rollback:${randomUUID()}`, fixture.productAId),
    p_items: [{ product_id: fixture.productAId, quantity: 1 }, { product_id: fixture.productBId, quantity: 99 }],
  });
  if (rollback.response.ok) throw new Error("multi-item insufficient-stock order unexpectedly committed");
  report.passed.push("multi-item rollback rejection");

  if (fixture.foreignStationId) {
    const foreign = await rawRpc(customer.accessToken, common("CUSTOMER_APP", `test:foreign:${randomUUID()}`, fixture.productAId, fixture.customerId, fixture.foreignStationId));
    if (foreign.response.ok) throw new Error("customer order against foreign station unexpectedly committed");
    report.passed.push("customer foreign-station authorization rejection");
  }

  const foreignCustomer = await rawRpc(owner.accessToken, common("OWNER_WALK_IN", `test:foreign-customer:${randomUUID()}`, fixture.productAId, fixture.foreignCustomerId));
  if (foreignCustomer.response.ok) throw new Error("order with foreign customer unexpectedly committed");
  report.passed.push("foreign-customer station ownership rejection");

  const parallelPayload = () => common("OWNER_WALK_IN", `test:race:${randomUUID()}`, fixture.stockOneProductId);
  const race = await Promise.allSettled([rpc(owner.accessToken, parallelPayload()), rpc(owner.accessToken, parallelPayload())]);
  if (race.filter((item) => item.status === "fulfilled").length !== 1) throw new Error("stock-one concurrency did not produce exactly one success");
  report.passed.push("parallel stock-one concurrency");

  // The remaining rollback/authorization/cancellation assertions are checked
  // by the SQL verifier after this script because they require row counts and
  // inventory movement totals, not just the RPC response.
  console.log(JSON.stringify({ ...report, disposableUserCount: users.length }, null, 2));
};

try {
  await run();
} catch (error) {
  report.failed.push(error instanceof Error ? error.message : "validation failed");
  console.error(JSON.stringify(report, null, 2));
  process.exitCode = 1;
} finally {
  for (const userId of users) {
    await admin(`/auth/v1/admin/users/${encodeURIComponent(userId)}`, { method: "DELETE" }).catch(() => {});
  }
  report.cleanup = "auth users deleted";
}
