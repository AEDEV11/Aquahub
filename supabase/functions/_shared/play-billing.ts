export const PLAY_PACKAGE_NAME = () =>
  Deno.env.get("GOOGLE_PLAY_PACKAGE_NAME")?.trim() || "com.aesprt.aquahub";

export type PlayPlan = {
  productId: string;
  basePlanId: string;
  planCode: "STARTER" | "BUSINESS" | "PRO";
  billingCycle: "MONTHLY" | "ANNUAL";
};

export const PLAY_PLANS: Record<string, PlayPlan> = {
  "aquahub_starter:monthly": {
    productId: "aquahub_starter",
    basePlanId: "monthly",
    planCode: "STARTER",
    billingCycle: "MONTHLY",
  },
  "aquahub_starter:yearly": {
    productId: "aquahub_starter",
    basePlanId: "yearly",
    planCode: "STARTER",
    billingCycle: "ANNUAL",
  },
  "aquahub_business:monthly": {
    productId: "aquahub_business",
    basePlanId: "monthly",
    planCode: "BUSINESS",
    billingCycle: "MONTHLY",
  },
  "aquahub_business:yearly": {
    productId: "aquahub_business",
    basePlanId: "yearly",
    planCode: "BUSINESS",
    billingCycle: "ANNUAL",
  },
  "aquahub_pro:monthly": {
    productId: "aquahub_pro",
    basePlanId: "monthly",
    planCode: "PRO",
    billingCycle: "MONTHLY",
  },
  "aquahub_pro:yearly": {
    productId: "aquahub_pro",
    basePlanId: "yearly",
    planCode: "PRO",
    billingCycle: "ANNUAL",
  },
};

export type VerifiedPlaySubscription = {
  productId: string;
  basePlanId: string;
  planCode: PlayPlan["planCode"];
  billingCycle: PlayPlan["billingCycle"];
  status:
    | "ACTIVE"
    | "ACTIVE_UNTIL_PERIOD_END"
    | "GRACE_PERIOD"
    | "SUSPENDED"
    | "EXPIRED"
    | "PENDING"
    | "PENDING_PURCHASE_CANCELED";
  currentPeriodStart: number;
  currentPeriodEnd: number;
  gracePeriodEndsAt: number | null;
  obfuscatedExternalAccountId?: string;
  obfuscatedExternalProfileId?: string;
  linkedPurchaseToken?: string;
  outOfAppExpiredPurchaseToken?: string;
  autoRenew: boolean;
  cancelAtPeriodEnd: boolean;
  acknowledged: boolean;
};

export type PlayResponse = {
  subscriptionState?: string;
  acknowledgementState?: string;
  startTime?: string;
  linkedPurchaseToken?: string;
  externalAccountIdentifiers?: {
    obfuscatedExternalAccountId?: string;
    obfuscatedExternalProfileId?: string;
  };
  outOfAppPurchaseContext?: {
    expiredPurchaseToken?: string;
    expiredExternalAccountIdentifiers?: {
      obfuscatedExternalAccountId?: string;
      obfuscatedExternalProfileId?: string;
    };
  };
  lineItems?: Array<{
    productId?: string;
    expiryTime?: string;
    offerDetails?: { basePlanId?: string };
    autoRenewingPlan?: { autoRenewEnabled?: boolean };
  }>;
};

function base64Url(value: Uint8Array | string): string {
  const bytes = typeof value === "string"
    ? new TextEncoder().encode(value)
    : value;
  let binary = "";
  bytes.forEach((byte) => binary += String.fromCharCode(byte));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(
    /=+$/g,
    "",
  );
}

function pemToBytes(pem: string): Uint8Array {
  const raw = atob(
    pem.replace(
      /-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g,
      "",
    ),
  );
  return Uint8Array.from(raw, (char) => char.charCodeAt(0));
}

function ownedArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(`aquahub-play-token-v1:${value}`),
  );
  return [...new Uint8Array(digest)].map((byte) =>
    byte.toString(16).padStart(2, "0")
  ).join("");
}

