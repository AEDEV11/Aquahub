const safeMessages = [
  "this station link is unavailable",
  "station is not accepting online orders",
  "station unavailable",
  "delivery address is required",
  "share your delivery location so the station can confirm its delivery area",
  "delivery address is outside this station’s service area",
  "station delivery settings are unavailable",
  "station delivery location is unavailable",
  "cash payment is not available at this station",
  "cash on delivery is not available for this order",
  "GCash is not available at this station",
  "Maya is not available at this station",
  "select a payment method supported by this station",
  "choose an available payment method",
  "enter your name",
  "enter a valid Philippine mobile number",
  "enter your delivery address",
  "your cart must contain 1 to 20 products",
  "product is unavailable",
  "invalid order quantity",
  "insufficient inventory",
  "duplicate products are not allowed",
  "station ordering rules changed; review and accept them before placing your order",
  "this retry key was already used for a different order request",
  "container option is invalid",
  "container option is only available for refills",
];

const validationMessages = [
  "this station link is unavailable",
  "station is not accepting online orders",
  "station unavailable",
  "delivery address is required",
  "share your delivery location",
  "delivery address is outside this station’s service area",
  "station delivery settings are unavailable",
  "station delivery location is unavailable",
  "cash payment is not available",
  "cash on delivery is not available",
  "GCash is not available",
  "Maya is not available",
  "select a payment method supported",
  "choose an available payment method",
  "enter your name",
  "enter a valid Philippine mobile number",
  "enter your delivery address",
  "your cart must contain",
  "product is unavailable",
  "invalid order quantity",
  "insufficient inventory",
  "duplicate products are not allowed",
  "station ordering rules changed",
  "this retry key was already used",
  "container option",
];

const genericFailure =
  "We couldn’t submit the order. Your details are still here; please review and try again.";

export function safeGuestOrderDatabaseMessage(
  message: string | undefined,
): string {
  const match = safeMessages.find((candidate) => message?.includes(candidate));
  if (message?.includes("inventory change would consume reserved stock")) {
    return "This item is no longer available in the requested quantity. Refresh your cart before trying again.";
  }
  return match ?? genericFailure;
}

export function classifyGuestOrderDatabaseError(message: string | undefined): {
  status: 409 | 422 | 503;
  message: string;
} {
  if (message?.includes("inventory change would consume reserved stock")) {
    return {
      status: 409,
      message:
        "This item is no longer available in the requested quantity. Refresh your cart before trying again.",
    };
  }
  const validation = validationMessages.some((candidate) =>
    message?.includes(candidate)
  );
  return {
    status: validation ? 422 : 503,
    message: safeGuestOrderDatabaseMessage(message),
  };
}
