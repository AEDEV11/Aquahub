package com.aesprt.aquahub.data.supabase.auth

/** Redirects that must also be allowlisted in the hosted Supabase dashboard. */
object SupabaseAuthRedirects {
    const val PASSWORD_RECOVERY = "aquahub://auth/recovery"
    const val EMAIL_CONFIRMATION = "aquahub://auth/confirm"
}
