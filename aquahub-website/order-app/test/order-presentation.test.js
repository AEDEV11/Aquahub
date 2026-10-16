import test from "node:test";
import assert from "node:assert/strict";
import { CONTAINER_SOURCE_OPTIONS, mergeOrderHistory, trackingProgress } from "../src/order-presentation.js";

test("offers the same refill container choices as the customer app", () => {
  assert.deepEqual(CONTAINER_SOURCE_OPTIONS.map(({ value }) => value), [
    "STATION_EXCHANGE", "CUSTOMER_OWNED", "STATION_BORROW",
  ]);
});

test("keeps earlier tracking capabilities when a newer order is created", () => {
  const first = { id: "first", token: "first-token" };
  const second = { id: "second", token: "second-token" };
  assert.deepEqual(mergeOrderHistory([first], second), [second, first]);
  assert.deepEqual(mergeOrderHistory([second, first], { ...first, status: "ACCEPTED" }), [
    second,
    { ...first, status: "ACCEPTED" },
  ]);
});

test("limits session order history and drops the oldest entry", () => {
  const orders = Array.from({ length: 3 }, (_, index) => ({ id: `order-${index}`, token: `token-${index}` }));
  assert.deepEqual(mergeOrderHistory(orders, { id: "new", token: "new-token" }, 3).map(({ id }) => id), [
    "new", "order-0", "order-1",
  ]);
});

test("maps pickup and delivery status to the right progress milestone", () => {
  const pickup = trackingProgress({ status: "READY_FOR_PICKUP", fulfillmentMode: "PICKUP" });
  assert.equal(pickup.steps.length, 5);
  assert.equal(pickup.activeIndex, 3);
  assert.equal(pickup.progressPercent, 75);

  const readyForDelivery = trackingProgress({ status: "READY_FOR_PICKUP", fulfillmentMode: "DELIVERY" });
  assert.equal(readyForDelivery.activeIndex, 3);
  assert.equal(readyForDelivery.steps[readyForDelivery.activeIndex][0], "READY_FOR_PICKUP");

  const delivery = trackingProgress({ status: "OUT_FOR_DELIVERY", fulfillmentMode: "DELIVERY" });
  assert.equal(delivery.steps.length, 8);
  assert.equal(delivery.activeIndex, 5);
  assert.equal(delivery.progressPercent, 5 / 7 * 100);
});
