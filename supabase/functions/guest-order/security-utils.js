function parseIpv4(value) {
  const parts = value.split(".");
  if (parts.length !== 4 || parts.some((part) => !/^\d{1,3}$/.test(part))) return null;
  const octets = parts.map(Number);
  if (octets.some((octet) => octet < 0 || octet > 255)) return null;
  return octets.map((octet) => String(octet)).join(".");
}

function expandIpv6(value) {
  if (!value || value.includes("%") || (value.match(/::/g) || []).length > 1) return null;
  let address = value.toLowerCase();
  if (address.includes(".")) {
    const splitAt = address.lastIndexOf(":");
    if (splitAt < 0) return null;
    const ipv4 = parseIpv4(address.slice(splitAt + 1));
    if (!ipv4) return null;
    const octets = ipv4.split(".").map(Number);
    address = `${address.slice(0, splitAt + 1)}${((octets[0] << 8) | octets[1]).toString(16)}:${((octets[2] << 8) | octets[3]).toString(16)}`;
  }
  const compressed = address.includes("::");
  const [leftText, rightText = ""] = compressed ? address.split("::") : [address, ""];
  const left = leftText ? leftText.split(":") : [];
  const right = rightText ? rightText.split(":") : [];
  const missing = compressed ? 8 - left.length - right.length : 0;
  if (compressed ? missing < 1 : left.length !== 8) return null;
  const groups = compressed ? [...left, ...Array(missing).fill("0"), ...right] : left;
  if (groups.length !== 8 || groups.some((group) => !/^[0-9a-f]{1,4}$/.test(group))) return null;
  return groups.map((group) => Number.parseInt(group, 16));
}

const LOCAL_DEVELOPMENT_ORIGINS = [
  "http://localhost:5173",
  "http://127.0.0.1:5173",
];

/** Build an exact Origin allowlist. Hosted runtimes receive no implicit origins. */
export function createAllowedOrigins(configuredOrigins, isLocalRuntime = false) {
  const allowed = new Set(isLocalRuntime ? LOCAL_DEVELOPMENT_ORIGINS : []);
  for (const configuredOrigin of String(configuredOrigins ?? "").split(",")) {
    try {
      const parsed = new URL(configuredOrigin.trim());
      const isLocalHttp = parsed.protocol === "http:"
        && ["localhost", "127.0.0.1"].includes(parsed.hostname);
      if ((parsed.protocol === "https:" || isLocalHttp)
        && !parsed.username && !parsed.password
        && parsed.pathname === "/" && !parsed.search && !parsed.hash) {
        allowed.add(parsed.origin);
      }
    } catch {
      // Ignore malformed and wildcard entries. Origins must be explicitly listed.
    }
  }
  return allowed;
}

/** Return a stable rate-limit identity for one trusted proxy-provided IP. */
export function normalizeClientNetwork(value) {
  if (typeof value !== "string") return null;
  const ip = value.trim();
  if (!ip || ip.includes(",") || ip.toLowerCase() === "unknown" || ip.startsWith("[") || ip.endsWith("]")) return null;
  const ipv4 = parseIpv4(ip);
  if (ipv4) return `ipv4:${ipv4}`;
  const ipv6 = expandIpv6(ip);
  if (!ipv6) return null;
  return `ipv6:${ipv6.slice(0, 4).map((group) => group.toString(16).padStart(4, "0")).join(":")}::/64`;
}
