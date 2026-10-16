import {
  HostingerReachClient,
  HostingerReachError,
  type ReachContact,
  type ReachContactDetails,
  type ReachSubscriptionStatus,
} from "./hostinger-reach.ts";
import { retryDelaySeconds } from "./marketing.ts";

type RpcResult = { data: unknown; error: unknown };
export type MarketingRpcClient = {
  rpc: (name: string, args?: Record<string, unknown>) => PromiseLike<RpcResult>;
};

export type MarketingSyncRow = {
  subscriber_id: string;
  email: string;
  first_name: string | null;
  owner_user_id: string | null;
  source: "website" | "owner_app";
  sync_action: "sync" | "remove";
  reach_contact_uuid: string | null;
  attempt_count: number;
  current_plan_code: string | null;
};

export type MarketingSyncResult =
  | "accepted"
  | "removed"
  | "suppressed"
  | "pending"
  | "deferred";

function tagNames(row: MarketingSyncRow): string[] {
  const names = ["aquahub-newsletter"];
  if (row.source === "website" || !row.owner_user_id) {
    names.push("aquahub-website", "aquahub-prospect");
  } else {
    names.push("aquahub-owner");
    switch (row.current_plan_code?.toUpperCase()) {
      case "FREE":
        names.push("aquahub-free-plan");
        break;
      case "STARTER":
      case "BUSINESS":
      case "PRO":
        names.push(
          `aquahub-${row.current_plan_code.toLowerCase()}-plan`,
          "aquahub-paid-plan",
        );
        break;
    }
  }
  return names;
}

const managedAudienceTags = new Set([
  "aquahub-prospect",
  "aquahub-owner",
  "aquahub-free-plan",
  "aquahub-paid-plan",
  "aquahub-starter-plan",
  "aquahub-business-plan",
  "aquahub-pro-plan",
]);

async function refreshContactAudienceTags(
  reach: HostingerReachClient,
  contact: ReachContactDetails,
  row: MarketingSyncRow,
): Promise<void> {
  const expected = new Set(tagNames(row));
  const current = new Map(contact.tags.map((tag) => [tag.value, tag.uuid]));
  const stale = contact.tags.filter((tag) =>
    managedAudienceTags.has(tag.value) && !expected.has(tag.value)
  );
  for (const tag of stale) await reach.removeContactTag(contact.uuid, tag.uuid);

  const missing = [...expected].filter((name) => !current.has(name));
  const created = await reach.ensureTags(missing);
  for (const name of missing) {
    const tagUuid = created.get(name);
    if (!tagUuid) {
      throw new HostingerReachError({
        safeCode: "provider_tag_response_invalid",
        retryable: true,
      });
    }
    await reach.assignContactTag(contact.uuid, tagUuid);
  }
}

async function complete(
  client: MarketingRpcClient,
  row: MarketingSyncRow,
  options: {
    success: boolean;
    contact?: ReachContact | null;
    confirmationStatus?: "unknown" | "pending" | "confirmed" | "not_required";
    retryAfterSeconds?: number | null;
    safeErrorCode?: string;
    permanentFailure?: boolean;
  },
): Promise<void> {
  const reachStatus: ReachSubscriptionStatus | null =
    options.contact?.subscription_status ?? null;
  const { error } = await client.rpc("complete_marketing_reach_sync", {
    p_subscriber_id: row.subscriber_id,
    p_sync_action: row.sync_action,
    p_success: options.success,
    p_reach_status: reachStatus,
    p_contact_uuid: options.contact?.uuid ?? null,
    p_confirmation_status: options.confirmationStatus ?? null,
    p_retry_after_seconds: options.retryAfterSeconds ?? null,
    p_safe_error_code: options.safeErrorCode ?? null,
    p_permanent_failure: options.permanentFailure ?? false,
  });
  if (error) throw new Error("marketing_sync_state_update_failed");
}

