package com.aesprt.aquahub.domain.usecase.customer

import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.domain.model.Payment
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.PaymentStatus
import com.aesprt.aquahub.domain.repository.OrderRepository
import com.aesprt.aquahub.domain.repository.PaymentRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CustomerAccountUseCasesTest {
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    @Test
    fun `account snapshot derives partial and outstanding balances from paid receipts`() = runTest {
        val orders = listOf(
            Order(id = 11, customerId = 7, total = 500.0, paymentMethod = PaymentMethod.CLIENT_ACCOUNT),
            Order(id = 12, customerId = 7, total = 300.0, paymentMethod = PaymentMethod.CLIENT_ACCOUNT),
            Order(id = 13, customerId = 7, total = 100.0, paymentMethod = PaymentMethod.CLIENT_ACCOUNT, paymentStatus = PaymentStatus.PAID),
            Order(id = 14, customerId = 7, total = 999.0, paymentMethod = PaymentMethod.CASH_ON_DELIVERY),
            Order(id = 15, customerId = 7, total = 100.0, paymentMethod = PaymentMethod.CASH, paymentStatus = PaymentStatus.PAID),
            Order(id = 16, customerId = 7, total = 25.0, paymentMethod = PaymentMethod.CASH, orderStatus = OrderStatus.CANCELLED)
        )
        val payments = listOf(
            Payment(id = 1, orderId = 11, amount = 200.0, status = PaymentStatus.PAID, paymentMethod = PaymentMethod.CASH),
            Payment(id = 2, orderId = 11, amount = 50.0, status = PaymentStatus.REFUNDED, paymentMethod = PaymentMethod.GCASH),
            Payment(id = 3, orderId = 12, amount = 300.0, status = PaymentStatus.PAID, paymentMethod = PaymentMethod.MAYA),
            Payment(id = 4, orderId = 15, amount = 100.0, status = PaymentStatus.PAID, paymentMethod = PaymentMethod.CASH)
        )
        val orderRepository = fake<OrderRepository> { name, _ ->
            when (name) {
                "observeOrdersForCustomer" -> flowOf(orders)
                else -> error(name)
            }
        }
        val paymentRepository = fake<PaymentRepository> { name, _ ->
            when (name) {
                "observePaymentsForCustomer" -> flowOf(payments)
                else -> error(name)
            }
        }

        val snapshot = ObserveCustomerAccountUseCase(orderRepository, paymentRepository)(
            Customer(id = 7, stationId = 1, name = "Corporate Client")
        ).first()

        assertEquals(900.0, snapshot.totalBilled, 0.0)
        assertEquals(500.0, snapshot.totalPaid, 0.0)
        assertEquals(400.0, snapshot.outstandingBalance, 0.0)
        assertEquals(300.0, snapshot.entries.single { it.order.id == 11L }.balance, 0.0)
        assertEquals(0.0, snapshot.entries.single { it.order.id == 12L }.balance, 0.0)
        assertEquals(100.0, snapshot.entries.single { it.order.id == 13L }.balance, 0.0)
        assertFalse(snapshot.entries.any { it.order.id == 14L })
        assertFalse(snapshot.entries.any { it.order.id == 15L })
        assertEquals(setOf(11L, 12L, 13L, 14L, 15L, 16L), snapshot.allOrders.map { it.id }.toSet())
    }
}
