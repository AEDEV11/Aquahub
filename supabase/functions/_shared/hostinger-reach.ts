export type ReachSubscriptionStatus =
  | "pending"
  | "subscribed"
  | "confirmed"
  | "unsubscribed"
  | "suppressed";

export type ReachContact = {
  uuid: string;
  email: string;
  subscription_status?: ReachSubscriptionStatus;
};

export type ReachTag = { uuid: string; value: string };

export type ReachContactDetails = ReachContact & { tags: ReachTag[] };

export type ReachProfile = {
  uuid: string;
  domain: string | null;
  status: string | null;
};

export type ReachSegment = {
  uuid: string;
  name: string;
  query?: unknown;
  contactsCount?: number;
};

export class HostingerReachError extends Error {
  readonly retryable: boolean;
  readonly retryAfterSeconds: number | null;
  readonly safeCode: string;
  readonly status: number | null;

  constructor(options: {
    safeCode: string;
    retryable: boolean;
    retryAfterSeconds?: number | null;
    status?: number | null;
  }) {
    super(options.safeCode);
    this.name = "HostingerReachError";
    this.safeCode = options.safeCode;
    this.retryable = options.retryable;
    this.retryAfterSeconds = options.retryAfterSeconds ?? null;
    this.status = options.status ?? null;
  }
}

export function parseRetryAfter(
  value: string | null,
  nowMs = Date.now(),
): number | null {
  if (!value) return null;
  const seconds = Number(value.trim());
  if (Number.isFinite(seconds) && seconds >= 0) {
    return Math.min(86_400, Math.ceil(seconds));
  }
  const date = Date.parse(value);
  if (!Number.isFinite(date)) return null;
  return Math.min(86_400, Math.max(0, Math.ceil((date - nowMs) / 1000)));
}

function recordsFrom(payload: unknown): Record<string, unknown>[] {
  if (Array.isArray(payload)) {
    return payload.filter((item): item is Record<string, unknown> =>
      !!item && typeof item === "object"
    );
  }
  if (!payload || typeof payload !== "object") return [];
  const object = payload as Record<string, unknown>;
  for (const key of ["data", "contacts", "tags", "items"]) {
    if (Array.isArray(object[key])) {
      return (object[key] as unknown[]).filter((
        item,
      ): item is Record<string, unknown> => !!item && typeof item === "object");
    }
  }
  return [];
}

function contactFrom(value: Record<string, unknown>): ReachContact | null {
  const uuid = typeof value.uuid === "string"
    ? value.uuid
    : typeof value.id === "string"
    ? value.id
    : null;
  const email = typeof value.email === "string" ? value.email : null;
  if (!uuid || !email) return null;
  const state = typeof value.subscription_status === "string"
    ? value.subscription_status
    : undefined;
  const allowed = [
    "pending",
    "subscribed",
    "confirmed",
    "unsubscribed",
    "suppressed",
  ];
  return {
    uuid,
    email,
    ...(state && allowed.includes(state)
      ? { subscription_status: state as ReachSubscriptionStatus }
      : {}),
  };
}

export class HostingerReachClient {
  private readonly profileUrl: string;
  private readonly profilesUrl =
    "https://developers.hostinger.com/api/reach/v1/profiles";

  constructor(
    private readonly apiToken: string,
    profileUuid: string,
    private readonly fetcher: typeof fetch = fetch,
    private readonly timeoutMs = 8_000,
  ) {
    if (!apiToken.trim()) {
      throw new HostingerReachError({
        safeCode: "provider_not_configured",
        retryable: true,
      });
    }
    if (!/^[0-9a-f-]{20,64}$/i.test(profileUuid)) {
      throw new HostingerReachError({
        safeCode: "profile_not_configured",
        retryable: false,
      });
    }
    this.profileUrl = `https://developers.hostinger.com/api/reach/v1/profiles/${
      encodeURIComponent(profileUuid)
    }`;
  }

  private async requestUrl(
    url: string,
    method: string,
    body?: unknown,
  ): Promise<unknown> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      let response: Response;
      try {
        response = await this.fetcher(url, {
          method,
          signal: controller.signal,
          headers: {
            authorization: `Bearer ${this.apiToken}`,
            accept: "application/json",
            ...(body === undefined
              ? {}
              : { "content-type": "application/json" }),
          },
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        });
      } catch {
        throw new HostingerReachError({
          safeCode: "provider_unavailable",
          retryable: true,
        });
      }

