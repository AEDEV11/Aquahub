#!/usr/bin/env node

/**
 * Authenticated hosted payment validation.
 *
 * The secret key is used only for disposable Auth/profile/membership setup and
 * Auth cleanup. Order/payment RPCs and payment reads use normal user tokens.
 */

import fs from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { supabasePublishableKey, supabaseSecretKey } from "./supabase-env.mjs";

const args = process.argv.slice(2);
const fixtureIndex = args.indexOf("--fixtures");
const fixturePath = fixtureIndex >= 0 ? args[fixtureIndex + 1] : null;
if (args.includes("--help") || !fixturePath) {
  console.error("Usage: validate-payments.mjs --fixtures <fixture.json>");
  process.exit(fixturePath ? 0 : 2);
}

const baseUrl = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const anonKey = supabasePublishableKey();
const serviceRole = supabaseSecretKey();
if (!baseUrl || !anonKey || !serviceRole) {
  console.error("PAYMENTS_AUTHENTICATED_VALIDATION_BLOCKED_BY_TEST_SETUP");
  process.exit(2);
}

const fixture = JSON.parse(await fs.readFile(fixturePath, "utf8"));
for (const field of ["stationId", "organizationId", "customerId", "productAId", "productBId"]) {
  if (!fixture[field]) throw new Error(`Fixture is missing ${field}`);
}

const users = [];
const report = { passed: [], failed: [], cleanup: "not-run" };

async function request(path, init = {}, key = anonKey) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      apikey: key,
      "content-type": "application/json",
      ...(init.headers ?? {}),
    },
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = { message: "non-json response" }; }
  return { response, body };
}

const admin = (path, init = {}) => request(path, {
  ...init,
  headers: { authorization: `Bearer ${serviceRole}`, ...(init.headers ?? {}) },
}, serviceRole);

const userRequest = (token, path, init = {}) => request(path, {
  ...init,
  headers: { authorization: `Bearer ${token}`, ...(init.headers ?? {}) },
});

const password = () => `AqH-${randomUUID()}-safe-test!`;
async function createUser(label, profile) {
  const email = `${label}-${randomUUID()}@example.com`;
  const userPassword = password();
  const created = await admin("/auth/v1/admin/users", {
    method: "POST",
    body: JSON.stringify({ email, password: userPassword, email_confirm: true, user_metadata: { test_fixture: true } }),
  });
  if (!created.response.ok || !created.body?.id) throw new Error(`Could not create disposable ${label}`);
  users.push(created.body.id);
  const signedIn = await request("/auth/v1/token?grant_type=password", {
    method: "POST",
    body: JSON.stringify({ email, password: userPassword }),
  });
  if (!signedIn.response.ok || !signedIn.body?.access_token) throw new Error(`Could not sign in disposable ${label}`);
  const patched = await admin("/rest/v1/profiles", {
    method: "POST",
    headers: { prefer: "resolution=merge-duplicates,return=minimal" },
    body: JSON.stringify({ id: created.body.id, ...profile, account_state: "ACTIVE" }),
  });
  if (!patched.response.ok) throw new Error(`Could not provision disposable ${label} profile`);
  return { id: created.body.id, accessToken: signedIn.body.access_token };
}

