/** Supabase key naming compatibility for trusted migration/validation tooling. */
export const supabaseSecretKey = () =>
  process.env.SUPABASE_SECRET_KEY ?? process.env.SUPABASE_SERVICE_ROLE_KEY ?? "";

export const supabasePublishableKey = () =>
  process.env.SUPABASE_PUBLISHABLE_KEY ?? process.env.SUPABASE_ANON_KEY ?? "";
