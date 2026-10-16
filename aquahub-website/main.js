/* ========================================================================
   AquaHub Static Site Client Scripts
   Support routing and accessible interactions for the marketing site
   ======================================================================== */

const AQUAHUB_CONFIG = {
  supportEmail: "adriel.dev.123@gmail.com",
  privacyEmail: "adriel.dev.123@gmail.com",
  // Set only after confirming the Owner package and the intended Play audience.
  ownerPlayUrl: ""
};

const qs = (selector, root = document) => root.querySelector(selector);
const qsa = (selector, root = document) => [...root.querySelectorAll(selector)];

function isOwnerPlayUrl(rawUrl) {
  try {
    const url = new URL(rawUrl);
    if (url.protocol !== 'https:' || url.hostname !== 'play.google.com' || url.port || url.username || url.password) return false;
    if (url.pathname === '/store/apps/details') {
      const packageIds = url.searchParams.getAll('id');
      return packageIds.length === 1 && packageIds[0] === 'com.aesprt.aquahub';
    }
    return url.pathname === '/apps/testing/com.aesprt.aquahub';
  } catch {
    return false;
  }
}

function initOwnerPlayCtas() {
  const url = AQUAHUB_CONFIG.ownerPlayUrl;
  if (!isOwnerPlayUrl(url)) return;

  qsa('[data-owner-play-cta]').forEach(button => {
    const link = document.createElement('a');
    link.className = button.className.replace(/\bis-unavailable\b/g, '').trim();
    link.href = url;
    link.rel = 'noopener noreferrer';
    link.target = '_blank';
    link.dataset.ownerPlayCta = '';
    link.innerHTML = button.innerHTML;
    button.replaceWith(link);
  });
}

/**
 * Configure email mailto links across pages
 */
function initEmailLinks() {
  qsa('[data-email-link]').forEach(link => {
    const subject = link.dataset.emailSubject;
    const query = subject ? `?subject=${encodeURIComponent(subject)}` : '';
    link.href = `mailto:${AQUAHUB_CONFIG.supportEmail}${query}`;
    if (!link.textContent.trim() || link.textContent.trim() === 'Contact') {
      link.textContent = AQUAHUB_CONFIG.supportEmail;
    }
  });

  qsa('[data-privacy-email]').forEach(el => {
    el.textContent = AQUAHUB_CONFIG.privacyEmail;
    if (el.tagName === 'A') el.href = `mailto:${AQUAHUB_CONFIG.privacyEmail}`;
  });
}

/**
 * Mobile responsive navigation menu
 */
function initMobileNav() {
  const toggle = qs('[data-menu-toggle]');
  const nav = qs('[data-nav]');
  if (!toggle || !nav) return;

  const close = () => {
    nav.classList.remove('open');
    document.body.classList.remove('menu-open');
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-label', 'Open navigation');
  };

  toggle.addEventListener('click', () => {
    const isOpen = toggle.getAttribute('aria-expanded') === 'true';
    toggle.setAttribute('aria-expanded', String(!isOpen));
    toggle.setAttribute('aria-label', isOpen ? 'Open navigation' : 'Close navigation');
    nav.classList.toggle('open', !isOpen);
    document.body.classList.toggle('menu-open', !isOpen);
  });

  qsa('a', nav).forEach(link => link.addEventListener('click', close));

  document.addEventListener('keydown', event => {
    if (event.key === 'Escape') close();
  });

  document.addEventListener('click', event => {
    if (!nav.classList.contains('open')) return;
    if (nav.contains(event.target) || toggle.contains(event.target)) return;
    close();
  });
}

/**
 * Header scroll state
 */
function initHeader() {
  const header = qs('[data-header]');
  if (!header) return;
  const update = () => header.classList.toggle('scrolled', window.scrollY > 12);
  update();
  window.addEventListener('scroll', update, { passive: true });
}

