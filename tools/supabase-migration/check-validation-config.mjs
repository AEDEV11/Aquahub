#!/usr/bin/env node

const checks = [
  ["Supabase fixture credentials", ["SUPABASE_URL", ["SUPABASE_SECRET_KEY", "SUPABASE_SERVICE_ROLE_KEY"], ["SUPABASE_PUBLISHABLE_KEY", "SUPABASE_ANON_KEY"]]],
  ["Google Play verification credentials", ["GOOGLE_PLAY_CLIENT_EMAIL", "GOOGLE_PLAY_PRIVATE_KEY", "GOOGLE_PLAY_PACKAGE_NAME"]],
  ["FCM dispatch credentials", ["FCM_PROJECT_ID", "FCM_CLIENT_EMAIL", "FCM_PRIVATE_KEY", "NOTIFICATION_DISPATCH_SECRET"]],
  ["Play RTDN push authentication", ["PLAY_RTDN_PUSH_AUDIENCE", "PLAY_RTDN_PUSH_SERVICE_ACCOUNT"]],
  ["Outbox scheduler auth", ["SUPABASE_URL", "NOTIFICATION_DISPATCH_SECRET"]],
];

const present = (name) => typeof process.env[name] === "string" && process.env[name].trim().length > 0;
let failed = false;
for (const [label, names] of checks) {
  const alternatives = names.filter(Array.isArray);
  const missing = names.filter((name) => !Array.isArray(name) && !present(name));
  const missingAlternatives = alternatives.filter((group) => !group.some(present)).map((group) => group.join(" or "));
  const ok = missing.length === 0 && missingAlternatives.length === 0;
  console.log(`${label}: ${ok ? "AVAILABLE" : "MISSING"}`);
  if (!ok) console.log(`  missing: ${[...missing, ...missingAlternatives].join(", ")}`);
  failed ||= !ok;
}

if (process.argv.includes("--strict") && failed) process.exitCode = 2;
