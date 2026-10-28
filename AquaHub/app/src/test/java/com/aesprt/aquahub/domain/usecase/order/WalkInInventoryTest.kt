package com.aesprt.aquahub.domain.usecase.order

import com.aesprt.aquahub.domain.common.LocalTransactionRunner
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.*
import com.aesprt.aquahub.domain.usecase.subscription.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class WalkInInventoryTest {
    private var stock = 10
    private var movements = 0
    private var ordersCreated = 0
    private var paymentsRecorded = 0
    private var insertedOrder: Order? = null
    private val product = Product(id = 1, stationId = 1, name = "Refill", type = ProductType.REFILL,
        sizeLabel = "5 Gallon", price = 35.0, stockQuantity = 10, isAvailable = true)
    private val item = OrderItem(productId = 1, quantity = 2, subtotal = 70.0)

    private inline fun <reified T> fake(crossinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T

    private suspend fun useCase(
        planId: Long = 1L,
        allowLocalTestPath: Boolean = true,
        offlinePosRepository: OfflinePosRepository? = null,
        online: Boolean? = null,
        offlinePosEnabled: Boolean = true,
    ): CreateWalkInOrderUseCase {
        val time = FakeTestTimeProvider(100)
        val plans = FakeTestSubscriptionPlanRepository()
        val subscriptions = FakeTestSubscriptionRepository(time, plans)
        subscriptions.activatePlan(1, planId, BillingCycle.MONTHLY)
        val access = CheckSubscriptionAccessUseCase(subscriptions, plans, GetSubscriptionStatusUseCase(time), FakeTestStationRepository())
        val limits = CheckSubscriptionLimitUseCase(subscriptions, plans, GetSubscriptionStatusUseCase(time))
        val orders = fake<OrderRepository> { name, args -> when (name) {
            "countOrdersBetween" -> 0
            "insertOrder" -> { insertedOrder = args[0] as Order; ordersCreated++; 1L }
            else -> error(name)
        } }
        val products = fake<ProductRepository> { name, args -> when (name) {
            "getProductById" -> product.copy(stockQuantity = stock)
            "updateStock" -> { stock = args[1] as Int; Unit }
            else -> error(name)
        } }
        val customers = fake<CustomerRepository> { name, _ -> when (name) {
            "getCustomerById" -> Customer(id = 2, stationId = 1)
            "incrementOrderCount" -> Unit
            else -> error(name)
        } }
        val inventory = fake<InventoryRepository> { name, _ -> when (name) {
            "getContainerBySizeLabel" -> null
            "observeCustomerTransactions" -> flowOf(emptyList<ContainerTransaction>())
            "insertTransaction", "adjustContainer" -> { movements++; error("Unexpected container movement") }
            else -> error(name)
        } }
        val runner = object : LocalTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
        val payments = fake<PaymentRepository> { name, args -> when (name) {
            "recordPayment" -> {
                paymentsRecorded++
                Payment(
                    id = paymentsRecorded.toLong(),
                    orderId = args[0] as Long,
                    amount = args[1] as Double,
                    paymentMethod = args[2] as PaymentMethod,
                    status = PaymentStatus.PAID
                )
            }
            else -> error(name)
        } }
        val connectivity = online?.let { isOnline ->
            object : com.aesprt.aquahub.sync.ConnectivityMonitor {
                override val isOnline = flowOf(isOnline)
                override fun isCurrentlyOnline() = isOnline
            }
        }
        return CreateWalkInOrderUseCase(
            orders,
            customers,
            inventory,
            runner,
            products,
            CanCreateOrderUseCase(access, limits, orders),
            access,
            payments,
            allowLocalTestPath = allowLocalTestPath,
            offlinePosRepository = offlinePosRepository,
            connectivityMonitor = connectivity,
            offlinePosEnabled = offlinePosEnabled,
        )
    }

    @Test
    fun offlineSaleUsesReservedSnapshotInsteadOfMutableRoomStockAndPrice() = runTest {
        stock = 0
        var request: OfflineCashSaleRequest? = null
        val offline = fake<OfflinePosRepository> { name, args -> when (name) {
            "recordCashSale" -> {
                request = args[0] as OfflineCashSaleRequest
                99L
            }
            else -> error(name)
        } }

        val result = useCase(offlinePosRepository = offline, online = false)(
            stationId = 1,
            customerName = "Walk-in",
            items = listOf(item.copy(unitPrice = 99.0, subtotal = 198.0)),
            containerSize = "5 Gallon",
        )

        assertEquals(99L, result.getOrThrow())
        assertEquals(listOf(OfflinePosReservationItem(productLocalId = 1L, quantity = 2)), request?.items)
        assertNull(request?.cashReceivedCentavos)
        assertEquals(0, ordersCreated)
        assertEquals(0, paymentsRecorded)
    }

    @Test
    fun onlineOnlyReleaseDoesNotCreateOfflineSaleWhenConnectivityIsLost() = runTest {
        var offlineRecordCalls = 0
        val offline = fake<OfflinePosRepository> { name, _ -> when (name) {
            "recordCashSale" -> { offlineRecordCalls++; 99L }
            else -> error(name)
        } }

        val result = useCase(
            allowLocalTestPath = false,
            offlinePosRepository = offline,
            online = false,
            offlinePosEnabled = false,
        )(
            stationId = 1,
            customerName = "Walk-in",
            items = listOf(item),
            containerSize = "5 Gallon",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("unavailable in this release"))
        assertEquals(0, offlineRecordCalls)
        assertEquals(10, stock)
        assertEquals(0, ordersCreated)
        assertEquals(0, paymentsRecorded)
    }

    @Test
    fun supabaseUnavailableDoesNotCommitALocalOrder() = runTest {
        val result = useCase(allowLocalTestPath = false)(
            stationId = 1,
            customerName = "Offline Customer",
            items = listOf(item),
            containerSize = "5 Gallon"
        )
        assertTrue(result.isFailure)
        assertEquals(10, stock)
        assertEquals(0, ordersCreated)
        assertEquals(0, paymentsRecorded)
    }

    @Test fun `customer owned refill deducts sale units without creating container loans`() = runTest {
        val result = useCase()(stationId = 1, customerName = "Walk-in", items = listOf(item), containerSize = "5 Gallon")
        assertTrue(result.isSuccess)
        assertEquals(8, stock)
        assertEquals(0, movements)
        assertEquals(1, ordersCreated)
        assertEquals(1, paymentsRecorded)
    }

    @Test fun `duplicate products cannot under deduct inventory`() = runTest {
        val result = useCase()(stationId = 1, customerName = "Walk-in", items = listOf(item, item), containerSize = "5 Gallon")
        assertTrue(result.isFailure)
        assertEquals(10, stock)
        assertEquals(0, ordersCreated)
    }

    @Test fun `anonymous returns are rejected before stock changes`() = runTest {
        val result = useCase()(stationId = 1, customerName = "Walk-in", items = listOf(item), containerSize = "5 Gallon", emptyBottlesReturned = 1)
        assertTrue(result.isFailure)
        assertEquals(10, stock)
    }

    @Test fun `station loans require physical available bottles`() = runTest {
        val result = useCase()(stationId = 1, customerId = 2, customerName = "Customer", items = listOf(item), containerSize = "5 Gallon", stationContainersIssued = 1)
        assertTrue(result.isFailure)
        assertEquals(10, stock)
        assertEquals(0, ordersCreated)
    }

    @Test
    fun starterPlanAllowsWalkInDelivery() = runTest {
        val result = useCase(planId = 1L)(
            stationId = 1,
            customerName = "Delivery Customer",
            customerPhone = "09170000000",
            items = listOf(item),
            containerSize = "5 Gallon",
            deliveryMode = "DELIVERY",
            deliveryAddress = "Customer address",
            deliveryFee = 50.0
        )
        assertTrue(result.isSuccess)
        assertEquals(8, stock)
        assertEquals(1, ordersCreated)
        assertEquals("DELIVERY", insertedOrder?.deliveryMode)
        assertEquals(PaymentStatus.PENDING, insertedOrder?.paymentStatus)
        assertEquals(0, paymentsRecorded)
    }

    @Test
    fun businessPlanCreatesRiderReadyWalkInDelivery() = runTest {
        val result = useCase(planId = 2L)(
            stationId = 1,
            customerName = "Delivery Customer",
            customerPhone = "09170000000",
            items = listOf(item),
            containerSize = "5 Gallon",
            deliveryMode = "DELIVERY",
            deliveryAddress = "Customer address",
            deliveryFee = 50.0
        )
        assertTrue(result.isSuccess)
        assertEquals("DELIVERY", insertedOrder?.deliveryMode)
        assertEquals(120.0, insertedOrder?.total ?: 0.0, 0.0)
        assertEquals(PaymentStatus.PENDING, insertedOrder?.paymentStatus)
        assertEquals(OrderStatus.READY_FOR_PICKUP, insertedOrder?.orderStatus)
        assertEquals(0, paymentsRecorded)
    }

    @Test
    fun businessPlanCanChargeSavedCustomerAccountWithoutRecordingPayment() = runTest {
        val result = useCase(planId = 2L)(
            stationId = 1,
            customerId = 2,
            customerName = "Corporate Client",
            items = listOf(item),
            containerSize = "5 Gallon",
            chargeToAccount = true
        )

        assertTrue(result.isSuccess)
        assertEquals(PaymentStatus.PENDING, insertedOrder?.paymentStatus)
        assertEquals(0, paymentsRecorded)
    }

    @Test
    fun starterPlanCannotChargeCustomerAccount() = runTest {
        val result = useCase(planId = 1L)(
            stationId = 1,
            customerId = 2,
            customerName = "Corporate Client",
            items = listOf(item),
            containerSize = "5 Gallon",
            chargeToAccount = true
        )

        assertTrue(result.isFailure)
        assertEquals(0, ordersCreated)
        assertEquals(0, paymentsRecorded)
    }

    @Test
    fun cardAndOnlinePaymentAreRejected() = runTest {
        val cardResult = useCase()(stationId = 1, customerName = "Walk-in", items = listOf(item), containerSize = "5 Gallon", paymentMethod = PaymentMethod.CARD)
        val onlineResult = useCase()(stationId = 1, customerName = "Walk-in", items = listOf(item), containerSize = "5 Gallon", paymentMethod = PaymentMethod.ONLINE_PAYMENT)

        assertTrue(cardResult.isFailure)
        assertTrue(onlineResult.isFailure)
        assertEquals(0, ordersCreated)
        assertEquals(0, paymentsRecorded)
    }

    @Test
    fun stationBottleLoanCannotBeRecordedTwiceForTheSameOrder() = runTest {
        val time = FakeTestTimeProvider(100)
        val plans = FakeTestSubscriptionPlanRepository()
        val subscriptions = FakeTestSubscriptionRepository(time, plans)
        subscriptions.activatePlan(1, 1L, BillingCycle.MONTHLY)
        val access = CheckSubscriptionAccessUseCase(
            subscriptions,
            plans,
            GetSubscriptionStatusUseCase(time),
            FakeTestStationRepository()
        )
        val existingLoan = ContainerTransaction(
            stationId = 1,
            customerId = 2,
            orderId = 42,
            type = ContainerTransactionType.DELIVERED,
            quantity = 3,
            containerSize = "3 Gallon"
        )
        var mutations = 0
        val inventory = fake<InventoryRepository> { name, _ ->
            when (name) {
                "getContainerBySizeLabel" -> Container(
                    stationId = 1,
                    sizeLabel = "3 Gallon",
                    totalOwned = 10,
                    available = 10
                )
                "observeCustomerTransactions" -> kotlinx.coroutines.flow.flowOf(listOf(existingLoan))
                "insertTransaction", "adjustContainer" -> {
                    mutations++
                    Unit
                }
                else -> error(name)
            }
        }
        val runner = object : LocalTransactionRunner {
            override suspend fun <T> run(block: suspend () -> T): T = block()
        }
        val useCase = com.aesprt.aquahub.domain.usecase.inventory.RecordContainerDeliveryUseCase(
            inventory,
            runner,
            access
        )

        val result = runCatching {
            useCase(
                stationId = 1,
                customerId = 2,
                customerName = "Customer",
                orderId = 42,
                sizeLabel = "3 Gallon",
                quantity = 1
            )
        }

        assertTrue(result.isFailure)
        assertEquals(0, mutations)
    }

}
