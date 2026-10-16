import {
  acknowledgePlaySubscription,
  mapPlaySubscriptionResponse,
  mapPlaySubscriptionStatus,
  type PlayResponse,
  verifyPlaySubscription,
} from "./play-billing.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) {
    throw new Error(`Expected ${String(expected)}; received ${String(actual)}`);
  }
}

function assertThrows(operation: () => unknown, message: string): void {
  try {
    operation();
  } catch (error) {
    if (error instanceof Error && error.message === message) return;
    throw error;
  }
  throw new Error(`Expected ${message}`);
}

async function assertRejects(
  operation: () => Promise<unknown>,
  message: string,
): Promise<void> {
  try {
    await operation();
  } catch (error) {
    if (error instanceof Error && error.message === message) return;
    throw error;
  }
  throw new Error(`Expected ${message}`);
}

let syntheticPrivateKeyPromise: Promise<string> | undefined;
function syntheticPrivateKey(): Promise<string> {
  return syntheticPrivateKeyPromise ??= (async () => {
    const pair = await crypto.subtle.generateKey(
      {
        name: "RSASSA-PKCS1-v1_5",
        modulusLength: 2048,
        publicExponent: new Uint8Array([1, 0, 1]),
        hash: "SHA-256",
      },
      true,
      ["sign", "verify"],
    );
    const bytes = new Uint8Array(
      await crypto.subtle.exportKey("pkcs8", pair.privateKey),
    );
    return `-----BEGIN PRIVATE KEY-----\n${
      btoa(Array.from(bytes, (byte) => String.fromCharCode(byte)).join(""))
    }\n-----END PRIVATE KEY-----`;
  })();
}

async function withMockPlayPublisher(
  publisherFetch: (url: string, init?: RequestInit) => Response,
  operation: () => Promise<void>,
): Promise<void> {
  const originalFetch = globalThis.fetch;
  const originalEmail = Deno.env.get("GOOGLE_PLAY_CLIENT_EMAIL");
  const originalKey = Deno.env.get("GOOGLE_PLAY_PRIVATE_KEY");
  const originalPackage = Deno.env.get("GOOGLE_PLAY_PACKAGE_NAME");
  Deno.env.set("GOOGLE_PLAY_CLIENT_EMAIL", "publisher-test@example.test");
  Deno.env.set("GOOGLE_PLAY_PRIVATE_KEY", await syntheticPrivateKey());
  Deno.env.set("GOOGLE_PLAY_PACKAGE_NAME", "com.aesprt.aquahub.test");
  let publisherCalls = 0;
  globalThis.fetch = (input, init) => {
    const url = String(input);
    if (url === "https://oauth2.googleapis.com/token") {
      return Promise.resolve(
        Response.json({ access_token: "synthetic-access-token" }),
      );
    }
    if (
      url.startsWith(
        "https://androidpublisher.googleapis.com/androidpublisher/v3/",
      )
    ) {
      publisherCalls++;
      return Promise.resolve(publisherFetch(url, init));
    }
    throw new Error(`Unexpected test URL: ${url}`);
  };
  try {
    await operation();
    assertEquals(publisherCalls, 1);
  } finally {
    globalThis.fetch = originalFetch;
    if (originalEmail === undefined) {
      Deno.env.delete("GOOGLE_PLAY_CLIENT_EMAIL");
    } else Deno.env.set("GOOGLE_PLAY_CLIENT_EMAIL", originalEmail);
    if (originalKey === undefined) Deno.env.delete("GOOGLE_PLAY_PRIVATE_KEY");
    else Deno.env.set("GOOGLE_PLAY_PRIVATE_KEY", originalKey);
    if (originalPackage === undefined) {
      Deno.env.delete("GOOGLE_PLAY_PACKAGE_NAME");
    } else Deno.env.set("GOOGLE_PLAY_PACKAGE_NAME", originalPackage);
  }
}

Deno.test("canceled subscription retains access only until the provider expiry", () => {
  const expiry = Date.parse("2026-11-09T00:00:00Z");
  assertEquals(
    mapPlaySubscriptionStatus(
      "SUBSCRIPTION_STATE_CANCELED",
      expiry,
      expiry - 1,
    ),
    "ACTIVE_UNTIL_PERIOD_END",
  );
  assertEquals(
    mapPlaySubscriptionStatus("SUBSCRIPTION_STATE_CANCELED", expiry, expiry),
    "EXPIRED",
  );
});

Deno.test("Google grace expiry is carried as the exact entitlement cutoff", () => {
  const expiry = "2026-10-20T12:00:00Z";
  const response: PlayResponse = {
    subscriptionState: "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
    lineItems: [{
      productId: "aquahub_business",
      expiryTime: expiry,
      offerDetails: { basePlanId: "monthly" },
    }],
  };
  const verified = mapPlaySubscriptionResponse(
    response,
    "aquahub_business",
    Date.parse("2026-10-10T12:00:00Z"),
  );
  assertEquals(verified.status, "GRACE_PERIOD");
  assertEquals(verified.gracePeriodEndsAt, Date.parse(expiry));
});

