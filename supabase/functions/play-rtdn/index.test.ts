import {
  assert,
  assertEquals,
  assertStringIncludes,
} from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  createPlayRtdnHandler,
  type PlayRtdnHandlerDependencies,
} from "./index.ts";

type BillingEvent = {
  payload: Record<string, unknown>;
  processedAt: number | null;
  processingAt: number | null;
  processingToken: string | null;
};

class FakeQuery {
  private operation: "select" | "update" = "select";
  private values: Record<string, unknown> = {};
  private filters: Array<{ column: string; value: unknown; is?: boolean }> = [];

  constructor(
    private readonly admin: FakeAdmin,
    private readonly table: string,
  ) {}

  select(_columns: string) {
    return this;
  }

  update(values: Record<string, unknown>) {
    this.operation = "update";
    this.values = values;
    return this;
  }

  eq(column: string, value: unknown) {
    this.filters.push({ column, value });
    return this;
  }

  is(column: string, value: unknown) {
    this.filters.push({ column, value, is: true });
    return this;
  }

  // deno-lint-ignore require-await -- Mirrors the async PostgREST builder API.
  async maybeSingle(): Promise<
    { data: Record<string, unknown> | null; error: { message: string } | null }
  > {
    if (this.table === "google_play_subscription_tokens") {
      if (this.admin.failTokenLookupOnce) {
        this.admin.failTokenLookupOnce = false;
        return {
          data: null,
          error: { message: "transient token lookup failure" },
        };
      }
      const hash = this.filters.find((filter) => filter.column === "token_hash")
        ?.value;
      return {
        data: hash === this.admin.tokenHash ? this.admin.tokenRecord : null,
        error: null,
      };
    }

    if (this.table !== "google_play_billing_events") {
      throw new Error(`unexpected table ${this.table}`);
    }
    const messageId = this.filters.find((filter) =>
      filter.column === "message_id"
    )?.value;
    const event = typeof messageId === "string"
      ? this.admin.events.get(messageId)
      : null;
    if (!event || !this.matches(event)) return { data: null, error: null };

    if (
      this.operation === "update" && this.values.processed_at !== undefined &&
      this.admin.failFinalizeOnce
    ) {
      this.admin.failFinalizeOnce = false;
      return { data: null, error: { message: "transient completion failure" } };
    }
    if (
      this.operation === "update" && this.values.processing_at === null &&
      this.admin.failReleaseOnce
    ) {
      this.admin.failReleaseOnce = false;
      return { data: null, error: { message: "transient release failure" } };
    }

    if (this.operation === "update") {
      if ("payload" in this.values) {
        event.payload = this.values.payload as Record<string, unknown>;
      }
      if ("processed_at" in this.values) {
        event.processedAt = Date.parse(String(this.values.processed_at));
      }
      if ("processing_at" in this.values) {
        event.processingAt = this.values.processing_at === null
          ? null
          : Number(this.values.processing_at);
      }
      if ("processing_token" in this.values) {
        event.processingToken = this.values.processing_token as string | null;
      }
    }
    return { data: { message_id: messageId as string }, error: null };
  }

  private matches(event: BillingEvent): boolean {
    return this.filters.every((filter) => {
      const actual = filter.column === "processing_token"
        ? event.processingToken
        : filter.column === "processed_at"
        ? event.processedAt
        : filter.column === "message_id"
        ? [...this.admin.events.entries()].find(([, candidate]) =>
          candidate === event
        )?.[0]
        : undefined;
      return filter.is ? actual === filter.value : actual === filter.value;
    });
  }
}

class FakeAdmin {
  readonly events = new Map<string, BillingEvent>();
  readonly tokenHash = "hash:abc123";
  readonly tokenRecord: Record<string, unknown> = {
    organization_id: "org-a",
    superseded_at: null,
  };
  readonly appliedKeys = new Set<string>();
  nextLease = 0;
  applyCalls = 0;
  applyCommits = 0;
  failClaimOnce = false;
  failApplyOnce = false;
  failTokenLookupOnce = false;
  failFinalizeOnce = false;
  failReleaseOnce = false;
  constructor(readonly now: () => number) {}

  from(table: string) {
    return new FakeQuery(this, table);
  }

