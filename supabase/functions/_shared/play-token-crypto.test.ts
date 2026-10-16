import { decryptPlayPurchaseToken, encryptPlayPurchaseToken } from "./play-token-crypto.ts";

function assertEquals(actual: unknown, expected: unknown): void {
  if (actual !== expected) throw new Error(`Expected ${String(expected)}; received ${String(actual)}`);
}

async function assertRejects(operation: () => Promise<unknown>): Promise<void> {
  try {
    await operation();
  } catch {
    return;
  }
  throw new Error("Expected operation to reject");
}

Deno.test("Play token encryption round-trips without storing plaintext", async () => {
  const previous = Deno.env.get("PLAY_TOKEN_ENCRYPTION_KEY");
  Deno.env.set("PLAY_TOKEN_ENCRYPTION_KEY", btoa("0123456789abcdef0123456789abcdef"));
  try {
    const token = "test-purchase-token-that-is-never-persisted-in-plaintext";
    const encrypted = await encryptPlayPurchaseToken(token);
    assertEquals(encrypted.includes(token), false);
    assertEquals(await decryptPlayPurchaseToken(encrypted), token);
  } finally {
    if (previous === undefined) Deno.env.delete("PLAY_TOKEN_ENCRYPTION_KEY");
    else Deno.env.set("PLAY_TOKEN_ENCRYPTION_KEY", previous);
  }
});

Deno.test("Play token ciphertext authentication rejects tampering", async () => {
  const previous = Deno.env.get("PLAY_TOKEN_ENCRYPTION_KEY");
  Deno.env.set("PLAY_TOKEN_ENCRYPTION_KEY", btoa("0123456789abcdef0123456789abcdef"));
  try {
    const encrypted = await encryptPlayPurchaseToken("purchase-token-for-tamper-test");
    const [version, nonce, ciphertext] = encrypted.split(".");
    const firstCharacter = ciphertext[0] === "A" ? "B" : "A";
    const tampered = `${version}.${nonce}.${firstCharacter}${ciphertext.slice(1)}`;
    await assertRejects(() => decryptPlayPurchaseToken(tampered));
  } finally {
    if (previous === undefined) Deno.env.delete("PLAY_TOKEN_ENCRYPTION_KEY");
    else Deno.env.set("PLAY_TOKEN_ENCRYPTION_KEY", previous);
  }
});
