package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.domain.model.GooglePlayBillingProducts

/** Any AquaHub subscription returned by Play must be reconciled before a new checkout. */
internal object PlayCheckoutOwnershipPolicy {
    fun aquaHubProductId(products: List<String>): String? =
        products.firstOrNull { it in GooglePlayBillingProducts.productIds }

    fun hasAquaHubSubscription(products: List<String>): Boolean =
        aquaHubProductId(products) != null
}
