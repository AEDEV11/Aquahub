package com.aesprt.aquahub.domain.usecase.subscription

import com.aesprt.aquahub.domain.common.TimeProvider
import com.aesprt.aquahub.domain.model.*
import com.aesprt.aquahub.domain.repository.SubscriptionPlanRepository
import com.aesprt.aquahub.domain.repository.SubscriptionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SubscriptionAccessManagerTest {

    private lateinit var fakeTimeProvider: FakeTestTimeProvider
    private lateinit var fakeSubscriptionPlanRepository: FakeTestSubscriptionPlanRepository
    private lateinit var fakeSubscriptionRepository: FakeTestSubscriptionRepository

    private lateinit var getSubscriptionStatusUseCase: GetSubscriptionStatusUseCase
    private lateinit var getSubscriptionRemainingDaysUseCase: GetSubscriptionRemainingDaysUseCase
    private lateinit var checkSubscriptionAccessUseCase: CheckSubscriptionAccessUseCase
    private lateinit var checkSubscriptionLimitUseCase: CheckSubscriptionLimitUseCase
    private lateinit var upgradeSubscriptionUseCase: UpgradeSubscriptionUseCase
    private lateinit var scheduleDowngradeUseCase: ScheduleDowngradeUseCase
    private lateinit var processSubscriptionExpirationUseCase: ProcessSubscriptionExpirationUseCase

    companion object {
        private const val BASE_TIME = 1700000000000L // arbitrary fixed epoch timestamp
        private const val ONE_DAY_MS = 24L * 60L * 60L * 1000L
    }

    @Before
    fun setUp() {
        fakeTimeProvider = FakeTestTimeProvider(BASE_TIME)
        fakeSubscriptionPlanRepository = FakeTestSubscriptionPlanRepository()
        fakeSubscriptionRepository = FakeTestSubscriptionRepository(fakeTimeProvider, fakeSubscriptionPlanRepository)

        getSubscriptionStatusUseCase = GetSubscriptionStatusUseCase(fakeTimeProvider)
        getSubscriptionRemainingDaysUseCase = GetSubscriptionRemainingDaysUseCase(fakeTimeProvider)
        checkSubscriptionAccessUseCase = CheckSubscriptionAccessUseCase(
            subscriptionRepository = fakeSubscriptionRepository,
            planRepository = fakeSubscriptionPlanRepository,
            getSubscriptionStatusUseCase = getSubscriptionStatusUseCase,
            stationRepository = FakeTestStationRepository()
        )
        checkSubscriptionLimitUseCase = CheckSubscriptionLimitUseCase(
            subscriptionRepository = fakeSubscriptionRepository,
            planRepository = fakeSubscriptionPlanRepository,
            getSubscriptionStatusUseCase = getSubscriptionStatusUseCase
        )
        upgradeSubscriptionUseCase = UpgradeSubscriptionUseCase(fakeSubscriptionRepository)
        scheduleDowngradeUseCase = ScheduleDowngradeUseCase(fakeSubscriptionRepository)
        processSubscriptionExpirationUseCase = ProcessSubscriptionExpirationUseCase(fakeSubscriptionRepository)
    }

    @Test
    fun `explicit expired status cannot regain access through grace dates`() = runTest {
        val sub = fakeSubscriptionRepository.activatePlan(1, 2, BillingCycle.MONTHLY)
        fakeSubscriptionRepository.updateSubscriptionStatus(sub.id, SubscriptionStatus.EXPIRED)
        fakeTimeProvider.advanceTime(31 * ONE_DAY_MS)
        assertTrue(checkSubscriptionAccessUseCase(1, SubscriptionFeature.ORDER_MANAGEMENT) is SubscriptionAccessResult.SubscriptionExpired)
    }

    @Test
    fun `business subscription lookup survives switching to station without replica`() = runTest {
        val pro = fakeSubscriptionRepository.activatePlan(1, 3, BillingCycle.MONTHLY)
        assertNull(fakeSubscriptionRepository.getCurrentSubscription(2))

        val resolved = GetCurrentBusinessSubscriptionUseCase(fakeSubscriptionRepository)()

        assertEquals(pro.planCode, resolved?.planCode)
        assertEquals(pro.currentPeriodEnd, resolved?.currentPeriodEnd)
    }

    @Test
    fun `immediate cancellation expires even before paid period ends`() = runTest {
        val sub = fakeSubscriptionRepository.activatePlan(1, 2, BillingCycle.MONTHLY)
        assertEquals(ResolvedSubscriptionState.EXPIRED,
            getSubscriptionStatusUseCase(sub.copy(status = SubscriptionStatus.CANCELLED, expiresAt = BASE_TIME)))
    }

    @Test
    fun `explicit grace state is allowed only before its deadline`() = runTest {
        val sub = fakeSubscriptionRepository.activatePlan(1, 2, BillingCycle.MONTHLY)
            .copy(status = SubscriptionStatus.GRACE_PERIOD, gracePeriodEndsAt = BASE_TIME + 1)
        assertEquals(ResolvedSubscriptionState.GRACE_PERIOD, getSubscriptionStatusUseCase(sub))
        fakeTimeProvider.advanceTime(1)
        assertEquals(ResolvedSubscriptionState.EXPIRED, getSubscriptionStatusUseCase(sub))
    }

    @Test
    fun `unknown status fails closed`() {
        assertEquals(SubscriptionStatus.EXPIRED, SubscriptionStatus.fromString("INVALID"))
    }

    @Test
    fun `server FREE status stays active despite zero length period timestamps`() {
        assertEquals(SubscriptionStatus.FREE, SubscriptionStatus.fromString("FREE"))

        val subscription = Subscription(
            stationId = 1,
            planId = 4,
            planCode = SubscriptionPlanCode.FREE,
            status = SubscriptionStatus.fromString("FREE"),
            billingCycle = BillingCycle.MONTHLY,
            startedAt = BASE_TIME,
            currentPeriodStart = BASE_TIME,
            currentPeriodEnd = BASE_TIME,
            expiresAt = BASE_TIME,
        )

        assertEquals(ResolvedSubscriptionState.ACTIVE, getSubscriptionStatusUseCase(subscription))
    }

    @Test
    fun `server FREE status grants free inventory but keeps premium container tracking blocked`() = runTest {
        val activeFree = fakeSubscriptionRepository.activatePlan(1L, 4L, BillingCycle.MONTHLY)
        fakeSubscriptionRepository.updateSubscriptionStatus(activeFree.id, SubscriptionStatus.FREE)

        assertTrue(checkSubscriptionAccessUseCase(1L, SubscriptionFeature.BASIC_INVENTORY) is SubscriptionAccessResult.Allowed)
        assertTrue(checkSubscriptionAccessUseCase(1L, SubscriptionFeature.BASIC_CONTAINER_TRACKING) is SubscriptionAccessResult.FeatureNotIncluded)
    }

    @Test
    fun `Play cancellation status keeps access only through paid period`() {
        val status = SubscriptionStatus.fromString("ACTIVE_UNTIL_PERIOD_END")
        assertEquals(SubscriptionStatus.ACTIVE_UNTIL_PERIOD_END, status)

        val subscription = Subscription(
            stationId = 1,
            planId = 2,
            planCode = SubscriptionPlanCode.BUSINESS,
            status = status,
            billingCycle = BillingCycle.MONTHLY,
            startedAt = BASE_TIME - ONE_DAY_MS,
            currentPeriodStart = BASE_TIME - ONE_DAY_MS,
            currentPeriodEnd = BASE_TIME + ONE_DAY_MS
        )

        assertEquals(ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END, getSubscriptionStatusUseCase(subscription))
        fakeTimeProvider.advanceTime(ONE_DAY_MS)
        assertEquals(ResolvedSubscriptionState.EXPIRED, getSubscriptionStatusUseCase(subscription))
    }

    @Test
    fun `station beyond downgraded allowance cannot use operational features`() = runTest {
        val stations = FakeTestStationRepository().apply { this.stations = listOf(Station(id = 1), Station(id = 2)) }
        fakeSubscriptionRepository.activatePlan(2, 1, BillingCycle.MONTHLY)
        val access = CheckSubscriptionAccessUseCase(fakeSubscriptionRepository, fakeSubscriptionPlanRepository, getSubscriptionStatusUseCase, stations)
        assertTrue(access(2, SubscriptionFeature.ORDER_MANAGEMENT) is SubscriptionAccessResult.LimitReached)
    }

    // ---------------- Scenario 1 ----------------
    @Test
    fun `1 - Free trial active within 30 days has active status and access`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.createTrial(stationId, SubscriptionPlanCode.BUSINESS)

        // Day 5 of trial
        fakeTimeProvider.advanceTime(5 * ONE_DAY_MS)

        val sub = fakeSubscriptionRepository.getCurrentSubscription(stationId)
        val state = getSubscriptionStatusUseCase(sub)
        val remaining = getSubscriptionRemainingDaysUseCase(sub)
        val access = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ORDER_MANAGEMENT)

        assertEquals(ResolvedSubscriptionState.TRIAL_ACTIVE, state)
        assertTrue(remaining in 24..25)
        assertTrue(access is SubscriptionAccessResult.Allowed)
    }

    // ---------------- Scenario 2 ----------------
    @Test
    fun `2 - Free trial expires exactly after 30 days with no grace period`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.createTrial(stationId, SubscriptionPlanCode.BUSINESS)

        // Day 31: the trial is expired and all feature access is blocked.
        fakeTimeProvider.advanceTime(31 * ONE_DAY_MS)

        val sub = fakeSubscriptionRepository.getCurrentSubscription(stationId)
        val state = getSubscriptionStatusUseCase(sub)
        val access = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ORDER_MANAGEMENT)

        assertEquals(ResolvedSubscriptionState.EXPIRED, state)
        assertTrue(access is SubscriptionAccessResult.SubscriptionExpired)
    }

    // ---------------- Scenario 3 ----------------
    @Test
    fun `3 - Free trial expired past grace period is blocked with SubscriptionExpired`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.createTrial(stationId, SubscriptionPlanCode.BUSINESS)

        // Day 34 (past 30 days + 3 days grace)
        fakeTimeProvider.advanceTime(34 * ONE_DAY_MS)

        val sub = fakeSubscriptionRepository.getCurrentSubscription(stationId)
        val state = getSubscriptionStatusUseCase(sub)
        val access = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ORDER_MANAGEMENT)

        assertEquals(ResolvedSubscriptionState.EXPIRED, state)
        assertTrue(access is SubscriptionAccessResult.SubscriptionExpired)
    }

    // ---------------- Scenario 4 ----------------
    @Test
    fun `4 - Active paid subscription within period end has access`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 2L, BillingCycle.MONTHLY)

        // Day 10 of monthly plan
        fakeTimeProvider.advanceTime(10 * ONE_DAY_MS)

        val sub = fakeSubscriptionRepository.getCurrentSubscription(stationId)
        val state = getSubscriptionStatusUseCase(sub)
        val access = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.DASHBOARD)

        assertEquals(ResolvedSubscriptionState.ACTIVE, state)
        assertTrue(access is SubscriptionAccessResult.Allowed)
    }

    // ---------------- Scenario 5 ----------------
    @Test
    fun `5 - Active subscription past period end enters grace period`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 2L, BillingCycle.MONTHLY)

        // Day 31 (past 30-day period, within 3-day grace period)
        fakeTimeProvider.advanceTime(31 * ONE_DAY_MS)

        val sub = fakeSubscriptionRepository.getCurrentSubscription(stationId)
        val state = getSubscriptionStatusUseCase(sub)
        val access = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ORDER_MANAGEMENT)

        assertEquals(ResolvedSubscriptionState.GRACE_PERIOD, state)
        assertTrue(access is SubscriptionAccessResult.Allowed)
    }

    // ---------------- Scenario 6 ----------------
    @Test
    fun `6 - Active subscription past grace period is marked expired and blocked`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 2L, BillingCycle.MONTHLY)

        // Day 34 (past 30 days + 3 days grace)
        fakeTimeProvider.advanceTime(34 * ONE_DAY_MS)

        val sub = fakeSubscriptionRepository.getCurrentSubscription(stationId)
        val state = getSubscriptionStatusUseCase(sub)
        val access = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ORDER_MANAGEMENT)

        assertEquals(ResolvedSubscriptionState.EXPIRED, state)
        assertTrue(access is SubscriptionAccessResult.SubscriptionExpired)
    }

    // ---------------- Scenario 7 ----------------
    @Test
    fun `7 - Subscription cancelled before period end maintains active access until period end`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 2L, BillingCycle.MONTHLY)

        // Cancel at Day 10 with cancelAtPeriodEnd = true
        fakeTimeProvider.advanceTime(10 * ONE_DAY_MS)
        fakeSubscriptionRepository.cancelSubscription(stationId, cancelAtPeriodEnd = true)

        // At Day 20 (still before 30-day period end)
        fakeTimeProvider.advanceTime(10 * ONE_DAY_MS)

        val sub = fakeSubscriptionRepository.getCurrentSubscription(stationId)
        val state = getSubscriptionStatusUseCase(sub)
        val access = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ORDER_MANAGEMENT)

        assertEquals(ResolvedSubscriptionState.ACTIVE_UNTIL_PERIOD_END, state)
        assertTrue(access is SubscriptionAccessResult.Allowed)
    }

    // ---------------- Scenario 8 ----------------
    @Test
    fun `8 - Subscription cancelled after period end is expired and blocked`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 2L, BillingCycle.MONTHLY)

        // Cancel at Day 10 with cancelAtPeriodEnd = true
        fakeTimeProvider.advanceTime(10 * ONE_DAY_MS)
        fakeSubscriptionRepository.cancelSubscription(stationId, cancelAtPeriodEnd = true)

        // At Day 31 (past 30-day period end)
        fakeTimeProvider.advanceTime(21 * ONE_DAY_MS)

        val sub = fakeSubscriptionRepository.getCurrentSubscription(stationId)
        val state = getSubscriptionStatusUseCase(sub)
        val access = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ORDER_MANAGEMENT)

        assertEquals(ResolvedSubscriptionState.EXPIRED, state)
        assertTrue(access is SubscriptionAccessResult.SubscriptionExpired)
    }

    // ---------------- Scenario 9 ----------------
    @Test
    fun `9 - Suspended subscription is blocked immediately regardless of active period`() = runTest {
        val stationId = 1L
        val sub = fakeSubscriptionRepository.activatePlan(stationId, 2L, BillingCycle.MONTHLY)
        fakeSubscriptionRepository.updateSubscriptionStatus(sub.id, SubscriptionStatus.SUSPENDED)

        val updatedSub = fakeSubscriptionRepository.getCurrentSubscription(stationId)
        val state = getSubscriptionStatusUseCase(updatedSub)
        val access = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.DASHBOARD)

        assertEquals(ResolvedSubscriptionState.SUSPENDED, state)
        assertTrue(access is SubscriptionAccessResult.SubscriptionSuspended)
    }

    // ---------------- Scenario 10 ----------------
    @Test
    fun `10 - Starter plan allows basic features and blocks Business and Pro features`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 1L, BillingCycle.MONTHLY) // Plan 1 = STARTER

        val basicInventory = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.BASIC_INVENTORY)
        val mobileOrdering = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.MOBILE_APP_ORDERING)
        val deliveryManagement = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.DELIVERY_MANAGEMENT)
        val advancedInventory = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ADVANCED_INVENTORY)
        val multiBranch = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.MULTI_BRANCH)

        assertTrue(basicInventory is SubscriptionAccessResult.Allowed)
        assertTrue(mobileOrdering is SubscriptionAccessResult.Allowed)
        assertTrue(deliveryManagement is SubscriptionAccessResult.Allowed)
        assertTrue(advancedInventory is SubscriptionAccessResult.FeatureNotIncluded)
        assertTrue(multiBranch is SubscriptionAccessResult.FeatureNotIncluded)
    }

    @Test
    fun `active Free plan remains active after its period end`() {
        val freeSubscription = Subscription(
            stationId = 1L,
            planId = 4L,
            planCode = SubscriptionPlanCode.FREE,
            status = SubscriptionStatus.ACTIVE,
            billingCycle = BillingCycle.MONTHLY,
            startedAt = BASE_TIME - ONE_DAY_MS,
            currentPeriodStart = BASE_TIME - ONE_DAY_MS,
            currentPeriodEnd = BASE_TIME - 1L
        )

        assertEquals(ResolvedSubscriptionState.ACTIVE, getSubscriptionStatusUseCase(freeSubscription))
    }

    @Test
    fun `Free plan includes saleable inventory but not station-owned container tracking`() = runTest {
        fakeSubscriptionRepository.activatePlan(1L, 4L, BillingCycle.MONTHLY)
        val freePlan = DEFAULT_SUBSCRIPTION_PLANS.first { it.code == SubscriptionPlanCode.FREE }

        assertTrue(freePlan.features.first { it.feature == SubscriptionFeature.BASIC_INVENTORY }.enabled)
        assertFalse(freePlan.features.first { it.feature == SubscriptionFeature.BASIC_CONTAINER_TRACKING }.enabled)
        assertEquals(20L, freePlan.limits.first { it.limitType == SubscriptionLimit.PRODUCT_COUNT }.maxValue)
        assertEquals(100L, freePlan.limits.first { it.limitType == SubscriptionLimit.MONTHLY_ORDER_COUNT }.maxValue)
        assertTrue(checkSubscriptionAccessUseCase(1L, SubscriptionFeature.BASIC_INVENTORY) is SubscriptionAccessResult.Allowed)
        assertTrue(checkSubscriptionAccessUseCase(1L, SubscriptionFeature.BASIC_CONTAINER_TRACKING) is SubscriptionAccessResult.FeatureNotIncluded)
    }

    @Test
    fun `Free plan stock edit delegates to the inventory ledger repository`() = runTest {
        fakeSubscriptionRepository.activatePlan(1L, 4L, BillingCycle.MONTHLY)
        var persistedStock = 8
        val product = Product(id = 42L, stationId = 1L, stockQuantity = persistedStock)
        val productRepository = object : com.aesprt.aquahub.domain.repository.ProductRepository {
            override fun observeProducts(stationId: Long): Flow<List<Product>> = flowOf(listOf(product))
            override fun observeAvailableProducts(stationId: Long): Flow<List<Product>> = flowOf(listOf(product))
            override suspend fun getProductById(productId: Long): Product? = product.takeIf { it.id == productId }
            override suspend fun insertProduct(product: Product): Long = product.id
            override suspend fun updateProduct(product: Product) = Unit
            override suspend fun toggleAvailability(productId: Long, isAvailable: Boolean) = Unit
            override suspend fun updateStock(productId: Long, newStock: Int) { persistedStock = newStock }
            override suspend fun archive(productId: Long) = Unit
        }

        val result = com.aesprt.aquahub.domain.usecase.product.UpdateProductStockUseCase(
            productRepository,
            checkSubscriptionAccessUseCase
        )(product.id, 23)

        assertTrue(result.isSuccess)
        assertEquals(23, persistedStock)
    }

    // ---------------- Scenario 11 ----------------
    @Test
    fun `11 - Business plan gates inactive features and blocks Pro consolidated reports feature`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 2L, BillingCycle.MONTHLY) // Plan 2 = BUSINESS

        val advancedInventory = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ADVANCED_INVENTORY)
        val advancedReports = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.ADVANCED_REPORTS)
        val consolidatedReports = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.CONSOLIDATED_REPORTS)

        assertTrue(advancedInventory is SubscriptionAccessResult.FeatureNotIncluded)
        assertTrue(advancedReports is SubscriptionAccessResult.Allowed)
        assertTrue(consolidatedReports is SubscriptionAccessResult.FeatureNotIncluded)
    }

    // ---------------- Scenario 12 ----------------
    @Test
    fun `12 - Pro plan allows all features including multi-branch and data export`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 3L, BillingCycle.MONTHLY) // Plan 3 = PRO

        val multiBranch = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.MULTI_BRANCH)
        val dataExport = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.DATA_EXPORT)
        val customerInsights = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.CUSTOMER_INSIGHTS)

        assertTrue(multiBranch is SubscriptionAccessResult.Allowed)
        assertTrue(dataExport is SubscriptionAccessResult.Allowed)
        assertTrue(customerInsights is SubscriptionAccessResult.Allowed)
    }

    // ---------------- Scenario 13 ----------------
    @Test
    fun `13 - Starter plan rider limit is enforced at maximum 2 riders`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 1L, BillingCycle.MONTHLY) // STARTER max 2 riders

        val underLimit = checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.RIDER_COUNT, currentUsage = 1)
        val atLimit = checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.RIDER_COUNT, currentUsage = 2)

        assertTrue(underLimit is SubscriptionAccessResult.Allowed)
        assertTrue(atLimit is SubscriptionAccessResult.LimitReached)
        val limitReached = atLimit as SubscriptionAccessResult.LimitReached
        assertEquals(2L, limitReached.maximum)
        assertEquals(2L, limitReached.current)
    }

    // ---------------- Scenario 14 ----------------
    @Test
    fun `14 - Business plan rider limit is enforced at maximum 10 riders`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 2L, BillingCycle.MONTHLY) // BUSINESS max 10 riders

        val underLimit = checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.RIDER_COUNT, currentUsage = 9)
        val atLimit = checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.RIDER_COUNT, currentUsage = 10)

        assertTrue(underLimit is SubscriptionAccessResult.Allowed)
        assertTrue(atLimit is SubscriptionAccessResult.LimitReached)
        assertEquals(10L, (atLimit as SubscriptionAccessResult.LimitReached).maximum)
    }

    // ---------------- Scenario 15 ----------------
    @Test
    fun `15 - Starter plan station limit is enforced at maximum 1 station`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 1L, BillingCycle.MONTHLY)

        val atLimit = checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.STATION_COUNT, currentUsage = 1)
        assertTrue(atLimit is SubscriptionAccessResult.LimitReached)
        assertEquals(1L, (atLimit as SubscriptionAccessResult.LimitReached).maximum)
    }

    // ---------------- Scenario 16 ----------------
    @Test
    fun `16 - Upgrade immediately activates new plan and updates feature access and limits`() = runTest {
        val stationId = 1L
        fakeSubscriptionRepository.activatePlan(stationId, 1L, BillingCycle.MONTHLY) // Starts with Starter

        // Verify multi-branch initially blocked
        val beforeUpgrade = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.MULTI_BRANCH)
        assertTrue(beforeUpgrade is SubscriptionAccessResult.FeatureNotIncluded)

        // Upgrade to Pro
        upgradeSubscriptionUseCase(stationId, 3L, BillingCycle.MONTHLY)

        // Verify multi-branch immediately unlocked and limits upgraded
        val afterUpgrade = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.MULTI_BRANCH)
        val riderLimitCheck = checkSubscriptionLimitUseCase(stationId, SubscriptionLimit.RIDER_COUNT, currentUsage = 5)

        assertTrue(afterUpgrade is SubscriptionAccessResult.Allowed)
        assertTrue(riderLimitCheck is SubscriptionAccessResult.Allowed)
    }

    // ---------------- Scenario 17 ----------------
    @Test
    fun `17 - Downgrade schedules change at end of current period and applies on expiration processing`() = runTest {
        val stationId = 1L
        val currentSub = fakeSubscriptionRepository.activatePlan(stationId, 3L, BillingCycle.MONTHLY) // Pro plan (end = +30d)

        // Day 10: Schedule downgrade to Starter at end of 30-day period
        fakeTimeProvider.advanceTime(10 * ONE_DAY_MS)
        scheduleDowngradeUseCase(stationId, 1L, currentSub.currentPeriodEnd)

        // Day 20: Access should still be Pro (pending downgrade not yet effective)
        fakeTimeProvider.advanceTime(10 * ONE_DAY_MS)
        val accessDuringPro = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.MULTI_BRANCH)
        assertTrue(accessDuringPro is SubscriptionAccessResult.Allowed)

        // Day 31: Period ended, process expiration
        fakeTimeProvider.advanceTime(11 * ONE_DAY_MS)
        processSubscriptionExpirationUseCase(stationId)

        // Access should now be Starter (MULTI_BRANCH blocked)
        val accessAfterDowngrade = checkSubscriptionAccessUseCase(stationId, SubscriptionFeature.MULTI_BRANCH)
        assertTrue(accessAfterDowngrade is SubscriptionAccessResult.FeatureNotIncluded)
    }
}

