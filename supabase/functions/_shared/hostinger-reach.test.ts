import {
  HostingerReachClient,
  HostingerReachError,
  parseRetryAfter,
} from "./hostinger-reach.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(
      "expected " + String(expected) + ", received " + String(actual),
    );
  }
}
function assertIncludes(actual: string, expected: string): void {
  if (!actual.includes(expected)) {
    throw new Error("expected value to include " + expected);
  }
}
async function rejected(callback: () => Promise<unknown>): Promise<unknown> {
  try {
    await callback();
  } catch (error) {
    return error;
  }
  throw new Error("expected request to reject");
}

Deno.test("Reach requests use the profile endpoint and bearer authentication", async () => {
  let requestedUrl = "";
  let authorization = "";
  const client = new HostingerReachClient(
    "secret-test-token",
    "12345678-1234-1234-1234-123456789abc",
    async (input, init) => {
      requestedUrl = String(input);
      authorization = new Headers(init?.headers).get("authorization") ?? "";
      return Response.json([{
        uuid: "contact-id",
        email: "owner@example.com",
        subscription_status: "confirmed",
      }]);
    },
  );
  const contact = await client.findContact("Owner@example.com");
  assertIncludes(
    requestedUrl,
    "/profiles/12345678-1234-1234-1234-123456789abc/contacts?",
  );
  assertEquals(authorization, "Bearer secret-test-token");
  assertEquals(contact?.uuid, "contact-id");
  assertEquals(contact?.subscription_status, "confirmed");
});

Deno.test("Reach tag creation is idempotent and contact payload contains only supported fields", async () => {
  let tagBody: Record<string, unknown> = {};
  let contactBody: Record<string, unknown> = {};
  const client = new HostingerReachClient(
    "secret-test-token",
    "12345678-1234-1234-1234-123456789abc",
    async (input, init) => {
      if (init?.method === "POST" && String(input).endsWith("/tags")) {
        tagBody = JSON.parse(String(init.body));
        return Response.json([{
          uuid: "tag-id",
          type: "custom",
          value: "aquahub-newsletter",
        }]);
      }
      contactBody = JSON.parse(String(init?.body));
      return Response.json({ message: "Request accepted" });
    },
  );
  const tags = await client.ensureTags(["aquahub-newsletter"]);
  await client.createContact({
    email: "owner@example.com",
    firstName: "Juan",
    tagUuids: [...tags.values()],
  });
  assertEquals(
    JSON.stringify(tagBody.names),
    JSON.stringify(["aquahub-newsletter"]),
  );
  assertEquals(contactBody.email, "owner@example.com");
  assertEquals(contactBody.name, "Juan");
  assertEquals(
    JSON.stringify(contactBody.tag_uuids),
    JSON.stringify(["tag-id"]),
  );
  assertEquals("consent" in contactBody, false);
});

Deno.test("Reach contact details parse the documented tag resource value", async () => {
  let requestedUrl = "";
  const client = new HostingerReachClient(
    "secret-test-token",
    "12345678-1234-1234-1234-123456789abc",
    async (input) => {
      requestedUrl = String(input);
      return Response.json({
        uuid: "contact-id",
        email: "owner@example.com",
        subscription_status: "subscribed",
        tags: [{
          uuid: "tag-id",
          type: "custom",
          value: "aquahub-free-plan",
          created_at: "2026-10-10T00:00:00Z",
        }],
      });
    },
  );
  const details = await client.getContactDetails("contact-id");
  assertIncludes(requestedUrl, "/contacts/contact-id");
  assertEquals(details?.tags[0]?.uuid, "tag-id");
  assertEquals(details?.tags[0]?.value, "aquahub-free-plan");
});

Deno.test("Reach audience tag assignment and removal use the documented profile routes", async () => {
  const urls: string[] = [];
  const methods: string[] = [];
  const client = new HostingerReachClient(
    "secret-test-token",
    "12345678-1234-1234-1234-123456789abc",
    async (input, init) => {
      urls.push(String(input));
      methods.push(init?.method ?? "GET");
      return Response.json({ message: "Request accepted" });
    },
  );
  await client.assignContactTag("contact-id", "tag-id");
  await client.removeContactTag("contact-id", "tag-id");
  assertIncludes(urls[0], "/tags/tag-id/contacts/contact-id");
  assertIncludes(urls[1], "/tags/tag-id/contacts/contact-id");
  assertEquals(methods[0], "POST");
  assertEquals(methods[1], "DELETE");
});

