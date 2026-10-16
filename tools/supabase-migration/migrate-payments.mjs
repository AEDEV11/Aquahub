#!/usr/bin/env node

/** Import historical payment exports without invoking payment providers. */
import fs from "node:fs/promises";
import { supabaseSecretKey } from "./supabase-env.mjs";
import { randomUUID } from "node:crypto";

const args = process.argv.slice(2);
const inputIndex = args.indexOf("--input");
const inputPath = inputIndex >= 0 ? args[inputIndex + 1] : null;
const apply = args.includes("--apply");
const confirmed = args.includes("--confirm-hosted-production");
if (args.includes("--help") || !inputPath) {
  console.error("Usage: migrate-payments.mjs --input <legacy-payments.json> [--apply --confirm-hosted-production]");
  process.exit(inputPath ? 0 : 2);
}
if (apply && !confirmed) throw new Error("Apply requires --confirm-hosted-production.");

const url = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const serviceRole = supabaseSecretKey();
if (!url || !serviceRole) throw new Error("Requires SUPABASE_URL and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");
const source = JSON.parse(await fs.readFile(inputPath, "utf8"));
const rows = Array.isArray(source.payments) ? source.payments : [];
const report = { mode: apply ? "apply" : "dry-run", sourceRows: rows.length, imported: [], skippedExisting: [], unmatchedOrders: [], conflicts: [], failures: [], providerActions: 0 };

const api = async (path, init = {}) => {
  const response = await fetch(`${url}${path}`, {
    ...init,
    headers: { apikey: serviceRole, Authorization: `Bearer ${serviceRole}`, "Content-Type": "application/json", Prefer: "return=representation", ...(init.headers ?? {}) },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Supabase request failed (${response.status})`);
  return text ? JSON.parse(text) : [];
};
const enc = (value) => encodeURIComponent(String(value));
const first = (value) => Array.isArray(value) ? value[0] : undefined;
const cents = (value) => { const n = Number(value); return Number.isSafeInteger(n) && n > 0 ? n : null; };
const iso = (value) => { const date = new Date(value); return Number.isNaN(date.getTime()) ? null : date.toISOString(); };

for (const row of rows) {
  const legacyId = String(row.firebase_id ?? row.id ?? "").trim();
  const legacyOrderId = String(row.order_firebase_id ?? row.orderId ?? "").trim();
  const amount = cents(row.amount_centavos ?? row.amountCentavos ?? row.amount);
  if (!legacyId || !legacyOrderId || amount === null) { report.conflicts.push({ legacyId, reason: "missing legacy id, order id, or positive centavo amount" }); continue; }
  const existing = await api(`/rest/v1/payments?select=id&firebase_id=eq.${enc(legacyId)}`);
  if (existing.length) { report.skippedExisting.push(legacyId); continue; }
  const order = first(await api(`/rest/v1/orders?select=id,station_id,customer_id&firebase_id=eq.${enc(legacyOrderId)}`));
  if (!order) { report.unmatchedOrders.push({ legacyId, legacyOrderId }); continue; }
  const paymentId = randomUUID();
  const payment = {
    id: paymentId,
    firebase_id: legacyId,
    station_id: order.station_id,
    order_id: order.id,
    customer_id: order.customer_id,
    payment_method: String(row.payment_method ?? row.paymentMethod ?? "CASH").toUpperCase(),
    amount_centavos: amount,
    status: String(row.status ?? "PAID").toUpperCase(),
    reference_number: row.reference_number ?? row.referenceNumber ?? null,
    operation_id: `legacy-payment-import:${legacyId}`,
    provider: row.provider ?? null,
    provider_transaction_id: row.provider_transaction_id ?? row.providerTransactionId ?? null,
    metadata: row.metadata ?? {},
    created_at: iso(row.created_at ?? row.createdAt) ?? new Date().toISOString(),
    paid_at: iso(row.paid_at ?? row.paidAt),
    updated_at: iso(row.updated_at ?? row.updatedAt ?? row.created_at ?? row.createdAt) ?? new Date().toISOString(),
  };
  if (!apply) { report.imported.push({ legacyId, orderId: order.id, amountCentavos: amount, providerActions: 0 }); continue; }
  try {
    await api("/rest/v1/payments", { method: "POST", body: JSON.stringify(payment) });
    report.imported.push({ legacyId, orderId: order.id, amountCentavos: amount, providerActions: 0 });
  } catch {
    report.failures.push({ legacyId });
  }
}
console.log(JSON.stringify(report, null, 2));
process.exit(report.conflicts.length || report.unmatchedOrders.length || report.failures.length ? 1 : 0);
