const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const homepage = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const siteScript = fs.readFileSync(path.join(__dirname, '..', 'main.js'), 'utf8');

function visibleText(markup) {
  return markup.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
}

test('Owner download links work without JavaScript and target the exact Play package', () => {
  const links = [...homepage.matchAll(/<a\b(?=[^>]*\bdata-owner-play-cta\b)([^>]*)>([\s\S]*?)<\/a>/gi)];
  assert.ok(links.length >= 4, 'header, hero, final section, and footer');
  for (const [, attributes, content] of links) {
    assert.match(attributes, /href="https:\/\/play\.google\.com\/store\/apps\/details\?id=com\.aesprt\.aquahub"/);
    assert.match(attributes, /target="_blank"/);
    assert.match(attributes, /rel="noopener noreferrer"/);
    assert.equal(visibleText(content), 'Get AquaHub Owner');
  }
  assert.doesNotMatch(homepage, /<button\b(?=[^>]*data-owner-play-cta)/i);
  assert.doesNotMatch(homepage, /Closed testing|limited to approved testers/i);
  assert.match(homepage, /href="https:\/\/play\.google\.com\/apps\/testing\/com\.aesprt\.aquahub"/);
  assert.match(homepage, /Google Play release availability/);
});

test('Owner CTA initialization updates real links without rebuilding their content', () => {
  const links = [{ href: '', rel: '', target: '', label: 'keep accessible label' }];
  const context = vm.createContext({
    URL,
    document: { readyState: 'loading', addEventListener() {}, querySelectorAll(selector) {
      assert.equal(selector, 'a[data-owner-play-cta]'); return links;
    } }
  });
  vm.runInContext(siteScript, context);
  context.initOwnerPlayCtas();
  assert.equal(links[0].href, 'https://play.google.com/store/apps/details?id=com.aesprt.aquahub');
  assert.equal(links[0].target, '_blank');
  assert.equal(links[0].rel, 'noopener noreferrer');
  assert.equal(links[0].label, 'keep accessible label');
});

test('Owner Play URL validation accepts only the AquaHub Owner package', () => {
  const context = vm.createContext({
    URL,
    document: { addEventListener() {}, readyState: 'loading' }
  });
  vm.runInContext(siteScript, context);

  assert.equal(vm.runInContext('AQUAHUB_CONFIG.ownerPlayUrl', context), 'https://play.google.com/store/apps/details?id=com.aesprt.aquahub');
  const isOwnerPlayUrl = context.isOwnerPlayUrl;
  assert.equal(typeof isOwnerPlayUrl, 'function');
  assert.equal(isOwnerPlayUrl('https://play.google.com/store/apps/details?id=com.aesprt.aquahub'), true);
  assert.equal(isOwnerPlayUrl('https://play.google.com/store/apps/details?id=com.aesprt.aquahub&hl=en'), true);
  assert.equal(isOwnerPlayUrl('https://play.google.com/apps/testing/com.aesprt.aquahub'), true);

  for (const url of [
    'https://play.google.com/store/apps/details?id=com.aesprt.aquahub_customer',
    'https://play.google.com/store/apps/details?id=com.aesprt.aquahub&id=com.aesprt.aquahub_customer',
    'https://other@play.google.com/store/apps/details?id=com.aesprt.aquahub',
    'https://play.google.com/store/apps/details?id=com.rtaqua.aquahub',
    'https://play.google.com.evil.example/store/apps/details?id=com.aesprt.aquahub',
    'http://play.google.com/store/apps/details?id=com.aesprt.aquahub',
    'https://play.google.com/store/apps/other?id=com.aesprt.aquahub',
    'javascript:alert(1)',
    ''
  ]) {
    assert.equal(isOwnerPlayUrl(url), false, url);
  }
});
