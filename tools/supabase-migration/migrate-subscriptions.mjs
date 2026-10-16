#!/usr/bin/env node

/**
 * Import legacy subscription snapshots without claiming Google verification.
 * Dry-run is the default. Firebase is never mutated and purchase tokens are
 * never accepted as proof of a current Google Play subscription.
 */
import fs from "node:fs/promises";
import { supabaseSecretKey } from "./supabase-env.mjs";

const args = process.argv.slice(2);
const inputIndex = args.indexOf("--input");
const inputPath = inputIndex >= 0 ? args[inputIndex + 1] : null;
const apply = args.includes("--apply");
const confirmed = args.includes("--confirm-hosted-production");

if (args.includes("--help") || !inputPath) {
  console.error("Usage: migrate-subscriptions.mjs --input <legacy-subscriptions.json> [--apply --confirm-hosted-production]");
  process.exit(inputPath ? 0 : 2);
}
if (apply && !confirmed) throw new Error("Apply requires --confirm-hosted-production.");

const baseUrl = (process.env.SUPABASE_URL ?? "").replace(/\/$/, "");
const serviceRole = supabaseSecretKey();
if (!baseUrl || !serviceRole) throw new Error("Requires SUPABASE_URL and SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY.");

const payload = JSON.parse(await fs.readFile(inputPath, "utf8"));
const rows = Array.isArray(payload.subscriptions) ? payload.subscriptions : [];
const validStatuses = new Set([
  "FREE", "TRIALING", "ACTIVE", "ACTIVE_UNTIL_PERIOD_END", "EXPIRED",
  "CANCELLED", "GRACE_PERIOD", "SUSPENDED",
]);
const validBillingCycles = new Set(["MONTHLY", "ANNUAL"]);
const report = {
  mode: apply ? "apply" : "dry-run",
  sourceRows: rows.length,
  imported: [],
  skippedExisting: [],
  unresolvedOwners: [],
  unresolvedOrganizations: [],
  unresolvedPlans: [],
  conflicts: [],
  failures: [],
};

