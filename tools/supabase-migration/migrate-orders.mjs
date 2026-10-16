#!/usr/bin/env node

/**
 * Import legacy Firebase order exports without replaying live inventory.
 *
 * Dry-run is the default. Writes require both --apply and
 * --confirm-hosted-production. Expected input is a redacted JSON export:
 * {
 *   "orders": [{
 *     "firebase_id": "...", "organization_firebase_id": "...",
 *     "station_firebase_id": "...", "customer_firebase_id": "...",
 *     "orderNumber": "...", "status": "COMPLETED", "items": [...],
 *     "subtotal_centavos": 10000, "total_centavos": 10000,
 *     "created_at": "2026-01-01T00:00:00Z"
 *   }]
 * }
 *
 * This importer writes orders/order_items with order_source=LEGACY_IMPORT.
 * It never calls create_order or adjust_inventory, so current stock is not
 * changed. Firebase source data is never deleted.
 */

import fs from "node:fs/promises";
import { supabaseSecretKey } from "./supabase-env.mjs";
import { randomUUID } from "node:crypto";

const args = process.argv.slice(2);
const inputIndex = args.indexOf("--input");
const inputPath = inputIndex >= 0 ? args[inputIndex + 1] : null;
const apply = args.includes("--apply");
const confirmed = args.includes("--confirm-hosted-production");

if (!inputPath || args.includes("--help")) {
  console.error("Usage: migrate-orders.mjs --input <legacy-orders.json> [--apply --confirm-hosted-production]");
  process.exit(inputPath ? 0 : 2);
}
if (apply && !confirmed) {
  console.error("Apply requires --confirm-hosted-production.");
  process.exit(2);
}

const url = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const serviceRole = supabaseSecretKey();
if (!url || !serviceRole) {
  console.error("Order import requires SUPABASE_URL and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");
  process.exit(2);
}

const source = JSON.parse(await fs.readFile(inputPath, "utf8"));
const orders = Array.isArray(source.orders) ? source.orders : [];

