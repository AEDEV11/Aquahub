import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { legacyOwnerTrialUidHash, ownerTrialIdentityHashes } from "../_shared/trial-identity.ts";

const corsHeaders = {
  "access-control-allow-headers": "authorization, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-origin": "*",
  "content-type": "application/json",
};
const json = (status: number, body: Record<string, unknown>) => new Response(JSON.stringify(body), { status, headers: corsHeaders });

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return json(405, { error: "METHOD_NOT_ALLOWED" });
  const authorization = request.headers.get("authorization") ?? "";
  if (!authorization.startsWith("Bearer ")) return json(401, { error: "UNAUTHORIZED" });
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.trim();
  if (!serviceKey || !supabaseUrl) return json(503, { error: "TRIAL_SERVICE_UNAVAILABLE" });
  const admin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  const { data: authData, error: authError } = await admin.auth.getUser(authorization.slice(7).trim());
  if (authError || !authData.user) return json(401, { error: "UNAUTHORIZED" });
  const { data: profile, error: profileError } = await admin.from("profiles")
    .select("preferred_business_id").eq("id", authData.user.id).maybeSingle();
  if (profileError) return json(503, { error: "OWNER_LOOKUP_FAILED" });
  let membershipQuery = admin.from("organization_members")
    .select("organization_id, role, is_active")
    .eq("profile_id", authData.user.id)
    .eq("role", "OWNER")
    .eq("is_active", true);
  if (profile?.preferred_business_id) {
    membershipQuery = membershipQuery.eq("organization_id", profile.preferred_business_id);
  }
  const { data: membership, error: membershipError } = await membershipQuery
    .order("created_at", { ascending: true }).limit(1).maybeSingle();
  if (membershipError) return json(503, { error: "OWNER_LOOKUP_FAILED" });
  if (!membership) return json(403, { error: "OWNER_REQUIRED" });

  let body: { operation?: unknown; plan_code?: unknown; billing_cycle?: unknown } = {};
  try { body = await request.json(); } catch { return json(400, { error: "INVALID_REQUEST" }); }
  if (body.operation === "check") {
    const identitySecret = Deno.env.get("OWNER_TRIAL_CLAIM_SECRET")?.trim();
    if (!identitySecret || identitySecret.length < 32) {
      // Fail closed for paid trial eligibility without blocking Free onboarding.
      return json(200, { trial_consumed: true });
    }
    let identityHashes: string[];
    try {
      identityHashes = await ownerTrialIdentityHashes({
        userId: authData.user.id,
        email: authData.user.email,
        emailVerified: Boolean(authData.user.email_confirmed_at),
        phone: authData.user.phone,
        phoneVerified: Boolean(authData.user.phone_confirmed_at),
      }, identitySecret);
    } catch {
      return json(200, { trial_consumed: true });
    }
    const lookupHashes = [...new Set([...identityHashes, await legacyOwnerTrialUidHash(authData.user.id)])];
    const { data: claims, error: claimError } = await admin.from("owner_trial_claims")
      .select("identity_hash").in("identity_hash", lookupHashes);
    if (claimError) return json(503, { error: "TRIAL_ELIGIBILITY_LOOKUP_FAILED" });
    return json(200, { trial_consumed: claims.length > 0 || identityHashes.length === 0 });
  }
  if (body.operation !== "ensure") return json(400, { error: "INVALID_OPERATION" });
  const planCode = typeof body.plan_code === "string" ? body.plan_code.toUpperCase() : "";
  const billingCycle = typeof body.billing_cycle === "string" ? body.billing_cycle.toUpperCase() : "";
  if (!["FREE", "STARTER", "BUSINESS", "PRO"].includes(planCode) || !["MONTHLY", "ANNUAL"].includes(billingCycle)) return json(400, { error: "INVALID_PLAN" });

  const { data: existing, error: existingError } = await admin.from("subscriptions")
    .select("*, subscription_plans(code)").eq("organization_id", membership.organization_id).maybeSingle();
  if (existingError) return json(503, { error: "SUBSCRIPTION_LOOKUP_FAILED" });
  if (existing) {
    const plan = existing.subscription_plans as { code?: string } | null;
    return json(200, {
      business_id: membership.organization_id,
      plan_code: plan?.code ?? "STARTER",
      status: existing.status,
      billing_cycle: existing.billing_cycle,
      started_at: existing.started_at,
      current_period_start: existing.current_period_start,
      current_period_end: existing.current_period_end,
      trial_started_at: existing.trial_started_at,
      trial_ends_at: existing.trial_ends_at,
      grace_period_ends_at: existing.grace_period_ends_at,
      cancelled_at: existing.cancelled_at,
      expires_at: existing.expires_at,
      auto_renew: existing.auto_renew,
      cancel_at_period_end: existing.cancel_at_period_end,
      currency: existing.currency,
      created_at: Date.parse(existing.created_at),
    });
  }
  const isFree = planCode === "FREE";
  let identityHashes: string[] = [];
  if (!isFree) {
    const identitySecret = Deno.env.get("OWNER_TRIAL_CLAIM_SECRET")?.trim();
    if (!identitySecret || identitySecret.length < 32) return json(503, { error: "TRIAL_IDENTITY_NOT_CONFIGURED" });
    try {
      identityHashes = await ownerTrialIdentityHashes({
        userId: authData.user.id,
        email: authData.user.email,
        emailVerified: Boolean(authData.user.email_confirmed_at),
        phone: authData.user.phone,
        phoneVerified: Boolean(authData.user.phone_confirmed_at),
      }, identitySecret);
    } catch {
      return json(503, { error: "TRIAL_IDENTITY_NOT_CONFIGURED" });
    }
  }
  if (!isFree && identityHashes.length === 0) return json(422, { error: "TRIAL_VERIFIED_CONTACT_REQUIRED" });
  const { data: provisioning, error: provisionError } = await admin.rpc("provision_owner_trial", {
    p_organization_id: membership.organization_id,
    p_profile_id: authData.user.id,
    p_plan_code: planCode,
    p_billing_cycle: billingCycle,
    p_identity_hashes: isFree ? [] : identityHashes,
  });
  if (provisionError) return json(503, { error: "TRIAL_PROVISIONING_FAILED" });
  if (provisioning?.result === "TRIAL_ALREADY_CONSUMED") return json(409, { error: "TRIAL_ALREADY_CONSUMED" });
  if (provisioning?.result === "SUBSCRIPTION_EXISTS") {
    const { data: current, error: currentError } = await admin.from("subscriptions")
      .select("*, subscription_plans(code)").eq("organization_id", membership.organization_id).maybeSingle();
    if (currentError || !current) return json(503, { error: "SUBSCRIPTION_LOOKUP_FAILED" });
    const plan = current.subscription_plans as { code?: string } | null;
    return json(200, {
      business_id: membership.organization_id,
      plan_code: plan?.code ?? "FREE",
      status: current.status,
      billing_cycle: current.billing_cycle,
      started_at: current.started_at,
      current_period_start: current.current_period_start,
      current_period_end: current.current_period_end,
      trial_started_at: current.trial_started_at,
      trial_ends_at: current.trial_ends_at,
      grace_period_ends_at: current.grace_period_ends_at,
      cancelled_at: current.cancelled_at,
      expires_at: current.expires_at,
      auto_renew: current.auto_renew,
      cancel_at_period_end: current.cancel_at_period_end,
      currency: current.currency,
      created_at: Date.parse(current.created_at),
    });
  }
  if (provisioning?.result !== "PROVISIONED") return json(503, { error: "TRIAL_PROVISIONING_FAILED" });
  const { data: subscription, error: subscriptionError } = await admin.from("subscriptions")
    .select("*, subscription_plans(code)").eq("organization_id", membership.organization_id).single();
  if (subscriptionError || !subscription) return json(503, { error: "TRIAL_PROVISIONING_FAILED" });
  const now = Number(subscription.started_at);
  const trialEnds = subscription.trial_ends_at as number | null;
  return json(200, {
    business_id: membership.organization_id,
    plan_code: planCode,
    status: subscription.status,
    billing_cycle: subscription.billing_cycle,
    started_at: now,
    current_period_start: now,
    current_period_end: subscription.current_period_end,
    trial_started_at: subscription.trial_started_at,
    trial_ends_at: subscription.trial_ends_at,
    grace_period_ends_at: null,
    cancelled_at: null,
    expires_at: null,
    auto_renew: false,
    cancel_at_period_end: false,
    currency: "PHP",
    created_at: Date.parse(subscription.created_at),
  });
});