// ---------------- Test Fakes ----------------

class FakeTestTimeProvider(private var currentTime: Long) : TimeProvider {
    override fun now(): Long = currentTime
    fun advanceTime(millis: Long) { currentTime += millis }
}

class FakeTestSubscriptionPlanRepository : SubscriptionPlanRepository {
    private val plans = DEFAULT_SUBSCRIPTION_PLANS

    override fun observePlans(): Flow<List<SubscriptionPlan>> = flowOf(plans)
    override suspend fun getPlans(): List<SubscriptionPlan> = plans
    override suspend fun getPlan(planId: Long): SubscriptionPlan? = plans.find { it.id == planId }
    override suspend fun getPlanByCode(code: SubscriptionPlanCode): SubscriptionPlan? = plans.find { it.code == code }
    override suspend fun getPlanFeatures(planId: Long): List<FeatureEntitlement> = plans.find { it.id == planId }?.features ?: emptyList()
    override suspend fun getPlanLimits(planId: Long): List<PlanLimit> = plans.find { it.id == planId }?.limits ?: emptyList()
}

class FakeTestSubscriptionRepository(
    private val timeProvider: TimeProvider,
    private val planRepository: SubscriptionPlanRepository
) : SubscriptionRepository {

    private val subscriptions = mutableMapOf<Long, Subscription>()
    private val history = mutableListOf<SubscriptionHistoryEntry>()
    private var nextId = 1L

    override fun observeCurrentSubscription(stationId: Long): Flow<Subscription?> = flowOf(subscriptions[stationId])
    override suspend fun getCurrentSubscription(stationId: Long): Subscription? = subscriptions[stationId]

    override suspend fun getCurrentBusinessSubscription(): Subscription? =
        subscriptions.values.maxByOrNull { it.updatedAt }

    override suspend fun isOwnerTrialConsumed(): Boolean = false

    override suspend fun createTrial(
        stationId: Long,
        planCode: SubscriptionPlanCode,
        billingCycle: BillingCycle
    ): Subscription {
        val now = timeProvider.now()
        val plan = planRepository.getPlanByCode(planCode) ?: planRepository.getPlans()[0]
        val trialEnd = now + (30L * 24L * 60L * 60L * 1000L)
        val graceEnd = trialEnd + (3L * 24L * 60L * 60L * 1000L)

        val sub = Subscription(
            id = nextId++,
            stationId = stationId,
            planId = plan.id,
            planCode = plan.code,
            status = SubscriptionStatus.TRIALING,
            billingCycle = billingCycle,
            startedAt = now,
            currentPeriodStart = now,
            currentPeriodEnd = trialEnd,
            trialStartedAt = now,
            trialEndsAt = trialEnd,
            gracePeriodEndsAt = graceEnd,
            autoRenew = true
        )
        subscriptions[stationId] = sub
        return sub
    }

    override suspend fun activatePlan(stationId: Long, planId: Long, billingCycle: BillingCycle): Subscription {
        val now = timeProvider.now()
        val plan = planRepository.getPlan(planId) ?: throw IllegalArgumentException("Plan not found")
        val periodDuration = if (billingCycle == BillingCycle.ANNUAL) 365L * 24L * 60L * 60L * 1000L else 30L * 24L * 60L * 60L * 1000L
        val periodEnd = now + periodDuration

        val sub = Subscription(
            id = nextId++,
            stationId = stationId,
            planId = plan.id,
            planCode = plan.code,
            status = SubscriptionStatus.ACTIVE,
            billingCycle = billingCycle,
            startedAt = now,
            currentPeriodStart = now,
            currentPeriodEnd = periodEnd,
            gracePeriodEndsAt = periodEnd + (3L * 24L * 60L * 60L * 1000L),
            autoRenew = true
        )
        subscriptions[stationId] = sub
        return sub
    }

    override suspend fun cancelSubscription(stationId: Long, cancelAtPeriodEnd: Boolean): Subscription? {
        val existing = subscriptions[stationId] ?: return null
        val updated = if (cancelAtPeriodEnd) {
            existing.copy(cancelAtPeriodEnd = true, cancelledAt = timeProvider.now(), autoRenew = false)
        } else {
            existing.copy(status = SubscriptionStatus.CANCELLED, cancelledAt = timeProvider.now(), expiresAt = timeProvider.now(), autoRenew = false)
        }
        subscriptions[stationId] = updated
        return updated
    }

    override suspend fun resumeSubscription(stationId: Long): Subscription? {
        val existing = subscriptions[stationId] ?: return null
        val updated = existing.copy(cancelAtPeriodEnd = false, cancelledAt = null, autoRenew = true)
        subscriptions[stationId] = updated
        return updated
    }

    override suspend fun renewSubscription(stationId: Long): Subscription? {
        val existing = subscriptions[stationId] ?: return null
        val now = timeProvider.now()
        val periodEnd = now + (30L * 24L * 60L * 60L * 1000L)
        val updated = existing.copy(
            status = SubscriptionStatus.ACTIVE,
            currentPeriodStart = now,
            currentPeriodEnd = periodEnd,
            gracePeriodEndsAt = periodEnd + (3L * 24L * 60L * 60L * 1000L),
            autoRenew = true,
            cancelAtPeriodEnd = false
        )
        subscriptions[stationId] = updated
        return updated
    }

    override suspend fun upgradePlan(stationId: Long, newPlanId: Long, billingCycle: BillingCycle): Subscription {
        return activatePlan(stationId, newPlanId, billingCycle)
    }

    override suspend fun schedulePlanChange(stationId: Long, planId: Long, effectiveAt: Long): Subscription? {
        val existing = subscriptions[stationId] ?: return null
        val updated = existing.copy(pendingPlanId = planId, pendingPlanEffectiveAt = effectiveAt)
        subscriptions[stationId] = updated
        return updated
    }

    override suspend fun updateSubscriptionStatus(subscriptionId: Long, status: SubscriptionStatus) {
        val entry = subscriptions.entries.find { it.value.id == subscriptionId }
        if (entry != null) {
            subscriptions[entry.key] = entry.value.copy(status = status)
        }
    }

    override suspend fun getSubscriptionHistory(stationId: Long): List<SubscriptionHistoryEntry> = history

    override suspend fun processSubscriptionExpiration(stationId: Long): Subscription? {
        val sub = subscriptions[stationId] ?: return null
        val now = timeProvider.now()

        // Handle pending plan change
        if (sub.pendingPlanId != null && sub.pendingPlanEffectiveAt != null && now >= sub.pendingPlanEffectiveAt) {
            val targetPlan = planRepository.getPlan(sub.pendingPlanId)
            if (targetPlan != null) {
                val updated = sub.copy(
                    planId = targetPlan.id,
                    planCode = targetPlan.code,
                    pendingPlanId = null,
                    pendingPlanEffectiveAt = null
                )
                subscriptions[stationId] = updated
                return updated
            }
        }

        return sub
    }

    override suspend fun replicateSubscription(sourceStationId: Long, targetStationId: Long): Subscription? {
        val source = subscriptions[sourceStationId] ?: return null
        val replicated = source.copy(id = nextId++, stationId = targetStationId)
        subscriptions[targetStationId] = replicated
        return replicated
    }
}

class FakeTestStationRepository : com.aesprt.aquahub.domain.repository.StationRepository {
    var stations = listOf(Station(id = 1))
    var createStationOverride: ((Station) -> Long)? = null
    override fun observeStation(stationId: Long) = flowOf(stations.find { it.id == stationId })
    override suspend fun getStation(stationId: Long) = stations.find { it.id == stationId }
    override fun observeAllStations() = flowOf(stations)
    override suspend fun getAllStations() = stations
    override suspend fun createStation(station: Station): Long = createStationOverride?.invoke(station) ?: (stations.size + 1).toLong()
    override suspend fun updateStation(station: Station) = Unit
    override suspend fun deleteStation(stationId: Long) = Unit
}
