package com.aesprt.aquahub.domain.repository

import android.app.Activity
import com.aesprt.aquahub.domain.model.BillingAvailability
import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.BillingOperationState
import com.aesprt.aquahub.domain.model.PlayBillingOffer
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import kotlinx.coroutines.flow.StateFlow

interface GooglePlayBillingRepository {
    val availability: StateFlow<BillingAvailability>
    val offers: StateFlow<List<PlayBillingOffer>>
    val operation: StateFlow<BillingOperationState>
    /** Play ownership only; this never grants AquaHub paid access. */
    val ownedPlayProductId: StateFlow<String?>

    fun connect()
    fun purchase(
        activity: Activity,
        planCode: SubscriptionPlanCode,
        billingCycle: BillingCycle,
        oldProductId: String? = null
    )
    fun restorePurchases()
    fun openSubscriptionManagement(activity: Activity, productId: String? = null)
}