/**
 * Interactive Persona Switcher Tabs (Owner vs Mobile Ordering)
 */
function initPersonaTabs() {
  const tabButtons = qsa('.tab-btn[data-tab]');
  const panels = {
    owner: qs('#panel-owner'),
    ordering: qs('#panel-ordering')
  };

  if (!tabButtons.length || !panels.owner || !panels.ordering) return;

  tabButtons.forEach(btn => {
    btn.addEventListener('click', () => {
      const targetTab = btn.getAttribute('data-tab');

      // Update button states
      tabButtons.forEach(b => {
        const isActive = b === btn;
        b.classList.toggle('active', isActive);
        b.setAttribute('aria-selected', String(isActive));
      });

      // Update panel visibility
      Object.entries(panels).forEach(([key, panel]) => {
        if (panel) {
          panel.classList.toggle('active', key === targetTab);
        }
      });
    });

    btn.addEventListener('keydown', event => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      const currentIndex = tabButtons.indexOf(btn);
      const nextIndex = event.key === 'ArrowRight'
        ? (currentIndex + 1) % tabButtons.length
        : (currentIndex - 1 + tabButtons.length) % tabButtons.length;
      tabButtons[nextIndex].focus();
      tabButtons[nextIndex].click();
    });
  });
}

/**
 * Switch the plan cards between their monthly and annual catalogue amounts.
 * The monthly amount stored in each <data> element is the sole price input;
 * annual totals and savings are derived with integer centavos.
 */
function initPricingToggle() {
  const selector = qs('[data-billing-selector]');
  const cards = qsa('[data-pricing-card]');
  const summary = qs('[data-billing-summary]');
  if (!selector || !cards.length || !window.AQUAHUB_PRICING) return;

  const priceUtils = window.AQUAHUB_PRICING;
  const monthlyPrices = new Map(cards.map(card => {
    const amount = qs('[data-price-amount]', card);
    return [card, Number(amount?.value || 0)];
  }));

  const render = cycle => {
    const isAnnual = cycle === 'annual';
    cards.forEach(card => {
      const amount = qs('[data-price-amount]', card);
      const period = qs('[data-price-period]', card);
      const note = qs('[data-price-note]', card);
      if (!amount || !period || !note) return;

      const monthly = monthlyPrices.get(card) ?? 0;
      if (!isAnnual || monthly === 0) {
        amount.value = String(monthly);
        amount.textContent = priceUtils.formatPhilippinePeso(monthly);
        period.textContent = monthly === 0 ? 'no charge' : '/ month';
        note.textContent = monthly === 0 ? 'No annual billing' : 'Billed monthly';
        return;
      }

      const annual = priceUtils.annualPriceCentavos(monthly);
      const savings = monthly * 12 - annual;
      amount.value = String(annual);
      amount.textContent = priceUtils.formatPhilippinePeso(annual, true);
      period.textContent = '/ year';
      note.textContent = `Billed annually · save ${priceUtils.formatPhilippinePeso(savings, true)}`;
    });

    // The native radio inputs expose the selected state accessibly.
    selector.setAttribute('data-cycle', cycle);
    if (summary) {
      summary.textContent = isAnnual
        ? 'Annual totals are billed once per year. The 20% catalogue discount is compared with 12 monthly payments.'
        : 'Monthly amounts are shown. Annual billing, where offered, saves 20% compared with 12 monthly payments.';
    }
  };

  selector.addEventListener('change', event => {
    if (event.target.matches('input[name="billing-cycle"]')) {
      render(event.target.value);
    }
  });
  render(selector.querySelector('input[name="billing-cycle"]:checked')?.value || 'monthly');
}

/**
 * Minimal Owner app screenshot carousel with autoplay, keyboard controls,
 * dots, and touch swiping. It pauses while the user interacts with it.
 */
