export type MarketingDispatchOptions = {
  batchSize: number;
  subscriberId: string | null;
};

export type MarketingDispatchOptionsResult =
  | { valid: true; value: MarketingDispatchOptions }
  | { valid: false };

const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function parseMarketingDispatchOptions(
  input: unknown,
): MarketingDispatchOptionsResult {
  if (!input || typeof input !== "object" || Array.isArray(input)) {
    return { valid: false };
  }
  const body = input as Record<string, unknown>;
  let subscriberId: string | null = null;
  if (Object.hasOwn(body, "subscriberId")) {
    if (
      typeof body.subscriberId !== "string" ||
      !uuidPattern.test(body.subscriberId)
    ) {
      return { valid: false };
    }
    subscriberId = body.subscriberId;
  }
  const requestedBatch = Number.isInteger(body.batchSize)
    ? body.batchSize as number
    : 10;
  return {
    valid: true,
    value: {
      batchSize: subscriberId ? 1 : Math.max(1, Math.min(20, requestedBatch)),
      subscriberId,
    },
  };
}
