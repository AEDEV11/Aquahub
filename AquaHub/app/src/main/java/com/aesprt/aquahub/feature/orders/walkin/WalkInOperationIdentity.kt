package com.aesprt.aquahub.feature.orders.walkin

import java.util.UUID

/** Keeps an idempotency key for identical retries and rotates it when the sale payload changes. */
internal class WalkInOperationIdentity(
    private val newId: () -> String = { "owner:walk-in:${UUID.randomUUID()}" }
) {
    private var lastFingerprint: Any? = null
    private var lastOperationId: String? = null

    fun forRequest(fingerprint: Any, initialOperationId: String): String {
        if (lastFingerprint == fingerprint) return requireNotNull(lastOperationId)
        val operationId = if (lastFingerprint == null) initialOperationId else newId()
        lastFingerprint = fingerprint
        lastOperationId = operationId
        return operationId
    }

    fun reset() {
        lastFingerprint = null
        lastOperationId = null
    }
}