async function markSuppressed(
  client: MarketingRpcClient,
  row: MarketingSyncRow,
  contact: ReachContact,
): Promise<"suppressed"> {
  await complete(client, row, {
    success: false,
    contact,
    safeErrorCode: "provider_suppressed",
    permanentFailure: true,
  });
  return "suppressed";
}

export async function processClaimedMarketingSubscriber(
  client: MarketingRpcClient,
  row: MarketingSyncRow,
  settings: {
    enabled: boolean;
    apiToken: string;
    profileUuid: string;
    doubleOptIn?: boolean;
  },
  fetcher: typeof fetch = fetch,
): Promise<MarketingSyncResult> {
  if (!settings.enabled) return "deferred";
  const reach = new HostingerReachClient(
    settings.apiToken,
    settings.profileUuid,
    fetcher,
  );
  const confirmationStatus = (
    status?: ReachSubscriptionStatus,
  ): "unknown" | "pending" | "confirmed" | "not_required" => {
    if (status === "pending") return "pending";
    if (status === "subscribed" || status === "confirmed") {
      return settings.doubleOptIn ? "confirmed" : "not_required";
    }
    return "unknown";
  };
  try {
    const existing = await reach.findContact(row.email);
    if (row.sync_action === "remove") {
      const contact = existing ??
        (row.reach_contact_uuid
          ? {
            uuid: row.reach_contact_uuid,
            email: row.email,
          } satisfies ReachContact
          : null);
      if (!contact) {
        await complete(client, row, { success: true });
        return "removed";
      }
      if (contact.subscription_status === "unsubscribed") {
        await complete(client, row, {
          success: true,
          contact,
          confirmationStatus: confirmationStatus(contact.subscription_status),
        });
        return "suppressed";
      }
      await reach.removeContact(contact.uuid);
      await complete(client, row, {
        success: true,
        contact: { uuid: contact.uuid, email: contact.email },
      });
      return "removed";
    }

    if (
      existing?.subscription_status === "unsubscribed" ||
      existing?.subscription_status === "suppressed"
    ) {
      return await markSuppressed(client, row, existing);
    }
    if (existing) {
      // Do not resend a create request or retrigger welcome automation for an
      // existing contact. Refresh tags using Reach's idempotent tag endpoints.
      const details = await reach.getContactDetails(existing.uuid);
      if (!details) {
        throw new HostingerReachError({
          safeCode: "provider_contact_response_invalid",
          retryable: true,
        });
      }
      if (
        details.subscription_status === "unsubscribed" ||
        details.subscription_status === "suppressed"
      ) {
        return await markSuppressed(client, row, details);
      }
      await refreshContactAudienceTags(reach, details, row);
      await complete(client, row, {
        success: true,
        contact: details,
        confirmationStatus: confirmationStatus(details.subscription_status),
      });
      return "accepted";
    }

    const tags = await reach.ensureTags(tagNames(row));
    await reach.createContact({
      email: row.email,
      firstName: row.first_name,
      tagUuids: tagNames(row).map((name) => tags.get(name)!).filter(Boolean),
    });
    // Contact creation returns an acknowledgement, not a contact resource.
    // A follow-up lookup records the provider UUID/status only when Reach exposes it.
    const acceptedContact = await reach.findContact(row.email);
    if (
      acceptedContact?.subscription_status === "unsubscribed" ||
      acceptedContact?.subscription_status === "suppressed"
    ) {
      return await markSuppressed(client, row, acceptedContact);
    }
    await complete(client, row, {
      success: true,
      contact: acceptedContact,
      confirmationStatus: confirmationStatus(
        acceptedContact?.subscription_status,
      ),
    });
    return "accepted";
  } catch (error) {
    const reachError = error instanceof HostingerReachError
      ? error
      : new HostingerReachError({
        safeCode: "provider_unavailable",
        retryable: true,
      });
    const retryAfter = reachError.retryAfterSeconds ??
      retryDelaySeconds(row.attempt_count, null);
    await complete(client, row, {
      success: false,
      retryAfterSeconds: retryAfter,
      safeErrorCode: reachError.safeCode,
      permanentFailure: !reachError.retryable,
    });
    return "pending";
  }
}