const api = async (path, init = {}) => {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      apikey: serviceRole,
      Authorization: `Bearer ${serviceRole}`,
      "Content-Type": "application/json",
      Prefer: "return=representation",
      ...(init.headers ?? {}),
    },
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Supabase request failed (${response.status})`);
  return text ? JSON.parse(text) : [];
};

const enc = (value) => encodeURIComponent(String(value));
const text = (value) => String(value ?? "").trim();
const epoch = (value) => {
  if (value === null || value === undefined || value === "") return null;
  const numeric = Number(value);
  if (Number.isSafeInteger(numeric)) return numeric;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.getTime();
};
const bool = (value, fallback = false) => {
  if (value === undefined || value === null || value === "") return fallback;
  if (typeof value === "boolean") return value;
  return ["true", "1", "yes"].includes(String(value).trim().toLowerCase());
};
const iso = (value) => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
};

for (const row of rows) {
  const legacyId = text(row.firebase_id ?? row.firebaseId ?? row.id);
  const legacyOrgId = text(row.organization_firebase_id ?? row.organizationFirebaseId ?? row.business_id ?? row.businessId);
  const legacyUid = text(row.owner_firebase_uid ?? row.ownerUid ?? row.firebase_uid ?? row.firebaseUid);
  const planCode = text(row.plan_code ?? row.planCode ?? row.plan).toUpperCase();
  const status = text(row.status).toUpperCase();
  const billingCycle = text(row.billing_cycle ?? row.billingCycle).toUpperCase() || "MONTHLY";
  const sourceExpiry = epoch(row.expires_at ?? row.expiresAt ?? row.current_period_end ?? row.currentPeriodEnd);
  if (!legacyId || !planCode || !status) {
    report.conflicts.push({ legacyId, reason: "missing legacy id, plan code, or status" });
    continue;
  }
  if (!validStatuses.has(status)) {
    report.conflicts.push({ legacyId, reason: "unsupported subscription status", status });
    continue;
  }
  if (!validBillingCycles.has(billingCycle)) {
    report.conflicts.push({ legacyId, reason: "unsupported billing cycle", billingCycle });
    continue;
  }
  if (status !== "FREE" && sourceExpiry === null) {
    report.conflicts.push({ legacyId, reason: "non-free subscription has no valid expiry" });
    continue;
  }

  const existing = await api(`/rest/v1/subscriptions?select=id,organization_id,firebase_id&firebase_id=eq.${enc(legacyId)}`);
  if (existing.length) {
    report.skippedExisting.push(legacyId);
    continue;
  }

  let organization = legacyOrgId
    ? (await api(`/rest/v1/organizations?select=id,owner_profile_id&firebase_id=eq.${enc(legacyOrgId)}`))[0]
    : null;
  if (!organization && legacyUid) {
    const owner = (await api(`/rest/v1/profiles?select=id&firebase_uid=eq.${enc(legacyUid)}`))[0];
    if (owner) organization = (await api(`/rest/v1/organizations?select=id,owner_profile_id&owner_profile_id=eq.${enc(owner.id)}`))[0];
    else report.unresolvedOwners.push({ legacyId, legacyUid });
  }
  if (!organization) {
    report.unresolvedOrganizations.push({ legacyId, legacyOrgId, legacyUid });
    continue;
  }

  const plan = (await api(`/rest/v1/subscription_plans?select=id,code&code=eq.${enc(planCode)}&is_active=eq.true`))[0];
  if (!plan) {
    report.unresolvedPlans.push({ legacyId, planCode });
    continue;
  }

  const current = (await api(`/rest/v1/subscriptions?select=id,firebase_id&organization_id=eq.${enc(organization.id)}`))[0];
  if (current && current.firebase_id !== legacyId) {
    report.conflicts.push({ legacyId, reason: "organization already has a different subscription row", organizationId: organization.id });
    continue;
  }

  const startedAt = epoch(row.started_at ?? row.startedAt ?? row.created_at ?? row.createdAt) ?? Date.now();
  const currentPeriodStart = epoch(row.current_period_start ?? row.currentPeriodStart) ?? startedAt;
  const currentPeriodEnd = epoch(row.current_period_end ?? row.currentPeriodEnd ?? row.expires_at ?? row.expiresAt) ?? sourceExpiry ?? startedAt;
  const subscription = {
    firebase_id: legacyId,
    organization_id: organization.id,
    plan_id: plan.id,
    status,
    billing_cycle: billingCycle,
    started_at: startedAt,
    current_period_start: currentPeriodStart,
    current_period_end: currentPeriodEnd,
    trial_started_at: epoch(row.trial_started_at ?? row.trialStartedAt),
    trial_ends_at: epoch(row.trial_ends_at ?? row.trialEndsAt),
    cancelled_at: epoch(row.cancelled_at ?? row.cancelledAt),
    expires_at: epoch(row.expires_at ?? row.expiresAt),
    grace_period_ends_at: epoch(row.grace_period_ends_at ?? row.gracePeriodEndsAt),
    auto_renew: bool(row.auto_renew ?? row.autoRenew),
    cancel_at_period_end: bool(row.cancel_at_period_end ?? row.cancelAtPeriodEnd),
    provider: "LEGACY_IMPORT",
    external_subscription_id: null,
    currency: text(row.currency).toUpperCase() || "PHP",
    client_updated_at: epoch(row.updated_at ?? row.updatedAt),
    created_at: iso(row.created_at ?? row.createdAt) ?? new Date().toISOString(),
    updated_at: iso(row.updated_at ?? row.updatedAt ?? row.created_at ?? row.createdAt) ?? new Date().toISOString(),
  };

  const summary = { legacyId, organizationId: organization.id, planCode, status, provider: "LEGACY_IMPORT" };
  if (!apply) {
    report.imported.push(summary);
    continue;
  }
  try {
    await api("/rest/v1/subscriptions", { method: "POST", body: JSON.stringify(subscription) });
    report.imported.push(summary);
  } catch {
    report.failures.push({ legacyId, organizationId: organization.id });
  }
}

console.log(JSON.stringify(report, null, 2));
process.exit(
  report.conflicts.length || report.unresolvedOrganizations.length || report.unresolvedPlans.length || report.failures.length ? 1 : 0,
);
