package com.aesprt.aquahub.domain.usecase.analytics

import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.*
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import com.aesprt.aquahub.domain.usecase.subscription.FakeTestStationRepository
import com.aesprt.aquahub.domain.usecase.subscription.FakeTestSubscriptionPlanRepository
import com.aesprt.aquahub.domain.usecase.subscription.FakeTestSubscriptionRepository
import com.aesprt.aquahub.domain.usecase.subscription.FakeTestTimeProvider
import com.aesprt.aquahub.domain.usecase.subscription.GetSubscriptionStatusUseCase
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthoritativeFinancialReportTest {
    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    @Test
    fun reportRefreshesRemoteReceiptsBeforeCalculatingRevenue() = runTest {
        val now = System.currentTimeMillis()
        val order = Order(
            id = 41L,
            stationId = 1L,
            orderStatus = OrderStatus.COMPLETED,
            total = 125.0,
            createdAt = now
        )
        val authoritativeReceipt = Payment(
            id = 900L,
            orderId = order.id,
            amount = 125.0,
            paymentMethod = PaymentMethod.GCASH,
            status = PaymentStatus.PAID,
            createdAt = now,
            paidAt = now
        )
        val authoritativeRefund = PaymentRefund(
            orderId = order.id,
            originalPaymentId = authoritativeReceipt.id,
            operationId = "refund-report-${order.id}",
            amountCentavos = 2_500L,
            paymentMethod = PaymentMethod.GCASH,
            status = RefundStatus.COMPLETED,
            reason = "Verified report fixture",
            createdAt = now,
            completedAt = now
        )
        var refreshedOrderIds: List<Long>? = null
        var remoteStationRefreshes = 0

        val orderRepository = fake<OrderRepository> { name, _ ->
            when {
                name.startsWith("refreshDeliveryReads") -> {
                    remoteStationRefreshes++
                    Result.success(Unit)
                }
                name == "observeOrders" -> flowOf(listOf(order))
                else -> error("Unexpected OrderRepository call: $name")
            }
        }
        val paymentRepository = fake<PaymentRepository> { name, args ->
            when (name) {
                "refreshPaymentsForOrders" -> {
                    refreshedOrderIds = (args[0] as List<*>).filterIsInstance<Long>()
                    listOf(authoritativeReceipt)
                }
                // A new installation has not opened this order's detail, so
                // its Room-only receipt cache is intentionally empty.
                "getPaymentsForOrders" -> emptyList<Payment>()
                "getRefundsForOrders" -> listOf(authoritativeRefund)
                else -> error("Unexpected PaymentRepository call: $name")
            }
        }
        val customerRepository = fake<CustomerRepository> { name, _ ->
            when (name) {
                "observeCustomers" -> flowOf(emptyList<Customer>())
                else -> error("Unexpected CustomerRepository call: $name")
            }
        }
        val riderRepository = fake<RiderRepository> { name, _ ->
            when (name) {
                "observeRiders" -> flowOf(emptyList<Rider>())
                else -> error("Unexpected RiderRepository call: $name")
            }
        }
        val productRepository = fake<ProductRepository> { name, _ ->
            when (name) {
                "observeProducts" -> flowOf(emptyList<Product>())
                else -> error("Unexpected ProductRepository call: $name")
            }
        }
        val planRepository = FakeTestSubscriptionPlanRepository()
        val subscriptionRepository = FakeTestSubscriptionRepository(FakeTestTimeProvider(now), planRepository)
        val plan = requireNotNull(planRepository.getPlanByCode(SubscriptionPlanCode.STARTER))
        subscriptionRepository.activatePlan(1L, plan.id, BillingCycle.MONTHLY)
        val stationRepository = FakeTestStationRepository().apply {
            stations = listOf(Station(id = 1L, createdAt = 1L))
        }
        val access = CheckSubscriptionAccessUseCase(
            subscriptionRepository,
            planRepository,
            GetSubscriptionStatusUseCase(FakeTestTimeProvider(now)),
            stationRepository
        )

        val analytics = GetFullBusinessAnalyticsUseCase(
            orderRepository,
            paymentRepository,
            customerRepository,
            riderRepository,
            productRepository,
            stationRepository,
            access
        )(stationId = 1L, dateRange = DateRangeFilter.LAST_7_DAYS)

        assertEquals(listOf(order.id), refreshedOrderIds)
        assertEquals(1, remoteStationRefreshes)
        assertEquals(100.0, analytics.revenue.totalRevenue, 0.001)
    }
}
