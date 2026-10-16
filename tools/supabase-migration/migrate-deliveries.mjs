#!/usr/bin/env node

/**
 * Delivery-history reconciliation for legacy orders.
 *
 * Delivery is embedded in the canonical orders row; this tool deliberately
 * does not create a second delivery table, send notifications, settle COD, or
 * delete Firebase data. It is dry-run by default.
 *
 * Usage:
 *   migrate-deliveries.mjs --input legacy-orders.json
 *   migrate-deliveries.mjs --input legacy-orders.json --apply --confirm-hosted-production
 */

import fs from "node:fs/promises";
import { supabaseSecretKey } from "./supabase-env.mjs";

const args = process.argv.slice(2);
const value = (flag) => { const index = args.indexOf(flag); return index >= 0 ? args[index + 1] : null; };
const inputPath = value("--input");
const apply = args.includes("--apply");
if (!inputPath || (apply && !args.includes("--confirm-hosted-production"))) {
  console.error("Usage: migrate-deliveries.mjs --input <legacy-orders.json> [--apply --confirm-hosted-production]");
  process.exit(2);
}

const baseUrl = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const serviceRole = supabaseSecretKey();
if (!baseUrl || !serviceRole) {
  console.error("Requires SUPABASE_URL and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY. Values are never logged.");
  process.exit(2);
}

const source = JSON.parse(await fs.readFile(inputPath, "utf8"));
const rows = Array.isArray(source) ? source : source.orders;
if (!Array.isArray(rows)) throw new Error("Input must be an array or an object with an orders array");

const report = { mode: apply ? "apply" : "dry-run", inspected: 0, deliveryOrders: 0, updated: 0, unchanged: 0, unmapped: [], conflicts: [], failures: [] };
const encode = (value) => encodeURIComponent(value);

async function request(path, init = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: { apikey: serviceRole, authorization: `Bearer ${serviceRole}`, "content-type": "application/json", ...(init.headers ?? {}) },
  });
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return body;
}

const first = async (path) => (await request(path))?.[0] ?? null;

function field(row, ...names) { return names.map((name) => row[name]).find((value) => value !== undefined); }
function iso(value) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toISOString();
}

for (const row of rows) {
  report.inspected++;
  const legacyOrderId = String(field(row, "firebase_id", "firebaseId", "id", "orderId") ?? "").trim();
  const mode = String(field(row, "delivery_mode", "deliveryMode") ?? "PICKUP").toUpperCase();
  if (!legacyOrderId) { report.conflicts.push({ reason: "missing legacy order id" }); continue; }
  if (mode !== "DELIVERY") { report.unchanged++; continue; }
  report.deliveryOrders++;

  const existing = await first(`/rest/v1/orders?select=id,station_id,status,rider_id,delivery_mode,delivery_address,delivery_latitude,delivery_longitude,delivered_at,completed_at&firebase_id=eq.${encode(legacyOrderId)}`);
  if (!existing) { report.unmapped.push({ legacyOrderId, reason: "order not found in Supabase" }); continue; }
  const stationLegacyId = String(field(row, "station_firebase_id", "stationFirebaseId", "station_id", "stationId") ?? "").trim();
  const station = stationLegacyId ? await first(`/rest/v1/stations?select=id&firebase_id=eq.${encode(stationLegacyId)}`) : null;
  if (station && station.id !== existing.station_id) { report.conflicts.push({ legacyOrderId, reason: "station mapping differs from canonical order" }); continue; }

  const requestedStatus = String(field(row, "status", "orderStatus") ?? existing.status).toUpperCase();
  const allowedStatuses = new Set(["PENDING", "ACCEPTED", "PREPARING", "READY_FOR_PICKUP", "RIDER_ASSIGNED", "OUT_FOR_DELIVERY", "DELIVERED", "COMPLETED", "REJECTED", "CANCELLED"]);
  if (!allowedStatuses.has(requestedStatus)) { report.conflicts.push({ legacyOrderId, reason: "invalid delivery order status" }); continue; }
  const riderLegacyId = String(field(row, "rider_firebase_id", "riderFirebaseId", "rider_id", "riderId") ?? "").trim();
  const rider = riderLegacyId ? await first(`/rest/v1/riders?select=id&firebase_id=eq.${encode(riderLegacyId)}`) : null;
  if (riderLegacyId && !rider) { report.unmapped.push({ legacyOrderId, riderLegacyId, reason: "rider not found in Supabase" }); continue; }
  const patch = {
    delivery_mode: "DELIVERY",
    delivery_address: String(field(row, "delivery_address", "deliveryAddress") ?? existing.delivery_address ?? ""),
    delivery_latitude: field(row, "delivery_latitude", "deliveryLatitude") ?? existing.delivery_latitude,
    delivery_longitude: field(row, "delivery_longitude", "deliveryLongitude") ?? existing.delivery_longitude,
    ...(rider ? { rider_id: rider.id } : {}),
    status: requestedStatus,
    delivered_at: iso(field(row, "delivered_at", "deliveredAt")) ?? existing.delivered_at,
    completed_at: iso(field(row, "completed_at", "completedAt")) ?? existing.completed_at,
    updated_at: iso(field(row, "updated_at", "updatedAt", "created_at", "createdAt")) ?? new Date().toISOString(),
  };
  const changed = Object.entries(patch).some(([key, next]) => String(existing[key] ?? "") !== String(next ?? ""));
  if (!changed) { report.unchanged++; continue; }
  if (apply) {
    try {
      await request(`/rest/v1/orders?id=eq.${encode(existing.id)}`, { method: "PATCH", headers: { prefer: "return=minimal" }, body: JSON.stringify(patch) });
      report.updated++;
    } catch (error) {
      report.failures.push({ legacyOrderId, reason: error instanceof Error ? error.message : "update failed" });
    }
  } else {
    report.updated++;
  }
}

console.log(JSON.stringify(report, null, 2));
