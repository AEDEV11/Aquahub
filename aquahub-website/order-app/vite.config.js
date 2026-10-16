import { defineConfig, loadEnv } from "vite";

const productionSupabaseProjectRef = "pfkxrggexueltytxtqil";
const isPlaceholder = (value) => /replace[-_ ]/i.test(value || "");

function getSupabaseProjectRef(value) {
  try {
    return new URL(value).hostname.match(/^([a-z0-9]{20})\.supabase\.co$/)?.[1] ?? null;
  } catch {
    return null;
  }
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "VITE_");
  if (mode === "staging") {
    const projectRef = getSupabaseProjectRef(env.VITE_SUPABASE_URL);
    const hasPublishableKey = Boolean(env.VITE_SUPABASE_PUBLISHABLE_KEY || env.VITE_SUPABASE_ANON_KEY);
    if (env.VITE_SUPABASE_ENV !== "staging" || !projectRef || projectRef === productionSupabaseProjectRef
      || !hasPublishableKey || !env.VITE_GOOGLE_MAPS_BROWSER_KEY
      || isPlaceholder(env.VITE_SUPABASE_PUBLISHABLE_KEY || env.VITE_SUPABASE_ANON_KEY)
      || isPlaceholder(env.VITE_GOOGLE_MAPS_BROWSER_KEY)) {
      throw new Error("Staging build requires staging Supabase URL, public key, Maps key, and VITE_SUPABASE_ENV=staging; production project references are rejected.");
    }
    console.info(`Validated staging Supabase project ${projectRef}.`);
  }

  if (mode === "production") {
    const projectRef = getSupabaseProjectRef(env.VITE_SUPABASE_URL);
    const publicKey = env.VITE_SUPABASE_PUBLISHABLE_KEY || env.VITE_SUPABASE_ANON_KEY || "";
    if (env.VITE_SUPABASE_ENV !== "production" || projectRef !== productionSupabaseProjectRef
      || !publicKey || publicKey.startsWith("sb_secret_") || isPlaceholder(publicKey)
      || !env.VITE_GOOGLE_MAPS_BROWSER_KEY || isPlaceholder(env.VITE_GOOGLE_MAPS_BROWSER_KEY)) {
      throw new Error("Production build requires the approved Supabase project, a public Supabase key, and a restricted Google Maps browser key.");
    }
    console.info(`Validated production Supabase project ${projectRef}.`);
  }

  return {
    base: "/order/",
    build: {
      outDir: "../order",
      emptyOutDir: true,
    },
  };
});
