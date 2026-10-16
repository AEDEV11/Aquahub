package com.aesprt.aquahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.entity.OrderEntity
import com.aesprt.aquahub.data.local.entity.OfflinePosOperationEntity
import com.aesprt.aquahub.data.local.entity.PaymentEntity
import com.aesprt.aquahub.data.local.entity.PaymentRefundEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectedRevenueTest {
    @Test
    fun newlySyncedOnlineOrderUpdatesDashboardSummaryAndOpenOrderBadge() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val reportRepository = RoomReportRepository(
                orderDao = database.orderDao(),
                orderItemDao = database.orderItemDao(),
                paymentDao = database.paymentDao(),
                offlinePosDao = database.offlinePosDao(),
                pendingOperationsProfileUid = { "owner-a" },
            )
            val dashboard = reportRepository.observeDashboardSummary(STATION_ID)
            assertEquals(0, dashboard.first().todayOrdersCount)

            val now = System.currentTimeMillis()
            database.orderDao().insertOrder(
                order("online-order", 70.0, "PENDING", now).copy(
                    orderStatus = "PENDING",
                    updatedAt = now,
                    completedAt = null,
                ),
            )

            val refreshedSummary = dashboard.first { it.todayOrdersCount == 1 }
            assertEquals(1, refreshedSummary.pendingOrdersCount)
            assertEquals(1, database.orderDao().observeUncompletedOrdersCount(STATION_ID).first())
        } finally {
            database.close()
        }
    }

    @Test
    fun updatingAnOrderDoesNotDeleteItsPaymentLedger() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val original = order("account-charge", 160.0, "PENDING", 1_200L)
            val orderId = database.orderDao().insertOrder(original)
            val paymentId = database.paymentDao().insertPayment(payment(orderId, 60.0, 1_300L))

            database.orderDao().upsertOrderPreservingChildren(
                original.copy(
                    id = orderId,
                    paymentStatus = "PAID",
                    updatedAt = 1_400L,
                ),
            )

            assertEquals(paymentId, database.paymentDao().getPaymentById(paymentId)?.id)
        } finally {
            database.close()
        }
    }

    @Test
    fun completedAccountChargesCountOnlyPaidReceipts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val partialOrder = database.orderDao().insertOrder(order("partial", 160.0, "PENDING", 1_200L))
            database.paymentDao().insertPayment(payment(partialOrder, 60.0, 1_300L))

            val paidOrder = database.orderDao().insertOrder(order("paid", 100.0, "PAID", 1_350L))
            database.paymentDao().insertPayment(payment(paidOrder, 100.0, 1_400L))

            // An aggregate order status without a receipt is not cash collected.
            database.orderDao().insertOrder(order("legacy", 40.0, "PAID", 1_500L))

            // Completing fulfilment without a receipt must not increase revenue.
            database.orderDao().insertOrder(order("unpaid", 999.0, "PENDING", 1_600L))

            val collected = database.paymentDao().observeCollectedRevenue(
                stationId = STATION_ID,
                start = 1_000L,
                end = 2_000L,
            ).first()

            assertEquals(160.0, collected, 0.001)
        } finally {
            database.close()
        }
    }

    @Test
    fun pendingOfflineCashIsShownSeparatelyFromConfirmedRevenue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val now = System.currentTimeMillis()
            database.offlinePosDao().insertOperation(
                OfflinePosOperationEntity(
                    operationId = "pending-offline-operation",
                    profileUid = "owner-a",
                    organizationId = "org-a",
                    stationId = STATION_ID,
                    deviceId = "device-a",
                    grantId = "grant-a",
                    leaseId = "lease-a",
                    localOrderId = null,
                    transactionAtMs = now,
                    elapsedRealtimeAtMs = 10_000L,
                    bootCount = 1,
                    totalCentavos = 12_345L,
                    cashReceivedCentavos = 15_000L,
                    changeCentavos = 2_655L,
                    payloadDigest = "a".repeat(64),
                    canonicalPayload = "{}",
                    deviceSignature = "signature",
                    createdAtMs = now,
                ),
            )
            val reportRepository = RoomReportRepository(
                orderDao = database.orderDao(),
                orderItemDao = database.orderItemDao(),
                paymentDao = database.paymentDao(),
                offlinePosDao = database.offlinePosDao(),
                pendingOperationsProfileUid = { "owner-a" },
            )

            val summary = reportRepository.getTodaySales(
                stationId = STATION_ID,
                startOfDay = now - 60_000L,
                endOfDay = now + 60_000L,
            )

            assertEquals(0.0, summary.totalSales, 0.001)
            assertEquals(123.45, summary.pendingOfflineCash, 0.001)
        } finally {
            database.close()
        }
    }

    @Test
    fun completedRefundsReduceCollectedRevenueAndFullyRefundedOrdersLeavePaidCount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val partialOrderId = database.orderDao().insertOrder(order("partial-refund", 100.0, "PAID", 1_100L))
            val partialPaymentId = database.paymentDao().insertPayment(payment(partialOrderId, 100.0, 1_100L))
            database.paymentRefundDao().insert(
                refund(partialOrderId, partialPaymentId, "partial", 2_500L, "COMPLETED", 1_700L),
            )
            // Pending and failed requests are not cash returned and must not reduce sales.
            database.paymentRefundDao().insert(
                refund(partialOrderId, partialPaymentId, "pending", 2_500L, "PENDING", null),
            )
            database.paymentRefundDao().insert(
                refund(partialOrderId, partialPaymentId, "failed", 5_000L, "FAILED", null),
            )

            val fullyRefundedOrderId = database.orderDao().insertOrder(order("full-refund", 40.0, "REFUNDED", 1_200L))
            val fullyRefundedPaymentId = database.paymentDao().insertPayment(payment(fullyRefundedOrderId, 40.0, 1_200L))
            database.paymentRefundDao().insert(
                refund(fullyRefundedOrderId, fullyRefundedPaymentId, "full", 4_000L, "COMPLETED", 1_800L),
            )

            val dao = database.paymentDao()
            assertEquals(75.0, dao.observeCollectedRevenue(STATION_ID, 1_000L, 2_000L).first(), 0.001)
            assertEquals(75.0, dao.getCollectedRevenue(STATION_ID, 1_000L, 2_000L), 0.001)
            assertEquals(1, dao.getPaidOrderCount(STATION_ID, 1_000L, 2_000L))
        } finally {
            database.close()
        }
    }

    private fun refund(
        orderId: Long,
        paymentId: Long,
        key: String,
        amountCentavos: Long,
        status: String,
        completedAt: Long?,
    ) = PaymentRefundEntity(
        orderId = orderId,
        originalPaymentId = paymentId,
        operationId = key,
        amountCentavos = amountCentavos,
        paymentMethod = "CASH",
        status = status,
        reason = "Regression fixture",
        createdAt = 1_300L,
        completedAt = completedAt,
        updatedAt = completedAt ?: 1_300L,
    )

    private fun order(number: String, total: Double, paymentStatus: String, completedAt: Long) = OrderEntity(
        orderNumber = number,
        stationId = STATION_ID,
        customerId = 1L,
        customerName = "Client",
        customerPhone = "09170000000",
        riderId = null,
        riderName = null,
        deliveryAddressId = null,
        deliveryAddress = "Station Walk-in / Pick-up",
        subtotal = total,
        deliveryFee = 0.0,
        discount = 0.0,
        total = total,
        paymentMethod = "CASH",
        paymentStatus = paymentStatus,
        orderStatus = "COMPLETED",
        scheduledDeliveryStart = null,
        scheduledDeliveryEnd = null,
        customerNote = null,
        stationNote = null,
        createdAt = completedAt,
        acceptedAt = null,
        readyAt = null,
        assignedAt = null,
        outForDeliveryAt = null,
        deliveredAt = null,
        updatedAt = completedAt,
        completedAt = completedAt,
        cancelledAt = null,
    )

    private fun payment(orderId: Long, amount: Double, paidAt: Long) = PaymentEntity(
        orderId = orderId,
        paymentMethod = "CASH",
        amount = amount,
        status = "PAID",
        referenceNumber = null,
        createdAt = paidAt,
        paidAt = paidAt,
    )

    private companion object {
        const val STATION_ID = 7L
    }
}