  // deno-lint-ignore require-await -- Mirrors Supabase RPC responses.
  async rpc(name: string, args: Record<string, unknown>) {
    if (name === "claim_play_rtdn_event") {
      if (this.failClaimOnce) {
        this.failClaimOnce = false;
        return { data: null, error: { message: "transient claim failure" } };
      }
      const messageId = String(args.p_message_id);
      let event = this.events.get(messageId);
      if (!event) {
        event = {
          payload: args.p_payload as Record<string, unknown>,
          processedAt: null,
          processingAt: null,
          processingToken: null,
        };
        this.events.set(messageId, event);
      }
      if (event.processedAt !== null) {
        return { data: { status: "already_processed" }, error: null };
      }
      if (
        event.processingAt !== null &&
        event.processingAt >= this.now() - 10 * 60_000
      ) {
        return { data: { status: "busy" }, error: null };
      }
      const leaseToken = `lease-${++this.nextLease}`;
      event.payload = args.p_payload as Record<string, unknown>;
      event.processingAt = this.now();
      event.processingToken = leaseToken;
      return {
        data: { status: "claimed", lease_token: leaseToken },
        error: null,
      };
    }
    if (name === "apply_verified_play_subscription") {
      this.applyCalls++;
      if (this.failApplyOnce) {
        this.failApplyOnce = false;
        return { data: null, error: { message: "transient apply failure" } };
      }
      const key =
        `${args.p_organization_id}:${args.p_token_hash}:${args.p_status}:${args.p_current_period_end}`;
      if (!this.appliedKeys.has(key)) {
        this.appliedKeys.add(key);
        this.applyCommits++;
      }
      return { data: { result: "APPLIED" }, error: null };
    }
    throw new Error(`unexpected RPC ${name}`);
  }
}

function makeRequest(messageId: string): Request {
  const payload = {
    packageName: "com.aesprt.aquahub",
    eventTimeMillis: "1791691200000",
    subscriptionNotification: {
      version: "1",
      notificationType: 2,
      purchaseToken: "play-token-secret",
      subscriptionId: "aquahub_starter",
    },
  };
  return new Request("https://example.test/functions/v1/play-rtdn", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      message: { data: btoa(JSON.stringify(payload)), messageId },
    }),
  });
}

function deferred<T = void>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function fixture(overrides: Partial<PlayRtdnHandlerDependencies> = {}) {
  let now = 1_791_691_200_000;
  let acknowledged = false;
  let verifyCalls = 0;
  let acknowledgeCalls = 0;
  const admin = new FakeAdmin(() => now);
  const verified = {
    productId: "aquahub_starter",
    basePlanId: "monthly",
    planCode: "STARTER" as const,
    billingCycle: "MONTHLY" as const,
    status: "ACTIVE" as const,
    currentPeriodStart: 1_791_691_200_000,
    currentPeriodEnd: 1_794_283_200_000,
    gracePeriodEndsAt: null,
    autoRenew: true,
    cancelAtPeriodEnd: false,
    acknowledged: false,
  };
  const dependencies: PlayRtdnHandlerDependencies = {
    env: (name) =>
      ({
        SUPABASE_URL: "https://supabase.example.test",
        SUPABASE_SERVICE_ROLE_KEY: "service-role-test-key",
        PLAY_RTDN_PUSH_AUDIENCE:
          "https://supabase.example.test/functions/v1/play-rtdn",
        PLAY_RTDN_PUSH_SERVICE_ACCOUNT: "publisher@example.test",
        GOOGLE_PLAY_PACKAGE_NAME: "com.aesprt.aquahub",
      })[name],
    verifyOidc: async () => {},
    createAdmin: () => admin as never,
    hashToken: () => Promise.resolve("hash:abc123"),
    verifySubscription: () => {
      verifyCalls++;
      return Promise.resolve({ ...verified, acknowledged });
    },
    encryptToken: (token) => Promise.resolve(`encrypted:${token}`),
    acknowledgeSubscription: () => {
      acknowledgeCalls++;
      acknowledged = true;
      return Promise.resolve();
    },
    now: () => now,
  };
  const handler = createPlayRtdnHandler({ ...dependencies, ...overrides });
  return {
    admin,
    handler,
    advance: (milliseconds: number) => {
      now += milliseconds;
    },
    get verifyCalls() {
      return verifyCalls;
    },
    get acknowledgeCalls() {
      return acknowledgeCalls;
    },
  };
}

