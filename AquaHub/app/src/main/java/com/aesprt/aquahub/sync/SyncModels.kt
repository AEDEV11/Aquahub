package com.aesprt.aquahub.sync

enum class SyncResourceType {
    STATION,
    CUSTOMER,
    CUSTOMER_ADDRESS,
    PRODUCT,
    RIDER,
    ORDER,
    CONTAINER,
    CONTAINER_MOVEMENT,
    PAYMENT,
    EXPENSE,
    SUBSCRIPTION
}

enum class SyncOperation { CREATE, UPDATE, ARCHIVE, ORDER_TRANSITION, IMPORT, STOCK_ADJUSTMENT }

data class SyncSummary(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val permanentFailures: Int = 0,
    val retryableFailures: Int = 0
) {
    operator fun plus(other: SyncSummary): SyncSummary = copy(
        pushed = pushed + other.pushed,
        pulled = pulled + other.pulled,
        conflicts = conflicts + other.conflicts,
        permanentFailures = permanentFailures + other.permanentFailures,
        retryableFailures = retryableFailures + other.retryableFailures
    )
}

sealed interface SyncUiState {
    data object Synced : SyncUiState
    data object Syncing : SyncUiState
    data class Offline(val pendingChanges: Int) : SyncUiState
    data class Pending(val pendingChanges: Int) : SyncUiState
    data class Failed(val failedChanges: Int, val message: String? = null) : SyncUiState
}
