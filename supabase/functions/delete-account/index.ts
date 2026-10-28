import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

function bearer(request: Request): string | null {
  const value = request.headers.get("authorization") ?? "";
  return value.startsWith("Bearer ") ? value.slice(7).trim() : null;
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json(405, { error: "METHOD_NOT_ALLOWED" });

  const accessToken = bearer(request);
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")?.trim();
  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.trim();
  if (!accessToken) return json(401, { error: "UNAUTHORIZED" });
  if (!serviceKey || !supabaseUrl) return json(503, { error: "ACCOUNT_SERVICE_UNAVAILABLE" });

  const admin = createClient(supabaseUrl, serviceKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: authData, error: authError } = await admin.auth.getUser(accessToken);
  const user = authData.user;
  if (authError || !user) return json(401, { error: "UNAUTHORIZED" });

  // Keep marketing consent aligned with account deletion. Only withdraw an
  // existing active Owner subscription; set_my_marketing_preference(false)
  // queues provider removal without creating a new suppression row for users
  // who never subscribed.
  const { data: profile, error: profileError } = await admin.from("profiles")
    .select("role").eq("id", user.id).maybeSingle();
  if (profileError) return json(503, { error: "ACCOUNT_PROFILE_LOOKUP_FAILED" });
  if (profile?.role === "OWNER") {
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY")?.trim();
    if (!anonKey) return json(503, { error: "ACCOUNT_SERVICE_UNAVAILABLE" });
    const userClient = createClient(supabaseUrl, anonKey, {
      global: { headers: { Authorization: `Bearer ${accessToken}` } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: preferences, error: preferenceError } = await userClient.rpc(
      "get_my_marketing_preference",
    );
    if (preferenceError) return json(503, { error: "ACCOUNT_MARKETING_LOOKUP_FAILED" });
    const preference = Array.isArray(preferences) ? preferences[0] : preferences;
    if (preference?.subscribed === true) {
      const { error: withdrawalError } = await userClient.rpc(
        "set_my_marketing_preference",
        { p_subscribed: false },
      );
      if (withdrawalError) return json(503, { error: "ACCOUNT_MARKETING_CLEANUP_FAILED" });
    }
  }

  let operation = "delete";
  try {
    const body = await request.json() as { operation?: unknown };
    if (typeof body.operation === "string") operation = body.operation;
  } catch {
    // The operation defaults to delete for backwards-compatible callers.
  }
  if (operation !== "delete") return json(400, { error: "UNSUPPORTED_OPERATION" });

  const now = Date.now();
  const { error: orderError } = await admin.from("orders").update({
    customer_uid: null,
    customer_name: "Deleted User",
    customer_phone: "",
    delivery_address: "Address removed",
    delivery_place_id: null,
    delivery_latitude: null,
    delivery_longitude: null,
    customer_note: null,
    updated_at: new Date().toISOString(),
    client_updated_at: now,
  }).eq("customer_uid", user.id);
  if (orderError) return json(503, { error: "ACCOUNT_DATA_UPDATE_FAILED" });

  const { error: requestError } = await admin.from("customer_order_requests")
    .delete().eq("customer_uid", user.id);
  if (requestError) return json(503, { error: "ACCOUNT_REQUEST_CLEANUP_FAILED" });

  const { error: auditError } = await admin.from("audit_events")
    .update({ actor_uid: null, payload: { account_deleted_at: now } })
    .eq("actor_uid", user.id);
  if (auditError) return json(503, { error: "ACCOUNT_AUDIT_CLEANUP_FAILED" });

  const { error: organizationError } = await admin.from("organizations")
    .update({ owner_profile_id: null, updated_at: new Date().toISOString() })
    .eq("owner_profile_id", user.id);
  if (organizationError) return json(503, { error: "ACCOUNT_WORKSPACE_CLEANUP_FAILED" });

  const { error: deleteError } = await admin.auth.admin.deleteUser(user.id);
  if (deleteError) return json(503, { error: "ACCOUNT_DELETE_FAILED" });

  return json(200, { deleted: true });
});
