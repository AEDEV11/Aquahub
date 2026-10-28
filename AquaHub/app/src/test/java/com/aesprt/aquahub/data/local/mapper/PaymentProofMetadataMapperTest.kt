package com.aesprt.aquahub.data.local.mapper

import com.aesprt.aquahub.data.local.entity.PaymentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentProofMetadataMapperTest {
    @Test
    fun `payment proof object path survives Room mapping`() {
        val payment = payment(
            metadataJson = """{"delivery_payment_proof_object_path":"organizations/org/stations/station/delivery-payment-proofs/order/proof.jpg"}"""
        ).toDomain()

        assertEquals(
            "organizations/org/stations/station/delivery-payment-proofs/order/proof.jpg",
            payment.deliveryPaymentProofObjectPath
        )
    }

    @Test
    fun `customer online payment proof uses generic metadata path`() {
        val path = "organizations/org/stations/station/customer-payment-proofs/order/proof.jpg"
        val payment = payment(metadataJson = """{"payment_proof_object_path":"$path"}""").toDomain()

        assertEquals(path, payment.deliveryPaymentProofObjectPath)
    }

    @Test
    fun `missing or malformed payment metadata does not break payment history`() {
        assertNull(payment(metadataJson = null).toDomain().deliveryPaymentProofObjectPath)
        assertNull(payment(metadataJson = "not-json").toDomain().deliveryPaymentProofObjectPath)
    }

    private fun payment(metadataJson: String?) = PaymentEntity(
        orderId = 10L,
        paymentMethod = "CASH_ON_DELIVERY",
        amount = 120.0,
        status = "PAID",
        referenceNumber = null,
        createdAt = 1_728_000_000_000L,
        paidAt = 1_728_000_001_000L,
        metadataJson = metadataJson
    )
}
