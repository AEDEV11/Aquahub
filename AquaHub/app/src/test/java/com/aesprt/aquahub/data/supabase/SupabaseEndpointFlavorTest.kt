package com.aesprt.aquahub.data.supabase

import com.aesprt.aquahub.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.net.URI

class SupabaseEndpointFlavorTest {
    @Test
    fun endpointMatchesItsBuildFlavor() {
        val host = runCatching { URI(BuildConfig.SUPABASE_URL).host?.lowercase() }.getOrNull()
        if (BuildConfig.QA_BUILD) {
            assertNotEquals(PRODUCTION_SUPABASE_HOST, host)
        } else {
            assertEquals(PRODUCTION_SUPABASE_HOST, host)
        }
    }

    private companion object {
        const val PRODUCTION_SUPABASE_HOST = "pfkxrggexueltytxtqil.supabase.co"
    }
}