Deno.test("first delivery is applied and durably finalized before 204", async () => {
  const test = fixture();
  const response = await test.handler(makeRequest("first-delivery"));
  const event = test.admin.events.get("first-delivery");
  assertEquals(response.status, 204);
  assert(event?.processedAt !== null && event?.processedAt !== undefined);
  assertEquals(event?.processingToken, null);
  assertEquals(test.admin.applyCommits, 1);
  assertEquals(test.acknowledgeCalls, 1);
  assert(!JSON.stringify(event?.payload).includes("play-token-secret"));
  assertStringIncludes(JSON.stringify(event?.payload), "hash:abc123");
});

Deno.test("processed duplicate is acknowledged without repeating verification or subscription application", async () => {
  const test = fixture();
  assertEquals((await test.handler(makeRequest("duplicate"))).status, 204);
  assertEquals((await test.handler(makeRequest("duplicate"))).status, 204);
  assertEquals(test.verifyCalls, 1);
  assertEquals(test.admin.applyCalls, 1);
  assertEquals(test.admin.applyCommits, 1);
});

Deno.test("concurrent duplicate receives retryable busy response while first worker owns the lease", async () => {
  const entered = deferred();
  const continueVerification = deferred();
  const test = fixture({
    verifySubscription: async () => {
      entered.resolve();
      await continueVerification.promise;
      return {
        productId: "aquahub_starter",
        basePlanId: "monthly",
        planCode: "STARTER",
        billingCycle: "MONTHLY",
        status: "ACTIVE",
        currentPeriodStart: 1_791_691_200_000,
        currentPeriodEnd: 1_794_283_200_000,
        gracePeriodEndsAt: null,
        autoRenew: true,
        cancelAtPeriodEnd: false,
        acknowledged: true,
      };
    },
  });
  const first = test.handler(makeRequest("concurrent"));
  await entered.promise;
  assertEquals((await test.handler(makeRequest("concurrent"))).status, 503);
  continueVerification.resolve();
  assertEquals((await first).status, 204);
  assertEquals(test.admin.applyCommits, 1);
});

Deno.test("expired lease rotates token and stale worker cannot release the newer claim", async () => {
  const firstEntered = deferred();
  const failFirst = deferred();
  const secondEntered = deferred();
  const continueSecond = deferred();
  let calls = 0;
  const test = fixture({
    verifySubscription: async () => {
      calls++;
      if (calls === 1) {
        firstEntered.resolve();
        await failFirst.promise;
        throw new Error("temporary provider timeout");
      }
      secondEntered.resolve();
      await continueSecond.promise;
      return {
        productId: "aquahub_starter",
        basePlanId: "monthly",
        planCode: "STARTER",
        billingCycle: "MONTHLY",
        status: "ACTIVE",
        currentPeriodStart: 1_791_691_200_000,
        currentPeriodEnd: 1_794_283_200_000,
        gracePeriodEndsAt: null,
        autoRenew: true,
        cancelAtPeriodEnd: false,
        acknowledged: true,
      };
    },
  });

  const staleWorker = test.handler(makeRequest("expired-lease"));
  await firstEntered.promise;
  test.advance(11 * 60_000);
  const currentWorker = test.handler(makeRequest("expired-lease"));
  await secondEntered.promise;
  const event = test.admin.events.get("expired-lease")!;
  const currentLease = event.processingToken;
  assertEquals(currentLease, "lease-2");
  failFirst.resolve();
  assertEquals((await staleWorker).status, 503);
  assertEquals(event.processingToken, currentLease);
  assertEquals(event.processedAt, null);
  continueSecond.resolve();
  assertEquals((await currentWorker).status, 204);
  assertEquals(event.processedAt !== null, true);
});

