const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const root = path.join(__dirname, '..');
const origin = 'https://aquahub.aesprt.com';
const files = fs.readdirSync(root).filter(file => file.endsWith('.html'));
const pages = Object.fromEntries(files.map(file => [file, fs.readFileSync(path.join(root, file), 'utf8')]));

function localFile(pathname) {
  let file = path.join(root, decodeURIComponent(pathname));
  if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
  if (!fs.existsSync(file) && !path.extname(file)) file += '.html';
  return file;
}

test('every public page has working local links, fragment targets, and assets', () => {
  for (const [file, html] of Object.entries(pages)) {
    const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
    assert.equal(new Set(ids).size, ids.length, `${file}: duplicate IDs`);
    const documentUrl = new URL(file === 'index.html' ? '/' : `/${file}`, origin);
    const baseMatch = html.match(/<base\b[^>]*href="([^"]+)"/i);
    const base = baseMatch ? new URL(baseMatch[1], documentUrl) : documentUrl;
    for (const [, ref] of html.matchAll(/\b(?:href|src)="([^"]+)"/g)) {
      const url = new URL(ref.replaceAll('&amp;', '&'), base);
      if (url.origin !== origin) continue;
      const target = localFile(url.pathname);
      assert.ok(fs.existsSync(target), `${file}: missing ${ref}`);
      if (url.hash && target.endsWith('.html')) {
        assert.ok(fs.readFileSync(target, 'utf8').includes(`id="${url.hash.slice(1)}"`), `${file}: broken fragment ${ref}`);
      }
    }
  }
});

test('public pages use SVG icons instead of emoji and Unicode arrow controls', () => {
  for (const [file, html] of Object.entries(pages)) {
    assert.doesNotMatch(html, /[\u2190-\u21ff\u{1f300}-\u{1faff}]/u, file);
    for (const [svg] of html.matchAll(/<svg\b[^>]*>/g)) {
      assert.match(svg, /aria-hidden="true"/, file);
      assert.match(svg, /focusable="false"/, file);
    }
  }
});

test('public copy matches online-only checkout and real open-test billing', () => {
  for (const file of ['index.html', 'privacy.html', 'terms.html', 'support.html']) {
    assert.match(pages[file].replace(/\s+/g, ' '), /offline (?:checkout|sales) (?:is|are) not available/i, file);
  }
  assert.doesNotMatch(pages['index.html'], /remain available offline|Local-first operations|approved testers/i);
  assert.match(pages['subscription-policy.html'], /Joining open testing does not make subscriptions free/);
  assert.match(pages['subscription-policy.html'], /manage subscriptions on Google Play/);
  assert.doesNotMatch(pages['subscription-policy.html'], /Keep this page synchronized|before launch/);
});

test('privacy covers guest orders, proof uploads, browser storage, hosting and SMS', () => {
  for (const detail of ['Browser ordering information', 'receipt image', 'browser storage', 'Cloudflare', 'Semaphore', 'Hostinger Reach', 'Supabase']) {
    assert.ok(pages['privacy.html'].toLowerCase().includes(detail.toLowerCase()), detail);
  }
  assert.match(pages['account-deletion.html'], /data-email-subject="AquaHub Account Deletion Request"/);
  assert.match(pages['account-deletion.html'], /browser order without an AquaHub account/);
});

test('legal pages link all policies and have canonical metadata', () => {
  const legal = ['privacy', 'terms', 'subscription-policy', 'account-deletion', 'support'];
  for (const page of legal) {
    const html = pages[`${page}.html`];
    assert.ok(html.includes(`rel="canonical" href="${origin}/${page}"`));
    const footer = html.match(/<footer\b[\s\S]*?<\/footer>/)?.[0] || '';
    for (const link of legal) assert.ok(footer.includes(`href="${link}.html"`), `${page} missing ${link}`);
  }
  assert.match(pages['404.html'], /name="robots" content="noindex"/);
});

test('hosting preserves ordering routes and excludes development files', () => {
  const config = JSON.parse(fs.readFileSync(path.join(root, 'firebase.json'), 'utf8')).hosting;
  for (const route of ['/s/**', '/order/s/**']) {
    assert.ok(config.rewrites.some(rule => rule.source === route && rule.destination === '/order/index.html'));
  }
  for (const ignored of ['order-app/**', '**/.env*', '**/node_modules/**', '**/*.test.js', '**/*.md']) {
    assert.ok(config.ignore.includes(ignored), ignored);
  }
  assert.ok(config.headers.find(rule => rule.source === '**').headers.some(header => header.key === 'X-Content-Type-Options' && header.value === 'nosniff'));
});
