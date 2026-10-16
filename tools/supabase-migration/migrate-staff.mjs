#!/usr/bin/env node
import fs from "node:fs/promises";

const args = new Set(process.argv.slice(2));
const apply = args.has("--apply");
const confirmed = args.has("--confirm-hosted-production");
const input = process.env.STAFF_EXPORT ?? "tools/supabase-migration/staff-export.json";

if (apply && !confirmed) throw new Error("Refusing staff apply without --confirm-hosted-production");
const payload = JSON.parse(await fs.readFile(input, "utf8"));
const rows = Array.isArray(payload.staff) ? payload.staff : [];
const validRoles = new Set(["OWNER", "MANAGER", "STAFF", "RIDER"]);
const seen = new Set();
const report = { mode: apply ? "apply" : "dry-run", input, total: rows.length, valid: 0, conflicts: [], unresolved: [], mappings: [] };

for (const row of rows) {
  const firebaseId = String(row.firebase_uid ?? row.uid ?? "").trim();
  const role = String(row.role ?? "").toUpperCase();
  const stations = Array.isArray(row.station_ids) ? row.station_ids.map(String) : [];
  if (!firebaseId || !row.organization_firebase_id || !validRoles.has(role)) {
    report.unresolved.push({ firebaseId, reason: "missing organization or supported role" });
    continue;
  }
  const key = `${row.organization_firebase_id}:${firebaseId}`;
  if (seen.has(key)) {
    report.conflicts.push({ key, reason: "duplicate organization/firebase identity" });
    continue;
  }
  seen.add(key);
  report.valid += 1;
  report.mappings.push({ firebaseId, organizationFirebaseId: String(row.organization_firebase_id), role, stationFirebaseIds: stations, action: apply ? "upsert-after-validated-mapping" : "would-upsert" });
}

console.log(JSON.stringify(report, null, 2));
