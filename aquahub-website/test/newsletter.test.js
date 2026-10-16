const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const newsletter = require('../newsletter-utils.js');

const homepage = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const script = fs.readFileSync(path.join(__dirname, '..', 'newsletter.js'), 'utf8');

test('newsletter validation normalizes intent and requires explicit marketing consent', () => {
  assert.equal(newsletter.isValidEmail('owner@example.com'), true);
  assert.equal(newsletter.isValidEmail('owner@'), false);
  assert.equal(newsletter.validateNewsletter('owner@example.com', false).field, 'consent');
  assert.equal(newsletter.validateNewsletter('owner@example.com', true).valid, true);
});

test('newsletter component is placed before the homepage footer and links the Privacy Policy', () => {
  const newsletterPosition = homepage.indexOf('id="newsletter"');
  const footerPosition = homepage.indexOf('<footer class="site-footer">');
  assert.ok(newsletterPosition >= 0 && newsletterPosition < footerPosition);
  assert.match(homepage, /name="email"[^>]*required/);
  assert.match(homepage, /name="consent"[^>]*required/);
  assert.match(homepage, /href="privacy\.html"/);
  assert.match(homepage, /Stay Updated with AquaHub/);
});

test('newsletter request sends consent only to the server endpoint and never includes provider credentials', () => {
  assert.match(script, /functions\/v1\/newsletter-subscribe/);
  assert.match(script, /consent: consent\.checked/);
  assert.match(script, /Your request has been saved\./);
  assert.doesNotMatch(script, /Check your inbox for AquaHub updates/);
  assert.doesNotMatch(script, /HOSTINGER_REACH_API_TOKEN|service_role|Bearer /i);
  assert.match(script, /AbortController/);
  assert.match(script, /submitting\) return/);
});