Deno.test("Reach 429 respects Retry-After and 401 is a permanent authentication error", async () => {
  const limited = new HostingerReachClient(
    "secret-test-token",
    "12345678-1234-1234-1234-123456789abc",
    async () =>
      new Response("{}", { status: 429, headers: { "Retry-After": "37" } }),
  );
  const rateError = await rejected(() =>
    limited.findContact("owner@example.com")
  );
  if (!(rateError instanceof HostingerReachError)) {
    throw new Error("expected a Reach API error");
  }
  assertEquals(rateError.safeCode, "provider_rate_limited");
  assertEquals(rateError.retryAfterSeconds, 37);

  const unauthorized = new HostingerReachClient(
    "secret-test-token",
    "12345678-1234-1234-1234-123456789abc",
    async () => new Response("{}", { status: 401 }),
  );
  const authError = await rejected(() =>
    unauthorized.findContact("owner@example.com")
  );
  if (!(authError instanceof HostingerReachError)) {
    throw new Error("expected a Reach API error");
  }
  assertEquals(authError.safeCode, "provider_authentication_failed");
  assertEquals(authError.retryable, false);
});

Deno.test("Reach diagnostics use only authenticated read endpoints", async () => {
  const requests: Array<
    { url: string; method: string; authorization: string }
  > = [];
  const client = new HostingerReachClient(
    "secret-test-token",
    "12345678-1234-1234-1234-123456789abc",
    async (input, init) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      requests.push({
        url,
        method,
        authorization: new Headers(init?.headers).get("authorization") ?? "",
      });
      if (url.endsWith("/api/reach/v1/profiles")) {
        return Response.json([{
          status: "active",
          domain: "aquahub.aesprt.com",
          profiles: [{
            uuid: "12345678-1234-1234-1234-123456789abc",
            domain: "aquahub.aesprt.com",
          }],
        }]);
      }
      if (url.endsWith("/tags")) {
        return Response.json([{
          uuid: "newsletter-tag-id",
          value: "aquahub-newsletter",
        }]);
      }
      if (url.includes("/segmentation/segments?")) {
        return Response.json({
          data: [{
            uuid: "segment-id",
            name: "AquaHub Newsletter Subscribers",
            contacts_count: 1,
          }],
          meta: { current_page: 1, last_page: 1 },
        });
      }
      if (url.endsWith("/segmentation/segments/segment-id")) {
        return Response.json({
          uuid: "segment-id",
          name: "AquaHub Newsletter Subscribers",
          query: {
            logic: "and",
            conditions: [
              {
                attribute: "tag",
                operator: "is",
                value: "aquahub-newsletter",
              },
              {
                attribute: "subscription_status",
                operator: "is",
                value: "subscribed",
              },
            ],
          },
        });
      }
      return new Response("unexpected endpoint", { status: 500 });
    },
  );
  const profiles = await client.listProfiles();
  const tags = await client.listTags();
  const segments = await client.listSegments(1, 100);
  const details = await client.getSegmentDetails("segment-id");

  assertEquals(profiles[0]?.uuid, "12345678-1234-1234-1234-123456789abc");
  assertEquals(profiles[0]?.domain, "aquahub.aesprt.com");
  assertEquals(tags[0]?.value, "aquahub-newsletter");
  assertEquals(segments[0]?.name, "AquaHub Newsletter Subscribers");
  assertEquals((details?.query as { logic?: string })?.logic, "and");
  assertEquals(requests.every((request) => request.method === "GET"), true);
  assertEquals(
    requests.every((request) =>
      request.authorization === "Bearer secret-test-token"
    ),
    true,
  );
  assertEquals(requests.length, 4);
});

Deno.test("Retry-After accepts delta seconds and HTTP dates", () => {
  assertEquals(parseRetryAfter("12"), 12);
  assertEquals(parseRetryAfter("Thu, 01 Jan 1970 00:00:10 GMT", 0), 10);
});