      if (!response.ok) {
        const retryAfterSeconds = parseRetryAfter(
          response.headers.get("retry-after"),
        );
        if (response.status === 429) {
          throw new HostingerReachError({
            safeCode: "provider_rate_limited",
            retryable: true,
            retryAfterSeconds,
            status: response.status,
          });
        }
        if (response.status === 408 || response.status >= 500) {
          throw new HostingerReachError({
            safeCode: "provider_unavailable",
            retryable: true,
            status: response.status,
          });
        }
        throw new HostingerReachError({
          safeCode: response.status === 401 || response.status === 403
            ? "provider_authentication_failed"
            : "provider_rejected_request",
          retryable: false,
          status: response.status,
        });
      }
      if (response.status === 204) return null;
      return await response.json().catch(() => null);
    } finally {
      clearTimeout(timeout);
    }
  }

  private request(
    path: string,
    method: string,
    body?: unknown,
  ): Promise<unknown> {
    return this.requestUrl(`${this.profileUrl}${path}`, method, body);
  }

  async listProfiles(): Promise<ReachProfile[]> {
    const payload = await this.requestUrl(this.profilesUrl, "GET");
    const resources = Array.isArray(payload)
      ? payload.filter((item): item is Record<string, unknown> =>
        !!item && typeof item === "object"
      )
      : recordsFrom(payload);
    const profiles: ReachProfile[] = [];
    for (const resource of resources) {
      const nested = Array.isArray(resource.profiles)
        ? resource.profiles.filter((item): item is Record<string, unknown> =>
          !!item && typeof item === "object"
        )
        : [resource];
      for (const profile of nested) {
        const uuid = typeof profile.uuid === "string"
          ? profile.uuid
          : typeof profile.id === "string"
          ? profile.id
          : null;
        if (!uuid) continue;
        profiles.push({
          uuid,
          domain: typeof profile.domain === "string"
            ? profile.domain
            : typeof resource.domain === "string"
            ? resource.domain
            : null,
          status: typeof resource.status === "string"
            ? resource.status
            : typeof profile.status === "string"
            ? profile.status
            : null,
        });
      }
    }
    return profiles;
  }

  async listTags(): Promise<ReachTag[]> {
    const payload = await this.request("/tags", "GET");
    return recordsFrom(payload).flatMap((row) => {
      const uuid = typeof row.uuid === "string"
        ? row.uuid
        : typeof row.id === "string"
        ? row.id
        : null;
      const value = typeof row.value === "string"
        ? row.value
        : typeof row.name === "string"
        ? row.name
        : null;
      return uuid && value ? [{ uuid, value }] : [];
    });
  }

  async listSegments(page = 1, perPage = 100): Promise<ReachSegment[]> {
    const query = new URLSearchParams({
      page: String(page),
      per_page: String(Math.min(100, Math.max(1, perPage))),
    });
    const payload = await this.request(
      `/segmentation/segments?${query.toString()}`,
      "GET",
    );
    return recordsFrom(payload).flatMap((row) => {
      const uuid = typeof row.uuid === "string"
        ? row.uuid
        : typeof row.id === "string"
        ? row.id
        : null;
      const name = typeof row.name === "string" ? row.name : null;
      if (!uuid || !name) return [];
      return [{
        uuid,
        name,
        ...(typeof row.contacts_count === "number"
          ? { contactsCount: row.contacts_count }
          : {}),
      }];
    });
  }

  async getSegmentDetails(segmentUuid: string): Promise<ReachSegment | null> {
    const payload = await this.request(
      `/segmentation/segments/${encodeURIComponent(segmentUuid)}`,
      "GET",
    );
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      return null;
    }
    const row = payload as Record<string, unknown>;
    const uuid = typeof row.uuid === "string" ? row.uuid : null;
    const name = typeof row.name === "string" ? row.name : null;
    if (!uuid || !name) return null;
    return {
      uuid,
      name,
      ...(row.query === undefined ? {} : { query: row.query }),
    };
  }

  async listSegmentContacts(
    segmentUuid: string,
    page = 1,
    perPage = 100,
  ): Promise<ReachContact[]> {
    const query = new URLSearchParams({
      page: String(page),
      per_page: String(Math.min(100, Math.max(1, perPage))),
    });
    const payload = await this.request(
      `/segmentation/segments/${
        encodeURIComponent(segmentUuid)
      }/contacts?${query.toString()}`,
      "GET",
    );
    return recordsFrom(payload).map(contactFrom).filter((
      contact,
    ): contact is ReachContact => contact !== null);
  }

  async ensureTags(names: string[]): Promise<Map<string, string>> {
    const uniqueNames = [...new Set(names)];
    if (uniqueNames.length === 0) return new Map();
    const payload = await this.request("/tags", "POST", { names: uniqueNames });
    const tags = new Map<string, string>();
    for (const row of recordsFrom(payload)) {
      // Reach's TagResource calls the visible tag name `value`.
      const name = typeof row.value === "string"
        ? row.value
        : typeof row.name === "string"
        ? row.name
        : null;
      const uuid = typeof row.uuid === "string"
        ? row.uuid
        : typeof row.id === "string"
        ? row.id
        : null;
      if (name && uuid) tags.set(name, uuid);
    }
    if (uniqueNames.some((name) => !tags.has(name))) {
      throw new HostingerReachError({
        safeCode: "provider_tag_response_invalid",
        retryable: true,
      });
    }
    return tags;
  }

  async findContact(email: string): Promise<ReachContact | null> {
    const query = new URLSearchParams({
      search: email,
      per_page: "100",
      page: "1",
    });
    const payload = await this.request(`/contacts?${query.toString()}`, "GET");
    const normalized = email.trim().toLowerCase();
    return recordsFrom(payload)
      .map(contactFrom)
      .find((contact) => contact?.email.trim().toLowerCase() === normalized) ??
      null;
  }

  async getContactDetails(
    contactUuid: string,
  ): Promise<ReachContactDetails | null> {
    let payload: unknown;
    try {
      payload = await this.request(
        `/contacts/${encodeURIComponent(contactUuid)}`,
        "GET",
      );
    } catch (error) {
      if (error instanceof HostingerReachError && error.status === 404) {
        return null;
      }
      throw error;
    }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      return null;
    }
    const value = payload as Record<string, unknown>;
    const contact = contactFrom(value);
    if (!contact) return null;
    const tags = Array.isArray(value.tags)
      ? value.tags
        .filter((tag): tag is Record<string, unknown> =>
          !!tag && typeof tag === "object"
        )
        .map((tag) => ({
          uuid: typeof tag.uuid === "string" ? tag.uuid : "",
          value: typeof tag.value === "string" ? tag.value : "",
        }))
        .filter((tag) => tag.uuid && tag.value)
      : [];
    return { ...contact, tags };
  }

  async assignContactTag(contactUuid: string, tagUuid: string): Promise<void> {
    await this.request(
      `/tags/${encodeURIComponent(tagUuid)}/contacts/${
        encodeURIComponent(contactUuid)
      }`,
      "POST",
    );
  }

  async removeContactTag(contactUuid: string, tagUuid: string): Promise<void> {
    await this.request(
      `/tags/${encodeURIComponent(tagUuid)}/contacts/${
        encodeURIComponent(contactUuid)
      }`,
      "DELETE",
    );
  }

  async createContact(input: {
    email: string;
    firstName?: string | null;
    tagUuids: string[];
  }): Promise<void> {
    await this.request("/contacts", "POST", {
      email: input.email,
      ...(input.firstName ? { name: input.firstName } : {}),
      tag_uuids: input.tagUuids,
    });
  }

  async removeContact(contactUuid: string): Promise<void> {
    try {
      await this.request(
        `/contacts/${encodeURIComponent(contactUuid)}`,
        "DELETE",
      );
    } catch (error) {
      if (error instanceof HostingerReachError && error.status === 404) return;
      throw error;
    }
  }

  async listUnsubscribedContacts(page: number): Promise<ReachContact[]> {
    const query = new URLSearchParams({
      subscription_status: "unsubscribed",
      per_page: "100",
      page: String(page),
    });
    const payload = await this.request(`/contacts?${query.toString()}`, "GET");
    return recordsFrom(payload).map(contactFrom).filter((
      contact,
    ): contact is ReachContact => contact !== null);
  }
}
