import {
  createClient,
  type SupabaseClient,
} from "https://esm.sh/@supabase/supabase-js@2";
import {
  acknowledgePlaySubscription,
  sha256Hex,
  verifyPlaySubscription,
} from "../_shared/play-billing.ts";
import {
  GoogleOidcVerificationError,
  verifyGoogleOidcBearer,
} from "../_shared/google-oidc.ts";
import { encryptPlayPurchaseToken } from "../_shared/play-token-crypto.ts";
import { rtdnOrganizationForVerifiedToken } from "../_shared/play-rtdn-claim.ts";

type RtdnPayload = {
  version?: string;
  packageName?: string;
  eventTimeMillis?: string;
  subscriptionNotification?: {
    version?: string;
    notificationType?: number;
    purchaseToken?: string;
    subscriptionId?: string;
  };
  testNotification?: { version?: string };
};

type PubSubEnvelope = {
  message?: {
    data?: string;
    messageId?: string;
    publishTime?: string;
    attributes?: Record<string, string>;
  };
  subscription?: string;
};

type ClaimResult =
  | { status: "claimed"; lease_token: string }
  | { status: "busy" | "already_processed" }
  | { status: string; lease_token?: string };

// deno-lint-ignore no-explicit-any -- Supabase's ungenerated table schema needs permissive client generics.
type AdminClient = SupabaseClient<any, "public", "public", any, any>;

export type PlayRtdnHandlerDependencies = {
  env: (name: string) => string | undefined;
  verifyOidc: typeof verifyGoogleOidcBearer;
  createAdmin: (url: string, serviceKey: string) => AdminClient;
  hashToken: typeof sha256Hex;
  verifySubscription: typeof verifyPlaySubscription;
  encryptToken: typeof encryptPlayPurchaseToken;
  acknowledgeSubscription: typeof acknowledgePlaySubscription;
  now: () => number;
};

const defaultDependencies: PlayRtdnHandlerDependencies = {
  env: (name) => Deno.env.get(name),
  verifyOidc: verifyGoogleOidcBearer,
  createAdmin: (url, serviceKey) =>
    createClient(url, serviceKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    }) as AdminClient,
  hashToken: sha256Hex,
  verifySubscription: verifyPlaySubscription,
  encryptToken: encryptPlayPurchaseToken,
  acknowledgeSubscription: acknowledgePlaySubscription,
  now: Date.now,
};

const json = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

function decodeMessage(data: string): RtdnPayload {
  const normalized = data.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - normalized.length % 4) % 4);
  const decoded = new TextDecoder().decode(
    Uint8Array.from(atob(padded), (character) => character.charCodeAt(0)),
  );
  return JSON.parse(decoded) as RtdnPayload;
}

function redactedPayload(
  payload: RtdnPayload,
  tokenHash: string | null,
): Record<string, unknown> {
  const notification = payload.subscriptionNotification;
  return {
    version: payload.version ?? null,
    packageName: payload.packageName ?? null,
    eventTimeMillis: payload.eventTimeMillis ?? null,
    testNotification: payload.testNotification
      ? { version: payload.testNotification.version ?? null }
      : null,
    subscriptionNotification: notification
      ? {
        version: notification.version ?? null,
        notificationType: notification.notificationType ?? null,
        subscriptionId: notification.subscriptionId ?? null,
        purchaseTokenHash: tokenHash,
      }
      : null,
  };
}

async function completeEvent(
  admin: AdminClient,
  messageId: string,
  leaseToken: string,
  now: () => number,
  payload: Record<string, unknown>,
  details: Record<string, unknown>,
): Promise<boolean> {
  const { data, error } = await admin.from("google_play_billing_events").update(
    {
      payload,
      processed_at: new Date(now()).toISOString(),
      processing_at: null,
      processing_token: null,
      ...details,
    },
  )
    .eq("message_id", messageId)
    .eq("processing_token", leaseToken)
    .is("processed_at", null)
    .select("message_id")
    .maybeSingle();
  return !error && data !== null;
}

async function releaseEvent(
  admin: AdminClient,
  messageId: string,
  leaseToken: string,
): Promise<boolean> {
  const { data, error } = await admin.from("google_play_billing_events")
    .update({ processing_at: null, processing_token: null })
    .eq("message_id", messageId)
    .eq("processing_token", leaseToken)
    .is("processed_at", null)
    .select("message_id")
    .maybeSingle();
  return !error && data !== null;
}

