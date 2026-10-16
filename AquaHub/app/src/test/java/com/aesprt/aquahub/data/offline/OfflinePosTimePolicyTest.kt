package com.aesprt.aquahub.data.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePosTimePolicyTest {
    @Test
    fun derivesTrustedTimeFromMonotonicAnchor() {
        val issuedAt = 1_000_000L
        val anchor = issuedAt + 1_000L
        val elapsedAnchor = 50_000L

        assertEquals(
            anchor + 5_000L,
            OfflinePosTimePolicy.trustedServerNowMs(
                issuedAtMs = issuedAt,
                grantExpiresAtMs = issuedAt + EIGHT_HOURS,
                leaseExpiresAtMs = issuedAt + EIGHT_HOURS,
                serverTimeAnchorMs = anchor,
                elapsedRealtimeAnchorMs = elapsedAnchor,
                currentElapsedRealtimeMs = elapsedAnchor + 5_000L,
                currentWallClockMs = anchor + 5_000L,
                grantBootCount = 7,
                currentBootCount = 7,
            ),
        )
    }

    @Test
    fun failsClosedOnGrantOrLeaseExpiryAndOverlongDurations() {
        val issuedAt = 1_000_000L
        val elapsed = 60_000L
        val anchor = issuedAt + 1_000L

        assertNull(trustedNow(issuedAt, issuedAt + 12 * 60 * 60 * 1000L + 1, issuedAt + EIGHT_HOURS, anchor, elapsed, anchor))
        assertNull(trustedNow(issuedAt, issuedAt + EIGHT_HOURS, issuedAt + 24 * 60 * 60 * 1000L + 1, anchor, elapsed, anchor))
        assertNull(trustedNow(issuedAt, issuedAt + 500L, issuedAt + EIGHT_HOURS, anchor, elapsed, anchor))
        assertNull(trustedNow(issuedAt, issuedAt + EIGHT_HOURS, issuedAt + 500L, anchor, elapsed, anchor))
    }

    @Test
    fun failsClosedOnClockRollbackBootChangeOrElapsedRealtimeRollback() {
        val issuedAt = 1_000_000L
        val anchor = issuedAt + 1_000L
        val elapsed = 60_000L

        assertNull(trustedNow(issuedAt, issuedAt + EIGHT_HOURS, issuedAt + EIGHT_HOURS, anchor, elapsed, anchor - 180_000L))
        assertNull(trustedNow(issuedAt, issuedAt + EIGHT_HOURS, issuedAt + EIGHT_HOURS, anchor, elapsed, anchor, currentBootCount = 8))
        assertNull(trustedNow(issuedAt, issuedAt + EIGHT_HOURS, issuedAt + EIGHT_HOURS, anchor, elapsed, anchor, currentElapsed = elapsed - 1))
    }

    private fun trustedNow(
        issuedAt: Long,
        grantExpires: Long,
        leaseExpires: Long,
        serverAnchor: Long,
        elapsedAnchor: Long,
        wallClock: Long,
        currentElapsed: Long = elapsedAnchor,
        currentBootCount: Int? = 7,
    ) = OfflinePosTimePolicy.trustedServerNowMs(
        issuedAtMs = issuedAt,
        grantExpiresAtMs = grantExpires,
        leaseExpiresAtMs = leaseExpires,
        serverTimeAnchorMs = serverAnchor,
        elapsedRealtimeAnchorMs = elapsedAnchor,
        currentElapsedRealtimeMs = currentElapsed,
        currentWallClockMs = wallClock,
        grantBootCount = 7,
        currentBootCount = currentBootCount,
    )

    private companion object {
        const val EIGHT_HOURS = 8 * 60 * 60 * 1000L
    }
}
