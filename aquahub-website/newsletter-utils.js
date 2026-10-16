(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.AQUAHUB_NEWSLETTER = api;
})(globalThis, function () {
  function isValidEmail(value) {
    const email = String(value || "").trim();
    return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
  }

  function validateNewsletter(email, consent) {
    if (!isValidEmail(email)) return { valid: false, field: "email", message: "Enter a valid email address." };
    if (consent !== true) return { valid: false, field: "consent", message: "Please agree to receive AquaHub marketing emails to subscribe." };
    return { valid: true, field: null, message: "" };
  }

  function isConfigured(config) {
    if (!config || typeof config.supabaseUrl !== "string") return false;
    try {
      const url = new URL(config.supabaseUrl);
      return (url.protocol === "https:" || ["localhost", "127.0.0.1"].includes(url.hostname))
        && url.hostname.length > 0;
    } catch {
      return false;
    }
  }

  return { isValidEmail, validateNewsletter, isConfigured };
});
