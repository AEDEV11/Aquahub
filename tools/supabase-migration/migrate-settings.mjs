#!/usr/bin/env node
import fs from "node:fs/promises";

const args = new Set(process.argv.slice(2));
const apply = args.has("--apply");
const confirmed = args.has("--confirm-hosted-production");
const input = process.env.SETTINGS_EXPORT ?? "tools/supabase-migration/settings-export.json";
if (apply && !confirmed) throw new Error("Refusing settings apply without --confirm-hosted-production");
const payload = JSON.parse(await fs.readFile(input, "utf8"));
const rows = Array.isArray(payload.stations) ? payload.stations : [];
const supported = new Set(["name", "description", "phone", "email", "address", "barangay", "city", "province", "logo_path", "opening_time", "closing_time", "is_accepting_online_orders", "delivery_radius_km", "delivery_fee_centavos", "cash_payment_enabled", "cod_payment_enabled", "gcash_payment_enabled", "gcash_account_name", "gcash_account_number", "gcash_qr_image_path", "maya_payment_enabled", "maya_account_name", "maya_account_number", "maya_qr_image_path"]);
const report = { mode: apply ? "apply" : "dry-run", input, total: rows.length, mappings: [], unsupported: [] };
for (const row of rows) {
  const values = {};
  for (const [key, value] of Object.entries(row.settings ?? {})) {
    if (supported.has(key)) values[key] = value;
    else report.unsupported.push({ stationFirebaseId: row.station_firebase_id, key });
  }
  report.mappings.push({ stationFirebaseId: row.station_firebase_id, organizationFirebaseId: row.organization_firebase_id, values, action: apply ? "upsert-after-validated-mapping" : "would-upsert" });
}
console.log(JSON.stringify(report, null, 2));
