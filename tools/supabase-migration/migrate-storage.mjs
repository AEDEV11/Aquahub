#!/usr/bin/env node

/** Migrate a reviewed Firebase Storage manifest without deleting Firebase objects. */
import fs from "node:fs/promises";
import { supabaseSecretKey } from "./supabase-env.mjs";
import crypto from "node:crypto";

const args = process.argv.slice(2);
const inputIndex = args.indexOf("--input");
const inputPath = inputIndex >= 0 ? args[inputIndex + 1] : null;
const apply = args.includes("--apply");
const confirmed = args.includes("--confirm-hosted-production");
if (args.includes("--help") || !inputPath) {
  console.error("Usage: migrate-storage.mjs --input <storage-manifest.json> [--apply --confirm-hosted-production]");
  process.exit(inputPath ? 0 : 2);
}
if (apply && !confirmed) throw new Error("Apply requires --confirm-hosted-production.");
const baseUrl = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const serviceRole = supabaseSecretKey();
if (!baseUrl || !serviceRole) throw new Error("Requires SUPABASE_URL and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");
const source = JSON.parse(await fs.readFile(inputPath, "utf8"));
const rows = Array.isArray(source.objects) ? source.objects : [];
const report = { mode: apply ? "apply" : "dry-run", sourceRows: rows.length, migrated: [], skipped: [], conflicts: [], failures: [], firebaseDeletes: 0 };
const enc = (value) => encodeURIComponent(String(value));
const api = async (path, init = {}) => {
  const response = await fetch(`${baseUrl}${path}`, { ...init, headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}`, ...(init.headers ?? {}) } });
  const text = await response.text();
  if (!response.ok) throw new Error(`Supabase request failed (${response.status})`);
  return text ? JSON.parse(text) : null;
};
const sha256 = (bytes) => crypto.createHash("sha256").update(bytes).digest("hex");

for (const row of rows) {
  const bucket = String(row.target_bucket ?? "").trim();
  const path = String(row.target_path ?? "").trim();
  const sourceFile = String(row.source_file ?? "").trim();
  if (!bucket || !path || !sourceFile || !["product-images", "station-assets", "payment-assets"].includes(bucket)) {
    report.conflicts.push({ legacyPath: row.firebase_path ?? row.download_url ?? null, reason: "missing source_file, target bucket/path, or unsupported bucket" }); continue;
  }
  const bytes = await fs.readFile(sourceFile);
  const checksum = sha256(bytes);
  if (row.sha256 && row.sha256 !== checksum) { report.conflicts.push({ legacyPath: row.firebase_path ?? null, reason: "checksum mismatch" }); continue; }
  if (row.size_bytes != null && Number(row.size_bytes) !== bytes.length) { report.conflicts.push({ legacyPath: row.firebase_path ?? null, reason: "size mismatch" }); continue; }
  const existing = await api(`/storage/v1/object/info/${enc(bucket)}/${enc(path)}`).catch(() => null);
  if (existing) { report.skipped.push({ bucket, path, checksum }); continue; }
  if (!apply) { report.migrated.push({ bucket, path, checksum, bytes: bytes.length, firebaseDeleted: false }); continue; }
  try {
    const response = await fetch(`${baseUrl}/storage/v1/object/${enc(bucket)}/${enc(path)}`, { method: "POST", headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}`, "Content-Type": row.content_type ?? "application/octet-stream", "x-upsert": "false" }, body: bytes });
    if (!response.ok) throw new Error(`Storage upload failed (${response.status})`);
    report.migrated.push({ bucket, path, checksum, bytes: bytes.length, firebaseDeleted: false });
  } catch { report.failures.push({ bucket, path }); }
}
console.log(JSON.stringify(report, null, 2));
process.exit(report.conflicts.length || report.failures.length ? 1 : 0);
