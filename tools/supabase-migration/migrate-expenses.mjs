#!/usr/bin/env node

/** Import historical station expenses. Dry-run is the default and Firebase is never mutated. */
import fs from "node:fs/promises";
import { supabaseSecretKey } from "./supabase-env.mjs";

const args = process.argv.slice(2);
const inputIndex = args.indexOf("--input");
const inputPath = inputIndex >= 0 ? args[inputIndex + 1] : null;
const apply = args.includes("--apply");
const confirmed = args.includes("--confirm-hosted-production");
if (args.includes("--help") || !inputPath) {
  console.error("Usage: migrate-expenses.mjs --input <legacy-expenses.json> [--apply --confirm-hosted-production]");
  process.exit(inputPath ? 0 : 2);
}
if (apply && !confirmed) throw new Error("Apply requires --confirm-hosted-production.");

const baseUrl = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const serviceRole = supabaseSecretKey();
if (!baseUrl || !serviceRole) throw new Error("Requires SUPABASE_URL and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");
const source = JSON.parse(await fs.readFile(inputPath, "utf8"));
const rows = Array.isArray(source.expenses) ? source.expenses : [];
const report = { mode: apply ? "apply" : "dry-run", sourceRows: rows.length, imported: [], skippedExisting: [], unmatchedStations: [], creatorMappings: [], conflicts: [], failures: [] };

const api = async (path, init = {}) => {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}`, "Content-Type": "application/json", Prefer: "return=representation", ...(init.headers ?? {}) },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Supabase request failed (${response.status})`);
  return text ? JSON.parse(text) : [];
};
const enc = (value) => encodeURIComponent(String(value));
const cents = (value) => { const n = Number(value); return Number.isSafeInteger(n) && n > 0 ? n : null; };
const epoch = (value) => { const n = Number(value); if (Number.isSafeInteger(n)) return n; const date = new Date(value); return Number.isNaN(date.getTime()) ? null : date.getTime(); };
const iso = (value) => { const date = new Date(value); return Number.isNaN(date.getTime()) ? null : date.toISOString(); };

for (const row of rows) {
  const legacyId = String(row.firebase_id ?? row.id ?? "").trim();
  const stationLegacyId = String(row.station_firebase_id ?? row.stationId ?? "").trim();
  const amount = cents(row.amount_centavos ?? row.amountCentavos ?? row.amount);
  const incurredAt = epoch(row.incurred_at ?? row.incurredAt ?? row.expense_date);
  if (!legacyId || !stationLegacyId || amount === null || incurredAt === null) {
    report.conflicts.push({ legacyId, reason: "missing legacy id, station id, positive centavo amount, or valid date" }); continue;
  }
  const existing = await api(`/rest/v1/expenses?select=id&firebase_id=eq.${enc(legacyId)}`);
  if (existing.length) { report.skippedExisting.push(legacyId); continue; }
  const station = (await api(`/rest/v1/stations?select=id,organization_id&firebase_id=eq.${enc(stationLegacyId)}`))[0];
  if (!station) { report.unmatchedStations.push({ legacyId, stationLegacyId }); continue; }
  const creator = row.created_by_firebase_id ?? row.createdByFirebaseId ?? null;
  if (creator) report.creatorMappings.push({ legacyId, legacyCreatorId: creator, mapped: false });
  const expense = {
    firebase_id: legacyId,
    station_id: station.id,
    organization_id: station.organization_id,
    amount_centavos: amount,
    category: String(row.category ?? "OTHER").toUpperCase(),
    description: String(row.description ?? "Operating expense").trim(),
    vendor: row.vendor ?? null,
    payment_method: String(row.payment_method ?? row.paymentMethod ?? "OTHER").toUpperCase(),
    reference_number: row.reference_number ?? row.referenceNumber ?? null,
    notes: row.notes ?? null,
    incurred_at: incurredAt,
    receipt_path: row.receipt_path ?? row.receiptPath ?? null,
    operation_id: `legacy-expense-import:${legacyId}`,
    is_active: row.is_active ?? row.isActive ?? row.deleted_at == null,
    deleted_at: iso(row.deleted_at ?? row.deletedAt),
    created_at: iso(row.created_at ?? row.createdAt) ?? new Date().toISOString(),
    updated_at: iso(row.updated_at ?? row.updatedAt ?? row.created_at ?? row.createdAt) ?? new Date().toISOString(),
  };
  if (!apply) { report.imported.push({ legacyId, stationId: station.id, amountCentavos: amount }); continue; }
  try { await api("/rest/v1/expenses", { method: "POST", body: JSON.stringify(expense) }); report.imported.push({ legacyId, stationId: station.id, amountCentavos: amount }); }
  catch { report.failures.push({ legacyId }); }
}
console.log(JSON.stringify(report, null, 2));
process.exit(report.conflicts.length || report.unmatchedStations.length || report.failures.length ? 1 : 0);
