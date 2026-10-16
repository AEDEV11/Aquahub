import type {
  HostingerReachClient,
  ReachProfile,
  ReachSegment,
  ReachTag,
} from "./hostinger-reach.ts";

const requiredTags = [
  "aquahub-newsletter",
  "aquahub-website",
  "aquahub-prospect",
  "aquahub-owner",
  "aquahub-free-plan",
  "aquahub-starter-plan",
  "aquahub-business-plan",
  "aquahub-pro-plan",
  "aquahub-paid-plan",
] as const;

type SegmentCondition = {
  attribute: string | null;
  operator: string | null;
  value: unknown;
};

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function valueText(value: unknown): string {
  if (typeof value === "string") return value.toLowerCase();
  if (Array.isArray(value)) return value.map(valueText).join(" ");
  if (typeof value === "number" || typeof value === "boolean") {
    return String(value).toLowerCase();
  }
  return "";
}

function safeConditionValue(value: unknown): unknown {
  if (typeof value === "string") {
    if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return "[redacted]";
    return value.length > 160 ? `${value.slice(0, 160)}…` : value;
  }
  if (
    typeof value === "number" || typeof value === "boolean" || value === null
  ) {
    return value;
  }
  if (Array.isArray(value)) return value.map(safeConditionValue).slice(0, 20);
  return value === undefined ? null : "[structured value]";
}

function conditionsOf(query: unknown): {
  logic: string | null;
  conditions: SegmentCondition[];
} {
  const object = asRecord(query);
  const raw = Array.isArray(object?.conditions) ? object.conditions : [];
  const conditions = raw.flatMap((item) => {
    const condition = asRecord(item);
    if (!condition) return [];
    return [{
      attribute: typeof condition.attribute === "string"
        ? condition.attribute
        : null,
      operator: typeof condition.operator === "string"
        ? condition.operator
        : null,
      value: condition.value,
    }];
  });
  return {
    logic: typeof object?.logic === "string" ? object.logic : null,
    conditions,
  };
}

function segmentSummary(
  segment: ReachSegment,
  newsletterTag: ReachTag | undefined,
) {
  const { logic, conditions } = conditionsOf(segment.query);
  const summarizedConditions = conditions.map((condition) => ({
    attribute: condition.attribute,
    operator: condition.operator,
    value: safeConditionValue(condition.value),
  }));
  const tagValue = newsletterTag?.value.toLowerCase() ?? "aquahub-newsletter";
  const tagUuid = newsletterTag?.uuid.toLowerCase() ?? "";
  const hasNewsletterTagCondition = conditions.some((condition) => {
    const attribute = condition.attribute?.toLowerCase() ?? "";
    const value = valueText(condition.value);
    return (
      (attribute.includes("tag") &&
        (value.includes(tagValue) || (tagUuid && value.includes(tagUuid)))) ||
      attribute.includes(tagValue) ||
      (tagUuid !== "" && attribute.includes(tagUuid))
    );
  });
  const hasSubscribedCondition = conditions.some((condition) => {
    const attribute = condition.attribute?.toLowerCase() ?? "";
    const value = valueText(condition.value);
    return (attribute.includes("status") ||
      attribute.includes("subscription")) &&
      (value.includes("subscribed") || value.includes("confirmed"));
  });
  return {
    uuid: segment.uuid,
    name: segment.name,
    ...(segment.contactsCount === undefined
      ? {}
      : { contactsCount: segment.contactsCount }),
    queryLogic: logic,
    conditions: summarizedConditions,
    hasNewsletterTagCondition,
    hasSubscribedOrConfirmedCondition: hasSubscribedCondition,
  };
}

export async function collectReachPreflight(
  reach: Pick<
    HostingerReachClient,
    "listProfiles" | "listTags" | "listSegments" | "getSegmentDetails"
  >,
  profileUuid: string,
  configuredDoubleOptIn: boolean,
) {
  const profiles = await reach.listProfiles();
  const profile = profiles.find((item) =>
    item.uuid.toLowerCase() === profileUuid.toLowerCase()
  );
  if (!profile) {
    return {
      providerAuthenticated: true,
      configuredProfileFound: false,
      configuredDoubleOptIn,
      hostingerDoubleOptIn: "not_exposed_by_profile_list_api",
      tags: { present: [] as string[], missing: [...requiredTags] },
      newsletterSegments: [],
    };
  }

  const tags = await reach.listTags();
  const allSegments: ReachSegment[] = [];
  for (let page = 1; page <= 5; page++) {
    const segmentPage = await reach.listSegments(page, 100);
    allSegments.push(...segmentPage);
    if (segmentPage.length < 100) break;
  }
  const newsletterTag = tags.find((tag) => tag.value === "aquahub-newsletter");
  const relevantSegments = allSegments.filter((segment) => {
    const name = segment.name.toLowerCase();
    return name.includes("newsletter") && name.includes("aqua");
  }).slice(0, 20);
  const newsletterSegments = await Promise.all(
    relevantSegments.map(async (listed) => {
      const detailed = await reach.getSegmentDetails(listed.uuid);
      return segmentSummary(detailed ?? listed, newsletterTag);
    }),
  );
  const present: string[] = requiredTags.filter((name) =>
    tags.some((tag) => tag.value === name)
  );
  return {
    providerAuthenticated: true,
    configuredProfileFound: true,
    profileDomain: profile.domain,
    profileStatus: profile.status,
    configuredDoubleOptIn,
    hostingerDoubleOptIn: "not_exposed_by_profile_list_api",
    tags: {
      present: [...present],
      missing: requiredTags.filter((name) => !present.includes(name)),
    },
    newsletterSegments,
  };
}

export async function inspectReachContact(
  reach: Pick<
    HostingerReachClient,
    | "findContact"
    | "getContactDetails"
    | "listSegments"
    | "getSegmentDetails"
    | "listSegmentContacts"
  >,
  email: string,
) {
  const contact = await reach.findContact(email);
  if (!contact) {
    return {
      contactFound: false,
      subscriptionStatus: null,
      newsletterTagAssigned: false,
      newsletterSegments: [],
    };
  }
  const details = await reach.getContactDetails(contact.uuid);
  const tags = new Set(details?.tags.map((tag) => tag.value) ?? []);
  const allSegments: ReachSegment[] = [];
  for (let page = 1; page <= 5; page++) {
    const segmentPage = await reach.listSegments(page, 100);
    allSegments.push(...segmentPage);
    if (segmentPage.length < 100) break;
  }
  const newsletterSegments = allSegments.filter((segment) => {
    const name = segment.name.toLowerCase();
    return name.includes("newsletter") && name.includes("aqua");
  }).slice(0, 20);
  const membership = await Promise.all(
    newsletterSegments.map(async (segment) => {
      let found = false;
      let complete = false;
      for (let page = 1; page <= 20; page++) {
        const contacts = await reach.listSegmentContacts(
          segment.uuid,
          page,
          100,
        );
        if (
          contacts.some((candidate) =>
            candidate.email.trim().toLowerCase() === email.trim().toLowerCase()
          )
        ) {
          found = true;
          break;
        }
        if (contacts.length < 100) {
          complete = true;
          break;
        }
      }
      return {
        name: segment.name,
        membership: found ? true : complete ? false : null,
      };
    }),
  );
  return {
    contactFound: true,
    subscriptionStatus: details?.subscription_status ??
      contact.subscription_status ?? null,
    newsletterTagAssigned: tags.has("aquahub-newsletter"),
    newsletterSegments: membership,
  };
}
