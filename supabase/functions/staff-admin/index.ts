import { createClient, type SupabaseClient, type User } from "https://esm.sh/@supabase/supabase-js@2";

const corsHeaders = {
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "POST, OPTIONS",
  "access-control-allow-origin": "*",
  "content-type": "application/json",
};

type StaffRole = "STAFF" | "MANAGER" | "RIDER";
type Operation = "create" | "update" | "delete";

type StaffRequest = {
  operation?: Operation;
  uid?: string;
  name?: string;
  phone?: string;
  role?: StaffRole;
  station_ids?: string[];
};

type CallerMembership = {
  id: string;
  organization_id: string;
  profile_id: string;
  role: "OWNER" | "MANAGER";
  is_active: boolean;
};

function response(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), { status, headers: corsHeaders });
}

function errorResponse(status: number, code: string): Response {
  return response(status, { error: code });
}

function requestId(): string {
  return crypto.randomUUID();
}

function normalizePhone(value: string): string {
  const digits = value.replace(/\D/g, "");
  if (digits.startsWith("63")) return `+${digits}`;
  if (digits.startsWith("0")) return `+63${digits.slice(1)}`;
  return `+${digits}`;
}

function isRole(value: unknown): value is StaffRole {
  return value === "STAFF" || value === "MANAGER" || value === "RIDER";
}

function safeRequest(raw: unknown): StaffRequest | null {
  if (!raw || typeof raw !== "object") return null;
  const value = raw as Record<string, unknown>;
  const operation = value.operation;
  if (operation !== "create" && operation !== "update" && operation !== "delete") return null;
  return {
    operation,
    uid: typeof value.uid === "string" ? value.uid : undefined,
    name: typeof value.name === "string" ? value.name.trim() : undefined,
    phone: typeof value.phone === "string" ? value.phone.trim() : undefined,
    role: isRole(value.role) ? value.role : undefined,
    station_ids: Array.isArray(value.station_ids) && value.station_ids.every((id) => typeof id === "string")
      ? value.station_ids as string[]
      : undefined,
  };
}

async function callerMembership(admin: SupabaseClient, uid: string): Promise<CallerMembership | null> {
  const { data, error } = await admin
    .from("organization_members")
    .select("id, organization_id, profile_id, role, is_active")
    .eq("profile_id", uid)
    .eq("is_active", true)
    .in("role", ["OWNER", "MANAGER"])
    .order("created_at", { ascending: true })
    .limit(1)
    .maybeSingle();
  if (error || !data) return null;
  return data as CallerMembership;
}

async function accessibleStations(admin: SupabaseClient, caller: CallerMembership): Promise<Set<string>> {
  if (caller.role === "OWNER") {
    const { data } = await admin.from("stations").select("id").eq("organization_id", caller.organization_id).eq("is_active", true);
    return new Set((data ?? []).map((row) => row.id as string));
  }
  const { data } = await admin
    .from("member_stations")
    .select("station_id, organization_members!inner(organization_id, profile_id)")
    .eq("organization_members.profile_id", caller.profile_id)
    .eq("organization_members.organization_id", caller.organization_id);
  return new Set((data ?? []).map((row) => row.station_id as string));
}

async function validateStations(admin: SupabaseClient, caller: CallerMembership, stationIds: string[]): Promise<Response | null> {
  if (stationIds.length === 0) return errorResponse(400, "STATION_REQUIRED");
  const unique = [...new Set(stationIds)];
  const { data, error } = await admin.from("stations").select("id, organization_id, is_active").in("id", unique);
  if (error || !data || data.length !== unique.length || data.some((row) => row.organization_id !== caller.organization_id || !row.is_active)) {
    return errorResponse(400, "INVALID_STATION");
  }
  const accessible = await accessibleStations(admin, caller);
  if (caller.role === "MANAGER" && data.some((row) => !accessible.has(row.id as string))) {
    return errorResponse(403, "STATION_NOT_AUTHORIZED");
  }
  return null;
}

