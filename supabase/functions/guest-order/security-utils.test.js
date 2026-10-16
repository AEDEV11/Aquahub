import test from "node:test";
import assert from "node:assert/strict";
import { createAllowedOrigins, normalizeClientNetwork } from "./security-utils.js";
import { GooglePlaceUnavailableError, InvalidGooglePlaceError, resolveGooglePlace } from "./google-places.js";

test("normalizes IPv4 addresses while keeping distinct peers separate", () => {
  assert.equal(normalizeClientNetwork("203.0.113.7"), "ipv4:203.0.113.7");
  assert.equal(normalizeClientNetwork("203.0.113.007"), "ipv4:203.0.113.7");
  assert.notEqual(normalizeClientNetwork("203.0.113.7"), normalizeClientNetwork("203.0.113.8"));
});

test("groups IPv6 privacy addresses by /64 and canonicalizes notation", () => {
  assert.equal(normalizeClientNetwork("2001:db8:abcd:12::1"), "ipv6:2001:0db8:abcd:0012::/64");
  assert.equal(normalizeClientNetwork("2001:0db8:abcd:0012:ffff::2"), "ipv6:2001:0db8:abcd:0012::/64");
  assert.notEqual(normalizeClientNetwork("2001:db8:abcd:13::1"), "ipv6:2001:0db8:abcd:0012::/64");
});

test("rejects untrusted, ambiguous, and malformed IP header values", () => {
  for (const value of [undefined, "", "unknown", "203.0.113.1, 10.0.0.1", "300.1.1.1", "2001:::1", "fe80::1%eth0", "[2001:db8::1]"]) {
    assert.equal(normalizeClientNetwork(value), null, String(value));
  }
});

test("hosted CORS origins are explicit and environment-specific", () => {
  const staging = createAllowedOrigins("https://aquahub-stage-preview.web.app");
  assert.deepEqual([...staging], ["https://aquahub-stage-preview.web.app"]);
  assert.equal(staging.has("https://aquahub.aesprt.com"), false);
  assert.equal(staging.has("http://localhost:5173"), false);
});

test("hosted CORS fails closed when no origins are configured", () => {
  assert.deepEqual([...createAllowedOrigins("")], []);
});

test("local CORS keeps only localhost defaults and still accepts configured HTTPS", () => {
  const local = createAllowedOrigins("https://localhost-preview.web.app", true);
  assert.equal(local.has("http://localhost:5173"), true);
  assert.equal(local.has("http://127.0.0.1:5173"), true);
  assert.equal(local.has("https://localhost-preview.web.app"), true);
  assert.equal(local.has("https://aquahub.aesprt.com"), false);
});

test("CORS parser rejects wildcard, non-origin URLs, and remote HTTP", () => {
  const allowed = createAllowedOrigins("*,https://site.example/path,https://user:pass@site.example,http://site.example,https://ok.example");
  assert.deepEqual([...allowed], ["https://ok.example"]);
});

test("resolves only the requested Google place fields with the server key", async () => {
  let request;
  const place = await resolveGooglePlace("place/a", "server-key-test", async (url, options) => {
    request = { url, options };
    return new Response(JSON.stringify({ id: "place/a", formattedAddress: "1 Sample Road, Cagayan de Oro", location: { latitude: 8.48, longitude: 124.65 } }), { status: 200 });
  });
  assert.equal(request.url, "https://places.googleapis.com/v1/places/place%2Fa");
  assert.equal(request.options.headers["X-Goog-Api-Key"], "server-key-test");
  assert.equal(request.options.headers["X-Goog-FieldMask"], "id,formattedAddress,location");
  assert.deepEqual(place, { id: "place/a", address: "1 Sample Road, Cagayan de Oro", latitude: 8.48, longitude: 124.65 });
});

test("fails closed for missing keys, invalid place results, and provider failures", async () => {
  await assert.rejects(
    () => resolveGooglePlace("place/a", ""),
    (error) => error instanceof GooglePlaceUnavailableError && error.reason === "missing_server_key"
  );
  await assert.rejects(() => resolveGooglePlace("place/a", "server-key-test", async () => new Response("{}", { status: 404 })), InvalidGooglePlaceError);
  await assert.rejects(
    () => resolveGooglePlace("place/a", "server-key-test", async () => new Response("{}", { status: 503 })),
    (error) => error instanceof GooglePlaceUnavailableError
      && error.reason === "provider_http_error"
      && error.status === 503
  );
  await assert.rejects(
    () => resolveGooglePlace("place/a", "server-key-test", async () => { throw new Error("network detail"); }),
    (error) => error instanceof GooglePlaceUnavailableError && error.reason === "network_error"
  );
  await assert.rejects(() => resolveGooglePlace("place/a", "server-key-test", async () => new Response(JSON.stringify({ id: "place/a", formattedAddress: "No point", location: { latitude: 200, longitude: 10 } }), { status: 200 })), InvalidGooglePlaceError);
});
