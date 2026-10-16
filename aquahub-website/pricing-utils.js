/* Integer-centavo pricing helpers shared by the static page and Node tests. */
(function (root, createPricingUtils) {
  const pricingUtils = createPricingUtils();
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = pricingUtils;
  } else {
    root.AQUAHUB_PRICING = pricingUtils;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  const ANNUAL_DISCOUNT_PERCENT = 20;

  function annualPriceCentavos(monthlyPriceCentavos) {
    if (!Number.isSafeInteger(monthlyPriceCentavos) || monthlyPriceCentavos < 0) {
      throw new RangeError('Monthly price must be a non-negative integer number of centavos.');
    }
    return Math.round(monthlyPriceCentavos * 12 * (100 - ANNUAL_DISCOUNT_PERCENT) / 100);
  }

  function formatPhilippinePeso(priceCentavos, forceTwoDecimals = false) {
    if (!Number.isSafeInteger(priceCentavos) || priceCentavos < 0) {
      throw new RangeError('Price must be a non-negative integer number of centavos.');
    }
    const hasCentavos = priceCentavos % 100 !== 0;
    return new Intl.NumberFormat('en-PH', {
      style: 'currency',
      currency: 'PHP',
      minimumFractionDigits: forceTwoDecimals || hasCentavos ? 2 : 0,
      maximumFractionDigits: 2
    }).format(priceCentavos / 100);
  }

  return Object.freeze({
    annualDiscountPercent: ANNUAL_DISCOUNT_PERCENT,
    annualPriceCentavos,
    formatPhilippinePeso
  });
});
