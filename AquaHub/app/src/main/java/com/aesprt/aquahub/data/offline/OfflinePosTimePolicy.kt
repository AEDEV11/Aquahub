package com.aesprt.aquahub.data.offline

/** Pure policy used by production code and deterministic unit tests. */
object OfflinePosTimePolicy {
    private const val MAX_GRANT_MS = 12 * 60 * 60 * 1000L
    private const val MAX_LEASE_MS = 24 * 60 * 60 * 1000L
    private const val CLOCK_SKEW_MS = 2 * 60 * 1000L

    fun trustedServerNowMs(
        issuedAtMs: Long,
        grantExpiresAtMs: Long,
        leaseExpiresAtMs: Long,
        serverTimeAnchorMs: Long,
        elapsedRealtimeAnchorMs: Long,
        currentElapsedRealtimeMs: Long,
        currentWallClockMs: Long,
        grantBootCount: Int,
        currentBootCount: Int?
    ): Long? {
        if (currentBootCount == null || currentBootCount != grantBootCount) return null
        if (grantExpiresAtMs <= issuedAtMs || grantExpiresAtMs - issuedAtMs > MAX_GRANT_MS) return null
        if (leaseExpiresAtMs <= issuedAtMs || leaseExpiresAtMs - issuedAtMs > MAX_LEASE_MS) return null
        if (elapsedRealtimeAnchorMs < 0 || currentElapsedRealtimeMs < elapsedRealtimeAnchorMs) return null

        val monotonicDelta = currentElapsedRealtimeMs - elapsedRealtimeAnchorMs
        val serverNow = serverTimeAnchorMs + monotonicDelta
        // Wall time is only a consistency check. The monotonic server-time anchor
        // determines validity; rollback, a large forward jump, or tampering fails closed.
        if (kotlin.math.abs(currentWallClockMs - serverNow) > CLOCK_SKEW_MS) return null
        if (serverNow < issuedAtMs - CLOCK_SKEW_MS) return null
        if (serverNow >= grantExpiresAtMs || serverNow >= leaseExpiresAtMs) return null
        return serverNow
    }
}
