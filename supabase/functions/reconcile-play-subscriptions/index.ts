import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { acknowledgePlaySubscription, sha256Hex, verifyPlaySubscription } from "../_shared/play-billing.ts";
import { GoogleOidcVerificationError, verifyGoogleOidcBearer } from "../_shared/google-oidc.ts";
import { decryptPlayPurchaseToken, encryptPlayPurchaseToken } from "../_shared/play-token-crypto.ts";

type TokenRow = {
  token_hash: string;
  organization_id: string;
  product_id: string;
  base_plan_id: string;
  encrypted_purchase_token: string;
};

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

Deno.serve(async (request) => {
  if (request.method !== "POST") return json(405, { error: "METHOD_NOT_ALLOWED" });
  try {
    await verifyGoogleOidcBearer(
      request,
      Deno.env.get("PLAY_RECONCILIATION_PUSH_AUDIENCE"),
      Deno.env.get("PLAY_RECONCILIATION_PUSH_SERVICE_ACCOUNT"),
    );
  } catch (error) {
    if (error instanceof GoogleOidcVerificationError && error.reason === "NOT_CONFIGURED") {
      return json(503, { error: "RECONCILIATION_AUTH_NOT_CONFIGURED" });
    }
    if (error instanceof GoogleOidcVerificationError && error.reason === "UNAVAILABLE") {
      return json(503, { error: "RECONCILIATION_AUTH_UNAVAILABLE" });
    }
    return json(401, { error: "UNAUTHORIZED" });
  }

  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.trim();
  if (!serviceKey || !supabaseUrl) return json(503, { error: "RECONCILIATION_SERVICE_UNAVAILABLE" });

  const admin = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
  let scanned = 0;
  let applied = 0;
  let failed = 0;
  let skipped = 0;
  let touchFailed = false;
  const pageSize = 100;
  const maxPerRun = 100;

  for (let offset = 0; offset < maxPerRun; offset += pageSize) {
    const { data, error } = await admin.from("google_play_subscription_tokens")
      .select("token_hash, organization_id, product_id, base_plan_id, encrypted_purchase_token")
      .not("organization_id", "is", null)
      .is("superseded_at", null)
      .not("encrypted_purchase_token", "is", null)
      .order("updated_at", { ascending: true })
      .order("token_hash", { ascending: true })
      .range(offset, offset + pageSize - 1);
    if (error) return json(503, { error: "RECONCILIATION_LOOKUP_FAILED" });
    const rows = (data ?? []) as TokenRow[];
    scanned += rows.length;
    for (const row of rows) {
      let purchaseToken: string;
      try {
        purchaseToken = await decryptPlayPurchaseToken(row.encrypted_purchase_token);
        if (await sha256Hex(purchaseToken) !== row.token_hash) throw new Error("TOKEN_HASH_MISMATCH");
      } catch {
        failed++;
        const { error: touchError } = await admin.from("google_play_subscription_tokens")
          .update({ updated_at: new Date().toISOString() })
          .eq("token_hash", row.token_hash)
          .eq("organization_id", row.organization_id)
          .is("superseded_at", null);
        if (touchError) touchFailed = true;
        continue;
      }
      try {
        const verificationStartedAt = Date.now();
        const verified = await verifyPlaySubscription(row.product_id, purchaseToken);
        if (verified.status === "PENDING" || verified.status === "PENDING_PURCHASE_CANCELED") {
          skipped++;
          continue;
        }
        const encryptedToken = await encryptPlayPurchaseToken(purchaseToken);
        const linkedTokenHash = verified.linkedPurchaseToken
          ? await sha256Hex(verified.linkedPurchaseToken)
          : null;
        const { data: result, error: applyError } = await admin.rpc("apply_verified_play_subscription", {
          p_organization_id: row.organization_id,
          p_actor_user_id: null,
          p_token_hash: row.token_hash,
          p_encrypted_purchase_token: encryptedToken,
          p_product_id: row.product_id,
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
          p_source: "RECONCILIATION",
        });
        if (applyError || result?.result !== "APPLIED") {
          skipped++;
          continue;
        }
        if (!verified.acknowledged) await acknowledgePlaySubscription(row.product_id, purchaseToken);
        applied++;
      } catch {
        // Do not log provider response bodies, tokens, or decrypted values.
        failed++;
      } finally {
        // Move every attempted token behind the remaining page, even when it
        // cannot be decrypted or Play is unavailable. Otherwise 100 poisoned
        // oldest rows can starve every healthy renewal indefinitely.
        const { error: touchError } = await admin.from("google_play_subscription_tokens")
          .update({ updated_at: new Date().toISOString() })
          .eq("token_hash", row.token_hash)
          .eq("organization_id", row.organization_id)
          .is("superseded_at", null);
        if (touchError) touchFailed = true;
      }
    }
    if (rows.length < pageSize) break;
  }
  if (touchFailed) return json(503, { error: "RECONCILIATION_CURSOR_UPDATE_FAILED" });
  return json(200, { scanned, applied, skipped, failed, partial: scanned === maxPerRun });
});
