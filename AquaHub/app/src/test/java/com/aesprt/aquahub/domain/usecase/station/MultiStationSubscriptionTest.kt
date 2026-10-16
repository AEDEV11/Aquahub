package com.aesprt.aquahub.domain.usecase.station

import com.aesprt.aquahub.domain.context.ActiveStationContext
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.usecase.subscription.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MultiStationSubscriptionTest {

    private lateinit var fakeTimeProvider: FakeTestTimeProvider
    private lateinit var fakePlanRepo: FakeTestSubscriptionPlanRepository
    private lateinit var fakeSubRepo: FakeTestSubscriptionRepository
    private lateinit var fakeStationRepo: FakeTestStationRepository
    private lateinit var activeStationContext: FakeActiveStationContext
    private lateinit var getStatusUseCase: GetSubscriptionStatusUseCase
    private lateinit var checkAccessUseCase: CheckSubscriptionAccessUseCase
    private lateinit var checkLimitUseCase: CheckSubscriptionLimitUseCase
    private lateinit var canCreateBranchUseCase: CanCreateBranchUseCase
    private lateinit var createNewStationUseCase: CreateNewStationUseCase

    @Before
    fun setUp() {
        fakeTimeProvider = FakeTestTimeProvider(1000000L)
        fakePlanRepo = FakeTestSubscriptionPlanRepository()
        fakeSubRepo = FakeTestSubscriptionRepository(fakeTimeProvider, fakePlanRepo)
        fakeStationRepo = FakeTestStationRepository().apply {
            stations = listOf(Station(id = 1, name = "Station 1"))
        }
        activeStationContext = FakeActiveStationContext(1L)
        getStatusUseCase = GetSubscriptionStatusUseCase(fakeTimeProvider)
        checkAccessUseCase = CheckSubscriptionAccessUseCase(
            subscriptionRepository = fakeSubRepo,
            planRepository = fakePlanRepo,
            getSubscriptionStatusUseCase = getStatusUseCase,
            stationRepository = fakeStationRepo
        )
        checkLimitUseCase = CheckSubscriptionLimitUseCase(
            subscriptionRepository = fakeSubRepo,
            planRepository = fakePlanRepo,
            getSubscriptionStatusUseCase = getStatusUseCase
        )
        canCreateBranchUseCase = CanCreateBranchUseCase(
            checkSubscriptionAccessUseCase = checkAccessUseCase,
            checkSubscriptionLimitUseCase = checkLimitUseCase
        )
        createNewStationUseCase = CreateNewStationUseCase(
            stationRepository = fakeStationRepo,
            canCreateBranchUseCase = canCreateBranchUseCase,
            activeStationContext = activeStationContext,
            subscriptionRepository = fakeSubRepo
        )
    }

    @Test
    fun `creating a new station inherits active subscription and has immediate operational access`() = runTest {
        // Station 1 is subscribed to BUSINESS plan (limit: 3 stations)
        fakeSubRepo.activatePlan(1L, 2L, BillingCycle.MONTHLY)

        // Create Station 2
        val newStation = Station(id = 2, name = "Station 2", address = "Address 2")
        fakeStationRepo.createStationOverride = { 2L }
        val result = createNewStationUseCase(1L, newStation)

        assertTrue(result.isSuccess)
        val createdId = result.getOrThrow()
        assertEquals(2L, createdId)

        // Station 2 must have an active subscription inherited from the business
        val sub2 = fakeSubRepo.getCurrentSubscription(createdId)
        assertNotNull("Station 2 must have a subscription row", sub2)
        assertEquals(SubscriptionPlanCode.BUSINESS, sub2?.planCode)
        assertEquals(SubscriptionStatus.ACTIVE, sub2?.status)

        // Add station 2 to repository to simulate it being part of the stations list
        fakeStationRepo.stations = listOf(
            Station(id = 1, name = "Station 1"),
            Station(id = 2, name = "Station 2")
        )

        // Station 2 should have Allowed access to operational features
        val access = checkAccessUseCase(createdId, SubscriptionFeature.ORDER_MANAGEMENT)
        assertTrue(access is SubscriptionAccessResult.Allowed)
    }

    @Test
    fun `starter plan blocks second station creation with clear limit message`() = runTest {
        // Station 1 is on STARTER plan (limit: 1 station)
        fakeSubRepo.activatePlan(1L, 1L, BillingCycle.MONTHLY)

        val newStation = Station(id = 2, name = "Station 2", address = "Address 2")
        val result = createNewStationUseCase(1L, newStation)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error?.message?.contains("Multi-station management is only available") == true ||
                error?.message?.contains("limit reached") == true)
    }

    @Test
    fun `business plan blocks 4th station creation when limit is 3`() = runTest {
        // Station 1 on BUSINESS plan (limit: 3 stations)
        fakeSubRepo.activatePlan(1L, 2L, BillingCycle.MONTHLY)
        fakeStationRepo.stations = listOf(
            Station(id = 1, name = "Station 1"),
            Station(id = 2, name = "Station 2"),
            Station(id = 3, name = "Station 3")
        )

        val newStation = Station(id = 4, name = "Station 4", address = "Address 4")
        val result = createNewStationUseCase(1L, newStation)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Station limit reached (3/3)") == true)
    }
}

class FakeActiveStationContext(initialId: Long? = null) : ActiveStationContext {
    private val _stationId = MutableStateFlow(initialId)
    override fun observeActiveStationId(): Flow<Long?> = _stationId.asStateFlow()
    override suspend fun getActiveStationId(): Long? = _stationId.value
    override suspend fun setActiveStationId(stationId: Long) { _stationId.value = stationId }
}
