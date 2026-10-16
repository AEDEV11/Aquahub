const encoder = new TextEncoder();

function hex(bytes: Uint8Array): string {
  return [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export type TrialIdentity = {
  userId: string;
  email?: string | null;
  emailVerified: boolean;
  phone?: string | null;
  phoneVerified: boolean;
};

/** Returns versioned keyed digests; raw contact identifiers are never returned or stored. */
export async function ownerTrialIdentityHashes(identity: TrialIdentity, secret: string): Promise<string[]> {
  if (secret.trim().length < 32) throw new Error("TRIAL_IDENTITY_SECRET_INVALID");
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const values: string[] = [];
  const email = identity.emailVerified ? identity.email?.trim().toLowerCase() : null;
  if (email && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) values.push(`email:${email}`);
  const phone = identity.phoneVerified ? identity.phone?.trim() : null;
  if (phone) {
    // Supabase verified phone numbers are E.164. Strip display punctuation
    // while preserving a leading plus so formatting alone cannot bypass a claim.
    const normalized = `${phone.startsWith("+") ? "+" : ""}${phone.replace(/\D/g, "")}`;
    if (normalized.replace(/\D/g, "").length >= 8) values.push(`phone:${normalized}`);
  }
  const hashes = await Promise.all([...new Set(values)].map(async (value) => {
    const signature = await crypto.subtle.sign("HMAC", key, encoder.encode(`aquahub-owner-trial-v3:${value}`));
    return hex(new Uint8Array(signature));
  }));
  return hashes.sort();
}

/** Previous UID-only digest is checked during rollout for same-account compatibility. */
export async function legacyOwnerTrialUidHash(userId: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(`aquahub-owner-trial-v2:${userId}`));
  return hex(new Uint8Array(digest));
}