export function createPlayRtdnHandler(
  overrides: Partial<PlayRtdnHandlerDependencies> = {},
): (request: Request) => Promise<Response> {
  const deps = { ...defaultDependencies, ...overrides };

  return async (request: Request): Promise<Response> => {
    if (request.method !== "POST") {
      return json(405, { error: "METHOD_NOT_ALLOWED" });
    }
    try {
      await deps.verifyOidc(
        request,
        deps.env("PLAY_RTDN_PUSH_AUDIENCE"),
        deps.env("PLAY_RTDN_PUSH_SERVICE_ACCOUNT"),
      );
    } catch (error) {
      if (
        error instanceof GoogleOidcVerificationError &&
        error.reason === "NOT_CONFIGURED"
      ) {
        return json(503, { error: "RTDN_AUTH_NOT_CONFIGURED" });
      }
      if (
        error instanceof GoogleOidcVerificationError &&
        error.reason === "UNAVAILABLE"
      ) {
        return json(503, { error: "RTDN_AUTH_UNAVAILABLE" });
      }
      return json(401, { error: "UNAUTHORIZED" });
    }

    const serviceKey = deps.env("SUPABASE_SERVICE_ROLE_KEY")?.trim();
    const supabaseUrl = deps.env("SUPABASE_URL")?.trim();
    if (!serviceKey || !supabaseUrl) {
      return json(503, { error: "RTDN_SERVICE_UNAVAILABLE" });
    }

    let admin: AdminClient;
    try {
      admin = deps.createAdmin(supabaseUrl, serviceKey);
    } catch {
      return json(503, { error: "RTDN_SERVICE_UNAVAILABLE" });
    }

    let envelope: PubSubEnvelope;
    try {
      envelope = await request.json() as PubSubEnvelope;
    } catch {
      return json(400, { error: "INVALID_PUBSUB_ENVELOPE" });
    }
    const message = envelope.message;
    if (!message?.data || !message.messageId) {
      return json(400, { error: "INVALID_PUBSUB_ENVELOPE" });
    }

    let payload: RtdnPayload;
    try {
      payload = decodeMessage(message.data);
    } catch {
      return json(400, { error: "INVALID_RTDN_PAYLOAD" });
    }

    const purchaseToken = payload.subscriptionNotification?.purchaseToken;
    let tokenHash: string | null;
    try {
      tokenHash = purchaseToken ? await deps.hashToken(purchaseToken) : null;
    } catch {
      return json(503, { error: "RTDN_PREPARATION_FAILED" });
    }
    const safePayload = redactedPayload(payload, tokenHash);

    let claim: ClaimResult | null;
    try {
      const { data, error } = await admin.rpc("claim_play_rtdn_event", {
        p_message_id: message.messageId,
        p_payload: safePayload,
      });
      if (error) return json(503, { error: "RTDN_CLAIM_UNAVAILABLE" });
      claim = data as ClaimResult | null;
    } catch {
      return json(503, { error: "RTDN_CLAIM_UNAVAILABLE" });
    }
    if (claim?.status === "already_processed") {
      return new Response(null, { status: 204 });
    }
    if (claim?.status === "busy") {
      return json(503, { error: "RTDN_EVENT_BUSY" });
    }
    if (
      claim?.status !== "claimed" || typeof claim.lease_token !== "string" ||
      !claim.lease_token
    ) {
      return json(503, { error: "RTDN_CLAIM_UNAVAILABLE" });
    }

    const leaseToken = claim.lease_token;
    const release = async () => {
      try {
        await releaseEvent(admin, message.messageId!, leaseToken);
      } catch {
        // The lease will become reclaimable after its expiry. The request still
        // returns non-2xx so Pub/Sub retains and retries the notification.
      }
    };
    const retry = async (error: string) => {
      await release();
      return json(503, { error });
    };
    const finish = async (details: Record<string, unknown>) => {
      try {
        const completed = await completeEvent(
          admin,
          message.messageId!,
          leaseToken,
          deps.now,
          safePayload,
          details,
        );
        if (completed) return new Response(null, { status: 204 });
      } catch {
        // Fall through to a retryable response; no event is acknowledged until
        // its durable completion update has succeeded under this lease.
      }
      return await retry("RTDN_EVENT_PERSIST_FAILED");
    };

    try {
      if (payload.testNotification) {
        return await finish({ product_id: null, purchase_token_hash: null });
      }

      const notification = payload.subscriptionNotification;
      const expectedPackage = deps.env("GOOGLE_PLAY_PACKAGE_NAME")?.trim() ||
        "com.aesprt.aquahub";
      if (
        payload.packageName !== expectedPackage || !purchaseToken ||
        !notification?.subscriptionId || !tokenHash
      ) {
        return await finish({
          product_id: notification?.subscriptionId ?? null,
          purchase_token_hash: tokenHash,
        });
      }

      const productId = notification.subscriptionId;
      const eventTime = payload.eventTimeMillis
        ? Number(payload.eventTimeMillis)
        : message.publishTime
        ? Date.parse(message.publishTime)
        : deps.now();
      const safeEventTime = Number.isFinite(eventTime) && eventTime > 0
        ? eventTime
        : deps.now();
      const { data: tokenRecord, error: tokenReadError } = await admin.from(
        "google_play_subscription_tokens",
      )
        .select("organization_id, superseded_at").eq("token_hash", tokenHash)
        .maybeSingle();
      if (tokenReadError) return await retry("PURCHASE_BINDING_LOOKUP_FAILED");
      if (tokenRecord?.superseded_at) {
        return await finish({
          product_id: productId,
          purchase_token_hash: tokenHash,
          event_time: safeEventTime,
        });
      }

      let verified;
      const verificationStartedAt = deps.now();
      try {
        verified = await deps.verifySubscription(productId, purchaseToken);
      } catch (error) {
        if (
          error instanceof Error &&
          (error.message === "PLAY_PURCHASE_PENDING" ||
            error.message === "PLAY_PENDING_PURCHASE_CANCELED")
        ) {
          return await finish({
            notification_type: notification.notificationType ?? null,
            product_id: productId,
            purchase_token_hash: tokenHash,
            organization_id: tokenRecord?.organization_id ?? null,
            event_time: safeEventTime,
          });
        }
        // A failed Google lookup is intentionally non-2xx so Pub/Sub retries it.
        return await retry("PLAY_REVERIFICATION_UNAVAILABLE");
      }
      if (
        verified.status === "PENDING" ||
        verified.status === "PENDING_PURCHASE_CANCELED"
      ) {
        return await finish({
          notification_type: notification.notificationType ?? null,
          product_id: productId,
          purchase_token_hash: tokenHash,
          organization_id: tokenRecord?.organization_id ?? null,
          event_time: safeEventTime,
        });
      }

      const predecessorToken = verified.linkedPurchaseToken ??
        verified.outOfAppExpiredPurchaseToken;
      const linkedTokenHash = predecessorToken
        ? await deps.hashToken(predecessorToken)
        : null;
      const { data: linkedTokenRecord, error: linkedTokenReadError } =
        !tokenRecord?.organization_id && linkedTokenHash
          ? await admin.from("google_play_subscription_tokens")
            .select("organization_id, superseded_at")
            .eq("token_hash", linkedTokenHash).maybeSingle()
          : { data: null, error: null };
      if (linkedTokenReadError) {
        return await retry("PURCHASE_BINDING_LOOKUP_FAILED");
      }

      const organizationId = rtdnOrganizationForVerifiedToken(
        tokenRecord,
        linkedTokenRecord,
      );
      if (!organizationId) {
        // A first purchase has no predecessor to trust. Android will restore it
        // with the signed-in owner; never guess an organization from the RTDN.
        return await finish({
          notification_type: notification.notificationType ?? null,
          product_id: productId,
          purchase_token_hash: tokenHash,
          event_time: safeEventTime,
        });
      }

      let encryptedToken: string;
      try {
        encryptedToken = await deps.encryptToken(purchaseToken);
      } catch {
        return await retry("PLAY_TOKEN_ENCRYPTION_NOT_CONFIGURED");
      }
      const { data: applied, error: updateError } = await admin.rpc(
        "apply_verified_play_subscription",
        {
          p_organization_id: organizationId,
          p_actor_user_id: null,
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
          p_provider_event_version: Number(notification.version ?? 0) || null,
          p_linked_token_hash: linkedTokenHash,
          p_source: "RTDN",
        },
      );
      if (updateError) return await retry("SUBSCRIPTION_PERSIST_FAILED");

      // Apply-before-ack preserves entitlement integrity. The SQL application
      // RPC is idempotent, so retries after an ack/finalization error can safely
      // reapply the verified state and retry the provider acknowledgement.
      if (applied?.result === "APPLIED" && !verified.acknowledged) {
        try {
          await deps.acknowledgeSubscription(productId, purchaseToken);
        } catch {
          return await retry("PURCHASE_ACKNOWLEDGEMENT_PENDING");
        }
      }
      return await finish({
        notification_type: notification.notificationType ?? null,
        product_id: productId,
        purchase_token_hash: tokenHash,
        organization_id: organizationId,
        event_time: safeEventTime,
      });
    } catch {
      return await retry("RTDN_PROCESSING_UNAVAILABLE");
    }
  };
}

if (import.meta.main) Deno.serve(createPlayRtdnHandler());
