import { createClient } from "@supabase/supabase-js";
import { calculateCartBreakdown, calculateCartSubtotal, confirmedOrderingAvailability, deliveryLocationError, effectiveProductPrice, guestPaymentMethods, normalizePhilippineMobile } from "./order-rules.js";
import { CONTAINER_SOURCE_OPTIONS, mergeOrderHistory, trackingProgress } from "./order-presentation.js";
import { publicAssetUrl } from "./storage-assets.js";
import { openGoogleLocationPicker } from "./google-location-picker.js";
import "./styles.css";

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL;
const SUPABASE_KEY = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY || import.meta.env.VITE_SUPABASE_ANON_KEY;
const GOOGLE_MAPS_BROWSER_KEY = import.meta.env.VITE_GOOGLE_MAPS_BROWSER_KEY || "";
const BASE_URL = import.meta.env.BASE_URL;
const BRAND_LOGO_URL = `${BASE_URL}aquahub-logo.png`;
const app = document.querySelector("#app");
const stationCode = window.location.pathname.match(/^(?:\/order)?\/s\/([A-Za-z0-9_-]{6,64})\/?$/)?.[1] || "";
const CART_KEY = `aquahub-cart:${stationCode}`;
const TRACK_KEY = `aquahub-guest-track:${stationCode}`;
const ORDERS_KEY = `aquahub-guest-orders:${stationCode}`;
const PROFILE_KEY = `aquahub-guest-profile:${stationCode}`;
const ATTEMPT_KEY = `aquahub-guest-attempt:${stationCode}`;
const MAX_PAYMENT_PROOF_BYTES = 5 * 1024 * 1024;
const state = {
  loading: true,
  station: null,
  products: [],
  cart: new Map(),
  containerSources: new Map(),
  selectedContainerSource: "STATION_EXCHANGE",
  orders: [],
  view: "catalog",
  notice: "",
  noticeKind: "info",
  search: "",
  category: "All",
  sort: "featured",
  cartSheetOpen: false,
  selectedProductId: null,
  checkout: null,
  orderBusy: false,
  orderAttempt: null,
  order: null,
  statusAnimation: null,
  trackingError: "",
};

const supabase = SUPABASE_URL && SUPABASE_KEY
  ? createClient(SUPABASE_URL, SUPABASE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
  })
  : null;
let trackingTimer;
let trackingBusy = false;
let dialogWasOpen = false;

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function formatMoney(centavos) {
  return new Intl.NumberFormat("en-PH", { style: "currency", currency: "PHP" }).format((Number(centavos) || 0) / 100);
}

function icon(name, size = 20) {
  const symbols = {
    cart: "shopping_cart", search: "search", plus: "add", minus: "remove", close: "close",
    pin: "location_on", clock: "schedule", water: "water_drop", check: "check",
    arrow: "arrow_forward", refresh: "refresh", shield: "verified_user", bag: "shopping_bag",
  };
  return `<span class="app-icon" aria-hidden="true" style="font-size:${size}px">${symbols[name] || symbols.water}</span>`;
}

function setNotice(message, kind = "info") {
  state.notice = message;
  state.noticeKind = kind;
  render();
}

function assetUrl(bucket, path) {
  return publicAssetUrl(supabase.storage, bucket, path);
}

function restoreCart() {
  try {
    const saved = JSON.parse(localStorage.getItem(CART_KEY) || "[]");
    state.cart = new Map();
    state.containerSources = new Map();
    for (const line of Array.isArray(saved) ? saved : []) {
      const quantity = Number(line?.quantity);
      if (!line?.id || !Number.isInteger(quantity) || quantity < 1) continue;
      state.cart.set(line.id, Math.min(99, quantity));
      if (CONTAINER_SOURCE_OPTIONS.some((option) => option.value === line.containerSource)) {
        state.containerSources.set(line.id, line.containerSource);
      }
    }
  } catch {
    state.cart = new Map();
    state.containerSources = new Map();
  }
}

function persistCart() {
  try {
    localStorage.setItem(CART_KEY, JSON.stringify([...state.cart].map(([id, quantity]) => ({
      id,
      quantity,
      containerSource: state.containerSources.get(id) || null,
    }))));
  } catch {
    // The cart remains usable for this visit when browser storage is unavailable.
  }
}

function restoreTracking() {
  try {
    const history = JSON.parse(sessionStorage.getItem(ORDERS_KEY) || "[]");
    state.orders = Array.isArray(history)
      ? history.filter((saved) => saved?.id && saved?.token).slice(0, 10)
      : [];
    const saved = JSON.parse(sessionStorage.getItem(TRACK_KEY) || "null");
    if (saved?.id && saved?.token) {
      const fromHistory = state.orders.find((order) => order.id === saved.id) || {};
      state.order = {
        ...fromHistory,
        ...saved,
        number: saved.number || fromHistory.number || "Your order",
        status: saved.status || fromHistory.status || "PENDING",
        totalCentavos: Number(saved.totalCentavos ?? fromHistory.totalCentavos ?? 0),
      };
      state.orders = mergeOrderHistory(state.orders, state.order);
      state.view = "tracking";
    }
  } catch {
    state.orders = [];
    state.order = null;
    try {
      sessionStorage.removeItem(TRACK_KEY);
      sessionStorage.removeItem(ORDERS_KEY);
    } catch { /* Tracking stays unavailable if session storage is blocked. */ }
  }
}

function readSavedProfile() {
  try {
    const profile = JSON.parse(localStorage.getItem(PROFILE_KEY) || "null");
    if (!profile || typeof profile !== "object") return {};
    return {
      name: typeof profile.name === "string" ? profile.name.slice(0, 100) : "",
      phone: typeof profile.phone === "string" ? profile.phone.slice(0, 16) : "",
      address: typeof profile.address === "string" ? profile.address.slice(0, 400) : "",
      addressDetails: typeof profile.addressDetails === "string" ? profile.addressDetails.slice(0, 160) : "",
      landmark: typeof profile.landmark === "string" ? profile.landmark.slice(0, 100) : "",
      placeId: typeof profile.placeId === "string" ? profile.placeId.slice(0, 200) : "",
      latitude: Number.isFinite(profile.latitude) ? profile.latitude : null,
      longitude: Number.isFinite(profile.longitude) ? profile.longitude : null,
      locationLabel: typeof profile.locationLabel === "string" ? profile.locationLabel.slice(0, 400) : "",
    };
  } catch {
    return {};
  }
}

function cacheGuestProfile(checkout) {
  if (!checkout.rememberDetails) {
    try { localStorage.removeItem(PROFILE_KEY); } catch { /* Do not interrupt a successful order. */ }
    return;
  }
  try {
    const previous = readSavedProfile();
    const profile = { ...previous, name: checkout.name, phone: checkout.phone };
    if (checkout.mode === "DELIVERY") {
      Object.assign(profile, {
        address: checkout.address,
        addressDetails: checkout.addressDetails,
        landmark: checkout.landmark,
        placeId: checkout.placeId,
        latitude: checkout.latitude,
        longitude: checkout.longitude,
        locationLabel: checkout.locationLabel,
      });
    }
    localStorage.setItem(PROFILE_KEY, JSON.stringify(profile));
  } catch {
    // Checkout still succeeds when this browser blocks local storage.
  }
}

function persistOrderHistory() {
  if (!state.order) return;
  state.orders = mergeOrderHistory(state.orders, state.order);
  try {
    const saved = state.orders.map((order) => ({
      id: order.id,
      token: order.token,
      number: order.number,
      status: order.status,
      totalCentavos: order.totalCentavos,
      stationName: order.stationName,
      fulfillmentMode: order.fulfillmentMode,
      createdAt: order.createdAt,
      updatedAt: order.updatedAt,
    }));
    sessionStorage.setItem(ORDERS_KEY, JSON.stringify(saved));
    sessionStorage.setItem(TRACK_KEY, JSON.stringify(saved.find((order) => order.id === state.order.id)));
  } catch {
    // Tracking remains available in memory if session storage is unavailable.
  }
}

function restoreOrderAttempt() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(ATTEMPT_KEY) || "null");
    if (/^guest:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(saved?.operationId || "")
      && /^[A-Za-z0-9_-]{40,60}$/.test(saved?.trackingToken || "")
      && /^[0-9a-f]{64}$/.test(saved?.signatureHash || "")) {
      state.orderAttempt = saved;
    }
  } catch {
    sessionStorage.removeItem(ATTEMPT_KEY);
  }
}