function initScreenshotCarousel() {
  const carousel = qs('[data-screenshot-carousel]');
  if (!carousel) return;

  const track = qs('[data-carousel-track]', carousel);
  const slides = qsa('[data-slide]', carousel);
  const dots = qsa('[data-carousel-dot]', carousel);
  const previous = qs('[data-carousel-prev]', carousel);
  const next = qs('[data-carousel-next]', carousel);
  if (!track || slides.length < 2) return;

  let activeIndex = 0;
  let timer;
  let touchStartX = 0;

  const render = (nextIndex, direction = 1) => {
    activeIndex = (nextIndex + slides.length) % slides.length;
    track.style.setProperty('--carousel-index', activeIndex);
    slides.forEach((slide, index) => {
      const isActive = index === activeIndex;
      slide.classList.toggle('is-active', isActive);
      slide.classList.toggle('is-entering-next', isActive && direction > 0);
      slide.classList.toggle('is-entering-prev', isActive && direction < 0);
      slide.setAttribute('aria-hidden', String(!isActive));
    });
    dots.forEach((dot, index) => {
      const isActive = index === activeIndex;
      dot.classList.toggle('is-active', isActive);
      dot.setAttribute('aria-pressed', String(isActive));
    });
  };

  const stop = () => window.clearInterval(timer);
  const start = () => {
    stop();
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    timer = window.setInterval(() => render(activeIndex + 1, 1), 6000);
  };
  const goTo = index => {
    const direction = index >= activeIndex ? 1 : -1;
    render(index, direction);
    start();
  };

  previous?.addEventListener('click', () => goTo(activeIndex - 1));
  next?.addEventListener('click', () => goTo(activeIndex + 1));
  dots.forEach(dot => dot.addEventListener('click', () => goTo(Number(dot.dataset.carouselDot))));

  carousel.addEventListener('mouseenter', stop);
  carousel.addEventListener('mouseleave', start);
  carousel.addEventListener('focusin', stop);
  carousel.addEventListener('focusout', event => {
    if (!carousel.contains(event.relatedTarget)) start();
  });
  carousel.addEventListener('keydown', event => {
    if (event.key === 'ArrowLeft') goTo(activeIndex - 1);
    if (event.key === 'ArrowRight') goTo(activeIndex + 1);
  });
  carousel.addEventListener('touchstart', event => {
    touchStartX = event.changedTouches[0].clientX;
    stop();
  }, { passive: true });
  carousel.addEventListener('touchend', event => {
    const distance = event.changedTouches[0].clientX - touchStartX;
    if (Math.abs(distance) > 45) goTo(activeIndex + (distance < 0 ? 1 : -1));
    else start();
  }, { passive: true });

  render(0);
  start();
}

/**
 * Dynamic footer copyright year
 */
function initYear() {
  qsa('[data-year]').forEach(el => {
    el.textContent = new Date().getFullYear();
  });
}

/**
 * Smooth anchor scrolling fallback
 */
function initSmoothHashFallback() {
  qsa('a[href^="#"]').forEach(link => {
    link.addEventListener('click', event => {
      const id = link.getAttribute('href');
      if (!id || id === '#') return;
      const target = qs(id);
      if (!target) return;
      event.preventDefault();
      const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      target.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' });
      history.replaceState(null, '', id);
    });
  });
}

let initialized = false;

// Initialize once. With a deferred script, both DOMContentLoaded and the
// readyState fallback can be reached; guarding here prevents duplicate event
// listeners (especially important for the mobile menu toggle).
function init() {
  if (initialized) return;
  initialized = true;

  initEmailLinks();
  initOwnerPlayCtas();
  initMobileNav();
  initHeader();
  initPersonaTabs();
  initPricingToggle();
  initScreenshotCarousel();
  initYear();
  initSmoothHashFallback();
}

document.addEventListener('DOMContentLoaded', init, { once: true });

// Fallback in case DOMContentLoaded already fired
if (document.readyState !== 'loading') init();
