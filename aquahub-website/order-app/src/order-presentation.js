export const CONTAINER_SOURCE_OPTIONS = [
  {
    value: "STATION_EXCHANGE",
    title: "Exchange an empty container",
    description: "Swap an empty bottle for a sanitized filled one.",
  },
  {
    value: "CUSTOMER_OWNED",
    title: "I have my own container",
    description: "Use your clean container for this refill.",
  },
  {
    value: "STATION_BORROW",
    title: "Borrow a station container",
    description: "Return the station container on your next refill.",
  },
];

const PICKUP_STEPS = [
  ["PENDING", "Received"],
  ["ACCEPTED", "Accepted"],
  ["PREPARING", "Preparing"],
  ["READY_FOR_PICKUP", "Pickup"],
  ["COMPLETED", "Complete"],
];

const DELIVERY_STEPS = [
  ["PENDING", "Received"],
  ["ACCEPTED", "Accepted"],
  ["PREPARING", "Preparing"],
  ["READY_FOR_PICKUP", "Ready"],
  ["RIDER_ASSIGNED", "Rider"],
  ["OUT_FOR_DELIVERY", "On the way"],
  ["DELIVERED", "Delivered"],
  ["COMPLETED", "Complete"],
];

export function trackingProgress(order = {}) {
  const steps = order.fulfillmentMode === "PICKUP" ? PICKUP_STEPS : DELIVERY_STEPS;
  const index = steps.findIndex(([status]) => status === order.status);
  const activeIndex = index < 0 ? 0 : index;
  return {
    steps,
    activeIndex,
    progressPercent: steps.length < 2 ? 0 : activeIndex / (steps.length - 1) * 100,
  };
}

export function mergeOrderHistory(orders, order, limit = 10) {
  if (!order?.id || !order?.token) return orders.slice(0, limit);
  const existingIndex = orders.findIndex((saved) => saved?.id === order.id);
  if (existingIndex >= 0) {
    const updated = orders.slice();
    updated[existingIndex] = order;
    return updated.slice(0, limit);
  }
  return [order, ...orders].slice(0, limit);
}
