#!/usr/bin/env node

/**
 * Restartable legacy product-stock importer.
 *
 * Input is a redacted/exported JSON file, never a live client credential:
 * { "products": [{ "firebase_id": "...", "station_firebase_id": "...",
 *                    "stockQuantity": 12 }] }
 *
 * Dry-run is the default. Apply requires --apply --confirm-hosted-production
 * and SUPABASE_URL/SUPABASE_SERVICE_ROLE_KEY in the process environment.
 * The service-role key is never printed. Firebase source data is never deleted.
 */

import fs from "node:fs/promises";
import { supabaseSecretKey } from "./supabase-env.mjs";
import process from "node:process";

const args = process.argv.slice(2);
const inputIndex = args.indexOf("--input");
const inputPath = inputIndex >= 0 ? args[inputIndex + 1] : null;
const apply = args.includes("--apply");
const confirmed = args.includes("--confirm-hosted-production");

if (!inputPath || args.includes("--help")) {
  console.error("Usage: migrate-inventory.mjs --input <legacy-export.json> [--apply --confirm-hosted-production]");
  process.exit(inputPath ? 0 : 2);
}
if (apply && !confirmed) {
  console.error("Apply requires --confirm-hosted-production.");
  process.exit(2);
}

const source = JSON.parse(await fs.readFile(inputPath, "utf8"));
const products = Array.isArray(source.products) ? source.products : [];
const duplicateIds = new Set();
const seenIds = new Set();
for (const product of products) {
  const id = String(product.firebase_id ?? "").trim();
  if (!id) continue;
  if (seenIds.has(id)) duplicateIds.add(id);
  seenIds.add(id);
}

const url = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const serviceRole = supabaseSecretKey();
if (!url || !serviceRole) {
  console.error("Inventory mapping requires SUPABASE_URL and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");
  process.exit(2);
}

async function supabase(path, init = {}) {
  const response = await fetch(`${url}${path}`, {
    ...init,
    headers: {
      apikey: serviceRole,
      Authorization: `Bearer ${serviceRole}`,
      "Content-Type": "application/json",
      Prefer: "return=representation",
      ...(init.headers ?? {})
    }
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`Supabase request failed (${response.status})`);
  return body ? JSON.parse(body) : null;
}

const report = {
  mode: apply ? "apply" : "dry-run",
  sourceRows: products.length,
  duplicateLegacyIds: [...duplicateIds],
  missingStations: [],
  missingProducts: [],
  invalidQuantities: [],
  applied: [],
  skippedExisting: [],
  failures: []
};

if (duplicateIds.size) {
  console.error(JSON.stringify(report, null, 2));
  process.exit(1);
}

for (const product of products) {
  const legacyProductId = String(product.firebase_id ?? "").trim();
  const legacyStationId = String(product.station_firebase_id ?? product.station_id ?? "").trim();
  const quantity = Number(product.stockQuantity ?? product.stock_quantity);
  if (!legacyProductId || !legacyStationId || !Number.isSafeInteger(quantity) || quantity < 0) {
    report.invalidQuantities.push({ legacyProductId, legacyStationId });
    continue;
  }

  try {
    const stations = await supabase(`/rest/v1/stations?select=id,firebase_id&firebase_id=eq.${encodeURIComponent(legacyStationId)}`);
    const station = stations[0];
    if (!station) {
      report.missingStations.push({ legacyStationId, legacyProductId });
      continue;
    }
    const productsByLegacyId = await supabase(`/rest/v1/products?select=id,station_id,firebase_id&firebase_id=eq.${encodeURIComponent(legacyProductId)}`);
    const target = productsByLegacyId[0];
    if (!target || target.station_id !== station.id) {
      report.missingProducts.push({ legacyProductId, legacyStationId });
      continue;
    }
    const operationId = `legacy-opening:${legacyProductId}`;
    if (!apply) {
      report.applied.push({ legacyProductId, stationId: station.id, productId: target.id, quantity, operationId });
      continue;
    }
    const result = await supabase("/rest/v1/rpc/adjust_inventory", {
      method: "POST",
      body: JSON.stringify({
        p_station_id: station.id,
        p_product_id: target.id,
        p_delta: quantity,
        p_operation_id: operationId,
        p_reason: "Legacy Firebase opening balance",
        p_order_id: null,
        p_movement_type: "LEGACY_OPENING_BALANCE",
        p_source_id: legacyProductId
      })
    });
    report.applied.push({ legacyProductId, stationId: station.id, productId: target.id, quantity, operationId, result: "committed-or-reused" });
  } catch {
    report.failures.push({ legacyProductId, legacyStationId });
  }
}

console.log(JSON.stringify(report, null, 2));
process.exit(report.missingStations.length || report.missingProducts.length || report.invalidQuantities.length || report.failures.length ? 1 : 0);