async function supabase(path, init = {}) {
  const response = await fetch(`${url}${path}`, {
    ...init,
    headers: {
      apikey: serviceRole,
      Authorization: `Bearer ${serviceRole}`,
      "Content-Type": "application/json",
      Prefer: "return=representation",
      ...(init.headers ?? {}),
    },
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`Supabase request failed (${response.status})`);
  return body ? JSON.parse(body) : [];
}

const report = {
  mode: apply ? "apply" : "dry-run",
  sourceRows: orders.length,
  imported: [],
  skippedExisting: [],
  unmappedOrganizations: [],
  unmappedStations: [],
  unmappedCustomers: [],
  unmappedProducts: [],
  conflicts: [],
  invalidRows: [],
  failures: [],
  inventoryMutations: 0,
};

const asCentavos = (value, fallback = 0) => {
  const n = Number(value ?? fallback);
  return Number.isSafeInteger(n) && n >= 0 ? n : null;
};
const asInt = (value, min, max) => {
  const n = Number(value);
  return Number.isSafeInteger(n) && n >= min && n <= max ? n : null;
};
const asIso = (value) => {
  const date = new Date(value ?? Date.now());
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
};
const asCoordinate = (value) => {
  if (value === null || value === undefined || value === "") return null;
  const n = Number(value);
  return Number.isFinite(n) && n >= -180 && n <= 180 ? n : null;
};
const encode = (value) => encodeURIComponent(String(value));
const first = (rows) => Array.isArray(rows) ? rows[0] : undefined;

for (const row of orders) {
  const legacyOrderId = String(row.firebase_id ?? row.id ?? "").trim();
  const legacyOrgId = String(row.organization_firebase_id ?? row.business_firebase_id ?? "").trim();
  const legacyStationId = String(row.station_firebase_id ?? row.station_id ?? "").trim();
  const legacyCustomerId = String(row.customer_firebase_id ?? row.customer_id ?? "").trim();
  const items = Array.isArray(row.items) ? row.items : [];
  if (!legacyOrderId || !legacyStationId || !items.length) {
    report.invalidRows.push({ legacyOrderId, reason: "missing firebase_id, station, or items" });
    continue;
  }

  const existing = await supabase(`/rest/v1/orders?select=id&firebase_id=eq.${encode(legacyOrderId)}`);
  if (existing.length) {
    report.skippedExisting.push(legacyOrderId);
    continue;
  }

  const station = first(await supabase(`/rest/v1/stations?select=id,organization_id&firebase_id=eq.${encode(legacyStationId)}`));
  if (!station) { report.unmappedStations.push({ legacyOrderId, legacyStationId }); continue; }
  if (legacyOrgId) {
    const org = first(await supabase(`/rest/v1/organizations?select=id&firebase_id=eq.${encode(legacyOrgId)}`));
    if (!org || org.id !== station.organization_id) {
      report.unmappedOrganizations.push({ legacyOrderId, legacyOrgId });
      continue;
    }
  }

  let customerId = null;
  if (legacyCustomerId) {
    const customer = first(await supabase(`/rest/v1/customers?select=id,station_id&firebase_id=eq.${encode(legacyCustomerId)}`));
    if (!customer || customer.station_id !== station.id) {
      report.unmappedCustomers.push({ legacyOrderId, legacyCustomerId });
      continue;
    }
    customerId = customer.id;
  }

  const mappedItems = [];
  let calculatedSubtotal = 0;
  for (const item of items) {
    const legacyProductId = String(item.product_firebase_id ?? item.productId ?? item.product_id ?? "").trim();
    const quantity = asInt(item.quantity, 1, 99);
    const unit = asCentavos(item.unit_price_centavos ?? item.unitPriceCentavos ?? item.unitPrice);
    const subtotal = asCentavos(item.subtotal_centavos ?? item.subtotal);
    if (!legacyProductId || quantity === null || unit === null || subtotal === null || subtotal !== unit * quantity) {
      report.conflicts.push({ legacyOrderId, reason: "invalid item quantity or centavo subtotal", legacyProductId });
      mappedItems.length = 0;
      break;
    }
    const product = first(await supabase(`/rest/v1/products?select=id,station_id,name,product_type,size_label&firebase_id=eq.${encode(legacyProductId)}`));
    if (!product || product.station_id !== station.id) {
      report.unmappedProducts.push({ legacyOrderId, legacyProductId });
      mappedItems.length = 0;
      break;
    }
    calculatedSubtotal += subtotal;
    mappedItems.push({ product, legacyProductId, quantity, unit, subtotal });
  }
  if (!mappedItems.length) continue;

  const subtotal = asCentavos(row.subtotal_centavos ?? row.subtotal, calculatedSubtotal);
  const deliveryFee = asCentavos(row.delivery_fee_centavos ?? row.deliveryFee, 0);
  const discount = asCentavos(row.discount_centavos ?? row.discount, 0);
  const tax = asCentavos(row.tax_centavos ?? row.tax, 0);
  const total = asCentavos(row.total_centavos ?? row.total, subtotal - discount + deliveryFee + tax);
  if (subtotal === null || deliveryFee === null || discount === null || tax === null || total === null || total !== subtotal - discount + deliveryFee + tax) {
    report.conflicts.push({ legacyOrderId, reason: "order centavo totals do not reconcile" });
    continue;
  }

  const orderId = randomUUID();
  const deliveryMode = String(row.delivery_mode ?? row.deliveryMode ?? "PICKUP").toUpperCase() === "DELIVERY" ? "DELIVERY" : "PICKUP";
  const order = {
    id: orderId,
    firebase_id: legacyOrderId,
    client_operation_id: `legacy-import:${legacyOrderId}`,
    station_id: station.id,
    customer_id: customerId,
    customer_uid: null,
    order_number: String(row.order_number ?? row.orderNumber ?? legacyOrderId),
    customer_name: String(row.customer_name ?? row.customerName ?? "Historical Customer"),
    customer_phone: String(row.customer_phone ?? row.customerPhone ?? ""),
    delivery_mode: deliveryMode,
    delivery_address: deliveryMode === "PICKUP" ? "Station pickup" : String(row.delivery_address ?? row.deliveryAddress ?? ""),
    delivery_latitude: asCoordinate(row.delivery_latitude ?? row.deliveryLatitude),
    delivery_longitude: asCoordinate(row.delivery_longitude ?? row.deliveryLongitude),
    subtotal_centavos: subtotal,
    delivery_fee_centavos: deliveryFee,
    discount_centavos: discount,
    tax_centavos: tax,
    total_centavos: total,
    payment_method: String(row.payment_method ?? row.paymentMethod ?? "CASH").toUpperCase(),
    payment_status: String(row.payment_status ?? row.paymentStatus ?? "PAID").toUpperCase(),
    status: String(row.status ?? "COMPLETED").toUpperCase(),
    customer_note: row.customer_note ?? row.customerNote ?? null,
    requested_at: asIso(row.requested_at ?? row.created_at ?? row.createdAt),
    created_at: asIso(row.created_at ?? row.createdAt),
    updated_at: asIso(row.updated_at ?? row.updatedAt ?? row.created_at ?? row.createdAt),
    order_source: "LEGACY_IMPORT",
  };

  const rawLatitude = row.delivery_latitude ?? row.deliveryLatitude;
  const rawLongitude = row.delivery_longitude ?? row.deliveryLongitude;
  if ((rawLatitude !== null && rawLatitude !== undefined && order.delivery_latitude === null) ||
      (rawLongitude !== null && rawLongitude !== undefined && order.delivery_longitude === null)) {
    report.conflicts.push({ legacyOrderId, reason: "invalid delivery coordinates" });
    continue;
  }

  if (!apply) {
    report.imported.push({ legacyOrderId, stationId: station.id, itemCount: mappedItems.length, orderId, inventoryMutations: 0 });
    continue;
  }

  try {
    await supabase("/rest/v1/orders", { method: "POST", body: JSON.stringify(order) });
    await supabase("/rest/v1/order_items", {
      method: "POST",
      body: JSON.stringify(mappedItems.map(({ product, legacyProductId, quantity, unit, subtotal }) => ({
        firebase_id: `${legacyOrderId}:${legacyProductId}`,
        order_id: orderId,
        product_id: product.id,
        product_name_snapshot: product.name,
        product_type_snapshot: product.product_type,
        size_label_snapshot: product.size_label,
        unit_price_centavos: unit,
        quantity,
        subtotal_centavos: subtotal,
        created_at: order.created_at,
      }))),
    });
    report.imported.push({ legacyOrderId, orderId, itemCount: mappedItems.length, inventoryMutations: 0 });
  } catch {
    await supabase(`/rest/v1/orders?id=eq.${encode(orderId)}`, { method: "DELETE", headers: { Prefer: "return=minimal" } }).catch(() => {});
    report.failures.push({ legacyOrderId });
  }
}

console.log(JSON.stringify(report, null, 2));
process.exit(report.invalidRows.length || report.conflicts.length || report.failures.length || report.unmappedStations.length || report.unmappedOrganizations.length || report.unmappedCustomers.length || report.unmappedProducts.length ? 1 : 0);
