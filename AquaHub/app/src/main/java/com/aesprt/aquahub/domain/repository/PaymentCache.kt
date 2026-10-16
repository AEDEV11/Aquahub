package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.PaymentStatus

/** Local cache seam for payments whose authority is Supabase. */
interface PaymentCache {
    suspend fun cacheRemotePayment(
        orderId: Long,
        remoteId: String,
        method: PaymentMethod,
        amountCentavos: Long,
        status: PaymentStatus,
        referenceNumber: String?,
        createdAt: Long,
        paidAt: Long?,
        provider: String?,
        providerTransactionId: String?,
        metadataJson: String?,
        operationId: String?
    )
}