Deno.test("pending and canceled-pending payments never map to paid access", () => {
  const expiry = Date.parse("2026-11-01T00:00:00Z");
  assertEquals(
    mapPlaySubscriptionStatus("SUBSCRIPTION_STATE_PENDING", expiry),
    "PENDING",
  );
  assertEquals(
    mapPlaySubscriptionStatus(
      "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED",
      expiry,
    ),
    "PENDING_PURCHASE_CANCELED",
  );
  assertEquals(
    mapPlaySubscriptionStatus("SUBSCRIPTION_STATE_ON_HOLD", expiry),
    "SUSPENDED",
  );
  assertEquals(
    mapPlaySubscriptionStatus("SUBSCRIPTION_STATE_PAUSED", expiry),
    "SUSPENDED",
  );
});

Deno.test("unrecognized base plans are rejected instead of inferred from their period", () => {
  const response: PlayResponse = {
    subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    lineItems: [{
      productId: "aquahub_starter",
      expiryTime: "2026-11-01T00:00:00Z",
      offerDetails: { basePlanId: "base" },
    }],
  };
  assertThrows(
    () => mapPlaySubscriptionResponse(response, "aquahub_starter"),
    "PURCHASE_NOT_ALLOWED",
  );
});

Deno.test("linked purchase token is preserved for atomic replacement handling", () => {
  const response: PlayResponse = {
    subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    linkedPurchaseToken: "previous-token",
    externalAccountIdentifiers: {
      obfuscatedExternalProfileId: "organization-hash",
    },
    lineItems: [{
      productId: "aquahub_pro",
      expiryTime: "2026-11-01T00:00:00Z",
      offerDetails: { basePlanId: "yearly" },
    }],
  };
  const verified = mapPlaySubscriptionResponse(response, "aquahub_pro");
  assertEquals(verified.linkedPurchaseToken, "previous-token");
  assertEquals(verified.obfuscatedExternalProfileId, "organization-hash");
  assertEquals(verified.billingCycle, "ANNUAL");
});

Deno.test("Play Store resubscription preserves the verified expired predecessor and account IDs", () => {
  const response: PlayResponse = {
    subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
    outOfAppPurchaseContext: {
      expiredPurchaseToken: "expired-predecessor-token",
      expiredExternalAccountIdentifiers: {
        obfuscatedExternalAccountId: "previous-account-hash",
        obfuscatedExternalProfileId: "previous-organization-hash",
      },
    },
    lineItems: [{
      productId: "aquahub_starter",
      expiryTime: "2099-11-01T00:00:00Z",
      offerDetails: { basePlanId: "monthly" },
    }],
  };
  const verified = mapPlaySubscriptionResponse(response, "aquahub_starter");
  assertEquals(verified.outOfAppExpiredPurchaseToken, "expired-predecessor-token");
  assertEquals(verified.obfuscatedExternalAccountId, "previous-account-hash");
  assertEquals(verified.obfuscatedExternalProfileId, "previous-organization-hash");
  assertEquals(verified.linkedPurchaseToken, undefined);
  assertThrows(
    () => mapPlaySubscriptionResponse({
      ...response,
      linkedPurchaseToken: "different-token",
    }, "aquahub_starter"),
    "PURCHASE_NOT_ALLOWED",
  );
});

Deno.test("Google Play acknowledgement accepts empty 200 and 204 success responses", async () => {
  for (const status of [200, 204]) {
    await withMockPlayPublisher((url, init) => {
      assertEquals(init?.method, "POST");
      assertEquals(
        url.endsWith("/tokens/synthetic-purchase-token:acknowledge"),
        true,
      );
      return new Response(null, { status });
    }, async () => {
      await acknowledgePlaySubscription(
        "aquahub_starter",
        "synthetic-purchase-token",
      );
    });
  }
});

Deno.test("Google Play acknowledgement rejects non-success provider responses without parsing their body", async () => {
  await withMockPlayPublisher(
    () =>
      Response.json({ error: "provider detail must not escape" }, {
        status: 403,
      }),
    () =>
      assertRejects(
        () =>
          acknowledgePlaySubscription(
            "aquahub_starter",
            "synthetic-purchase-token",
          ),
        "PLAY_PROVIDER_403",
      ),
  );
});

Deno.test("subscriptionsv2 verification still parses the provider JSON response", async () => {
  await withMockPlayPublisher((url, init) => {
    assertEquals(init?.method, undefined);
    assertEquals(
      url.endsWith(
        "/purchases/subscriptionsv2/tokens/synthetic-purchase-token",
      ),
      true,
    );
    return Response.json({
      subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
      acknowledgementState: "ACKNOWLEDGEMENT_STATE_PENDING",
      lineItems: [{
        productId: "aquahub_starter",
        expiryTime: "2099-01-01T00:00:00Z",
        offerDetails: { basePlanId: "monthly" },
      }],
    });
  }, async () => {
    const verified = await verifyPlaySubscription(
      "aquahub_starter",
      "synthetic-purchase-token",
    );
    assertEquals(verified.planCode, "STARTER");
    assertEquals(verified.acknowledged, false);
  });
});
