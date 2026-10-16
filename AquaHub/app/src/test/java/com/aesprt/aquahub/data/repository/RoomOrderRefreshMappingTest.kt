package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.domain.model.PaymentMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomOrderRefreshMappingTest {
    @Test
    fun `refresh preserves cached customer while remote customer mapping is unresolved`() {
        assertEquals(
            42L,
            resolveRefreshedOrderCustomerId(
                remoteCustomerId = "remote-customer",
                mappedLocalCustomerId = null,
                cachedLocalCustomerId = 42L
            )
        )
    }

    @Test
    fun `refresh uses resolved customer mapping when available`() {
        assertEquals(
            84L,
            resolveRefreshedOrderCustomerId(
                remoteCustomerId = "remote-customer",
                mappedLocalCustomerId = 84L,
                cachedLocalCustomerId = 42L
            )
        )
    }

    @Test
    fun `refresh keeps account order classified as client account after cash receipt`() {
        assertEquals(
            PaymentMethod.CLIENT_ACCOUNT.name,
            resolveRefreshedOrderPaymentMethod(
                remotePaymentMethod = PaymentMethod.CASH.name,
                cachedPaymentMethod = PaymentMethod.CLIENT_ACCOUNT.name
            )
        )
    }

    @Test
    fun `refresh still updates non-account payment methods from server`() {
        assertEquals(
            PaymentMethod.CASH.name,
            resolveRefreshedOrderPaymentMethod(
                remotePaymentMethod = PaymentMethod.CASH.name,
                cachedPaymentMethod = PaymentMethod.CASH_ON_DELIVERY.name
            )
        )
    }
}
