import {
  type MarketingRpcClient,
  type MarketingSyncRow,
  processClaimedMarketingSubscriber,
} from "./marketing-sync.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(
      "expected " + String(expected) + ", received " + String(actual),
    );
  }
}

const row: MarketingSyncRow = {
  subscriber_id: "local-id",
  email: "owner@example.com",
  first_name: "Juan",
  owner_user_id: null,
  source: "website",
  sync_action: "sync",
  reach_contact_uuid: null,
  attempt_count: 1,
  current_plan_code: null,
};

function fakeRpc(
  calls: Array<{ name: string; args: Record<string, unknown> }>,
): MarketingRpcClient {
  return {
    async rpc(name, args = {}) {
      calls.push({ name, args });
      return { data: true, error: null };
    },
  };
}

Deno.test("existing active Reach contact is not created twice", async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  let postRequests = 0;
  const result = await processClaimedMarketingSubscriber(
    fakeRpc(calls),
    row,
    {
      enabled: true,
      apiToken: "test-token",
      profileUuid: "12345678-1234-1234-1234-123456789abc",
    },
    async (input, init) => {
      if (init?.method === "POST") postRequests++;
      if (String(input).includes("/contacts?")) {
        return Response.json([{
          uuid: "reach-id",
          email: row.email,
          subscription_status: "subscribed",
        }]);
      }
      return Response.json({
        uuid: "reach-id",
        email: row.email,
        subscription_status: "subscribed",
        tags: ["aquahub-newsletter", "aquahub-website", "aquahub-prospect"].map(
          (value, index) => ({ uuid: `tag-${index}`, value }),
        ),
      });
    },
  );
  assertEquals(result, "accepted");
  assertEquals(postRequests, 0);
  assertEquals(calls[0]?.name, "complete_marketing_reach_sync");
});

Deno.test("Reach unsubscribe is preserved and never replaced with a new contact", async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  let postRequests = 0;
  const result = await processClaimedMarketingSubscriber(
    fakeRpc(calls),
    row,
    {
      enabled: true,
      apiToken: "test-token",
      profileUuid: "12345678-1234-1234-1234-123456789abc",
    },
    async (input, init) => {
      if (init?.method === "POST") postRequests++;
      if (!String(input).includes("/contacts?")) {
        throw new Error("unexpected follow-up request");
      }
      return Response.json([{
        uuid: "reach-id",
        email: row.email,
        subscription_status: "unsubscribed",
      }]);
    },
  );
  assertEquals(result, "suppressed");
  assertEquals(postRequests, 0);
  assertEquals(calls[0]?.args.p_reach_status, "unsubscribed");
  assertEquals(calls[0]?.args.p_success, false);
});

Deno.test("transient Reach outage is queued with a safe code and retry delay", async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const result = await processClaimedMarketingSubscriber(
    fakeRpc(calls),
    row,
    {
      enabled: true,
      apiToken: "test-token",
      profileUuid: "12345678-1234-1234-1234-123456789abc",
    },
    async () => new Response("{}", { status: 503 }),
  );
  assertEquals(result, "pending");
  assertEquals(calls[0]?.args.p_success, false);
  assertEquals(calls[0]?.args.p_safe_error_code, "provider_unavailable");
  const retryAfter = calls[0]?.args.p_retry_after_seconds;
  if (typeof retryAfter !== "number" || retryAfter < 48 || retryAfter > 72) {
    throw new Error(
      `expected jittered retry between 48 and 72 seconds, received ${
        String(retryAfter)
      }`,
    );
  }
});

Deno.test("a retry can recover after a transient Reach failure", async () => {
  const firstCalls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const firstResult = await processClaimedMarketingSubscriber(
    fakeRpc(firstCalls),
    row,
    {
      enabled: true,
      apiToken: "test-token",
      profileUuid: "12345678-1234-1234-1234-123456789abc",
    },
    async () => new Response("{}", { status: 503 }),
  );
  assertEquals(firstResult, "pending");

  const retryCalls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const retriedRow = { ...row, attempt_count: 2 };
  const retryResult = await processClaimedMarketingSubscriber(
    fakeRpc(retryCalls),
    retriedRow,
    {
      enabled: true,
      apiToken: "test-token",
      profileUuid: "12345678-1234-1234-1234-123456789abc",
      doubleOptIn: true,
    },
    async (input) =>
      String(input).includes("/contacts?")
        ? Response.json([{
          uuid: "reach-id",
          email: row.email,
          subscription_status: "pending",
        }])
        : Response.json({
          uuid: "reach-id",
          email: row.email,
          subscription_status: "pending",
          tags: ["aquahub-newsletter", "aquahub-website", "aquahub-prospect"]
            .map((value, index) => ({ uuid: `tag-${index}`, value })),
        }),
  );
  assertEquals(retryResult, "accepted");
  assertEquals(retryCalls[0]?.args.p_confirmation_status, "pending");
});

Deno.test("owner plan changes remove stale plan tags and apply the current Reach plan tag", async () => {
  const calls: Array<{ name: string; args: Record<string, unknown> }> = [];
  const rowForPlan = {
    ...row,
    source: "owner_app" as const,
    owner_user_id: "owner-id",
    current_plan_code: "STARTER",
  };
  const requests: Array<{ url: string; method: string }> = [];
  const result = await processClaimedMarketingSubscriber(
    fakeRpc(calls),
    rowForPlan,
    {
      enabled: true,
      apiToken: "test-token",
      profileUuid: "12345678-1234-1234-1234-123456789abc",
    },
    async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      requests.push({ url, method });
      if (url.includes("/contacts?")) {
        return Response.json([{
          uuid: "reach-id",
          email: row.email,
          subscription_status: "subscribed",
        }]);
      }
      if (url.endsWith("/contacts/reach-id")) {
        return Response.json({
          uuid: "reach-id",
          email: row.email,
          subscription_status: "subscribed",
          tags: [
            { uuid: "newsletter-id", value: "aquahub-newsletter" },
            { uuid: "owner-id", value: "aquahub-owner" },
            { uuid: "old-free-id", value: "aquahub-free-plan" },
          ],
        });
      }
      if (url.endsWith("/tags")) {
        return Response.json([
          { uuid: "starter-id", type: "custom", value: "aquahub-starter-plan" },
          { uuid: "paid-id", type: "custom", value: "aquahub-paid-plan" },
        ]);
      }
      return Response.json({ message: "Request accepted" });
    },
  );
  assertEquals(result, "accepted");
  assertEquals(
    requests.some((request) =>
      request.method === "DELETE" &&
      request.url.includes("/tags/old-free-id/contacts/reach-id")
    ),
    true,
  );
  assertEquals(
    requests.some((request) =>
      request.method === "POST" &&
      request.url.includes("/tags/starter-id/contacts/reach-id")
    ),
    true,
  );
  assertEquals(
    requests.some((request) =>
      request.method === "POST" &&
      request.url.includes("/tags/paid-id/contacts/reach-id")
    ),
    true,
  );
  assertEquals(
    requests.some((request) =>
      request.method === "POST" && request.url.endsWith("/contacts")
    ),
    false,
  );
});
