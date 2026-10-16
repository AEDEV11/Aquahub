#!/usr/bin/env node

import fs from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { supabaseSecretKey } from "./supabase-env.mjs";

const args = process.argv.slice(2);
const outputIndex = args.indexOf("--output");
const cleanupIndex = args.indexOf("--cleanup");
const outputPath = outputIndex >= 0 ? args[outputIndex + 1] : null;
const cleanupPath = cleanupIndex >= 0 ? args[cleanupIndex + 1] : null;
const baseUrl = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const secretKey = supabaseSecretKey();

if (!baseUrl || !secretKey) throw new Error("Order fixture setup requires SUPABASE_URL and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");
if ((!outputPath && !cleanupPath) || (outputPath && cleanupPath)) throw new Error("Use exactly one of --output or --cleanup.");

const request = async (path, init = {}) => {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      apikey: secretKey,
      authorization: `Bearer ${secretKey}`,
      "content-type": "application/json",
      Prefer: "return=representation",
      ...(init.headers ?? {}),
    },
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  if (!response.ok) throw new Error(`fixture request failed (${response.status})`);
  return body;
};

const insert = async (table, row) => {
  const body = await request(`/rest/v1/${table}`, { method: "POST", body: JSON.stringify(row) });
  const result = Array.isArray(body) ? body[0] : body;
  if (!result?.id) throw new Error(`fixture ${table} insert returned no id`);
  return result.id;
};

const remove = async (table, id) => {
  if (!id) return;
  await request(`/rest/v1/${table}?id=eq.${encodeURIComponent(id)}`, { method: "DELETE", headers: { Prefer: "return=minimal" } });
};

const cleanup = async (fixture) => {
  // Exact fixture IDs are used; this never performs a broad production delete.
  if (fixture.stationId) {
    await request(`/rest/v1/orders?station_id=eq.${encodeURIComponent(fixture.stationId)}`, { method: "DELETE", headers: { Prefer: "return=minimal" } }).catch(() => {});
    await request(`/rest/v1/inventory_movements?station_id=eq.${encodeURIComponent(fixture.stationId)}`, { method: "DELETE", headers: { Prefer: "return=minimal" } }).catch(() => {});
    await request(`/rest/v1/inventory_balances?station_id=eq.${encodeURIComponent(fixture.stationId)}`, { method: "DELETE", headers: { Prefer: "return=minimal" } }).catch(() => {});
  }
  if (fixture.foreignStationId) {
    await request(`/rest/v1/inventory_movements?station_id=eq.${encodeURIComponent(fixture.foreignStationId)}`, { method: "DELETE", headers: { Prefer: "return=minimal" } }).catch(() => {});
    await request(`/rest/v1/inventory_balances?station_id=eq.${encodeURIComponent(fixture.foreignStationId)}`, { method: "DELETE", headers: { Prefer: "return=minimal" } }).catch(() => {});
  }
  for (const id of [fixture.customerId, fixture.foreignCustomerId]) await remove("customers", id).catch(() => {});
  for (const id of [fixture.productAId, fixture.productBId, fixture.stockOneProductId]) await remove("products", id).catch(() => {});
  for (const id of [fixture.stationId, fixture.foreignStationId]) await remove("stations", id).catch(() => {});
  for (const id of [fixture.organizationId, fixture.foreignOrganizationId]) await remove("organizations", id).catch(() => {});
};

if (cleanupPath) {
  const fixture = JSON.parse(await fs.readFile(cleanupPath, "utf8"));
  await cleanup(fixture);
  console.log("ORDER_FIXTURE_CLEANUP_COMPLETE");
} else {
  const marker = `order-validation-${randomUUID()}`;
  const created = {};
  try {
    created.organizationId = await insert("organizations", { name: `${marker} org`, firebase_id: `${marker}:org`, is_active: true });
    created.stationId = await insert("stations", { organization_id: created.organizationId, name: `${marker} station`, firebase_id: `${marker}:station`, address: "Validation station", is_active: true, is_open: true, is_accepting_online_orders: true });
    created.foreignOrganizationId = await insert("organizations", { name: `${marker} foreign org`, firebase_id: `${marker}:foreign-org`, is_active: true });
    created.foreignStationId = await insert("stations", { organization_id: created.foreignOrganizationId, name: `${marker} foreign station`, firebase_id: `${marker}:foreign-station`, address: "Foreign validation station", is_active: true, is_open: true, is_accepting_online_orders: true });
    created.customerId = await insert("customers", { station_id: created.stationId, name: `${marker} customer`, phone: "09000000001", firebase_id: `${marker}:customer`, is_active: true });
    created.foreignCustomerId = await insert("customers", { station_id: created.foreignStationId, name: `${marker} foreign customer`, phone: "09000000002", firebase_id: `${marker}:foreign-customer`, is_active: true });
    created.productAId = await insert("products", { station_id: created.stationId, name: `${marker} product A`, firebase_id: `${marker}:product-a`, product_type: "REFILL", size_label: "A", price_centavos: 1000, stock_quantity: 20, is_active: true, is_available: true });
    created.productBId = await insert("products", { station_id: created.stationId, name: `${marker} product B`, firebase_id: `${marker}:product-b`, product_type: "REFILL", size_label: "B", price_centavos: 1500, stock_quantity: 20, is_active: true, is_available: true });
    created.stockOneProductId = await insert("products", { station_id: created.stationId, name: `${marker} stock one`, firebase_id: `${marker}:stock-one`, product_type: "REFILL", size_label: "ONE", price_centavos: 2000, stock_quantity: 1, is_active: true, is_available: true });
    for (const [productId, quantity] of [[created.productAId, 20], [created.productBId, 20], [created.stockOneProductId, 1]]) {
      await insert("inventory_balances", { station_id: created.stationId, product_id: productId, quantity, version: 0 });
    }
    await fs.writeFile(outputPath, `${JSON.stringify(created, null, 2)}\n`, { mode: 0o600 });
    console.log("ORDER_FIXTURE_SETUP_COMPLETE");
  } catch (error) {
    await cleanup(created);
    throw error;
  }
}
