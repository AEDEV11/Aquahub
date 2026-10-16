const encoder = new TextEncoder();

function ownedArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return buffer;
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  bytes.forEach((byte) => binary += String.fromCharCode(byte));
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64UrlDecode(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  const binary = atob(normalized);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

async function encryptionKey(): Promise<CryptoKey> {
  const configured = Deno.env.get("PLAY_TOKEN_ENCRYPTION_KEY")?.trim();
  if (!configured) throw new Error("PLAY_TOKEN_ENCRYPTION_NOT_CONFIGURED");
  let raw: Uint8Array;
  try {
    raw = Uint8Array.from(atob(configured), (character) => character.charCodeAt(0));
  } catch {
    throw new Error("PLAY_TOKEN_ENCRYPTION_KEY_INVALID");
  }
  if (raw.byteLength !== 32) throw new Error("PLAY_TOKEN_ENCRYPTION_KEY_INVALID");
  return await crypto.subtle.importKey("raw", ownedArrayBuffer(raw), { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}

/** Encrypts a Play purchase token for backend-only reconciliation storage. */
export async function encryptPlayPurchaseToken(token: string): Promise<string> {
  if (!token || token.length > 4096) throw new Error("INVALID_PURCHASE");
  const key = await encryptionKey();
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: ownedArrayBuffer(nonce) },
    key,
    ownedArrayBuffer(encoder.encode(token)),
  );
  return `v1.${base64UrlEncode(nonce)}.${base64UrlEncode(new Uint8Array(ciphertext))}`;
}

/** Decrypts only rows selected by a service-role reconciliation function. */
export async function decryptPlayPurchaseToken(encrypted: string): Promise<string> {
  const [version, noncePart, ciphertextPart, extra] = encrypted.split(".");
  if (version !== "v1" || !noncePart || !ciphertextPart || extra !== undefined) {
    throw new Error("PLAY_TOKEN_CIPHERTEXT_INVALID");
  }
  const nonce = base64UrlDecode(noncePart);
  const ciphertext = base64UrlDecode(ciphertextPart);
  if (nonce.byteLength !== 12 || ciphertext.byteLength < 17 || ciphertext.byteLength > 4112) {
    throw new Error("PLAY_TOKEN_CIPHERTEXT_INVALID");
  }
  const key = await encryptionKey();
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: ownedArrayBuffer(nonce) },
    key,
    ownedArrayBuffer(ciphertext),
  );
  const token = new TextDecoder().decode(plaintext);
  if (!token || token.length > 4096) throw new Error("PLAY_TOKEN_CIPHERTEXT_INVALID");
  return token;
}