async function orderSignature(request) {
  const bytes = new TextEncoder().encode(JSON.stringify(request));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function loadOrderingAvailability(stationId) {
  const { data, error } = await supabase.rpc("get_public_station_ordering_eligibility", {
    p_station_ids: [stationId],
  });
  if (error) throw new Error("Ordering availability could not be confirmed. Refresh this page and try again.");
  return confirmedOrderingAvailability(stationId, data);
}

async function loadAccountBalanceAvailability(stationId) {
  const { data, error } = await supabase.rpc("get_public_station_account_receivable_eligibility", {
    p_station_ids: [stationId],
  });
  if (error) return false;
  return Array.isArray(data) && data.some((row) =>
    row?.station_id === stationId && row.is_account_balance_available === true);
}

async function load() {
  state.loading = true;
  state.notice = "";
  render();
  if (!supabase) {
    state.loading = false;
    state.notice = "Ordering is being set up. Please try again later.";
    state.noticeKind = "error";
    return render();
  }
  if (!stationCode) {
    state.loading = false;
    state.notice = "This station link is not valid. Ask the station for its latest QR code.";
    state.noticeKind = "error";
    return render();
  }
  try {
    const { data: stationRow, error: stationError } = await supabase.from("public_stations")
      .select("*").eq("public_station_code", stationCode).maybeSingle();
    if (stationError) throw stationError;
    if (!stationRow) throw new Error("This station link is no longer available.");
    const orderingAvailable = await loadOrderingAvailability(stationRow.station_id);
    const accountBalanceAvailable = await loadAccountBalanceAvailability(stationRow.station_id);
    const { data: productRows, error: productError } = await supabase.from("public_products")
      .select("*").eq("station_id", stationRow.station_id)
      .eq("is_available_for_ordering", true).order("name");
    if (productError) throw productError;
    state.station = {
      id: stationRow.station_id,
      code: stationCode,
      name: stationRow.name,
      address: stationRow.address,
      phone: stationRow.phone,
      logoUrl: assetUrl("station-assets", stationRow.logo_path),
      isOpen: Boolean(stationRow.is_open),
      acceptsOrders: orderingAvailable,
      deliveryFeeCentavos: Number(stationRow.delivery_fee_centavos || 0),
      deliveryRadiusKm: Number(stationRow.delivery_radius_km || 0),
      preparationMinutes: Number(stationRow.estimated_preparation_minutes || 0),
      latitude: stationRow.latitude == null ? null : Number(stationRow.latitude),
      longitude: stationRow.longitude == null ? null : Number(stationRow.longitude),
      rules: stationRow.business_rules_text || "",
      rulesVersion: Number(stationRow.business_rules_version || 0),
      cashPaymentEnabled: Boolean(stationRow.cash_payment_enabled),
      codPaymentEnabled: Boolean(stationRow.cod_payment_enabled),
      gcashPaymentEnabled: Boolean(stationRow.gcash_payment_enabled),
      gcashAccountName: stationRow.gcash_account_name || "",
      gcashAccountNumber: stationRow.gcash_account_number || "",
      mayaPaymentEnabled: Boolean(stationRow.maya_payment_enabled),
      mayaAccountName: stationRow.maya_account_name || "",
      mayaAccountNumber: stationRow.maya_account_number || "",
      accountBalanceEnabled: accountBalanceAvailable,
    };
    state.products = (productRows || []).map((row) => ({
      id: row.product_id,
      name: row.name,
      type: row.product_type || "Water",
      size: row.size_label || "",
      description: row.description || "",
      imageUrl: assetUrl("product-images", row.image_path),
      priceCentavos: Number(row.price_centavos || 0),
      promotionLabel: row.promotion_label || "",
      promotionType: row.promotion_type || "",
      promotionPercentBps: Number(row.promotion_percent_bps || 0),
      promotionalPriceCentavos: row.promotional_price_centavos == null ? null : Number(row.promotional_price_centavos),
      promotionMinimumQuantity: Number(row.promotion_minimum_quantity || 1),
      promotionStartsAt: row.promotion_starts_at == null ? null : Number(row.promotion_starts_at),
      promotionEndsAt: row.promotion_ends_at == null ? null : Number(row.promotion_ends_at),
      promotionIsActive: Boolean(row.promotion_is_active),
    }));
    restoreCart();
    restoreTracking();
    restoreOrderAttempt();
    state.checkout ||= emptyCheckout();
    if (state.order) startOrderPolling();
  } catch (error) {
    state.station = null;
    state.notice = error?.message?.includes("fetch")
      ? "We couldn’t connect to this station. Check your connection and try again."
      : String(error?.message || "This station could not be loaded.");
    state.noticeKind = "error";
  } finally {
    state.loading = false;
    render();
  }
}

function emptyCheckout() {
  const saved = readSavedProfile();
  return {
    name: saved.name || "", phone: saved.phone || "", mode: "PICKUP",
    address: saved.address || "", addressDetails: saved.addressDetails || "", landmark: saved.landmark || "",
    placeId: saved.placeId || "", latitude: saved.latitude ?? null, longitude: saved.longitude ?? null,
    locationLabel: saved.locationLabel || "", paymentMethod: "",
    note: "", acceptedRules: false, rememberDetails: true,
    paymentReference: "", paymentProofFile: null,
  };
}

function currentPrice(product, quantity = 1) {
  return effectiveProductPrice(product, quantity);
}

function productHasPromotion(product) {
  return product.promotionIsActive && (product.promotionStartsAt == null || product.promotionStartsAt <= Date.now())
    && (product.promotionEndsAt == null || product.promotionEndsAt >= Date.now());
}

function addToCart(id, delta = 1, containerSource = null) {
  if (delta > 0 && !ordersAllowed()) return;
  const product = state.products.find((item) => item.id === id);
  const next = Math.max(0, Math.min(99, (state.cart.get(id) || 0) + delta));
  if (next === 0) state.cart.delete(id); else state.cart.set(id, next);
  if (next === 0 || product?.type !== "REFILL") state.containerSources.delete(id);
  else if (containerSource && CONTAINER_SOURCE_OPTIONS.some((option) => option.value === containerSource)) {
    state.containerSources.set(id, containerSource);
  } else if (!state.containerSources.has(id)) {
    state.containerSources.set(id, "STATION_EXCHANGE");
  }
  persistCart();
  render();
}

function cartLines() {
  return [...state.cart].map(([id, quantity]) => {
    const product = state.products.find((item) => item.id === id);
    return product ? {
      product,
      quantity,
      containerSource: product.type === "REFILL" ? state.containerSources.get(id) || "STATION_EXCHANGE" : null,
      unitPrice: currentPrice(product, quantity),
    } : null;
  }).filter(Boolean);
}

function containerSourceTitle(source) {
  return CONTAINER_SOURCE_OPTIONS.find((option) => option.value === source)?.title || "Exchange an empty container";
}

function cartCount() {
  return [...state.cart.values()].reduce((sum, quantity) => sum + quantity, 0);
}

function cartSubtotal() {
  return calculateCartSubtotal(cartLines());
}

function availablePayments(mode = state.checkout?.mode || "PICKUP") {
  return guestPaymentMethods(state.station, mode);
}

function ordersAllowed() {
  return Boolean(state.station?.isOpen && state.station?.acceptsOrders);
}

function beginCheckout() {
  if (!cartLines().length) return;
  state.checkout ||= emptyCheckout();
  state.checkout.mode ||= "PICKUP";
  const options = availablePayments(state.checkout.mode);
  if (!options.some((option) => option.value === state.checkout.paymentMethod)) {
    state.checkout.paymentMethod = options[0]?.value || "";
  }
  state.view = "checkout";
  state.cartSheetOpen = false;
  state.notice = "";
  render();
  window.scrollTo({ top: 0, behavior: "smooth" });
}

function skeletons() {
  return `<div class="skeleton-hero"><div class="skeleton skeleton-circle"></div><div class="skeleton-copy"><i class="skeleton"></i><i class="skeleton"></i><i class="skeleton short"></i></div></div><div class="skeleton-grid">${Array.from({ length: 6 }, () => `<article class="skeleton-card"><i class="skeleton skeleton-image"></i><div class="skeleton-copy"><i class="skeleton"></i><i class="skeleton short"></i><i class="skeleton"></i></div></article>`).join("")}</div>`;
}

function productImage(product, compact = false) {
  const fallback = `<div class="media-fallback" aria-hidden="true">${icon("water", compact ? 29 : 38)}</div>`;
  const image = product.imageUrl
    ? `<img class="product-photo" src="${escapeHtml(product.imageUrl)}" alt="${escapeHtml(product.name)}" loading="lazy" />`
    : "";
  return `<div class="product-media${compact ? " compact" : ""}">${fallback}${image}</div>`;
}

function stationImage() {
  return state.station?.logoUrl
    ? `<img class="station-photo" src="${escapeHtml(state.station.logoUrl)}" alt="" />`
    : `<span class="station-mark" aria-hidden="true">${icon("water", 25)}</span>`;
}

function render() {
  if (!app) return;
  const active = document.activeElement;
  const focusKey = active?.dataset?.focus || "";
  const focusAction = active?.dataset?.action || "";
  const focusId = active?.dataset?.id || "";
  const selection = active && "selectionStart" in active ? [active.selectionStart, active.selectionEnd] : null;
  if (state.loading) {
    app.innerHTML = shell(`<main class="page loading-page" aria-busy="true"><div class="skeleton-heading"><i class="skeleton"></i><i class="skeleton"></i></div>${skeletons()}</main>`);
  } else if (!state.station) {
    app.innerHTML = shell(`<main class="page"><section class="empty-state error-state"><span class="empty-icon"><img src="${BRAND_LOGO_URL}" alt="" /></span><p class="eyebrow">AQUAHUB ORDERING</p><h1>Station link unavailable</h1><p>${escapeHtml(state.notice || "Ask the station for its latest QR code.")}</p><button class="button primary" data-action="retry-load">${icon("refresh", 18)} Try again</button></section></main>`);
  } else {
    const body = state.view === "checkout" ? renderCheckout()
      : state.view === "tracking" ? renderTracking()
        : state.view === "orders" ? renderOrders()
          : renderCatalog();
    app.innerHTML = shell(body);
    if (state.cartSheetOpen && state.view === "catalog") app.insertAdjacentHTML("beforeend", renderCartSheet());
    if (state.selectedProductId && state.view === "catalog") app.insertAdjacentHTML("beforeend", renderProductSheet());
    if (state.view === "tracking") state.statusAnimation = null;
  }
  if (focusKey) {
    const next = app.querySelector(`[data-focus="${CSS.escape(focusKey)}"]`);
    if (next) {
      next.focus({ preventScroll: true });
      if (selection && typeof next.setSelectionRange === "function") next.setSelectionRange(...selection);
    }
  } else if (focusAction && focusId) {
    app.querySelector(`[data-action="${CSS.escape(focusAction)}"][data-id="${CSS.escape(focusId)}"]`)?.focus({ preventScroll: true });
  }
  const dialogOpen = Boolean(app.querySelector('.bottom-sheet[role="dialog"]'));
  document.body.classList.toggle("modal-open", dialogOpen);
  if (dialogOpen && !dialogWasOpen) app.querySelector('.bottom-sheet[role="dialog"] button:not(:disabled)')?.focus({ preventScroll: true });
  if (!dialogOpen && dialogWasOpen) app.querySelector('[data-focus="search"]')?.focus({ preventScroll: true });
  dialogWasOpen = dialogOpen;
}

function shell(body) {
  const count = cartCount();
  const cartVisible = state.station && state.view === "catalog";
  return `<header class="topbar">
    <a href="${BASE_URL}" class="brand" aria-label="AquaHub home"><img class="brand-logo" src="${BRAND_LOGO_URL}" alt="" /><span>AquaHub</span></a>
    <nav class="topbar-right" aria-label="Store navigation">
      ${state.station ? `<button class="topbar-orders ${state.view === "orders" ? "active" : ""}" data-action="open-orders" aria-label="View your orders${state.orders.length ? `, ${state.orders.length} saved` : ""}" ${state.view === "orders" ? 'aria-current="page"' : ""}>${icon("bag", 19)}<span class="topbar-nav-label">Orders</span>${state.orders.length ? `<span class="order-count">${state.orders.length}</span>` : ""}</button>` : ""}
      ${cartVisible ? `<button class="topbar-cart" data-action="open-cart" aria-label="Open cart${count ? `, ${count} items` : ""}">${icon("cart", 19)}<span class="topbar-nav-label">Cart</span>${count ? `<span class="cart-count">${count}</span>` : ""}</button>` : ""}
    </nav>
  </header>${state.notice && state.station && state.view !== "tracking" ? `<div class="notice-wrap"><div class="notice ${state.noticeKind}" role="${state.noticeKind === "error" ? "alert" : "status"}">${escapeHtml(state.notice)}<button class="notice-close" data-action="clear-notice" aria-label="Dismiss">${icon("close", 17)}</button></div></div>` : ""}${body}<footer class="footer"><span>Powered by <strong>AquaHub</strong></span><span>Fresh water, one easy order at a time</span></footer>`;
}

function renderCatalog() {
  const station = state.station;
  const count = cartCount();
  const unavailable = !ordersAllowed();
  const categories = ["All", ...new Set(state.products.map((product) => product.type).filter(Boolean))];
  const visible = state.products.filter((product) => {
    const matchesSearch = `${product.name} ${product.type} ${product.size} ${product.description}`.toLowerCase().includes(state.search.toLowerCase());
    return matchesSearch && (state.category === "All" || product.type === state.category);
  });
  if (state.sort === "price-low") visible.sort((a, b) => currentPrice(a, 1) - currentPrice(b, 1));
  if (state.sort === "price-high") visible.sort((a, b) => currentPrice(b, 1) - currentPrice(a, 1));
  return `<main class="page store-page">
    <section class="station-hero"><div class="station-hero-main"><div class="station-hero-mark">${stationImage()}</div><div class="station-hero-copy"><div class="hero-badges"><span class="status-pill ${unavailable ? "closed" : "open"}"><i></i>${unavailable ? "Not taking orders" : "Open for orders"}</span><span class="eyebrow">STATION STORE</span></div><h1>${escapeHtml(station.name)}</h1><p class="station-address">${icon("pin", 17)} ${escapeHtml(station.address || "Address available from the station")}</p><div class="service-facts">${station.preparationMinutes ? `<span>${icon("clock", 16)} About ${station.preparationMinutes} min</span>` : ""}${station.deliveryRadiusKm > 0 ? `<span>${icon("pin", 16)} Delivery within ${station.deliveryRadiusKm} km</span>` : ""}<span>${station.deliveryFeeCentavos ? `${formatMoney(station.deliveryFeeCentavos)} delivery` : "Pickup available"}</span></div></div></div><div class="hero-side-note"><span class="hero-water-icon">${icon("water", 28)}</span><strong>Pure water, simple ordering</strong><small>Choose what you need. No account required.</small></div></section>
    ${unavailable ? `<div class="closed-banner" role="status"><strong>This station is taking a break.</strong><span>You can browse the catalog, then check back when online orders reopen.</span></div>` : ""}
    <section class="store-layout"><div class="catalog-column"><div class="catalog-header"><div><p class="eyebrow">THE CATALOG</p><h2>Find your water</h2><p class="muted">Current prices and stock are confirmed when you order.</p></div><label class="sort-control"><span>Sort</span><select data-focus="sort" aria-label="Sort products"><option value="featured" ${state.sort === "featured" ? "selected" : ""}>Featured</option><option value="price-low" ${state.sort === "price-low" ? "selected" : ""}>Price: low to high</option><option value="price-high" ${state.sort === "price-high" ? "selected" : ""}>Price: high to low</option></select></label></div>
      <label class="search-box">${icon("search", 19)}<span class="sr-only">Search products</span><input type="search" data-focus="search" placeholder="Search water, containers…" value="${escapeHtml(state.search)}" autocomplete="off" /><kbd>⌘ K</kbd></label>
      <div class="category-list" role="group" aria-label="Filter products by category">${categories.map((category) => `<button class="category-chip ${state.category === category ? "active" : ""}" aria-pressed="${state.category === category}" data-action="category" data-category="${escapeHtml(category)}">${escapeHtml(category)}</button>`).join("")}</div>
      <div class="product-grid" aria-live="polite">${visible.length ? visible.map(renderProduct).join("") : `<div class="empty-results"><span>${icon("search", 25)}</span><strong>No products found</strong><small>Try another search or category.</small></div>`}</div>
    </div>${renderCart()}</section>
    ${count > 0 ? `<div class="mobile-cartbar"><div><strong>${count} ${count === 1 ? "item" : "items"}</strong><small>${formatMoney(cartSubtotal())}</small></div><button class="button primary" data-action="open-cart">View cart ${icon("arrow", 18)}</button></div>` : ""}
  </main>`;
}

function renderProduct(product) {
  const quantity = state.cart.get(product.id) || 0;
  const promoLive = productHasPromotion(product);
  const price = currentPrice(product, quantity || 1);
  const promoHint = promoLive && product.promotionMinimumQuantity > 1 && quantity < product.promotionMinimumQuantity
    ? ` · ${product.promotionMinimumQuantity}+ for ${formatMoney(currentPrice(product, product.promotionMinimumQuantity))}`
    : "";
  return `<article class="product-card"><button class="product-open" data-action="details" data-id="${escapeHtml(product.id)}" aria-label="View ${escapeHtml(product.name)} details">${productImage(product)}<span class="product-info"><span class="product-meta">${escapeHtml(product.type)}${product.size ? ` <i>·</i> ${escapeHtml(product.size)}` : ""}</span><strong class="product-name">${escapeHtml(product.name)}</strong>${product.description ? `<span class="product-description">${escapeHtml(product.description)}</span>` : ""}<span class="product-price-row"><strong>${formatMoney(price)}</strong>${price < product.priceCentavos ? `<del>${formatMoney(product.priceCentavos)}</del>` : ""}</span>${promoLive ? `<span class="promo-label">${escapeHtml(product.promotionLabel || "Special price")}${promoHint}</span>` : ""}</span></button><div class="product-actions">${quantity ? quantityControl(product.id, quantity, "card") : `<button class="add-button" data-action="add" data-id="${escapeHtml(product.id)}" ${!ordersAllowed() ? "disabled" : ""}>${icon("plus", 18)} Add</button>`}</div></article>`;
}

function quantityControl(id, quantity, context = "") {
  return `<div class="quantity-control ${context}" aria-label="Quantity ${quantity}"><button data-action="decrease" data-id="${escapeHtml(id)}" aria-label="Remove one">${icon("minus", 16)}</button><span aria-live="polite">${quantity}</span><button data-action="increase" data-id="${escapeHtml(id)}" aria-label="Add one" ${!ordersAllowed() ? "disabled" : ""}>${icon("plus", 16)}</button></div>`;
}

function renderCart() {
  const lines = cartLines();
  return `<aside class="cart-panel"><div class="cart-heading"><div><p class="eyebrow">YOUR ORDER</p><h2>Your cart <span>${cartCount()}</span></h2></div>${icon("bag", 23)}</div>${lines.length ? `<div class="cart-items">${lines.map(({ product, quantity, unitPrice, containerSource }) => `<div class="cart-line"><div class="cart-line-info">${productImage(product, true)}<div><strong>${escapeHtml(product.name)}</strong><small>${quantity} × ${formatMoney(unitPrice)}${containerSource ? ` · ${escapeHtml(containerSourceTitle(containerSource))}` : ""}</small></div></div>${quantityControl(product.id, quantity, "cart")}</div>`).join("")}</div><div class="cart-total"><span>Subtotal</span><strong>${formatMoney(cartSubtotal())}</strong></div><button class="button primary checkout-button" data-action="checkout" ${!ordersAllowed() ? "disabled" : ""}>Continue to checkout ${icon("arrow", 18)}</button><p class="cart-reassurance">${icon("shield", 14)} No account needed · Pay your station directly</p>` : `<div class="cart-empty"><div class="cart-empty-icon">${icon("cart", 27)}</div><strong>Your cart is waiting</strong><p>Add your favorites and they’ll appear here.</p></div><button class="button secondary checkout-button" disabled>Continue to checkout ${icon("arrow", 18)}</button>`}</aside>`;
}

function renderCartSheet() {
  const lines = cartLines();
  return `<div class="sheet-scrim" data-action="close-cart"><section class="bottom-sheet cart-sheet" role="dialog" aria-modal="true" aria-labelledby="cart-sheet-title"><div class="sheet-handle"></div><div class="sheet-heading"><div><p class="eyebrow">YOUR ORDER</p><h2 id="cart-sheet-title">Your cart <span>${cartCount()}</span></h2></div><button class="icon-button" data-action="close-cart" aria-label="Close cart">${icon("close")}</button></div>${lines.length ? `<div class="sheet-items">${lines.map(({ product, quantity, unitPrice, containerSource }) => `<div class="cart-line"><div class="cart-line-info">${productImage(product, true)}<div><strong>${escapeHtml(product.name)}</strong><small>${quantity} × ${formatMoney(unitPrice)}${containerSource ? ` · ${escapeHtml(containerSourceTitle(containerSource))}` : ""}</small></div></div>${quantityControl(product.id, quantity, "cart")}</div>`).join("")}</div><div class="cart-total"><span>Subtotal</span><strong>${formatMoney(cartSubtotal())}</strong></div><button class="button primary checkout-button" data-action="checkout" ${!ordersAllowed() ? "disabled" : ""}>Continue to checkout ${icon("arrow", 18)}</button>` : `<div class="cart-empty"><strong>Your cart is empty</strong><p>Add something from the catalog to continue.</p></div>`}</section></div>`;
}

function renderContainerSourceOptions(product) {
  if (product.type !== "REFILL") return "";
  const selectedSource = state.selectedContainerSource || state.containerSources.get(product.id) || "STATION_EXCHANGE";
  return `<fieldset class="container-options"><legend>How will you get a container?</legend><p>Choose an option for this refill.</p>${CONTAINER_SOURCE_OPTIONS.map((option) => `<label class="container-option ${selectedSource === option.value ? "selected" : ""}"><input type="radio" name="containerSource" data-container-source data-focus="container-source-${option.value}" value="${option.value}" ${selectedSource === option.value ? "checked" : ""} /><span class="container-option-check">${icon("check", 16)}</span><span><strong>${escapeHtml(option.title)}</strong><small>${escapeHtml(option.description)}</small></span></label>`).join("")}</fieldset>`;
}

function renderProductSheet() {
  const product = state.products.find((item) => item.id === state.selectedProductId);
  if (!product) return "";
  const quantity = state.cart.get(product.id) || 0;
  return `<div class="sheet-scrim product-scrim" data-action="close-product"><section class="bottom-sheet product-sheet" role="dialog" aria-modal="true" aria-labelledby="product-sheet-title"><div class="sheet-handle"></div><button class="icon-button sheet-close" data-action="close-product" aria-label="Close product details">${icon("close")}</button>${productImage(product)}<div class="product-sheet-copy"><span class="product-meta">${escapeHtml(product.type)}${product.size ? ` · ${escapeHtml(product.size)}` : ""}</span><h2 id="product-sheet-title">${escapeHtml(product.name)}</h2><p>${escapeHtml(product.description || "Fresh, quality water from your AquaHub station.")}</p><div class="product-sheet-price"><strong>${formatMoney(currentPrice(product, quantity || 1))}</strong>${currentPrice(product, quantity || 1) < product.priceCentavos ? `<del>${formatMoney(product.priceCentavos)}</del>` : ""}</div>${renderContainerSourceOptions(product)}<div class="product-sheet-actions">${quantity ? quantityControl(product.id, quantity, "sheet") : ""}<button class="button primary" data-action="add-close" data-id="${escapeHtml(product.id)}" ${!ordersAllowed() ? "disabled" : ""}>${quantity ? "Add another" : "Add to cart"} ${icon("plus", 18)}</button></div></div></section></div>`;
}

function checkoutAddress() {
  return state.checkout?.address?.trim() || "";
}

function renderDeliveryFields(form, radiusNeedsLocation) {
  const selected = Boolean(form.placeId && form.latitude != null && form.longitude != null);
  return `<div class="delivery-fields"><label class="field">Delivery address<textarea data-checkout="address" data-focus="checkout-address" name="address" rows="2" maxlength="400" autocomplete="street-address" required ${selected ? "readonly" : ""} placeholder="Search and choose your delivery address, or enter it manually">${escapeHtml(form.address)}</textarea><small>${selected ? "This address is tied to the selected map location." : "Include your house number, street, barangay and city."}</small></label><label class="field">House, unit or building details <span class="optional">Optional</span><input data-checkout="addressDetails" data-focus="checkout-address-details" name="addressDetails" value="${escapeHtml(form.addressDetails)}" maxlength="160" autocomplete="address-line2" placeholder="e.g. Unit 3B, blue gate" /></label><label class="field">Nearby landmark <span class="optional">Optional</span><input data-checkout="landmark" data-focus="checkout-landmark" name="landmark" value="${escapeHtml(form.landmark)}" maxlength="100" placeholder="e.g. across the public market" /></label><div class="location-picker ${selected ? "located" : ""}"><div class="location-icon">${icon("pin", 20)}</div><div><strong>${selected ? "Delivery pin confirmed" : radiusNeedsLocation ? "Choose your delivery location" : "Add a map location"}</strong><small>${escapeHtml(form.locationLabel || (radiusNeedsLocation ? `A map pin is required to check this station’s ${state.station.deliveryRadiusKm} km delivery area.` : "Optional for this station; a pin helps confirm the destination."))}</small></div><button type="button" class="button secondary location-button" data-action="open-location-picker">${selected ? "Change pin" : "Find address & pin"}</button></div>${!GOOGLE_MAPS_BROWSER_KEY ? `<p class="inline-help">Map search is not configured in this build. Delivery stations that require a pin cannot accept delivery until it is available.</p>` : ""}</div>`;
}

function renderOnlinePaymentFields(form) {
  const method = form.paymentMethod;
  if (!['GCASH', 'MAYA'].includes(method)) return "";
  const provider = method === "GCASH" ? "GCash" : "Maya";
  const accountName = method === "GCASH" ? state.station.gcashAccountName : state.station.mayaAccountName;
  const accountNumber = method === "GCASH" ? state.station.gcashAccountNumber : state.station.mayaAccountNumber;
  const account = [accountName, accountNumber].filter(Boolean).join(" · ");
  const file = form.paymentProofFile;
  const paymentTotal = calculateCartBreakdown(cartLines()).discountedSubtotal
    + (form.mode === "DELIVERY" ? state.station.deliveryFeeCentavos : 0);
  return `<div class="online-payment-fields"><div class="online-payment-instructions"><strong>Pay ${formatMoney(paymentTotal)} to ${escapeHtml(account || `the station’s ${provider} account`)}.</strong><small>Enter the payment reference and attach a screenshot of your completed ${provider} payment. The station will receive this order as paid.</small></div><label class="field">${provider} reference number<input data-checkout="paymentReference" data-focus="payment-reference" name="paymentReference" value="${escapeHtml(form.paymentReference || "")}" maxlength="100" autocomplete="off" required placeholder="Enter the reference from your receipt" /></label><label class="field">Payment screenshot<input data-focus="payment-proof" name="paymentProof" type="file" accept="image/jpeg,image/png,image/webp" ${file ? "" : "required"} /><small>JPG, PNG or WebP, up to 5 MB.</small></label>${file ? `<div class="selected-proof"><span>${escapeHtml(file.name)} · ${(file.size / 1024 / 1024).toFixed(1)} MB</span><button type="button" data-action="remove-payment-proof">Remove</button></div>` : ""}</div>`;
}

function renderCheckout() {
  const form = state.checkout || emptyCheckout();
  const delivery = form.mode === "DELIVERY";
  const savedProfile = readSavedProfile();
  const profileSaved = Boolean(savedProfile.name || savedProfile.phone);
  const paymentOptions = availablePayments(form.mode);
  const radiusNeedsLocation = delivery && state.station.deliveryRadiusKm > 0;
  const rules = state.station.rules.trim();
  const lines = cartLines();
  const priceBreakdown = calculateCartBreakdown(lines);
  const total = priceBreakdown.discountedSubtotal + (delivery ? state.station.deliveryFeeCentavos : 0);
  return `<main class="page checkout-page"><button class="back-link" data-action="back-catalog">${icon("arrow", 17)} Back to products</button><div class="checkout-heading"><p class="eyebrow">ALMOST THERE</p><h1>Checkout</h1><p>Place your order as a guest. No account or password needed.</p></div><div class="checkout-layout"><form id="order-form" class="checkout-form" novalidate>
      <section class="checkout-card"><div class="step-heading"><span class="step-number">1</span><div><h2>Your details</h2><p>We’ll use these to confirm your order.</p></div></div><label class="profile-remember"><input data-checkout="rememberDetails" data-focus="remember-details" type="checkbox" name="rememberDetails" ${form.rememberDetails ? "checked" : ""} />${icon("shield", 14)} <span>Remember contact and delivery details on this device</span></label>${profileSaved ? `<p class="profile-saved-note">Your details are saved here. <button type="button" data-action="clear-profile">Clear saved details</button></p>` : `<p class="profile-save-note">Details are saved after a successful order and can be cleared at any time.</p>`}<div class="form-grid"><label class="field full">Full name<input data-checkout="name" data-focus="checkout-name" name="name" value="${escapeHtml(form.name)}" autocomplete="name" maxlength="100" required placeholder="e.g. Alex Santos" /></label><label class="field full">Philippine mobile number<span class="input-with-prefix"><span>+63</span><input data-checkout="phone" data-focus="checkout-phone" name="phone" value="${escapeHtml(form.phone)}" autocomplete="tel-national" inputmode="tel" maxlength="16" required placeholder="9XX XXX XXXX" /></span><small id="phone-help">Enter your 10-digit mobile number after +63.</small></label></div></section>
      <section class="checkout-card"><div class="step-heading"><span class="step-number">2</span><div><h2>How should we get it to you?</h2><p>Fulfilment options are set by ${escapeHtml(state.station.name)}.</p></div></div><div class="fulfilment-options"><label class="fulfilment-option ${!delivery ? "selected" : ""}"><input type="radio" name="mode" value="PICKUP" ${!delivery ? "checked" : ""} /><span class="fulfilment-icon">${icon("bag", 20)}</span><span><strong>Pick up</strong><small>Collect from the station</small></span></label><label class="fulfilment-option ${delivery ? "selected" : ""}"><input type="radio" name="mode" value="DELIVERY" ${delivery ? "checked" : ""} /><span class="fulfilment-icon">${icon("pin", 20)}</span><span><strong>Delivery</strong><small>${state.station.deliveryRadiusKm > 0 ? `Within ${state.station.deliveryRadiusKm} km` : "To your address"}</small></span></label></div>${delivery ? renderDeliveryFields(form, radiusNeedsLocation) : `<div class="pickup-note">${icon("pin", 18)} <span>Pickup at <strong>${escapeHtml(state.station.address || state.station.name)}</strong></span></div>`}</section>
      <section class="checkout-card"><div class="step-heading"><span class="step-number">3</span><div><h2>Payment</h2><p>Select a method this station currently accepts.</p></div></div>${paymentOptions.length ? `<fieldset class="payment-options"><legend class="sr-only">Payment method</legend>${paymentOptions.map((option) => `<label class="payment-option ${form.paymentMethod === option.value ? "selected" : ""}"><input type="radio" name="paymentMethod" value="${option.value}" ${form.paymentMethod === option.value ? "checked" : ""} /><span class="payment-radio"></span><span><strong>${escapeHtml(option.label)}</strong><small>${escapeHtml(option.detail)}</small></span>${option.value === "GCASH" ? `<span class="payment-brand gcash">GCash</span>` : option.value === "MAYA" ? `<span class="payment-brand maya">Maya</span>` : ""}</label>`).join("")}</fieldset>${renderOnlinePaymentFields(form)}</section>` : `<div class="no-payment"><strong>No payment method is available for ${delivery ? "delivery" : "pickup"}.</strong><span>Contact the station before placing an order.</span>${state.station.phone ? `<a href="tel:${escapeHtml(state.station.phone)}">Call ${escapeHtml(state.station.phone)}</a>` : ""}</div>`}</section>
      ${rules ? `<section class="checkout-card rules-card"><div class="step-heading"><span class="step-number">4</span><div><h2>Station ordering rules</h2><p>Please review before placing your order.</p></div></div><div class="rules-copy">${escapeHtml(rules).replaceAll("\n", "<br>")}</div><label class="rules-accept"><input type="checkbox" data-checkout="acceptedRules" name="acceptedRules" ${form.acceptedRules ? "checked" : ""} /><span>I’ve read and agree to these station rules.</span></label></section>` : ""}
      <section class="checkout-card note-card"><label class="field">Order note <span class="optional">Optional</span><textarea data-checkout="note" data-focus="checkout-note" name="note" rows="2" maxlength="500" placeholder="Anything the station should know?">${escapeHtml(form.note)}</textarea></label></section>
      <div class="checkout-submit"><button class="button primary submit-order" type="submit" ${state.orderBusy || !ordersAllowed() || !paymentOptions.length ? "disabled" : ""}>${state.orderBusy ? `<span class="button-spinner"></span> Sending order…` : `Place order · ${formatMoney(total)} ${icon("arrow", 18)}`}</button><p>${icon("shield", 15)} Your contact details are only shared with this station to fulfil your order.</p></div>
    </form><aside class="checkout-summary"><div class="summary-head"><span>${icon("bag", 19)} Order summary</span><button data-action="back-catalog">Edit</button></div><div class="summary-station">${stationImage()}<div><strong>${escapeHtml(state.station.name)}</strong><small>${delivery ? "Delivery" : "Station pickup"}</small></div></div><div class="summary-lines">${lines.map(({ product, quantity, unitPrice, containerSource }) => { const regular = product.priceCentavos * quantity; const discounted = unitPrice * quantity; return `<div class="summary-line"><span>${quantity} × ${escapeHtml(product.name)}${containerSource ? `<small>${escapeHtml(containerSourceTitle(containerSource))}</small>` : ""}</span><strong>${regular > discounted ? `<del>${formatMoney(regular)}</del>` : ""}${formatMoney(discounted)}</strong></div>`; }).join("")}</div><div class="summary-calculations">${priceBreakdown.discountCentavos ? `<div><span>Regular subtotal</span><strong>${formatMoney(priceBreakdown.regularSubtotal)}</strong></div><div class="summary-promotion"><span>Promotion discount</span><strong>−${formatMoney(priceBreakdown.discountCentavos)}</strong></div><div><span>Discounted subtotal</span><strong>${formatMoney(priceBreakdown.discountedSubtotal)}</strong></div>` : `<div><span>Subtotal</span><strong>${formatMoney(priceBreakdown.discountedSubtotal)}</strong></div>`}<div><span>${delivery ? "Delivery fee" : "Pickup"}</span><strong>${delivery ? state.station.deliveryFeeCentavos ? formatMoney(state.station.deliveryFeeCentavos) : "Free" : "Free"}</strong></div><div class="summary-total"><span>Total</span><strong>${formatMoney(total)}</strong></div></div><div class="summary-assurance">${icon("shield", 16)}<span>Final price and stock are checked securely by the station system.</span></div></aside></div></main>`;
}

function friendlyStatus(status) {
  const statuses = {
    PENDING: ["Order received", "The station has your order and will review it shortly."],
    ACCEPTED: ["Order accepted", "The station is getting your order ready."],
    PREPARING: ["Being prepared", "Your water is being prepared."],
    READY_FOR_PICKUP: ["Ready for pickup", "Your order is ready at the station."],
    RIDER_ASSIGNED: ["Rider assigned", "A rider has been assigned to your delivery."],
    OUT_FOR_DELIVERY: ["On the way", "Your order is on its way to you."],
    DELIVERED: ["Delivered", "Your order has been delivered."],
    COMPLETED: ["Completed", "Thanks for ordering from this station."],
    REJECTED: ["Order declined", "The station couldn’t fulfil this order. Contact them for help."],
    CANCELLED: ["Order cancelled", "This order has been cancelled."],
  };
  return statuses[status] || ["Order received", "Your order status will appear here."];
}

function renderTracking() {
  const order = state.order || {};
  const [statusLabel, statusText] = friendlyStatus(order.status);
  const failure = ["REJECTED", "CANCELLED"].includes(order.status);
  const stationName = order.stationName || state.station?.name || "your station";
  const progress = trackingProgress(order);
  const animation = state.statusAnimation;
  const railInset = 50 / progress.steps.length;
  const timelinePosition = (percent) => railInset + (Math.max(0, Math.min(100, percent)) / 100) * (100 - railInset * 2);
  const progressFrom = timelinePosition(animation?.from ?? progress.progressPercent);
  const progressTo = timelinePosition(animation?.to ?? progress.progressPercent);
  const milestones = progress.steps.map(([status, label], index) => {
    const completed = !failure && index < progress.activeIndex;
    const current = !failure && index === progress.activeIndex;
    return `<div class="tracking-step ${completed ? "complete" : ""} ${current ? "current" : ""}"><span class="tracking-step-icon">${completed ? icon("check", 13) : icon(current ? "clock" : "water", 13)}</span><span>${escapeHtml(label)}</span></div>`;
  }).join("");
  return `<main class="page tracking-page"><section class="tracking-card"><div class="tracking-success ${failure ? "failed" : ""}">${failure ? icon("close", 25) : icon("check", 27)}</div><p class="eyebrow">${failure ? "ORDER UPDATE" : "ORDER PLACED"}</p><h1>${escapeHtml(order.number || "Your order")}</h1><p class="tracking-intro">${failure ? statusText : `Your order was sent to ${escapeHtml(stationName)}.`}</p><div class="tracking-status"><div class="status-icon">${icon(failure ? "close" : "water", 20)}</div><div><strong>${escapeHtml(statusLabel)}</strong><small>${escapeHtml(statusText)}</small></div><span class="status-live"><i></i>Live</span></div><div class="tracking-timeline-wrap"><div class="tracking-timeline ${failure ? "failed" : ""}" style="--step-count:${progress.steps.length};--rail-inset:${railInset}%;--progress-from:${progressFrom}%;--progress-to:${progressTo}%" aria-label="Order progress">${milestones}<div class="tracking-rail"><span class="tracking-progress-fill"></span></div><span class="tracking-runner ${animation ? "moving" : ""}" aria-hidden="true">${icon("check", 13)}</span></div></div><div class="tracking-meta"><span>Order total</span><strong>${formatMoney(order.totalCentavos || 0)}</strong></div>${state.trackingError ? `<div class="tracking-error" role="status">${escapeHtml(state.trackingError)} <button data-action="refresh-tracking">Try again</button></div>` : `<p class="tracking-refresh">${icon("refresh", 15)} Updates automatically · Last checked ${escapeHtml(order.updatedAt || "just now")}</p>`}<div class="tracking-actions"><button class="button primary" data-action="new-order">Order again ${icon("arrow", 18)}</button><button class="button secondary" data-action="open-orders">${icon("bag", 17)} View my orders</button><button class="button secondary" data-action="refresh-tracking">${icon("refresh", 17)} Refresh status</button></div><p class="tracking-privacy">${icon("shield", 15)} Your order history and private tracking links stay in this browser session.</p></section></main>`;
}

function renderOrders() {
  return `<main class="page orders-page"><header class="orders-heading"><div><p class="eyebrow">ORDER HISTORY</p><h1>Your orders</h1><p>Check any recent order or continue tracking its status.</p></div><button class="button primary" data-action="new-order">Shop again ${icon("arrow", 18)}</button></header>${state.orders.length ? `<section class="order-history-list" aria-label="Recent orders">${state.orders.map((order) => {
    const [label, description] = friendlyStatus(order.status);
    const failed = ["REJECTED", "CANCELLED"].includes(order.status);
    return `<article class="order-history-card"><div class="order-history-icon ${failed ? "failed" : ""}">${icon(failed ? "close" : "water", 20)}</div><div class="order-history-main"><div><strong>${escapeHtml(order.number || "Your order")}</strong><span class="order-history-status">${escapeHtml(label)}</span></div><p>${escapeHtml(order.stationName || state.station?.name || "Your station")} · ${order.fulfillmentMode === "PICKUP" ? "Pickup" : "Delivery"}</p><small>${escapeHtml(description)}</small></div><div class="order-history-total"><strong>${formatMoney(order.totalCentavos || 0)}</strong><button class="button secondary" data-action="open-order" data-id="${escapeHtml(order.id)}">Track order ${icon("arrow", 16)}</button></div></article>`;
  }).join("")}</section>` : `<section class="empty-state orders-empty"><div class="empty-icon">${icon("bag", 31)}</div><p class="eyebrow">NO ORDERS YET</p><h2>Your orders will show here</h2><p>After placing your first order, you can return here to check its latest status.</p><button class="button primary" data-action="new-order">Browse products ${icon("arrow", 18)}</button></section>`}</main>`;
}

function randomTrackingToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

async function chooseDeliveryLocation() {
  state.notice = "";
  try {
    await openGoogleLocationPicker({
      apiKey: GOOGLE_MAPS_BROWSER_KEY,
      station: state.station,
      initialLocation: state.checkout?.placeId ? {
        placeId: state.checkout.placeId,
        address: state.checkout.address,
        latitude: state.checkout.latitude,
        longitude: state.checkout.longitude,
      } : null,
      onConfirm: (selection) => {
        Object.assign(state.checkout, {
          placeId: selection.placeId,
          address: selection.address,
          latitude: selection.latitude,
          longitude: selection.longitude,
          locationLabel: selection.address,
        });
        state.notice = "";
        render();
      },
    });
  } catch (error) {
    state.notice = error.message || "Address search is unavailable. Please choose pickup or contact the station.";
    state.noticeKind = "error";
    render();
  }
}

function captureCheckout(form) {
  if (!state.checkout) state.checkout = emptyCheckout();
  const data = new FormData(form);
  state.checkout.name = String(data.get("name") || "").trim();
  state.checkout.phone = String(data.get("phone") || "").trim();
  state.checkout.mode = String(data.get("mode") || "PICKUP");
  state.checkout.address = String(data.get("address") || "").trim();
  state.checkout.addressDetails = String(data.get("addressDetails") || "").trim();
  state.checkout.landmark = String(data.get("landmark") || "").trim();
  state.checkout.paymentMethod = String(data.get("paymentMethod") || "");
  state.checkout.paymentReference = String(data.get("paymentReference") || "").trim();
  const proofFile = data.get("paymentProof");
  if (proofFile instanceof File && proofFile.size > 0) state.checkout.paymentProofFile = proofFile;
  state.checkout.note = String(data.get("note") || "").trim();
  state.checkout.acceptedRules = data.has("acceptedRules");
  state.checkout.rememberDetails = data.has("rememberDetails");
}

function orderPayload() {
  const checkout = state.checkout;
  if (!ordersAllowed()) throw new Error("This station is not accepting online orders right now.");
  const phone = normalizePhilippineMobile(checkout.phone);
  const delivery = checkout.mode === "DELIVERY";
  const payment = availablePayments(checkout.mode).find((option) => option.value === checkout.paymentMethod);
  if (checkout.name.length < 2) throw new Error("Enter your full name.");
  if (checkout.name.length > 100) throw new Error("Your name is too long.");
  if (!phone) throw new Error("Enter a valid Philippine mobile number, such as 09XX XXX XXXX.");
  if (!payment) throw new Error("Choose a payment method this station currently accepts.");
  if (["GCASH", "MAYA"].includes(checkout.paymentMethod)) {
    if (!checkout.paymentReference || checkout.paymentReference.length > 100 || /[\u0000-\u001f\u007f]/.test(checkout.paymentReference)) {
      throw new Error("Enter the reference number from your payment receipt.");
    }
    const proof = checkout.paymentProofFile;
    if (!(proof instanceof File) || proof.size < 1 || proof.size > MAX_PAYMENT_PROOF_BYTES
      || !["image/jpeg", "image/png", "image/webp"].includes(proof.type)) {
      throw new Error("Attach a JPG, PNG or WebP payment screenshot up to 5 MB.");
    }
  }
  const locationError = deliveryLocationError({
    mode: checkout.mode,
    radiusKm: state.station.deliveryRadiusKm,
    address: checkout.address,
    placeId: checkout.placeId,
    latitude: checkout.latitude,
    longitude: checkout.longitude,
  });
  if (locationError) throw new Error(locationError);
  if (state.station.rules.trim() && !checkout.acceptedRules) throw new Error("Please review and accept the station ordering rules.");
  const items = cartLines().map(({ product, quantity, containerSource }) => ({
    product_id: product.id,
    quantity,
    ...(containerSource ? { container_source: containerSource } : {}),
  }));
  if (!items.length) throw new Error("Your cart is empty.");
  return {
    customer_name: checkout.name,
    customer_phone: phone,
    delivery_mode: checkout.mode,
    delivery_address: delivery ? checkoutAddress() : "",
    delivery_address_details: delivery ? [checkout.addressDetails, checkout.landmark ? `Near ${checkout.landmark}` : ""].filter(Boolean).join(", ") : "",
    delivery_place_id: delivery ? checkout.placeId || null : null,
    latitude: delivery ? checkout.latitude : null,
    longitude: delivery ? checkout.longitude : null,
    payment_method: checkout.paymentMethod,
    customer_note: checkout.note || null,
    accepted_rules_version: state.station.rules.trim() ? String(state.station.rulesVersion) : null,
    items,
  };
}

async function submitGuestOrder(form) {
  captureCheckout(form);
  state.notice = "";
  try {
    state.station.acceptsOrders = await loadOrderingAvailability(state.station.id);
  } catch (error) {
    if (state.station) state.station.acceptsOrders = false;
    state.notice = error.message;
    state.noticeKind = "error";
    render();
    return;
  }
  let request;
  try {
    request = orderPayload();
  } catch (error) {
    state.notice = error.message;
    state.noticeKind = "error";
    render();
    return;
  }
  const signatureHash = await orderSignature(request);
  if (!state.orderAttempt || state.orderAttempt.signatureHash !== signatureHash) {
    state.orderAttempt = {
      signatureHash,
      operationId: `guest:${crypto.randomUUID()}`,
      trackingToken: randomTrackingToken(),
    };
    try { sessionStorage.setItem(ATTEMPT_KEY, JSON.stringify(state.orderAttempt)); } catch { /* Keep the attempt in memory for this visit. */ }
  }
  request.operation_id = state.orderAttempt.operationId;
  state.orderBusy = true;
  render();
  try {
    const { data, error } = await supabase.functions.invoke("guest-order", {
      body: { action: "create", stationCode, trackingToken: state.orderAttempt.trackingToken, request },
    });
    if (error) {
      let message = "We couldn’t submit your order. Your details are still here; please try again.";
      try {
        const response = error.context instanceof Response ? await error.context.clone().json() : null;
        if (response?.error) message = response.error;
      } catch { /* Keep the recoverable checkout message. */ }
      throw new Error(message);
    }
    if (data?.error) throw new Error(data.error);
    const result = data?.order;
    if (!result?.order_id || !result?.order_number) throw new Error("We couldn’t confirm the order. Your details are still here; please try again.");
    if (["GCASH", "MAYA"].includes(request.payment_method)) {
      const proofForm = new FormData();
      proofForm.set("orderId", result.order_id);
      proofForm.set("trackingToken", state.orderAttempt.trackingToken);
      proofForm.set("paymentMethod", request.payment_method);
      proofForm.set("referenceNumber", state.checkout.paymentReference);
      proofForm.set("proof", state.checkout.paymentProofFile, state.checkout.paymentProofFile.name);
      const { data: proofData, error: proofError } = await supabase.functions.invoke("guest-payment-proof", { body: proofForm });
      if (proofError || proofData?.error) {
        let message = proofData?.error || "Your order was received, but the payment proof could not be sent. Keep this page open and try again.";
        try {
          const response = proofError?.context instanceof Response ? await proofError.context.clone().json() : null;
          if (response?.error) message = response.error;
        } catch { /* Preserve the recoverable receipt message. */ }
        throw new Error(message);
      }
    }
    state.order = {
      id: result.order_id,
      token: state.orderAttempt.trackingToken,
      number: result.order_number,
      status: result.status || "PENDING",
      totalCentavos: Number(result.total_centavos || 0),
      stationName: state.station.name,
      fulfillmentMode: request.delivery_mode,
      createdAt: Date.now(),
    };
    state.statusAnimation = null;
    persistOrderHistory();
    cacheGuestProfile(state.checkout);
    state.cart.clear();
    state.containerSources.clear();
    persistCart();
    state.checkout = emptyCheckout();
    try { sessionStorage.removeItem(ATTEMPT_KEY); } catch { /* Do not interrupt a successful order. */ }
    state.orderAttempt = null;
    state.view = "tracking";
    state.trackingError = "";
    state.notice = "";
    startOrderPolling();
  } catch (error) {
    state.notice = error.message || "We couldn’t submit your order. Your details are still here; please try again.";
    state.noticeKind = "error";
  } finally {
    state.orderBusy = false;
    render();
  }
}

function startOrderPolling() {
  window.clearInterval(trackingTimer);
  if (state.view !== "tracking" || !state.order?.id) return;
  refreshOrderStatus();
  trackingTimer = window.setInterval(refreshOrderStatus, 15_000);
}

async function refreshOrderStatus() {
  if (!state.order?.id || !state.order?.token || trackingBusy) return;
  trackingBusy = true;
  try {
    const { data, error } = await supabase.functions.invoke("guest-order", {
      body: { action: "status", orderId: state.order.id, trackingToken: state.order.token },
    });
    if (error) throw error;
    const order = data?.order;
    if (!order) throw new Error("Order tracking is unavailable.");
    const previousStatus = state.order.status;
    const nextStatus = order.status || previousStatus;
    if (nextStatus !== previousStatus && !["REJECTED", "CANCELLED"].includes(nextStatus)) {
      state.statusAnimation = {
        from: trackingProgress(state.order).progressPercent,
        to: trackingProgress({ ...state.order, status: nextStatus }).progressPercent,
      };
    } else {
      state.statusAnimation = null;
    }
    state.order = {
      ...state.order,
      number: order.order_number,
      status: nextStatus,
      totalCentavos: Number(order.total_centavos || 0),
      stationName: order.station_name || state.station?.name || "your station",
      updatedAt: new Intl.DateTimeFormat("en-PH", { hour: "numeric", minute: "2-digit" }).format(new Date(order.updated_at)),
    };
    state.trackingError = "";
    persistOrderHistory();
  } catch {
    state.trackingError = "We couldn’t refresh the order right now.";
  } finally {
    trackingBusy = false;
    if (state.view === "tracking") render();
  }
}

app?.addEventListener("click", (event) => {
  const target = event.target.closest("[data-action]");
  if (!target) return;
  const action = target.dataset.action;
  if ((action === "close-cart" || action === "close-product") && target.classList.contains("sheet-scrim") && event.target !== target) return;
  if (action === "add") {
    const product = state.products.find((item) => item.id === target.dataset.id);
    if (product?.type === "REFILL") {
      state.selectedProductId = product.id;
      state.selectedContainerSource = state.containerSources.get(product.id) || "STATION_EXCHANGE";
      render();
    } else addToCart(target.dataset.id, 1);
  }
  if (action === "increase") addToCart(target.dataset.id, 1);
  if (action === "decrease") addToCart(target.dataset.id, -1);
  if (action === "add-close") { addToCart(target.dataset.id, 1, state.selectedContainerSource); state.selectedProductId = null; render(); }
  if (action === "details") {
    state.selectedProductId = target.dataset.id;
    state.selectedContainerSource = state.containerSources.get(target.dataset.id) || "STATION_EXCHANGE";
    render();
  }
  if (action === "close-product") { state.selectedProductId = null; render(); }
  if (action === "open-cart") { state.cartSheetOpen = true; render(); }
  if (action === "close-cart") { state.cartSheetOpen = false; render(); }
  if (action === "checkout") beginCheckout();
  if (action === "back-catalog") { state.view = "catalog"; state.notice = ""; render(); }
  if (action === "category") { state.category = target.dataset.category; render(); }
  if (action === "clear-notice") { state.notice = ""; render(); }
  if (action === "open-orders") {
    window.clearInterval(trackingTimer);
    state.view = "orders";
    render();
  }
  if (action === "open-order") {
    const selectedOrder = state.orders.find((order) => order.id === target.dataset.id);
    if (selectedOrder) {
      state.order = { ...selectedOrder };
      state.statusAnimation = null;
      state.trackingError = "";
      state.view = "tracking";
      persistOrderHistory();
      startOrderPolling();
      render();
    }
  }
  if (action === "clear-profile") {
    try { localStorage.removeItem(PROFILE_KEY); } catch { /* Continue with an empty form in memory. */ }
    state.checkout = { ...emptyCheckout(), rememberDetails: false };
    state.notice = "Saved contact and delivery details were cleared from this device.";
    state.noticeKind = "info";
    render();
  }
  if (action === "remove-payment-proof") {
    if (state.checkout) state.checkout.paymentProofFile = null;
    render();
  }
  if (action === "open-location-picker") chooseDeliveryLocation();
  if (action === "retry-load") load();
  if (action === "refresh-tracking") refreshOrderStatus();
  if (action === "new-order") {
    window.clearInterval(trackingTimer);
    try { sessionStorage.removeItem(TRACK_KEY); } catch { /* Keep starting a new order when storage is blocked. */ }
    state.order = null;
    state.statusAnimation = null;
    state.view = "catalog";
    state.checkout = emptyCheckout();
    state.orderAttempt = null;
    try { sessionStorage.removeItem(ATTEMPT_KEY); } catch { /* Ignore browser storage restrictions. */ }
    state.trackingError = "";
    state.notice = "";
    render();
    window.scrollTo({ top: 0, behavior: "smooth" });
  }
});

app?.addEventListener("change", (event) => {
  const target = event.target;
  if (target.matches("[data-container-source]")) {
    state.selectedContainerSource = target.value;
    render();
  } else if (target.name === "mode") {
    state.checkout.mode = target.value;
    const options = availablePayments(target.value);
    if (!options.some((option) => option.value === state.checkout.paymentMethod)) state.checkout.paymentMethod = options[0]?.value || "";
    render();
  } else if (target.name === "paymentMethod") {
    state.checkout.paymentMethod = target.value;
    render();
  } else if (target.name === "paymentProof") {
    state.checkout.paymentProofFile = target.files?.[0] || null;
    render();
  } else if (target.matches("[data-checkout='acceptedRules']")) {
    state.checkout.acceptedRules = target.checked;
  } else if (target.matches("[data-checkout='rememberDetails']")) {
    state.checkout.rememberDetails = target.checked;
  } else if (target.dataset.focus === "sort") {
    state.sort = target.value;
    render();
  }
});

app?.addEventListener("input", (event) => {
  const target = event.target;
  if (target.dataset.checkout && state.checkout) {
    const key = target.dataset.checkout;
    if (key !== "acceptedRules" && key !== "rememberDetails") state.checkout[key] = target.value;
  }
  if (target.dataset.focus === "search") {
    state.search = target.value;
    render();
  }
});

app?.addEventListener("submit", (event) => {
  if (event.target.id !== "order-form") return;
  event.preventDefault();
  submitGuestOrder(event.target);
});

app?.addEventListener("error", (event) => {
  const image = event.target;
  if (!(image instanceof HTMLImageElement)) return;
  if (image.classList.contains("product-photo")) image.remove();
  if (image.classList.contains("station-photo")) {
    const fallback = document.createElement("span");
    fallback.className = "station-mark";
    fallback.setAttribute("aria-hidden", "true");
    fallback.innerHTML = icon("water", 22);
    image.replaceWith(fallback);
  }
}, true);

window.addEventListener("keydown", (event) => {
  const dialog = app?.querySelector('.bottom-sheet[role="dialog"]');
  if (event.key === "Tab" && dialog) {
    const focusable = [...dialog.querySelectorAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), a[href]')];
    const first = focusable[0];
    const last = focusable.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
    event.preventDefault();
    document.querySelector('[data-focus="search"]')?.focus();
  }
  if (event.key === "Escape") {
    if (state.selectedProductId) { state.selectedProductId = null; render(); }
    else if (state.cartSheetOpen) { state.cartSheetOpen = false; render(); }
  }
});

state.checkout = emptyCheckout();
render();
load();