async function googleAccessToken(): Promise<string> {
  const email = Deno.env.get("GOOGLE_PLAY_CLIENT_EMAIL")?.trim();
  const privateKey = Deno.env.get("GOOGLE_PLAY_PRIVATE_KEY")?.replace(
    /\\n/g,
    "\n",
  );
  if (!email || !privateKey) throw new Error("PLAY_BILLING_NOT_CONFIGURED");
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = base64Url(JSON.stringify({
    iss: email,
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    ownedArrayBuffer(pemToBytes(privateKey)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(`${header}.${claim}`),
  );
  const response = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claim}.${base64Url(new Uint8Array(signature))}`,
    }),
  });
  if (!response.ok) throw new Error("PLAY_BILLING_AUTH_FAILED");
  const body = await response.json() as { access_token?: string };
  if (!body.access_token) throw new Error("PLAY_BILLING_AUTH_FAILED");
  return body.access_token;
}

async function publisherRequest(
  path: string,
  init?: RequestInit,
): Promise<Response> {
  const token = await googleAccessToken();
  const response = await fetch(
    `https://androidpublisher.googleapis.com/androidpublisher/v3${path}`,
    {
      ...init,
      headers: {
        authorization: `Bearer ${token}`,
        "content-type": "application/json",
      },
    },
  );
  if (!response.ok) throw new Error(`PLAY_PROVIDER_${response.status}`);
  return response;
}

function millis(value: string | undefined, field: string): number {
  const result = value ? Date.parse(value) : NaN;
  if (!Number.isFinite(result)) {
    throw new Error(`PLAY_INVALID_${field.toUpperCase()}`);
  }
  return result;
}

export function mapPlaySubscriptionStatus(
  state: string | undefined,
  expiry: number,
  now = Date.now(),
): VerifiedPlaySubscription["status"] {
  if (state === "SUBSCRIPTION_STATE_PENDING") return "PENDING";
  if (state === "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED") {
    return "PENDING_PURCHASE_CANCELED";
  }
  if (expiry <= now || state === "SUBSCRIPTION_STATE_EXPIRED") return "EXPIRED";
  if (state === "SUBSCRIPTION_STATE_ACTIVE") return "ACTIVE";
  if (state === "SUBSCRIPTION_STATE_CANCELED") return "ACTIVE_UNTIL_PERIOD_END";
  if (state === "SUBSCRIPTION_STATE_IN_GRACE_PERIOD") return "GRACE_PERIOD";
  return "SUSPENDED";
}

export function mapPlaySubscriptionResponse(
  response: PlayResponse,
  productId: string,
  now = Date.now(),
): VerifiedPlaySubscription {
  const lineItem = response.lineItems?.find((item) =>
    item.productId === productId
  );
  const basePlanId = lineItem?.offerDetails?.basePlanId ?? "";
  const plan = PLAY_PLANS[`${productId}:${basePlanId}`];
  if (response.subscriptionState === "SUBSCRIPTION_STATE_PENDING") {
    throw new Error("PLAY_PURCHASE_PENDING");
  }
  if (
    response.subscriptionState ===
      "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED"
  ) throw new Error("PLAY_PENDING_PURCHASE_CANCELED");
  if (!lineItem || !plan || !lineItem.expiryTime) {
    throw new Error("PURCHASE_NOT_ALLOWED");
  }
  const expiredPurchaseToken = response.outOfAppPurchaseContext
    ?.expiredPurchaseToken;
  if (
    expiredPurchaseToken && response.linkedPurchaseToken &&
    expiredPurchaseToken !== response.linkedPurchaseToken
  ) throw new Error("PURCHASE_NOT_ALLOWED");
  const currentPeriodEnd = millis(lineItem.expiryTime, "expiry");
  const verifiedStatus = mapPlaySubscriptionStatus(
    response.subscriptionState,
    currentPeriodEnd,
    now,
  );
  return {
    productId,
    basePlanId,
    planCode: plan.planCode,
    billingCycle: plan.billingCycle,
    status: verifiedStatus,
    currentPeriodStart: response.startTime
      ? millis(response.startTime, "start")
      : now,
    currentPeriodEnd,
    gracePeriodEndsAt: verifiedStatus === "GRACE_PERIOD"
      ? currentPeriodEnd
      : null,
    obfuscatedExternalAccountId: response.externalAccountIdentifiers
      ?.obfuscatedExternalAccountId ?? response.outOfAppPurchaseContext
      ?.expiredExternalAccountIdentifiers?.obfuscatedExternalAccountId,
    obfuscatedExternalProfileId: response.externalAccountIdentifiers
      ?.obfuscatedExternalProfileId ?? response.outOfAppPurchaseContext
      ?.expiredExternalAccountIdentifiers?.obfuscatedExternalProfileId,
    linkedPurchaseToken: response.linkedPurchaseToken,
    outOfAppExpiredPurchaseToken: expiredPurchaseToken,
    autoRenew: lineItem.autoRenewingPlan?.autoRenewEnabled === true,
    cancelAtPeriodEnd:
      response.subscriptionState === "SUBSCRIPTION_STATE_CANCELED",
    acknowledged:
      response.acknowledgementState === "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
  };
}

export async function verifyPlaySubscription(
  productId: string,
  purchaseToken: string,
): Promise<VerifiedPlaySubscription> {
  if (
    !/^[a-z0-9_]{1,100}$/.test(productId) || purchaseToken.length < 20 ||
    purchaseToken.length > 4096
  ) {
    throw new Error("INVALID_PURCHASE");
  }
  const response = await (await publisherRequest(
    `/applications/${
      encodeURIComponent(PLAY_PACKAGE_NAME())
    }/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`,
  )).json() as PlayResponse;
  if (response.subscriptionState === "SUBSCRIPTION_STATE_PENDING") {
    throw new Error("PLAY_PURCHASE_PENDING");
  }
  if (
    response.subscriptionState ===
      "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED"
  ) throw new Error("PLAY_PENDING_PURCHASE_CANCELED");
  return mapPlaySubscriptionResponse(response, productId);
}

export async function acknowledgePlaySubscription(
  productId: string,
  purchaseToken: string,
): Promise<void> {
  await publisherRequest(
    `/applications/${
      encodeURIComponent(PLAY_PACKAGE_NAME())
    }/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${
      encodeURIComponent(purchaseToken)
    }:acknowledge`,
    { method: "POST", body: "{}" },
  );
}
