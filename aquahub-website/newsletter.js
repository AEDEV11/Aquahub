(() => {
  const form = document.querySelector("[data-newsletter-form]");
  if (!form) return;

  const email = form.querySelector("[name=email]");
  const firstName = form.querySelector("[name=firstName]");
  const consent = form.querySelector("[name=consent]");
  const emailError = form.querySelector("[data-newsletter-email-error]");
  const consentError = form.querySelector("[data-newsletter-consent-error]");
  const status = form.querySelector("[data-newsletter-status]");
  const submit = form.querySelector("[data-newsletter-submit]");
  const buttonLabel = submit.querySelector("[data-newsletter-label]");
  const config = window.AQUAHUB_NEWSLETTER_CONFIG;
  let submitted = false;
  let submitting = false;

  const announce = (message, kind = "") => {
    status.textContent = message;
    status.dataset.kind = kind;
  };

  if (!window.AQUAHUB_NEWSLETTER?.isConfigured(config)) {
    form.querySelectorAll("input, button").forEach((field) => { field.disabled = true; });
    announce("Email updates are temporarily unavailable. Please check back soon.", "error");
    return;
  }

  const validation = () => window.AQUAHUB_NEWSLETTER.validateNewsletter(email.value, consent.checked);
  const refresh = () => {
    const result = validation();
    const showEmailError = (submitted || email.dataset.touched === "true") && result.field === "email";
    const showConsentError = (submitted || consent.dataset.touched === "true") && result.field === "consent";
    emailError.textContent = showEmailError ? result.message : "";
    consentError.textContent = showConsentError ? result.message : "";
    email.setAttribute("aria-invalid", String(showEmailError));
    consent.setAttribute("aria-invalid", String(showConsentError));
    submit.disabled = submitting || !result.valid;
  };

  email.addEventListener("input", () => {
    email.dataset.touched = "true";
    announce("");
    refresh();
  });
  email.addEventListener("blur", () => {
    email.dataset.touched = "true";
    refresh();
  });
  consent.addEventListener("change", () => {
    consent.dataset.touched = "true";
    announce("");
    refresh();
  });
  firstName.addEventListener("input", () => announce(""));

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitting) return;
    submitted = true;
    const result = validation();
    refresh();
    if (!result.valid) {
      (result.field === "email" ? email : consent).focus();
      announce(result.message, "error");
      return;
    }

    submitting = true;
    submit.setAttribute("aria-busy", "true");
    buttonLabel.textContent = "Subscribing…";
    refresh();
    announce("Submitting your subscription…", "pending");

    const controller = new AbortController();
    const timeoutId = window.setTimeout(() => controller.abort(), 12_000);
    try {
      const endpointUrl = new URL("/functions/v1/newsletter-subscribe", config.supabaseUrl);
      const response = await fetch(endpointUrl, {
          method: "POST",
          headers: { "content-type": "application/json" },
          signal: controller.signal,
          body: JSON.stringify({
            email: email.value.trim(),
            firstName: firstName.value.trim(),
            consent: consent.checked,
            website: form.querySelector("[name=website]").value
          })
        }
      );
      const body = await response.json().catch(() => ({}));
      if (response.status === 429) {
        announce("Too many attempts. Please wait a little and try again.", "error");
      } else if (!response.ok) {
        announce("We couldn’t complete your subscription right now. Please try again shortly.", "error");
      } else if (body.accepted === true && body.confirmationRequired === true) {
        announce("Thanks! Your request is saved. A confirmation email will arrive after processing; confirm your address before we send updates.", "success");
        form.reset();
        submitted = false;
        email.dataset.touched = "false";
        consent.dataset.touched = "false";
      } else if (body.accepted === true) {
        announce("Thank you for subscribing! Your request has been saved.", "success");
        form.reset();
        submitted = false;
        email.dataset.touched = "false";
        consent.dataset.touched = "false";
      } else {
        announce("We couldn’t complete your subscription right now. Please try again shortly.", "error");
      }
    } catch {
      announce("We couldn’t reach the subscription service. Please check your connection and try again.", "error");
    } finally {
      window.clearTimeout(timeoutId);
      submitting = false;
      submit.removeAttribute("aria-busy");
      buttonLabel.textContent = "Subscribe to Updates";
      refresh();
    }
  });

  refresh();
})();
