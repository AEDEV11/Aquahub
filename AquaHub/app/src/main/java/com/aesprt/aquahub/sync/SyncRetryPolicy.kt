package com.aesprt.aquahub.sync

import io.ktor.client.plugins.ResponseException

/** Retry only transport failures and HTTP responses that may recover without changing the operation. */
internal fun Throwable.isTransientSyncFailure(): Boolean = generateSequence(this) { it.cause }.any { error ->
    error is java.io.IOException ||
        error is java.net.ConnectException ||
        error is java.net.UnknownHostException ||
        error is java.net.SocketTimeoutException ||
        error is java.nio.channels.UnresolvedAddressException ||
        (error is ResponseException && error.response.status.value.isRetryableSyncStatus())
}

internal fun Int.isRetryableSyncStatus(): Boolean = this == 408 || this == 425 || this == 429 || this in 500..599

internal fun syncRetryState(failureCount: Int, maximumFailures: Int = 8): String =
    if (failureCount < maximumFailures) "RETRY" else "FAILED"
