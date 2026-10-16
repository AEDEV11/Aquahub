export function normalizePhilippineMobile(value) {
  if (typeof value !== "string") return null;
  const input = value.trim();
  if (!input || /[^\d+\s().-]/.test(input) || (input.match(/\+/g) || []).length > 1) return null;
  let digits = input.replace(/[\s().-]/g, "");
  const hasCountryCode = digits.startsWith("+63") || digits.startsWith("63");
  if (digits.startsWith("+63")) digits = digits.slice(3);
  else if (digits.startsWith("63")) digits = digits.slice(2);
  if (!hasCountryCode && digits.startsWith("0")) digits = digits.slice(1);
  return /^9\d{9}$/.test(digits) ? `+63${digits}` : null;
}

export function confirmedOrderingAvailability(stationId, rows) {
  return Array.isArray(rows) && rows.some((row) =>
    row?.station_id === stationId && row.is_ordering_available === true);
}

export function effectiveProductPrice(product, quantity = 1, now = Date.now()) {
  const price = Number(product?.priceCentavos || 0);
  const minimum = Math.max(1, Number(product?.promotionMinimumQuantity || 1));
  if (!product?.promotionIsActive
    || (product.promotionStartsAt != null && Number(product.promotionStartsAt) > now)
    || (product.promotionEndsAt != null && Number(product.promotionEndsAt) < now)
    || quantity < minimum) return price;
  if (["FIXED_PRICE", "QUANTITY_BREAK"].includes(product.promotionType) && product.promotionalPriceCentavos != null) {
    return Math.min(price, Number(product.promotionalPriceCentavos));
  }
  if (product.promotionType === "PERCENTAGE" && Number(product.promotionPercentBps) > 0) {
    return Math.floor(price * (10_000 - Number(product.promotionPercentBps)) / 10_000);
  }
  return price;
}

export function guestPaymentMethods(station, mode = "PICKUP") {
  if (!station) return [];
  const options = [];
  if (station.cashPaymentEnabled) options.push({
    value: "CASH",
    label: mode === "DELIVERY" ? "Cash" : "Cash at pickup",
    detail: "Pay the station when your order is handed over.",
  });
  if (mode === "DELIVERY" && station.codPaymentEnabled) options.push({
    value: "COD",
    label: "Cash on delivery",
    detail: "Pay the rider when your water arrives.",
  });
  if (station.gcashPaymentEnabled) options.push({
    value: "GCASH",
    label: "GCash",
    detail: paymentDetail(station.gcashAccountName, station.gcashAccountNumber),
  });
  if (station.mayaPaymentEnabled) options.push({
    value: "MAYA",
    label: "Maya",
    detail: paymentDetail(station.mayaAccountName, station.mayaAccountNumber),
  });
  return options;
}

export function deliveryLocationError({ mode, radiusKm, address, placeId, latitude, longitude }) {
  if (mode !== "DELIVERY") return null;
  if (typeof address !== "string" || address.trim().length < 8) return "Enter your complete delivery address, including barangay and city.";
  const hasAnyPinData = Boolean(placeId) || latitude != null || longitude != null;
  const validCoordinates = Number.isFinite(latitude) && latitude >= -90 && latitude <= 90
    && Number.isFinite(longitude) && longitude >= -180 && longitude <= 180;
  const validPin = typeof placeId === "string" && placeId.trim().length > 0 && validCoordinates;
  if (hasAnyPinData && !validPin) return "Choose your delivery address again so its map pin can be confirmed.";
  if (Number(radiusKm) > 0 && !validPin) return "Search for your delivery address or choose its map pin so the station can check its delivery area.";
  return null;
}

function paymentDetail(name, account) {
  const details = [name, account].filter(Boolean).join(" · ");
  return details ? `The station will confirm payment instructions (${details}).` : "The station will confirm payment instructions.";
}

export function calculateCartSubtotal(lines, now = Date.now()) {
  return lines.reduce((sum, line) => sum + effectiveProductPrice(line.product, line.quantity, now) * line.quantity, 0);
}