async function rpc(token, name, payload) {
  return userRequest(token, `/rest/v1/rpc/${name}`, {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function commonOrder(source, operationId, productId, customerId = null) {
  return {
    p_station_id: fixture.stationId,
    p_client_operation_id: operationId,
    p_source: source,
    p_items: [{ product_id: productId, quantity: 1 }],
    p_customer_id: customerId,
    p_customer_name: "Disposable payment fixture",
    p_customer_phone: "09000000000",
    p_delivery_mode: "PICKUP",
    p_delivery_address: "Station pickup",
    p_delivery_fee_centavos: 0,
    p_payment_method: "CASH",
  };
}

async function run() {
  const owner = await createUser("aquahub-payment-owner", {
    display_name: "Disposable Payment Owner",
    role: "OWNER",
    setup_complete: true,
    email_verified: true,
    preferred_business_id: fixture.organizationId,
    preferred_station_id: fixture.stationId,
    phone: "09000000000",
  });
  const customer = await createUser("aquahub-payment-customer", {
    display_name: "Disposable Payment Customer",
    role: "CUSTOMER",
    setup_complete: true,
    phone_verified: true,
    preferred_station_id: fixture.stationId,
    phone: "09000000001",
  });
  const membership = await admin("/rest/v1/organization_members", {
    method: "POST",
    headers: { prefer: "return=representation" },
    body: JSON.stringify({ organization_id: fixture.organizationId, profile_id: owner.id, role: "OWNER", is_active: true }),
  });
  const memberId = membership.body?.[0]?.id;
  assert(membership.response.ok && memberId, "Could not provision disposable payment owner membership");
  const stationLink = await admin("/rest/v1/member_stations", {
    method: "POST",
    body: JSON.stringify({ member_id: memberId, station_id: fixture.stationId }),
  });
  assert(stationLink.response.ok, "Could not provision disposable payment station membership");

  const walkIn = await rpc(owner.accessToken, "create_order", commonOrder("OWNER_WALK_IN", `test:payment-walkin:${randomUUID()}`, fixture.productAId));
  assert(walkIn.response.ok && walkIn.body?.[0]?.order_id, `walk-in order failed: ${JSON.stringify(walkIn.body)}`);
  const walkInOrderId = walkIn.body[0].order_id;
  const receipts = await userRequest(owner.accessToken, `/rest/v1/payments?order_id=eq.${encodeURIComponent(walkInOrderId)}&select=id,status,amount_centavos,operation_id`);
  assert(receipts.response.ok && receipts.body?.length === 1 && receipts.body[0].status === "PAID", "walk-in receipt trigger did not create one paid receipt");
  report.passed.push("walk-in order creates one authoritative Supabase paid receipt");

  const customerOrder = await rpc(customer.accessToken, "create_order", commonOrder("CUSTOMER_APP", `test:payment-customer:${randomUUID()}`, fixture.productBId, fixture.customerId));
  assert(customerOrder.response.ok && customerOrder.body?.[0]?.order_id, `customer order failed: ${JSON.stringify(customerOrder.body)}`);
  const customerOrderRow = customerOrder.body[0];
  const operationId = `test:payment:${randomUUID()}`;
  const settled = await rpc(customer.accessToken, "record_payment", {
    p_order_id: customerOrderRow.order_id,
    p_operation_id: operationId,
    p_amount_centavos: customerOrderRow.total_centavos,
    p_payment_method: "CASH",
    p_reference_number: null,
    p_provider: null,
    p_provider_transaction_id: null,
    p_metadata: { validation: true },
  });
  assert(settled.response.ok && settled.body?.[0]?.status === "PAID" && settled.body[0].created === true, `record_payment failed: ${JSON.stringify(settled.body)}`);
  report.passed.push("authenticated customer payment settlement");

  const retry = await rpc(customer.accessToken, "record_payment", {
    p_order_id: customerOrderRow.order_id,
    p_operation_id: operationId,
    p_amount_centavos: customerOrderRow.total_centavos,
    p_payment_method: "CASH",
  });
  assert(retry.response.ok && retry.body?.[0]?.created === false && retry.body[0].payment_id === settled.body[0].payment_id, "record_payment retry was not idempotent");
  report.passed.push("payment operation idempotency");

  const overpayment = await rpc(customer.accessToken, "record_payment", {
    p_order_id: customerOrderRow.order_id,
    p_operation_id: `test:overpayment:${randomUUID()}`,
    p_amount_centavos: 1,
    p_payment_method: "CASH",
  });
  assert(!overpayment.response.ok, "overpayment unexpectedly committed");
  report.passed.push("payment over-settlement rejection");
}

try {
  await run();
  console.log(JSON.stringify({ ...report, disposableUserCount: users.length }, null, 2));
} catch (error) {
  report.failed.push(error instanceof Error ? error.message : "validation failed");
  console.error(JSON.stringify(report, null, 2));
  process.exitCode = 1;
} finally {
  for (const userId of users) await admin(`/auth/v1/admin/users/${encodeURIComponent(userId)}`, { method: "DELETE" }).catch(() => {});
  report.cleanup = "auth users deleted";
}
