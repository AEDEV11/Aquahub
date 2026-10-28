package com.aesprt.aquahub.data.offline

import com.aesprt.aquahub.core.config.ReleaseFeatureFlags
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseOfflinePosDataSourceReleaseGateTest {
    @Test
    fun `disabled release cannot request authorization or a stock lease`() = runTest {
        val source = SupabaseOfflinePosDataSource(offlinePosEnabled = false)

        val error = runCatching {
            source.authorize(
                organizationId = "org",
                stationId = "station",
                deviceId = "device",
                publicKeyDerBase64 = "key",
                verificationPublicKeySha256 = "fingerprint",
                grantId = "grant",
                leaseId = "lease",
                items = listOf("product" to 1),
                elapsedRealtimeAnchorMs = 1L,
                bootCount = 1,
            )
        }.exceptionOrNull()

        assertEquals(ReleaseFeatureFlags.OFFLINE_POS_DISABLED_MESSAGE, error?.message)
    }

    @Test
    fun `disabled release cannot commit a locally saved offline receipt`() = runTest {
        val source = SupabaseOfflinePosDataSource(offlinePosEnabled = false)

        val error = runCatching {
            source.commit(
                deviceId = "device",
                grantToken = "grant",
                leaseToken = "lease",
                canonicalPayload = "{}",
                payloadDigest = "digest",
                deviceSignature = "signature",
            )
        }.exceptionOrNull()

        assertEquals(ReleaseFeatureFlags.OFFLINE_POS_RECOVERY_MESSAGE, error?.message)
    }
}
