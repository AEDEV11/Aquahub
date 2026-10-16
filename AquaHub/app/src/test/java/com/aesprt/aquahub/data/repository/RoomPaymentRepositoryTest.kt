package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.domain.model.PaymentStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomPaymentRepositoryTest {
    @Test
    fun `partial account payment remains pending`() {
        assertEquals(PaymentStatus.PENDING.name, aggregatePaymentStatus(20_000, 5_000))
        assertEquals(PaymentStatus.PENDING.name, aggregatePaymentStatus(20_000, 19_999))
    }

    @Test
    fun `only full balance marks account paid`() {
        assertEquals(PaymentStatus.PAID.name, aggregatePaymentStatus(20_000, 20_000))
    }
}