async function ensureCapacity(admin: SupabaseClient, organizationId: string): Promise<Response | null> {
  const { data: subscription, error: subscriptionError } = await admin
    .from("subscriptions")
    .select("plan_id, status, current_period_end, trial_ends_at, grace_period_ends_at, expires_at")
    .eq("organization_id", organizationId)
    .is("deleted_at", null)
    .order("updated_at", { ascending: false })
    .limit(1)
    .maybeSingle();
  if (subscriptionError) return errorResponse(503, "STAFF_LIMIT_CHECK_FAILED");
  const now = Date.now();
  const expiry = subscription?.expires_at == null ? null : Number(subscription.expires_at);
  const periodEnd = subscription?.current_period_end == null ? null : Number(subscription.current_period_end);
  const trialEnd = subscription?.trial_ends_at == null ? periodEnd : Number(subscription.trial_ends_at);
  const graceEnd = subscription?.grace_period_ends_at == null ? null : Number(subscription.grace_period_ends_at);
  const notExplicitlyExpired = expiry == null || expiry > now;
  const entitled = !!subscription && notExplicitlyExpired && (
    (subscription.status === "TRIALING" && trialEnd != null && trialEnd > now) ||
    (["ACTIVE", "ACTIVE_UNTIL_PERIOD_END"].includes(subscription.status) && periodEnd != null && periodEnd > now) ||
    (subscription.status === "GRACE_PERIOD" && graceEnd != null && graceEnd > now)
  );
  if (!entitled) {
    return errorResponse(403, "STAFF_PLAN_REQUIRED");
  }
  const [{ data: feature, error: featureError }, { data: limit, error: limitError }] = await Promise.all([
    admin.from("plan_features")
      .select("enabled")
      .eq("plan_id", subscription.plan_id)
      .eq("feature", "STAFF_ACCOUNTS")
      .maybeSingle(),
    admin.from("plan_limits")
      .select("max_value")
      .eq("plan_id", subscription.plan_id)
      .eq("limit_type", "STAFF_COUNT")
      .maybeSingle(),
  ]);
  if (featureError || limitError) return errorResponse(503, "STAFF_LIMIT_CHECK_FAILED");
  if (!feature?.enabled) return errorResponse(403, "STAFF_PLAN_REQUIRED");
  if (!limit) return errorResponse(503, "STAFF_LIMIT_UNCONFIGURED");

  const { count, error: countError } = await admin
    .from("organization_members")
    .select("id", { count: "exact", head: true })
    .eq("organization_id", organizationId)
    .eq("is_active", true)
    .neq("role", "OWNER");
  if (countError || count == null) return errorResponse(503, "STAFF_LIMIT_CHECK_FAILED");
  if (count >= Number(limit.max_value)) return errorResponse(409, "STAFF_LIMIT_REACHED");
  return null;
}

async function targetMembership(admin: SupabaseClient, caller: CallerMembership, uid: string) {
  const { data } = await admin
    .from("organization_members")
    .select("id, organization_id, profile_id, role, is_active, profiles(display_name, phone, email)")
    .eq("organization_id", caller.organization_id)
    .eq("profile_id", uid)
    .maybeSingle();
  return data;
}

function canManageTarget(caller: CallerMembership, targetRole: string): boolean {
  if (targetRole === "OWNER") return false;
  return caller.role === "OWNER" || targetRole !== "MANAGER";
}

function staffResult(user: User, role: string, active: boolean, stationIds: string[], deleted = false) {
  return {
    uid: user.id,
    name: user.user_metadata?.display_name ?? "",
    phone: user.phone ?? "",
    role,
    isActive: active,
    stationIds,
    deleted,
  };
}

async function createStaff(admin: SupabaseClient, caller: CallerMembership, input: StaffRequest): Promise<Response> {
  if (!input.name || input.name.length < 2 || input.name.length > 120 || !input.phone || !isRole(input.role)) {
    return errorResponse(400, "INVALID_STAFF_INPUT");
  }
  if (caller.role === "MANAGER" && input.role === "MANAGER") return errorResponse(403, "ROLE_NOT_AUTHORIZED");
  const stationIds = [...new Set(input.station_ids ?? [])];
  const stationError = await validateStations(admin, caller, stationIds);
  if (stationError) return stationError;
  const capacityError = await ensureCapacity(admin, caller.organization_id);
  if (capacityError) return capacityError;

  const normalizedPhone = normalizePhone(input.phone);
  const { data: existingProfile } = await admin.from("profiles").select("id").eq("phone", normalizedPhone).maybeSingle();
  if (existingProfile) return errorResponse(409, "STAFF_ALREADY_EXISTS");

  const { data: created, error: authError } = await admin.auth.admin.createUser({
    phone: normalizedPhone,
    // Staff accounts are passwordless. The staff member verifies this phone
    // with the normal signInWithOtp/verifyOtp flow on their own device.
    phone_confirm: false,
    user_metadata: { display_name: input.name },
  });
  if (authError || !created.user) return errorResponse(409, "STAFF_ALREADY_EXISTS");

  const user = created.user;
  try {
    const { error: profileError } = await admin.from("profiles").upsert({
      id: user.id,
      display_name: input.name,
      phone: normalizedPhone,
      role: input.role,
      setup_complete: true,
    }, { onConflict: "id" });
    if (profileError) throw profileError;
    const { data: member, error: memberError } = await admin.from("organization_members").insert({
      organization_id: caller.organization_id,
      profile_id: user.id,
      role: input.role,
      is_active: true,
    }).select("id").single();
    if (memberError || !member) throw memberError ?? new Error("membership_failed");
    const { error: assignmentError } = await admin.from("member_stations").insert(stationIds.map((stationId) => ({ member_id: member.id, station_id: stationId })));
    if (assignmentError) throw assignmentError;
    return response(200, staffResult(user, input.role, true, stationIds));
  } catch {
    await admin.auth.admin.deleteUser(user.id);
    return errorResponse(500, "STAFF_PROVISIONING_FAILED");
  }
}

