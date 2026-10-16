import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import {
  acknowledgePlaySubscription,
  sha256Hex,
  verifyPlaySubscription,
} from "../_shared/play-billing.ts";
import {
  playApplyResultCategory,
  type PlayPurchaseRejectionCategory,
  playPurchaseRejectionResponse,
} from "../_shared/play-purchase-rejection.ts";
import {
  canReplaceBoundPlayToken,
  decidePurchaseOrganizationClaim,
} from "../_shared/play-token-claim.ts";
import { encryptPlayPurchaseToken } from "../_shared/play-token-crypto.ts";

const corsHeaders = {
  "access-control-allow-headers": "authorization, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-origin": "*",
  "content-type": "application/json",
};
const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: corsHeaders });
const rejectPurchase = (category: PlayPurchaseRejectionCategory) =>
  playPurchaseRejectionResponse(category, corsHeaders);
const organizationIdPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function bearer(request: Request): string | null {
  const value = request.headers.get("authorization") ?? "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : null;
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }
  if (request.method !== "POST") {
    return json(405, { error: "METHOD_NOT_ALLOWED" });
  }
  const accessToken = bearer(request);
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.trim();
  if (!accessToken) return rejectPurchase("AUTH_MISSING");
  if (!serviceKey || !supabaseUrl) {
    return json(503, { error: "BILLING_SERVICE_UNAVAILABLE" });
  }
  const admin = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: authData, error: authError } = await admin.auth.getUser(
    accessToken,
  );
  if (authError || !authData.user) return rejectPurchase("AUTH_INVALID");

  let productId = "";
  let purchaseToken = "";
  let requestedOrganizationId: string | null = null;
  let operation: "verify" | "preflight_replacement" = "verify";
  try {
    const body = await request.json() as {
      product_id?: unknown;
      purchase_token?: unknown;
      organization_id?: unknown;
      operation?: unknown;
    };
    if (body.operation !== undefined) {
      if (body.operation !== "preflight_replacement") {
        return json(400, { error: "INVALID_REQUEST" });
      }
      operation = "preflight_replacement";
    }
    productId = typeof body.product_id === "string"
      ? body.product_id.trim()
      : "";
    purchaseToken = typeof body.purchase_token === "string"
      ? body.purchase_token.trim()
      : "";
    if (body.organization_id !== undefined) {
      if (
        typeof body.organization_id !== "string" ||
        !organizationIdPattern.test(body.organization_id)
      ) {
        return json(400, { error: "INVALID_REQUEST" });
      }
      requestedOrganizationId = body.organization_id.toLowerCase();
    }
  } catch {
    return json(400, { error: "INVALID_REQUEST" });
  }

  if (operation === "preflight_replacement") {
    if (
      !requestedOrganizationId || purchaseToken.length < 20 ||
      purchaseToken.length > 4096
    ) {
      return json(400, { error: "INVALID_REQUEST" });
    }
    const { data: membership, error: membershipError } = await admin.from(
      "organization_members",
    )
      .select("organization_id")
      .eq("profile_id", authData.user.id).eq("role", "OWNER").eq(
        "is_active",
        true,
      )
      .eq("organization_id", requestedOrganizationId).maybeSingle();
    if (membershipError) return json(503, { error: "OWNER_LOOKUP_FAILED" });
    if (!membership) return json(403, { error: "OWNER_REQUIRED" });

    const oldTokenHash = await sha256Hex(purchaseToken);
    const { data: binding, error: bindingError } = await admin.from(
      "google_play_subscription_tokens",
    )
      .select("organization_id, superseded_at")
      .eq("token_hash", oldTokenHash).maybeSingle();
    if (bindingError) {
      return json(503, { error: "PURCHASE_BINDING_LOOKUP_FAILED" });
    }
    if (
      !binding || binding.organization_id !== requestedOrganizationId ||
      binding.superseded_at
    ) {
      return rejectPurchase("REPLACEMENT_NOT_ALLOWED");
    }
    const { data: currentSubscription, error: subscriptionError } = await admin
      .from("subscriptions")
      .select("id")
      .eq("organization_id", requestedOrganizationId)
      .eq("provider", "GOOGLE_PLAY")
      .eq("external_subscription_id", oldTokenHash)
      .is("deleted_at", null).maybeSingle();
    if (subscriptionError) {
      return json(503, { error: "PURCHASE_BINDING_LOOKUP_FAILED" });
    }
    if (
      !canReplaceBoundPlayToken(
        requestedOrganizationId,
        true,
        binding.organization_id,
        binding.superseded_at,
        Boolean(currentSubscription),
      )
    ) return rejectPurchase("REPLACEMENT_NOT_ALLOWED");
    return json(200, {
      allowed: true,
      organization_id: requestedOrganizationId,
    });
  }

  let verified;
  const verificationStartedAt = Date.now();
  try {
    verified = await verifyPlaySubscription(productId, purchaseToken);
  } catch (error) {
    if (
      error instanceof Error && error.message === "PLAY_BILLING_NOT_CONFIGURED"
    ) return json(503, { error: "PLAY_BILLING_NOT_CONFIGURED" });
    if (error instanceof Error && error.message === "INVALID_PURCHASE") {
      return json(400, { error: "INVALID_PURCHASE" });
    }
    if (error instanceof Error && error.message === "PURCHASE_NOT_ALLOWED") {
      return json(422, { error: "PURCHASE_NOT_ALLOWED" });
    }
    if (error instanceof Error && error.message === "PLAY_PURCHASE_PENDING") {
      return json(202, { error: "PAYMENT_PENDING" });
    }
    if (
      error instanceof Error &&
      error.message === "PLAY_PENDING_PURCHASE_CANCELED"
    ) return rejectPurchase("PENDING_CANCELED");
    console.error("Google Play verification failed", {
      category: "PROVIDER_OR_TRANSPORT",
      status: 502,
    });
    return json(502, { error: "PLAY_VERIFICATION_UNAVAILABLE" });
  }
  if (verified.status === "PENDING") {
    return json(202, { error: "PAYMENT_PENDING" });
  }
  if (verified.status === "PENDING_PURCHASE_CANCELED") {
    return rejectPurchase("PENDING_CANCELED");
  }

  const tokenHash = await sha256Hex(purchaseToken);
  const predecessorToken = verified.linkedPurchaseToken ??
    verified.outOfAppExpiredPurchaseToken;
  const linkedTokenHash = predecessorToken
    ? await sha256Hex(predecessorToken)
    : null;
  const { data: existingToken, error: tokenReadError } = await admin.from(
    "google_play_subscription_tokens",
  )
    .select("organization_id, superseded_at").eq("token_hash", tokenHash)
    .maybeSingle();
  if (tokenReadError) {
    return json(503, { error: "PURCHASE_BINDING_LOOKUP_FAILED" });
  }
  if (existingToken?.superseded_at) return rejectPurchase("TOKEN_SUPERSEDED");
  const { data: linkedToken, error: linkedTokenReadError } = linkedTokenHash
    ? await admin.from("google_play_subscription_tokens")
      .select("organization_id, superseded_at").eq("token_hash", linkedTokenHash).maybeSingle()
    : { data: null, error: null };
  if (linkedTokenReadError) {
    return json(503, { error: "PURCHASE_BINDING_LOOKUP_FAILED" });
  }
  if (
    verified.outOfAppExpiredPurchaseToken && !existingToken?.organization_id &&
    (!linkedToken?.organization_id || linkedToken.superseded_at)
  ) return rejectPurchase("STALE_LINKED_TOKEN");

  const selectedOrganizationId = existingToken?.organization_id ??
    linkedToken?.organization_id ??
    (verified.obfuscatedExternalProfileId ? requestedOrganizationId : null);
  const ownerOrganizationIds: string[] = [];
  if (selectedOrganizationId) {
    const { data: membership, error } = await admin.from("organization_members")
      .select("organization_id")
      .eq("profile_id", authData.user.id).eq("role", "OWNER").eq(
        "is_active",
        true,
      )
      .eq("organization_id", selectedOrganizationId).maybeSingle();
    if (error) return json(503, { error: "OWNER_LOOKUP_FAILED" });
    if (membership) ownerOrganizationIds.push(membership.organization_id);
  } else {
    // Restore after process death has no local checkout context. Match Play's
    // profile hash against all active owner organizations, with stable paging.
    const pageSize = verified.obfuscatedExternalProfileId ? 500 : 2;
    for (let offset = 0;; offset += pageSize) {
      const { data: memberships, error } = await admin.from(
        "organization_members",
      )
        .select("organization_id")
        .eq("profile_id", authData.user.id).eq("role", "OWNER").eq(
          "is_active",
          true,
        )
        .order("organization_id", { ascending: true })
        .range(offset, offset + pageSize - 1);
      if (error) return json(503, { error: "OWNER_LOOKUP_FAILED" });
      ownerOrganizationIds.push(
        ...(memberships ?? []).map((membership) => membership.organization_id),
      );
      if (
        (memberships?.length ?? 0) < pageSize ||
        !verified.obfuscatedExternalProfileId
      ) break;
    }
  }
  const claimDecision = await decidePurchaseOrganizationClaim({
    ownerUid: authData.user.id,
    ownerOrganizationIds,
    existingOrganizationId: existingToken?.organization_id,
    linkedOrganizationId: linkedToken?.organization_id,
    requestedOrganizationId,
    purchaseObfuscatedAccountId: verified.obfuscatedExternalAccountId,
    purchaseObfuscatedProfileId: verified.obfuscatedExternalProfileId,
    verifiedOutOfAppPredecessor: Boolean(verified.outOfAppExpiredPurchaseToken),
  });
  if (claimDecision.result === "OWNER_REQUIRED") {
    return json(403, { error: "OWNER_REQUIRED" });
  }
  if (claimDecision.result === "CONFLICT") {
    return rejectPurchase("TOKEN_BOUND_ELSEWHERE");
  }
  if (claimDecision.result === "ACCOUNT_MISMATCH") {
    return rejectPurchase("ACCOUNT_MISMATCH");
  }
  if (claimDecision.result === "ORGANIZATION_MISMATCH") {
    return rejectPurchase("ORGANIZATION_MISMATCH");
  }
  if (claimDecision.result === "ORGANIZATION_REQUIRED") {
    return rejectPurchase("ORGANIZATION_REQUIRED");
  }
  const organizationId = claimDecision.organizationId;

  let encryptedToken: string;
  try {
    encryptedToken = await encryptPlayPurchaseToken(purchaseToken);
  } catch (error) {
    if (
      error instanceof Error &&
      error.message.startsWith("PLAY_TOKEN_ENCRYPTION")
    ) {
      return json(503, { error: "PLAY_TOKEN_ENCRYPTION_NOT_CONFIGURED" });
    }
    return json(400, { error: "INVALID_PURCHASE" });
  }

  const verifiedAt = Date.now();
  const { data: applied, error: applyError } = await admin.rpc(
    "apply_verified_play_subscription",
    {
      p_organization_id: organizationId,
      p_actor_user_id: authData.user.id,
      p_token_hash: tokenHash,
      p_encrypted_purchase_token: encryptedToken,
      p_product_id: productId,
      p_base_plan_id: verified.basePlanId,
      p_plan_code: verified.planCode,
      p_billing_cycle: verified.billingCycle,
      p_status: verified.status,
      p_current_period_start: verified.currentPeriodStart,
      p_current_period_end: verified.currentPeriodEnd,
      p_grace_period_ends_at: verified.gracePeriodEndsAt,
      p_auto_renew: verified.autoRenew,
      p_cancel_at_period_end: verified.cancelAtPeriodEnd,
      p_provider_event_time: verificationStartedAt,
      p_provider_event_version: null,
      p_linked_token_hash: linkedTokenHash,
      p_source: verified.outOfAppExpiredPurchaseToken
        ? "OUT_OF_APP_PURCHASE"
        : "PURCHASE",
    },
  );
  if (applyError) {
    if (
      applyError.code === "23505" ||
      applyError.message.includes("PLAY_TOKEN") ||
      applyError.message.includes("PLAY_LINKED_TOKEN")
    ) {
      return rejectPurchase("BINDING_CONFLICT");
    }
    console.error("Verified purchase could not be committed", {
      category: "PERSISTENCE",
      status: 503,
    });
    return json(503, { error: "SUBSCRIPTION_PERSIST_FAILED" });
  }
  if (applied?.result !== "APPLIED") {
    return rejectPurchase(playApplyResultCategory(applied?.result));
  }

  // Acknowledge only after the verified entitlement and ownership binding
  // have committed; retries can safely reapply the same token.
  if (!verified.acknowledged) {
    try {
      await acknowledgePlaySubscription(productId, purchaseToken);
    } catch (error) {
      const providerStatus = error instanceof Error
        ? /^PLAY_PROVIDER_(\d{3})$/.exec(error.message)?.[1]
        : undefined;
      const category = providerStatus
        ? "PROVIDER_HTTP"
        : error instanceof Error && error.message === "PLAY_BILLING_AUTH_FAILED"
        ? "AUTH"
        : error instanceof Error &&
            error.message === "PLAY_BILLING_NOT_CONFIGURED"
        ? "CONFIGURATION"
        : "UNEXPECTED";
      console.error("Google Play acknowledgement failed", {
        category,
        ...(providerStatus ? { status: Number(providerStatus) } : {}),
      });
      return json(502, { error: "PURCHASE_ACKNOWLEDGEMENT_PENDING" });
    }
  }
  return json(200, {
    organization_id: organizationId,
    plan_code: verified.planCode,
    billing_cycle: verified.billingCycle,
    status: verified.status,
    current_period_end: verified.currentPeriodEnd,
    verified_at: verifiedAt,
  });
});
