import test from "node:test";
import assert from "node:assert/strict";
import { publicAssetUrl } from "../src/storage-assets.js";

function storageStub() {
  const calls = [];
  return {
    calls,
    from(bucket) {
      return {
        getPublicUrl(path) {
          calls.push({ bucket, path });
          return { data: { publicUrl: `https://storage.example/${bucket}/${path}` } };
        },
      };
    },
  };
}

test("resolves an owner-saved Supabase bucket URI as a public object path", () => {
  const storage = storageStub();
  assert.equal(
    publicAssetUrl(storage, "product-images", "supabase://product-images/station/item.webp"),
    "https://storage.example/product-images/station/item.webp",
  );
  assert.deepEqual(storage.calls, [{ bucket: "product-images", path: "station/item.webp" }]);
});

test("preserves direct URLs and raw Storage object paths", () => {
  const storage = storageStub();
  assert.equal(publicAssetUrl(storage, "station-assets", "https://cdn.example/logo.webp"), "https://cdn.example/logo.webp");
  assert.equal(publicAssetUrl(storage, "product-images", "station/item.webp"), "https://storage.example/product-images/station/item.webp");
  assert.deepEqual(storage.calls, [{ bucket: "product-images", path: "station/item.webp" }]);
});

test("rejects malformed or cross-bucket Supabase references", () => {
  const storage = storageStub();
  assert.equal(publicAssetUrl(storage, "product-images", "supabase://station-assets/logo.webp"), "");
  assert.equal(publicAssetUrl(storage, "product-images", "supabase://product-images"), "");
  assert.deepEqual(storage.calls, []);
});
