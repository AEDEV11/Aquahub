package com.aesprt.aquahub.data.repository

/** Keep provider exception details, including request headers, out of billing screens. */
internal object PlayPurchaseErrorMessages {
    private const val ACKNOWLEDGEMENT_PENDING = "PURCHASE_ACKNOWLEDGEMENT_PENDING"
    enum class Kind {
        AUTH,
        BOUND_TO_ANOTHER_ACCOUNT,
        BUSINESS_MISMATCH,
        BUSINESS_SELECTION_REQUIRED,
        REPLACEMENT_NOT_ALLOWED,
        STALE_TOKEN,
        PENDING_CANCELED,
        OTHER
    }

    fun kind(error: Throwable): Kind {
        val messages = generateSequence(error) { it.cause }.take(5).mapNotNull { it.message }.toList()
        return when {
            messages.any { "UNAUTHORIZED" in it } -> Kind.AUTH
            messages.any { "PURCHASE_ALREADY_BOUND" in it || "PURCHASE_ACCOUNT_MISMATCH" in it || "PURCHASE_BINDING_CONFLICT" in it } -> Kind.BOUND_TO_ANOTHER_ACCOUNT
            messages.any { "PURCHASE_ORGANIZATION_MISMATCH" in it } -> Kind.BUSINESS_MISMATCH
            messages.any { "PURCHASE_ORGANIZATION_REQUIRED" in it } -> Kind.BUSINESS_SELECTION_REQUIRED
            messages.any { "PURCHASE_REPLACEMENT_NOT_ALLOWED" in it } -> Kind.REPLACEMENT_NOT_ALLOWED
            messages.any { "PURCHASE_TOKEN_SUPERSEDED" in it || "PURCHASE_REPLACED_OR_STALE" in it } -> Kind.STALE_TOKEN
            messages.any { "PENDING_PURCHASE_CANCELED" in it } -> Kind.PENDING_CANCELED
            else -> Kind.OTHER
        }
    }

    fun verificationFailure(error: Throwable): String {
        val acknowledgementPending = generateSequence(error) { it.cause }
            .take(5)
            .any { it.message?.contains(ACKNOWLEDGEMENT_PENDING) == true }

        return if (acknowledgementPending) {
            "Your Google Play purchase is awaiting confirmation. Please wait a moment and check your plan again. Do not start another purchase."
        } else {
            when (kind(error)) {
                Kind.AUTH -> "Your AquaHub session has expired. Sign in again, then restore your Google Play purchase. Do not start another purchase."
                Kind.BOUND_TO_ANOTHER_ACCOUNT -> "This Google Play purchase is linked to another AquaHub account. Sign in with the account that made the purchase, or contact support. Do not buy again."
                Kind.BUSINESS_MISMATCH -> "This Google Play purchase belongs to a different AquaHub business. Switch to the business used at checkout, then restore it. Do not buy again."
                Kind.BUSINESS_SELECTION_REQUIRED -> "AquaHub cannot identify which business owns this older Google Play purchase. Contact support to restore it. Do not buy again."
                Kind.REPLACEMENT_NOT_ALLOWED -> "This Google Play subscription cannot be changed from the selected AquaHub business. Restore it or manage it in Google Play before trying again. Do not buy again."
                Kind.STALE_TOKEN -> "This is an older Google Play purchase. Restore your current subscription, or contact support if your paid plan is missing. Do not buy again."
                Kind.PENDING_CANCELED -> "Google Play cancelled this pending payment. Check your Play subscriptions before trying again."
                Kind.OTHER -> "We could not verify your Google Play purchase yet. If checkout completed, check your plan again later. Do not start another purchase."
            }
        }
    }

    fun replacementPreflightFailure(error: Throwable? = null): String = when (error?.let(::kind)) {
        Kind.AUTH -> "Your AquaHub session has expired. Sign in again before changing your Google Play plan."
        else -> "AquaHub could not confirm that this Google Play subscription belongs to the selected business. Restore it or manage it in Google Play before trying again. Do not buy again."
    }

    const val ENTITLEMENT_REFRESH_FAILURE =
        "Your purchase was verified, but your plan has not updated on this device yet. Keep the app online and check your plan again."
}
