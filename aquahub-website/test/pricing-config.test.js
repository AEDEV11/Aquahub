const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const pricing = require('../pricing-utils.js');

const homepage = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');

function pagePlanPrices() {
  const cards = [...homepage.matchAll(/<article\b(?=[^>]*data-pricing-card)(?=[^>]*data-plan-key="([^"]+)")[^>]*>[\s\S]*?<data\b[^>]*data-price-amount[^>]*value="(\d+)"/g)];
  return Object.fromEntries(cards.map(([, plan, cents]) => [plan, Number(cents)]));
}

test('homepage has one monthly price source for each advertised plan', () => {
  assert.deepEqual(pagePlanPrices(), {
    free: 0,
    starter: 19900,
    business: 49900,
    pro: 99900
  });
});

test('annual prices and savings use a 20 percent discount in integer centavos', () => {
  assert.equal(pricing.annualDiscountPercent, 20);
  const expected = {
    starter: [191040, 47760],
    business: [479040, 119760],
    pro: [959040, 239760]
  };

  for (const [plan, monthly] of Object.entries(pagePlanPrices())) {
    if (plan === 'free') continue;
    const annual = pricing.annualPriceCentavos(monthly);
    assert.deepEqual([annual, monthly * 12 - annual], expected[plan]);
  }
});

test('Philippine peso formatting keeps annual centavos visible', () => {
  assert.equal(pricing.formatPhilippinePeso(19900), '₱199');
  assert.equal(pricing.formatPhilippinePeso(191040, true), '₱1,910.40');
  assert.equal(pricing.formatPhilippinePeso(0), '₱0');
});

test('monthly and annual calculator reject invalid price input', () => {
  assert.throws(() => pricing.annualPriceCentavos(-1), RangeError);
  assert.throws(() => pricing.annualPriceCentavos(1.5), RangeError);
  assert.throws(() => pricing.formatPhilippinePeso(-1), RangeError);
});

test('marketing page no longer promotes a downloadable Customer app', () => {
  assert.doesNotMatch(homepage, /Download AquaHub Customer|Download Customer App|data-store-link="customer"/i);
  assert.doesNotMatch(homepage, /<a\b[^>]*href="[^"]*play\.google\.com[^"]*id=com\.aesprt\.aquahub_customer/i);
  assert.doesNotMatch(homepage, /30-day trial|30-araw na trial|free trial|trial status/i);
  const siteScript = fs.readFileSync(path.join(__dirname, '..', 'main.js'), 'utf8');
  assert.doesNotMatch(siteScript, /customerPlayStoreUrl|data-store-link=["']customer/i);
});

test('support and plan inquiry email links have actionable destinations', () => {
  const links = [...homepage.matchAll(/<a\b(?=[^>]*data-email-link)([^>]*)>/g)].map(([, attributes]) => attributes);
  assert.ok(links.length >= 1);
  for (const attributes of links) {
    assert.match(attributes, /href="mailto:/i);
    assert.doesNotMatch(attributes, /href="#"/i);
  }
  assert.match(homepage, /<a\b(?=[^>]*data-email-link)[^>]*>Email Support<\/a>/i);
});

test('canonical, public page links, and station ordering routes remain available', () => {
  assert.match(homepage, /<link rel="canonical" href="https:\/\/aquahub\.aesprt\.com\/"/);
  for (const page of ['privacy', 'terms', 'support', 'account-deletion', 'subscription-policy']) {
    assert.match(homepage, new RegExp(`href="${page}\\.html"`));
    assert.ok(fs.existsSync(path.join(__dirname, '..', `${page}.html`)));
  }
  for (const anchor of ['mobile-ordering', 'features', 'pricing', 'faq']) {
    assert.match(homepage, new RegExp(`id="${anchor}"`));
  }
  const hosting = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'firebase.json'), 'utf8')).hosting;
  for (const source of ['/s/**', '/order/s/**']) {
    assert.ok(hosting.rewrites.some(rewrite => rewrite.source === source && rewrite.destination === '/order/index.html'));
  }
});

test('subscription policy matches the intended catalogue and does not publish retired monthly prices', () => {
  const policy = fs.readFileSync(path.join(__dirname, '..', 'subscription-policy.html'), 'utf8');
  assert.match(policy, /Starter:<\/strong> ₱199\/month/);
  assert.match(policy, /Business:<\/strong> ₱499\/month/);
  assert.match(policy, /Pro:<\/strong> ₱999\/month/);
  assert.doesNotMatch(policy, /₱299\/month|₱699\/month|₱1,499\/month/);
  assert.match(policy, /not a live checkout quote/);
});

test('email links remain actionable without JavaScript on every public HTML page', () => {
  const htmlFiles = fs.readdirSync(path.join(__dirname, '..')).filter(name => name.endsWith('.html'));
  assert.ok(htmlFiles.length >= 6);
  for (const file of htmlFiles) {
    const page = fs.readFileSync(path.join(__dirname, '..', file), 'utf8');
    assert.doesNotMatch(page, /<a\b(?=[^>]*(?:data-email-link|data-privacy-email))[^>]*href="#"/i, file);
  }
});