Deno.test("transient Google verification failure releases its lease and succeeds on retry", async () => {
  let attempts = 0;
  const test = fixture({
    verifySubscription: () => {
      attempts++;
      if (attempts === 1) return Promise.reject(new Error("PLAY_PROVIDER_503"));
      return Promise.resolve({
        productId: "aquahub_starter",
        basePlanId: "monthly",
        planCode: "STARTER",
        billingCycle: "MONTHLY",
        status: "ACTIVE",
        currentPeriodStart: 1_791_691_200_000,
        currentPeriodEnd: 1_794_283_200_000,
        gracePeriodEndsAt: null,
        autoRenew: true,
        cancelAtPeriodEnd: false,
        acknowledged: true,
      });
    },
  });
  assertEquals((await test.handler(makeRequest("provider-retry"))).status, 503);
  assertEquals(test.admin.events.get("provider-retry")?.processingToken, null);
  assertEquals((await test.handler(makeRequest("provider-retry"))).status, 204);
  assertEquals(test.admin.applyCommits, 1);
});

Deno.test("database claim and token lookup failures remain retryable", async () => {
  const claimTest = fixture();
  claimTest.admin.failClaimOnce = true;
  assertEquals(
    (await claimTest.handler(makeRequest("claim-db-error"))).status,
    503,
  );
  assertEquals(
    (await claimTest.handler(makeRequest("claim-db-error"))).status,
    204,
  );

  const lookupTest = fixture();
  lookupTest.admin.failTokenLookupOnce = true;
  assertEquals(
    (await lookupTest.handler(makeRequest("lookup-db-error"))).status,
    503,
  );
  assertEquals(
    lookupTest.admin.events.get("lookup-db-error")?.processingToken,
    null,
  );
  assertEquals(
    (await lookupTest.handler(makeRequest("lookup-db-error"))).status,
    204,
  );
});

Deno.test("Play acknowledgement failure after apply retries without duplicating subscription effects", async () => {
  let acknowledged = false;
  let ackAttempts = 0;
  const test = fixture({
    verifySubscription: () =>
      Promise.resolve({
        productId: "aquahub_starter",
        basePlanId: "monthly",
        planCode: "STARTER",
        billingCycle: "MONTHLY",
        status: "ACTIVE",
        currentPeriodStart: 1_791_691_200_000,
        currentPeriodEnd: 1_794_283_200_000,
        gracePeriodEndsAt: null,
        autoRenew: true,
        cancelAtPeriodEnd: false,
        acknowledged,
      }),
    acknowledgeSubscription: () => {
      ackAttempts++;
      if (ackAttempts === 1) {
        return Promise.reject(new Error("PLAY_PROVIDER_503"));
      }
      acknowledged = true;
      return Promise.resolve();
    },
  });
  assertEquals((await test.handler(makeRequest("ack-retry"))).status, 503);
  assertEquals(test.admin.applyCommits, 1);
  assertEquals(test.admin.events.get("ack-retry")?.processedAt, null);
  assertEquals((await test.handler(makeRequest("ack-retry"))).status, 204);
  assertEquals(test.admin.applyCalls, 2);
  assertEquals(test.admin.applyCommits, 1);
  assertEquals(ackAttempts, 2);
});

Deno.test("event completion failure after apply and acknowledgement retries idempotently", async () => {
  const test = fixture();
  test.admin.failFinalizeOnce = true;
  assertEquals(
    (await test.handler(makeRequest("completion-retry"))).status,
    503,
  );
  assertEquals(test.admin.applyCommits, 1);
  assertEquals(test.admin.events.get("completion-retry")?.processedAt, null);
  assertEquals(
    (await test.handler(makeRequest("completion-retry"))).status,
    204,
  );
  assertEquals(test.admin.applyCalls, 2);
  assertEquals(test.admin.applyCommits, 1);
  assertEquals(
    test.admin.events.get("completion-retry")?.processedAt !== null,
    true,
  );
  assertEquals(test.acknowledgeCalls, 1);
});

Deno.test("subscription persistence database failure retries without acknowledging", async () => {
  const test = fixture();
  test.admin.failApplyOnce = true;
  assertEquals((await test.handler(makeRequest("apply-db-error"))).status, 503);
  assertEquals(test.acknowledgeCalls, 0);
  assertEquals(test.admin.events.get("apply-db-error")?.processedAt, null);
  assertEquals((await test.handler(makeRequest("apply-db-error"))).status, 204);
  assertEquals(test.admin.applyCommits, 1);
  assertEquals(test.acknowledgeCalls, 1);
});
