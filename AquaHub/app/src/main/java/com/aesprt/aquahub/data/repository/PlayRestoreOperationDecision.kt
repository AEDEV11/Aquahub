package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.domain.model.BillingOperationState

/** An older token must not hide a newer purchase's failed verification. */
internal object PlayRestoreOperationDecision {
    fun choose(
        verifiedCurrent: Boolean,
        verifiedOlder: Boolean,
        currentFailure: String?,
        pendingCurrent: Boolean,
        pendingOlder: Boolean,
        failure: String?
    ): BillingOperationState = when {
        verifiedCurrent -> BillingOperationState.Completed
        currentFailure != null -> BillingOperationState.Failed(currentFailure)
        pendingCurrent -> BillingOperationState.Pending()
        verifiedOlder -> BillingOperationState.Completed
        pendingOlder -> BillingOperationState.Pending()
        failure != null -> BillingOperationState.Failed(failure)
        else -> BillingOperationState.Failed("No completed Google Play subscription was found for this Play account.")
    }
}