async function updateStaff(admin: SupabaseClient, caller: CallerMembership, input: StaffRequest): Promise<Response> {
  if (!input.uid || !input.name || !input.phone || !isRole(input.role)) return errorResponse(400, "INVALID_STAFF_INPUT");
  if (input.uid === caller.profile_id) return errorResponse(403, "SELF_UPDATE_NOT_ALLOWED");
  const target = await targetMembership(admin, caller, input.uid);
  if (!target || !canManageTarget(caller, target.role as string)) return errorResponse(404, "STAFF_NOT_FOUND");
  if (caller.role === "MANAGER" && input.role === "MANAGER") return errorResponse(403, "ROLE_NOT_AUTHORIZED");
  const stationIds = [...new Set(input.station_ids ?? [])];
  const stationError = await validateStations(admin, caller, stationIds);
  if (stationError) return stationError;
  const { data: oldUser } = await admin.auth.admin.getUserById(input.uid);
  const oldPhone = oldUser.user?.phone;
  const newPhone = normalizePhone(input.phone);
  const { data: updated, error: authError } = await admin.auth.admin.updateUserById(input.uid, {
    phone: newPhone,
    phone_confirm: true,
    user_metadata: { ...(oldUser.user?.user_metadata ?? {}), display_name: input.name },
  });
  if (authError || !updated.user) return errorResponse(409, "STAFF_UPDATE_FAILED");
  try {
    const { error: profileError } = await admin.from("profiles").update({ display_name: input.name, phone: newPhone, role: input.role }).eq("id", input.uid);
    if (profileError) throw profileError;
    const { error: memberError } = await admin.from("organization_members").update({ role: input.role, is_active: true }).eq("id", target.id);
    if (memberError) throw memberError;
    const { error: clearError } = await admin.from("member_stations").delete().eq("member_id", target.id);
    if (clearError) throw clearError;
    const { error: assignmentError } = await admin.from("member_stations").insert(stationIds.map((stationId) => ({ member_id: target.id, station_id: stationId })));
    if (assignmentError) throw assignmentError;
    return response(200, staffResult(updated.user, input.role, true, stationIds));
  } catch {
    if (oldPhone) await admin.auth.admin.updateUserById(input.uid, { phone: oldPhone, phone_confirm: true });
    return errorResponse(500, "STAFF_UPDATE_FAILED");
  }
}

async function deleteStaff(admin: SupabaseClient, caller: CallerMembership, input: StaffRequest): Promise<Response> {
  if (!input.uid || input.uid === caller.profile_id) return errorResponse(403, "SELF_DELETE_NOT_ALLOWED");
  const target = await targetMembership(admin, caller, input.uid);
  if (!target || !canManageTarget(caller, target.role as string)) return errorResponse(404, "STAFF_NOT_FOUND");
  const { data: user } = await admin.auth.admin.getUserById(input.uid);
  if (!user.user) return errorResponse(404, "STAFF_NOT_FOUND");
  const { error: memberError } = await admin.from("organization_members").update({ is_active: false }).eq("id", target.id);
  if (memberError) return errorResponse(500, "STAFF_DEACTIVATION_FAILED");
  await admin.from("member_stations").delete().eq("member_id", target.id);
  return response(200, staffResult(user.user, target.role as string, false, [], true));
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return errorResponse(405, "METHOD_NOT_ALLOWED");
  const id = requestId();
  const authorization = request.headers.get("authorization") ?? "";
  const match = authorization.match(/^Bearer\s+([^\s]+)$/i);
  if (!match) return errorResponse(401, "UNAUTHENTICATED");

  const url = Deno.env.get("SUPABASE_URL");
  const serviceRole = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!url || !serviceRole) {
    console.error("staff_admin_not_configured", { id });
    return errorResponse(500, "STAFF_SERVICE_UNAVAILABLE");
  }
  const admin = createClient(url, serviceRole);
  const { data: authData, error: authError } = await admin.auth.getUser(match[1]);
  if (authError || !authData.user) return errorResponse(401, "UNAUTHENTICATED");
  const caller = await callerMembership(admin, authData.user.id);
  if (!caller) return errorResponse(403, "NOT_AUTHORIZED");

  let input: StaffRequest | null;
  try { input = safeRequest(await request.json()); } catch { input = null; }
  if (!input) return errorResponse(400, "INVALID_REQUEST");

  let result: Response;
  if (input.operation === "create") result = await createStaff(admin, caller, input);
  else if (input.operation === "update") result = await updateStaff(admin, caller, input);
  else result = await deleteStaff(admin, caller, input);
  console.log("staff_admin_result", { id, operation: input.operation, status: result.status });
  return result;
});
