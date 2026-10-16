import assert from "node:assert/strict";
import test from "node:test";
import {
  calculateCartSubtotal,
  confirmedOrderingAvailability,
  deliveryLocationError,
  effectiveProductPrice,
  guestPaymentMethods,
  normalizePhilippineMobile,
} from "../src/order-rules.js";

test("station ordering requires a matching, affirmative public eligibility row", () => {
  assert.equal(confirmedOrderingAvailability("station-a", [{ station_id: "station-a", is_ordering_available: true }]), true);
  assert.equal(confirmedOrderingAvailability("station-a", [{ station_id: "station-b", is_ordering_available: true }]), false);
  assert.equal(confirmedOrderingAvailability("station-a", [{ station_id: "station-a", is_ordering_available: false }]), false);
  assert.equal(confirmedOrderingAvailability("station-a", [{ station_id: "station-a", is_ordering_available: "true" }]), false);
  assert.equal(confirmedOrderingAvailability("station-a", null), false);
});

test("normalizes Philippine mobile numbers into the canonical E.164 format", () => {
  assert.equal(normalizePhilippineMobile("0917 123 4567"), "+639171234567");
  assert.equal(normalizePhilippineMobile("+63 917-123-4567"), "+639171234567");
  assert.equal(normalizePhilippineMobile("639171234567"), "+639171234567");
  assert.equal(normalizePhilippineMobile("9 171 234 567"), "+639171234567");
});

test("rejects malformed and non-Philippine mobile numbers", () => {
  assert.equal(normalizePhilippineMobile("0917 123 456"), null);
  assert.equal(normalizePhilippineMobile("+1 415 555 0100"), null);
  assert.equal(normalizePhilippineMobile("landline 02 8123 4567"), null);
  assert.equal(normalizePhilippineMobile("+63 0917 123 4567"), null);
  assert.equal(normalizePhilippineMobile("0917abc1234"), null);
  assert.equal(normalizePhilippineMobile("++63 917 123 4567"), null);
  assert.equal(normalizePhilippineMobile(null), null);
});

test("applies fixed and percentage promotions using integer centavos", () => {
  const fixed = { priceCentavos: 5000, promotionIsActive: true, promotionType: "FIXED_PRICE", promotionalPriceCentavos: 4200 };
  const percent = { priceCentavos: 10001, promotionIsActive: true, promotionType: "PERCENTAGE", promotionPercentBps: 1250 };
  assert.equal(effectiveProductPrice(fixed, 1, 1_000), 4200);
  assert.equal(effectiveProductPrice(percent, 1, 1_000), 8750);
});

test("quantity promotions respect minimum quantity and active time window", () => {
  const product = {
    priceCentavos: 12000,
    promotionalPriceCentavos: 9500,
    promotionIsActive: true,
    promotionType: "QUANTITY_BREAK",
    promotionMinimumQuantity: 3,
    promotionStartsAt: 1_000,
    promotionEndsAt: 9_000,
  };
  assert.equal(effectiveProductPrice(product, 2, 5_000), 12000);
  assert.equal(effectiveProductPrice(product, 3, 5_000), 9500);
  assert.equal(effectiveProductPrice(product, 3, 10_000), 12000);
});

test("payment choices contain only station-enabled methods and never invent cash", () => {
  const station = { cashPaymentEnabled: false, codPaymentEnabled: true, gcashPaymentEnabled: false, mayaPaymentEnabled: true };
  assert.deepEqual(guestPaymentMethods(station, "PICKUP").map((method) => method.value), ["MAYA"]);
  assert.deepEqual(guestPaymentMethods(station, "DELIVERY").map((method) => method.value), ["COD", "MAYA"]);
  assert.deepEqual(guestPaymentMethods({ ...station, mayaPaymentEnabled: false }, "PICKUP"), []);
});

test("cart totals use discounted per-unit prices and integer centavos", () => {
  const lines = [
    { product: { priceCentavos: 5000, promotionIsActive: true, promotionType: "FIXED_PRICE", promotionalPriceCentavos: 4200 }, quantity: 2 },
    { product: { priceCentavos: 1250 }, quantity: 1 },
  ];
  assert.equal(calculateCartSubtotal(lines, 1_000), 9650);
});

test("delivery location validation requires a verified pin only when radius rules need it", () => {
  const base = { mode: "DELIVERY", radiusKm: 5, address: "Barangay 1, City" };
  assert.match(deliveryLocationError(base), /map pin/);
  assert.equal(deliveryLocationError({ ...base, placeId: "place-123", latitude: 8.48, longitude: 124.65 }), null);
  assert.match(deliveryLocationError({ ...base, placeId: "place-123", latitude: 8.48, longitude: null }), /map pin/);
  assert.equal(deliveryLocationError({ ...base, radiusKm: 0 }), null);
  assert.equal(deliveryLocationError({ mode: "PICKUP", radiusKm: 5, address: "" }), null);
});
