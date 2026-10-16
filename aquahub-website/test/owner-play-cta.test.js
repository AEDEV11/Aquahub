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

test('Owner Play CTAs explain closed-test availability without a dead download link', () => {
  const buttons = [...homepage.matchAll(/<button\b(?=[^>]*\bdata-owner-play-cta\b)([^>]*)>([\s\S]*?)<\/button>/gi)];
  assert.ok(buttons.length >= 1);

  for (const [, attributes, content] of buttons) {
    assert.match(attributes, /\sdisabled(?:\s|=|$)/i);
    assert.equal(visibleText(content), 'Get Owner App on Google Play');
  }

  assert.match(homepage, /Closed testing/i);
  const emailLinks = [...homepage.matchAll(/<a\b(?=[^>]*href="mailto:)([^>]*)>([\s\S]*?)<\/a>/gi)];
  assert.ok(emailLinks.some(([, , content]) => /test(?:er|ing) access/i.test(visibleText(content))));
  assert.doesNotMatch(homepage, /<a\b(?=[^>]*\bdata-owner-play-cta\b)[^>]*href="(?:#|mailto:)/i);
});

test('Owner Play URL validation accepts only the AquaHub Owner package', () => {
  const context = vm.createContext({
    URL,
    document: { addEventListener() {}, readyState: 'loading' }
  });
  vm.runInContext(siteScript, context);

  assert.equal(vm.runInContext('AQUAHUB_CONFIG.ownerPlayUrl', context), '');
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
