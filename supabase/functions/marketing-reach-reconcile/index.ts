import { createClient } from "https://esm.sh/@supabase/supabase-js@2.117.2";
import {
  HostingerReachClient,
  type ReachContact,
  type ReachSubscriptionStatus,
} from "../_shared/hostinger-reach.ts";
import {
  collectReachPreflight,
  inspectReachContact,
} from "../_shared/reach-preflight.ts";
import { parseMarketingReconcileOptions } from "../_shared/marketing-reconcile-options.ts";

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

function readReachClient(): HostingerReachClient {
  return new HostingerReachClient(
    Deno.env.get("HOSTINGER_REACH_API_TOKEN") ?? "",
    Deno.env.get("HOSTINGER_REACH_PROFILE_UUID") ?? "",
  );
}

async function readBody(request: Request): Promise<unknown | null> {
  const rawBody = await request.text();
  if (rawBody.length > 4_096) return null;
  if (!rawBody.trim()) return {};
  try {
    return JSON.parse(rawBody);
  } catch {
    return null;
  }
}

function providerErrorCode(error: unknown): string {
  return error instanceof Error && "safeCode" in error
    ? String((error as { safeCode: unknown }).safeCode)
    : "provider_unavailable";
}

Deno.serve(async (request) => {
  const secret = Deno.env.get("MARKETING_DISPATCH_SECRET") ?? "";
  if (request.method !== "POST") {
    return Response.json({ error: "method_not_allowed" }, { status: 405 });
  }
  if (
    !secret ||
    !constantTimeEquals(
      request.headers.get("authorization") ?? "",
      `Bearer ${secret}`,
    )
  ) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }

  const body = await readBody(request);
  if (body === null) {
    return Response.json({ error: "invalid_request" }, { status: 400 });
  }
  const options = parseMarketingReconcileOptions(body);
  if (!options.valid) {
    return Response.json({ error: "invalid_reconcile_scope" }, { status: 400 });
  }

  if (options.value.mode === "diagnostic") {
    try {
      const report = await collectReachPreflight(
        readReachClient(),
        Deno.env.get("HOSTINGER_REACH_PROFILE_UUID") ?? "",
        Deno.env.get("HOSTINGER_REACH_DOUBLE_OPT_IN") === "true",
      );
      return Response.json(report);
    } catch (error) {
      const code = providerErrorCode(error);
      console.warn(JSON.stringify({
        event: "marketing_reach_diagnostic_failed",
        code,
      }));
      return Response.json({ error: "reach_diagnostic_unavailable", code }, {
        status: 503,
      });
    }
  }

  if (options.value.mode === "contact_status") {
    try {
      const report = await inspectReachContact(
        readReachClient(),
        options.value.email,
      );
      return Response.json(report);
    } catch (error) {
      const code = providerErrorCode(error);
      console.warn(JSON.stringify({
        event: "marketing_reach_contact_inspection_failed",
        code,
      }));
      return Response.json({
        error: "reach_contact_inspection_unavailable",
        code,
      }, {
        status: 503,
      });
    }
  }

  if (
    !supabaseUrl || !serviceRoleKey ||
    Deno.env.get("HOSTINGER_REACH_ENABLED") !== "true"
  ) {
    return Response.json({ error: "integration_unavailable" }, { status: 503 });
  }

  const client = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const reach = readReachClient();

  if (options.value.mode === "single_contact") {
    try {
      const contact = await reach.findContact(options.value.email);
      if (contact?.subscription_status !== "unsubscribed") {
        return Response.json({
          reconciled: 0,
          providerStatus: contact?.subscription_status ?? "not_found",
        });
      }
      const suppression: ReachContact = {
        uuid: contact.uuid,
        email: contact.email,
        subscription_status: "unsubscribed" satisfies ReachSubscriptionStatus,
      };
      const result = await client.rpc(
        "reconcile_marketing_reach_suppressions",
        { p_contacts: [suppression] },
      );
      if (result.error) throw new Error("marketing_suppression_store_failed");
      return Response.json({
        reconciled: Number(result.data) || 0,
        providerStatus: "unsubscribed",
      });
    } catch (error) {
      console.warn(JSON.stringify({
        event: "marketing_single_contact_reconciliation_failed",
        code: providerErrorCode(error),
      }));
      return Response.json({ error: "reconciliation_unavailable" }, {
        status: 503,
      });
    }
  }

  let reconciled = 0;
  try {
    // The official profile list endpoint caps a page at 100. Keep each run
    // below Reach's documented request ceiling and stop at the first short page.
    for (let page = 1; page <= 80; page++) {
      const contacts = await reach.listUnsubscribedContacts(page);
      const result = await client.rpc(
        "reconcile_marketing_reach_suppressions",
        { p_contacts: contacts },
      );
      if (result.error) throw new Error("marketing_suppression_store_failed");
      reconciled += Number(result.data) || 0;
      if (contacts.length < 100) break;
    }
  } catch (error) {
    console.warn(JSON.stringify({
      event: "marketing_reconciliation_failed",
      code: providerErrorCode(error),
    }));
    return Response.json({ error: "reconciliation_unavailable" }, {
      status: 503,
    });
  }
  return Response.json({ reconciled });
});
