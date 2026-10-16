import { createClient } from "https://esm.sh/@supabase/supabase-js@2.117.2";
import {
  type MarketingRpcClient,
  type MarketingSyncRow,
  processClaimedMarketingSubscriber,
} from "../_shared/marketing-sync.ts";
import { parseMarketingDispatchOptions } from "../_shared/marketing-dispatch-options.ts";

const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "") ?? "";
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";

function constantTimeEquals(left: string, right: string): boolean {
  const a = new TextEncoder().encode(left);
  const b = new TextEncoder().encode(right);
  let mismatch = a.length ^ b.length;
  for (let index = 0; index < Math.max(a.length, b.length); index++) {
    mismatch |= (a[index] ?? 0) ^ (b[index] ?? 0);
  }
  return mismatch === 0;
}

Deno.serve(async (request) => {
  const secret = Deno.env.get("MARKETING_DISPATCH_SECRET") ?? "";
  if (request.method !== "POST") {
    return Response.json({ error: "method_not_allowed" }, { status: 405 });
  }
  const authorization = request.headers.get("authorization") ?? "";
  if (!secret || !constantTimeEquals(authorization, `Bearer ${secret}`)) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }
  if (
    !supabaseUrl || !serviceRoleKey ||
    Deno.env.get("HOSTINGER_REACH_ENABLED") !== "true"
  ) {
    return Response.json({ error: "integration_unavailable" }, { status: 503 });
  }

  let requestBody: unknown = {};
  try {
    const rawBody = await request.text();
    if (rawBody.length > 4_096) {
      return Response.json({ error: "request_too_large" }, { status: 413 });
    }
    if (rawBody.trim()) {
      requestBody = JSON.parse(rawBody);
    }
  } catch {
    return Response.json({ error: "invalid_request" }, { status: 400 });
  }
  const options = parseMarketingDispatchOptions(requestBody);
  if (!options.valid) {
    return Response.json({ error: "invalid_dispatch_scope" }, { status: 400 });
  }

  const client = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const claim = await client.rpc("claim_marketing_reach_sync", {
    p_batch_size: options.value.batchSize,
    p_subscriber_id: options.value.subscriberId,
  });
  if (claim.error) {
    console.warn(JSON.stringify({ event: "marketing_dispatch_claim_failed" }));
    return Response.json({ error: "dispatch_unavailable" }, { status: 503 });
  }

  const counts = { accepted: 0, removed: 0, suppressed: 0, pending: 0 };
  for (const row of (claim.data ?? []) as MarketingSyncRow[]) {
    const result = await processClaimedMarketingSubscriber(
      client as unknown as MarketingRpcClient,
      row,
      {
        enabled: true,
        apiToken: Deno.env.get("HOSTINGER_REACH_API_TOKEN") ?? "",
        profileUuid: Deno.env.get("HOSTINGER_REACH_PROFILE_UUID") ?? "",
        doubleOptIn: Deno.env.get("HOSTINGER_REACH_DOUBLE_OPT_IN") === "true",
      },
    );
    if (result in counts) counts[result as keyof typeof counts] += 1;
  }
  return Response.json({
    processed: (claim.data ?? []).length,
    scope: options.value.subscriberId ? "single_subscriber" : "queue",
    ...counts,
  });
});
