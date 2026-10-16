package com.aesprt.aquahub.domain.common

/** Domain-facing unit-of-work boundary; the implementation is Room-specific. */
interface LocalTransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}
