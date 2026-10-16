package com.aesprt.aquahub.data.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aesprt.aquahub.BuildConfig
import com.aesprt.aquahub.data.local.entity.ProductEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.supabase.inventory.SupabaseInventoryDataSource
import com.aesprt.aquahub.domain.model.DateRangeFilter
import com.aesprt.aquahub.domain.model.OfflineCashSaleRequest
import com.aesprt.aquahub.domain.model.OfflinePosReservationItem
import com.aesprt.aquahub.domain.repository.AuthRepository
import com.aesprt.aquahub.domain.repository.OfflinePosRepository
import com.aesprt.aquahub.domain.repository.OrderRepository
import com.aesprt.aquahub.domain.repository.ReportRepository
import com.aesprt.aquahub.domain.repository.ExpenseRepository
import com.aesprt.aquahub.sync.SyncResourceType
import com.aesprt.aquahub.sync.SyncScheduler
import com.aesprt.aquahub.core.database.AquaHubDatabase
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.core.context.GlobalContext
import kotlin.math.roundToLong

/**
 * Opt-in multi-process test. It uses the real QA Supabase client, repository,
 * Room database, Edge Function and WorkManager. The host controls connectivity
 * and the disposable response-loss proxy between phases.
 */
@org.junit.runner.RunWith(AndroidJUnit4::class)
class OfflinePosDisposableE2eTest {
    private val args get() = InstrumentationRegistry.getArguments()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun offlinePosDisposableFlow() = runBlocking {
        assumeTrue("Opt-in disposable integration only", args.getString("offlinePosE2e") == "true")
        assertTrue("QA flavor required", BuildConfig.QA_BUILD)
        assertTrue("Never allow the production Supabase project in this harness", !BuildConfig.SUPABASE_URL.contains("pfkxrggexueltytxtqil"))
        val fixtureUid = requiredArg("offlinePosUserId")
        val phase = requiredArg("offlinePosPhase")
        when (phase) {
            "prepare" -> prepare(fixtureUid, requiredArg("offlinePosEmail"), requiredArg("offlinePosPassword"), requiredArg("offlinePosStationId"), requiredArg("offlinePosProductId"))
            "replenish-stock" -> replenishDisposableStock(
                fixtureUid,
                requiredArg("offlinePosStationId"),
                requiredArg("offlinePosProductId"),
                args.getString("offlinePosStockAdjustmentId")
            )
            "record-offline" -> {
                args.getString("offlineStartDelayMs")?.toLongOrNull()?.let { delay(it.coerceIn(0L, 60_000L)) }
                recordOffline(fixtureUid)
            }
            "verify-pending" -> verifyPending(fixtureUid)
            "trigger-sync" -> triggerSync(fixtureUid)
            "recover-sync" -> recoverAndVerify(fixtureUid)
            "verify-expense-hydration" -> verifyExpenseHydration(
                fixtureUid,
                requiredArg("offlinePosEmail"),
                requiredArg("offlinePosPassword"),
                requiredArg("offlinePosExpenseId"),
                requiredArg("offlinePosPreviousExpenseCentavos").toLong(),
                requiredArg("offlinePosExpectedExpenseCentavos").toLong(),
                requiredArg("offlinePosExpectedExpenseDescription")
            )
            else -> error("Unknown disposable integration phase: $phase")
        }
    }

    private suspend fun prepare(uid: String, email: String, password: String, stationRemoteId: String, productRemoteId: String) {
        Log.i(TAG, "prepare:login-start")
        val owner = GlobalContext.get().get<AuthRepository>().loginWithEmail(email, password).getOrThrow()
        Log.i(TAG, "prepare:login-complete")
        assertEquals(uid, SupabaseClientProvider.client.auth.currentUserOrNull()?.id)
        assertTrue("Authenticated owner has a local station", owner.stationId > 0L)

        val database = GlobalContext.get().get<AquaHubDatabase>()
        val syncDao = database.syncDao()
        val stationMapping = requireNotNull(syncDao.getMetadataByRemoteId(SyncResourceType.STATION.name, stationRemoteId))
        assertEquals(owner.stationId, stationMapping.localId)

        val productJson = SupabaseClientProvider.client.from("products").select {
            filter { eq("id", productRemoteId); eq("station_id", stationRemoteId); eq("is_active", true); eq("is_available", true) }
        }.decodeList<JsonObject>().single()
        Log.i(TAG, "prepare:product-loaded")
        val productDao = database.productDao()
        val existingMapping = syncDao.getMetadataByRemoteId(SyncResourceType.PRODUCT.name, productRemoteId)
        val productLocalId = existingMapping?.localId ?: productDao.insertProduct(
            ProductEntity(
                stationId = owner.stationId,
                name = productJson.string("name"),
                type = productJson.string("product_type"),
                sizeLabel = productJson.string("size_label"),
                price = productJson.long("price_centavos") / 100.0,
                stockQuantity = 10,
                imagePath = null,
                isAvailable = true,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isActive = true
            )
        )
        if (existingMapping != null) {
            val current = productDao.getProductById(productLocalId)
            assertNotNull("Existing local product mapping resolves", current)
            productDao.updateProduct(requireNotNull(current).copy(
                name = productJson.string("name"),
                type = productJson.string("product_type"),
                sizeLabel = productJson.string("size_label"),
                price = productJson.long("price_centavos") / 100.0,
                stockQuantity = 10,
                isAvailable = true,
                isActive = true
            ))
        } else {
            val now = System.currentTimeMillis()
            syncDao.upsertMetadata(SyncMetadataEntity(
                resourceType = SyncResourceType.PRODUCT.name,
                localId = productLocalId,
                remoteId = productRemoteId,
                stationLocalId = owner.stationId,
                lastLocalUpdatedAt = now,
                lastServerUpdatedAt = now,
                lastSyncedAt = now
            ))
        }

        val offlinePosRepository = GlobalContext.get().get<OfflinePosRepository>()
        val existingStatus = offlinePosRepository.getStatus(owner.stationId)
        val existingRemaining = existingStatus.productSnapshots[productLocalId]?.quantityRemaining ?: 0
        val requestedQuantity = if (existingStatus.canCreateOfflineSale && existingRemaining < 3) 1 else 3
        val balanceBefore = SupabaseClientProvider.client.from("inventory_balances").select {
            filter { eq("station_id", stationRemoteId); eq("product_id", productRemoteId) }
        }.decodeList<JsonObject>().single()
        val reservedBefore = balanceBefore.long("reserved_quantity")

        Log.i(TAG, "prepare:reservation-start")
        val status = offlinePosRepository
            .prepareReservation(owner.stationId, listOf(OfflinePosReservationItem(productLocalId, requestedQuantity)))
        Log.i(TAG, "prepare:reservation-complete")
        assertTrue("Server issued and Android verified the grant and lease", status.canCreateOfflineSale)
        assertTrue(requireNotNull(status.productSnapshots[productLocalId]?.quantityRemaining) >= requestedQuantity)
        assertEquals(2_500L, status.productSnapshots[productLocalId]?.unitPriceCentavos)
        val balance = SupabaseClientProvider.client.from("inventory_balances").select {
            filter { eq("station_id", stationRemoteId); eq("product_id", productRemoteId) }
        }.decodeList<JsonObject>().single()
        assertEquals(balanceBefore.long("quantity"), balance.long("quantity"))
        val expectedReserved = reservedBefore + if (existingStatus.canCreateOfflineSale) 0L else requestedQuantity.toLong()
        assertEquals("This device's reservation adds stock once without assuming an empty station", expectedReserved, balance.long("reserved_quantity"))
    }

    private suspend fun replenishDisposableStock(
        uid: String,
        stationRemoteId: String,
        productRemoteId: String,
        requestedOperationId: String?
    ) {
        GlobalContext.get().get<AuthRepository>().restoreCurrentSession().getOrThrow()
        assertEquals(uid, SupabaseClientProvider.client.auth.currentUserOrNull()?.id)
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val operationId = requestedOperationId
            ?: preferences.getString(KEY_STOCK_REPLENISHMENT_OPERATION_ID, null)
            ?: java.util.UUID.randomUUID().toString()
        check(preferences.edit().putString(KEY_STOCK_REPLENISHMENT_OPERATION_ID, operationId).commit())
        val result = SupabaseInventoryDataSource().adjust(
            stationId = stationRemoteId,
            productId = productRemoteId,
            delta = 12,
            operationId = "qa-offline-pos-replenishment:$operationId",
            reason = "Disposable OFF-03 end-to-end fixture replenishment",
            movementType = "QA_FIXTURE_REPLENISHMENT",
            sourceId = operationId
        )
        val movement = SupabaseClientProvider.client.from("inventory_movements").select {
            filter { eq("operation_id", "qa-offline-pos-replenishment:$operationId") }
        }.decodeList<JsonObject>()
        assertEquals("The test replenishment has one auditable inventory movement", 1, movement.size)
        assertEquals(12L, movement.single().long("delta"))
        assertTrue("The disposable station has inventory after replenishment", result.quantity > 0)
    }

    private suspend fun recordOffline(uid: String) {
        Log.i(TAG, "offline:session-restore-start")
        assertNoValidatedNetwork()
        val auth = GlobalContext.get().get<AuthRepository>()
        val owner = auth.restoreCurrentSession().getOrThrow()
        Log.i(TAG, "offline:session-restore-complete")
        assertEquals(uid, SupabaseClientProvider.client.auth.currentUserOrNull()?.id)
        val database = GlobalContext.get().get<AquaHubDatabase>()
        val stationId = owner.stationId
        val productMapping = requireNotNull(database.syncDao().getMetadataForStation(SyncResourceType.PRODUCT.name, stationId).singleOrNull())

        val repository = GlobalContext.get().get<OfflinePosRepository>()
        val status = repository.getStatus(stationId)
        assertTrue(status.canCreateOfflineSale)
        val reservedForDevice = requireNotNull(status.productSnapshots[productMapping.localId]?.quantityRemaining)
        assertTrue("This device retains at least one unit from its signed lease", reservedForDevice >= 1)
        val (start, end) = DateRangeFilter.TODAY.calculateTimestamps()
        val reports = GlobalContext.get().get<ReportRepository>()
        val reportBeforeSale = reports.getTodaySales(stationId, start, end)
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val baselineSaved = preferences.edit()
            .putLong(KEY_BASELINE_CONFIRMED_CENTAVOS, (reportBeforeSale.totalSales * 100.0).roundToLong())
            .putLong(KEY_BASELINE_PENDING_CENTAVOS, (reportBeforeSale.pendingOfflineCash * 100.0).roundToLong())
            .putInt(KEY_BASELINE_ORDER_COUNT, reportBeforeSale.totalOrders)
            .commit()
        assertTrue("The financial baseline is durable across a force-stop", baselineSaved)
        Log.i(TAG, "offline:cash-sale-start")
        repository.recordCashSale(OfflineCashSaleRequest(
            stationId = stationId,
            customerId = 0L,
            customerName = "Offline QA Walk-in",
            customerPhone = "",
            notes = "Disposable offline POS E2E",
            items = listOf(OfflinePosReservationItem(productMapping.localId, 1)),
            cashReceivedCentavos = 5_000L
        ))
        Log.i(TAG, "offline:cash-sale-complete")

        val operation = requireNotNull(database.offlinePosDao().getRecoveryOperations(stationId, uid).singleOrNull()?.operation)
        val operationIdentitySaved = preferences.edit()
            .putString(KEY_OPERATION_ID, operation.operationId)
            .commit()
        assertTrue("The test operation UUID is durable across a force-stop", operationIdentitySaved)
        assertTrue(
            "An interrupted or retryable commit stays recoverable until confirmed",
            operation.state in setOf("PENDING", "RETRY", "SYNCING")
        )
        assertEquals(2_500L, operation.totalCentavos)
        assertEquals(5_000L, operation.cashReceivedCentavos)
        assertEquals(2_500L, operation.changeCentavos)
        assertEquals(64, operation.payloadDigest.length)
        assertTrue(operation.operationId.matches(Regex("[0-9a-fA-F-]{36}")))
        assertTrue(operation.canonicalPayload.contains(operation.operationId))
        assertTrue(operation.deviceSignature.isNotBlank())
        val localLines = database.offlinePosDao().getOperationWithLines(operation.operationId)?.lines.orEmpty()
        assertEquals(1, localLines.size)
        assertEquals(2_500L, localLines.single().lineTotalCentavos)
        val payment = requireNotNull(database.paymentDao().getPaymentByOperationId("offline-pos-payment:${operation.operationId}"))
        assertEquals("PENDING", payment.status)
        assertEquals(25.0, payment.amount, 0.001)
        assertEquals(
            reservedForDevice - 1,
            repository.getStatus(stationId).productSnapshots[productMapping.localId]?.quantityRemaining ?: 0
        )

        val report = reports.getTodaySales(stationId, start, end)
        assertEquals(preferences.getLong(KEY_BASELINE_CONFIRMED_CENTAVOS, Long.MIN_VALUE), (report.totalSales * 100.0).roundToLong())
        assertEquals(preferences.getLong(KEY_BASELINE_PENDING_CENTAVOS, Long.MIN_VALUE) + 2_500L, (report.pendingOfflineCash * 100.0).roundToLong())
        val dashboard = reports.observeDashboardSummary(stationId).first()
        assertEquals(preferences.getLong(KEY_BASELINE_CONFIRMED_CENTAVOS, Long.MIN_VALUE), (dashboard.todaySales * 100.0).roundToLong())
        assertEquals(preferences.getLong(KEY_BASELINE_PENDING_CENTAVOS, Long.MIN_VALUE) + 2_500L, (dashboard.pendingOfflineCash * 100.0).roundToLong())
    }

    private suspend fun triggerSync(uid: String) {
        Log.i(TAG, "sync:trigger-start")
        GlobalContext.get().get<AuthRepository>().restoreCurrentSession().getOrThrow()
        assertEquals(uid, SupabaseClientProvider.client.auth.currentUserOrNull()?.id)
        val stationId = GlobalContext.get().get<AuthRepository>().getCurrentOwner()?.stationId ?: error("Restored owner is missing")
        val stationRemoteId = requireNotNull(GlobalContext.get().get<AquaHubDatabase>().syncDao()
            .getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId)
        val scheduler = GlobalContext.get().get<SyncScheduler>()
        scheduler.enqueueImmediate(stationRemoteId)
        scheduler.enqueueImmediate(stationRemoteId)
        Log.i(TAG, "sync:trigger-enqueued")
        delay(1_000)
    }

    private suspend fun verifyPending(uid: String) {
        val auth = GlobalContext.get().get<AuthRepository>()
        val owner = auth.restoreCurrentSession().getOrThrow()
        assertEquals(uid, owner.firebaseUid)
        assertEquals(uid, SupabaseClientProvider.client.auth.currentUserOrNull()?.id)
        val database = GlobalContext.get().get<AquaHubDatabase>()
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val operation = resolveTestOperation(owner.stationId, uid, database, preferences)
        val operationId = operation.operationId
        assertTrue(
            "An interrupted or retryable commit remains locally recoverable",
            operation.state in setOf("PENDING", "RETRY", "SYNCING")
        )
        assertEquals(uid, operation.profileUid)
        assertEquals(2_500L, operation.totalCentavos)
        assertEquals(5_000L, operation.cashReceivedCentavos)
        assertEquals(2_500L, operation.changeCentavos)
        assertEquals(64, operation.payloadDigest.length)
        assertEquals(1, database.offlinePosDao().getOperationWithLines(operationId)?.lines?.size)
        val (start, end) = DateRangeFilter.TODAY.calculateTimestamps()
        val report = GlobalContext.get().get<ReportRepository>().getTodaySales(owner.stationId, start, end)
        val baselineConfirmed = preferences.getLong(KEY_BASELINE_CONFIRMED_CENTAVOS, Long.MIN_VALUE)
        val baselinePending = preferences.getLong(KEY_BASELINE_PENDING_CENTAVOS, Long.MIN_VALUE)
        assertTrue("The pre-sale financial baseline was retained", baselineConfirmed >= 0L && baselinePending >= 0L)
        assertEquals(baselineConfirmed, (report.totalSales * 100.0).roundToLong())
        assertEquals(baselinePending + 2_500L, (report.pendingOfflineCash * 100.0).roundToLong())
        // The local order count includes pending offline orders; revenue remains
        // excluded until the server confirms the cash transaction.
        assertEquals(preferences.getInt(KEY_BASELINE_ORDER_COUNT, -1) + 1, report.totalOrders)
    }

    private suspend fun recoverAndVerify(uid: String) {
        val auth = GlobalContext.get().get<AuthRepository>()
        auth.restoreCurrentSession().getOrThrow()
        assertEquals(uid, SupabaseClientProvider.client.auth.currentUserOrNull()?.id)
        val database = GlobalContext.get().get<AquaHubDatabase>()
        val stationId = requireNotNull(auth.getCurrentOwner()?.stationId)
        val syncDao = database.syncDao()
        val stationRemoteId = requireNotNull(syncDao.getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId)
        val offlineDao = database.offlinePosDao()
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val operation = resolveTestOperation(stationId, uid, database, preferences)

        // A process killed while an RPC response is in flight leaves the Room
        // claim until its bounded five-minute lease expires. Wait for that real
        // deadline; then wake the same durable WorkManager queue again.
        val deadline = System.currentTimeMillis() + 6 * 60 * 1000L
        while (operation.state == "SYNCING" && operation.nextAttemptAtMs != null &&
            System.currentTimeMillis() < operation.nextAttemptAtMs!! && System.currentTimeMillis() < deadline) {
            delay(2_000)
        }
        GlobalContext.get().get<SyncScheduler>().enqueueImmediate(stationRemoteId)
        if (args.getString("offlinePosRefreshRace") == "true") {
            var canonicalRemoteOrderId: String? = null
            kotlinx.coroutines.withTimeout<Unit>(30_000) {
                while (canonicalRemoteOrderId == null) {
                    val rows = SupabaseClientProvider.client.from("orders").select {
                        filter { eq("client_operation_id", operation.operationId) }
                    }.decodeList<JsonObject>()
                    if (rows.isNotEmpty()) {
                        assertEquals("The backend has exactly one canonical operation order", 1, rows.size)
                        canonicalRemoteOrderId = rows.single().string("id")
                    } else {
                        delay(200)
                    }
                }
            }
            GlobalContext.get().get<OrderRepository>().refreshDeliveryReads(stationId).getOrThrow()
            val localOrderId = requireNotNull(operation.localOrderId)
            assertEquals(
                "A Realtime/read refresh merges by client operation UUID instead of duplicating the offline order",
                1,
                database.orderDao().getOrderCountAtTimestamp(stationId, operation.transactionAtMs)
            )
            assertEquals(
                localOrderId,
                syncDao.getMetadataByRemoteId(SyncResourceType.ORDER.name, requireNotNull(canonicalRemoteOrderId))?.localId
            )
            Log.i(TAG, "refresh-race-merge-verified")
        }
        if (args.getString("offlinePosRefreshRace") == "true") Log.i(TAG, "commit-response-awaiting")
        val finalOperation = kotlinx.coroutines.withTimeout(90_000) {
            while (true) {
                val current = offlineDao.getOperation(operation.operationId)
                if (current?.state == "COMMITTED") return@withTimeout current
                delay(1_000)
            }
            error("unreachable")
        }
        assertNotNull(finalOperation.remoteOrderId)
        val lines = offlineDao.getOperationWithLines(operation.operationId)?.lines.orEmpty()
        assertEquals(1, lines.size)
        assertEquals(2_500L, finalOperation.totalCentavos)

        val orderRows = SupabaseClientProvider.client.from("orders").select {
            filter { eq("client_operation_id", operation.operationId) }
        }.decodeList<JsonObject>()
        assertEquals("Exactly one canonical server order", 1, orderRows.size)
        val canonicalOrder = orderRows.single()
        assertEquals(finalOperation.remoteOrderId, canonicalOrder.string("id"))
        assertEquals(2_500L, canonicalOrder.long("total_centavos"))

        val paymentRows = SupabaseClientProvider.client.from("payments").select {
            filter { eq("operation_id", "offline-pos-payment:${operation.operationId}") }
        }.decodeList<JsonObject>()
        assertEquals("Exactly one server payment", 1, paymentRows.size)
        assertEquals(2_500L, paymentRows.single().long("amount_centavos"))
        assertEquals("PAID", paymentRows.single().string("status"))

        val productRemoteId = lines.single().productRemoteId
        val movementRows = SupabaseClientProvider.client.from("inventory_movements").select {
            filter { eq("operation_id", "offline-pos:${operation.operationId}:$productRemoteId") }
        }.decodeList<JsonObject>()
        assertEquals("Exactly one inventory deduction", 1, movementRows.size)
        assertEquals(-1L, movementRows.single().long("delta"))
        assertEquals(finalOperation.remoteOrderId, movementRows.single().string("order_id"))
        assertEquals(
            "The offline operation has exactly one local order at its immutable transaction time",
            1,
            database.orderDao().getOrderCountAtTimestamp(stationId, operation.transactionAtMs)
        )

        val (start, end) = DateRangeFilter.TODAY.calculateTimestamps()
        val report = GlobalContext.get().get<ReportRepository>().getTodaySales(stationId, start, end)
        val baselineConfirmed = preferences.getLong(KEY_BASELINE_CONFIRMED_CENTAVOS, Long.MIN_VALUE)
        val baselinePending = preferences.getLong(KEY_BASELINE_PENDING_CENTAVOS, Long.MIN_VALUE)
        val baselineOrders = preferences.getInt(KEY_BASELINE_ORDER_COUNT, -1)
        assertEquals(baselineConfirmed + 2_500L, (report.totalSales * 100.0).roundToLong())
        assertEquals(baselinePending, (report.pendingOfflineCash * 100.0).roundToLong())
        assertEquals(baselineOrders + 1, report.totalOrders)
        val dashboard = GlobalContext.get().get<ReportRepository>().observeDashboardSummary(stationId).first()
        assertEquals(baselineConfirmed + 2_500L, (dashboard.todaySales * 100.0).roundToLong())
        assertEquals(baselinePending, (dashboard.pendingOfflineCash * 100.0).roundToLong())
    }

    private suspend fun verifyExpenseHydration(
        uid: String,
        email: String,
        password: String,
        remoteExpenseId: String,
        previousAmountCentavos: Long,
        expectedAmountCentavos: Long,
        expectedDescription: String
    ) {
        val owner = GlobalContext.get().get<AuthRepository>().loginWithEmail(email, password).getOrThrow()
        assertEquals(uid, SupabaseClientProvider.client.auth.currentUserOrNull()?.id)
        val database = GlobalContext.get().get<AquaHubDatabase>()
        val expenseDao = database.expenseDao()
        val repository = GlobalContext.get().get<ExpenseRepository>()
        val existing = expenseDao.getExpenseByRemoteId(remoteExpenseId)
        if (previousAmountCentavos == 0L) {
            assertEquals("A server-only fixture starts absent from Room", null, existing)
        } else {
            assertNotNull("A previously hydrated server expense remains mapped", existing)
            assertEquals(previousAmountCentavos, requireNotNull(existing).amountCentavos)
        }

        val totalBefore = repository.observeTotal(owner.stationId, 0L, Long.MAX_VALUE).first()
        val refresh = repository.refreshExpenses(owner.stationId)
        assertTrue("Android expense repository refresh succeeds", refresh.isSuccess)
        val hydrated = requireNotNull(expenseDao.getExpenseByRemoteId(remoteExpenseId))
        assertEquals(expectedAmountCentavos, hydrated.amountCentavos)
        assertEquals(expectedDescription, hydrated.description)
        assertTrue(hydrated.isActive)
        val metadata = requireNotNull(database.syncDao().getMetadataByRemoteId(SyncResourceType.EXPENSE.name, remoteExpenseId))
        assertEquals(hydrated.id, metadata.localId)
        assertEquals(0, database.syncDao().hasOutstandingMutation(SyncResourceType.EXPENSE.name, hydrated.id))

        val totalAfter = repository.observeTotal(owner.stationId, 0L, Long.MAX_VALUE).first()
        assertEquals(
            "Remote expense hydration replaces the previous amount exactly once",
            totalBefore - previousAmountCentavos + expectedAmountCentavos,
            totalAfter
        )
        assertEquals(1, repository.observeExpenses(owner.stationId, 0L, Long.MAX_VALUE).first()
            .count { it.id == hydrated.id })

        assertTrue(repository.refreshExpenses(owner.stationId).isSuccess)
        assertEquals(expectedAmountCentavos, expenseDao.getExpenseByRemoteId(remoteExpenseId)?.amountCentavos)
        assertEquals(totalAfter, repository.observeTotal(owner.stationId, 0L, Long.MAX_VALUE).first())
        Log.i(TAG, "expense-hydration-and-convergence-verified")
    }

    private suspend fun resolveTestOperation(
        stationId: Long,
        uid: String,
        database: AquaHubDatabase,
        preferences: android.content.SharedPreferences
    ): com.aesprt.aquahub.data.local.entity.OfflinePosOperationEntity {
        val dao = database.offlinePosDao()
        val latest = dao.observeOperations(stationId).first().firstOrNull { it.profileUid == uid }
            ?: error("No durable offline POS operation exists for the QA user")
        val savedId = preferences.getString(KEY_OPERATION_ID, null)
        val saved = savedId?.let { dao.getOperation(it) }
        if (saved?.operationId == latest.operationId) return saved

        // SharedPreferences.apply() may not have reached disk before a test
        // runner force-stop. Recover the latest durable Room operation and
        // derive its pre-sale baseline from the current summary without
        // changing or deleting any transaction data.
        val (start, end) = DateRangeFilter.TODAY.calculateTimestamps()
        val summary = GlobalContext.get().get<ReportRepository>().getTodaySales(stationId, start, end)
        val isCommitted = latest.state == "COMMITTED"
        val baselineConfirmed = (summary.totalSales * 100.0).roundToLong() -
            if (isCommitted) latest.totalCentavos else 0L
        val baselinePending = (summary.pendingOfflineCash * 100.0).roundToLong() -
            if (isCommitted) 0L else latest.totalCentavos
        val baselineOrders = summary.totalOrders - 1
        require(baselineConfirmed >= 0L && baselinePending >= 0L && baselineOrders >= 0) {
            "Cannot derive a valid test baseline for the latest offline POS operation"
        }
        val identityAndBaselineSaved = preferences.edit()
            .putString(KEY_OPERATION_ID, latest.operationId)
            .putLong(KEY_BASELINE_CONFIRMED_CENTAVOS, baselineConfirmed)
            .putLong(KEY_BASELINE_PENDING_CENTAVOS, baselinePending)
            .putInt(KEY_BASELINE_ORDER_COUNT, baselineOrders)
            .commit()
        check(identityAndBaselineSaved) { "The recovered test identity and baseline must be durable" }
        return latest
    }

    private fun assertNoValidatedNetwork() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        assertFalse("Android must have no validated Internet route during the offline sale", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
    }

    private fun requiredArg(key: String): String = requireNotNull(args.getString(key)) { "Missing instrumentation argument: $key" }

    private fun JsonObject.string(key: String): String = requireNotNull(get(key)?.jsonPrimitive?.contentOrNull) { "Missing server field $key" }
    private fun JsonObject.long(key: String): Long = requireNotNull(get(key)?.jsonPrimitive?.longOrNull) { "Missing numeric server field $key" }

    private companion object {
        const val TAG = "OfflinePosDisposableE2E"
        const val PREFS_NAME = "offline_pos_disposable_e2e"
        const val KEY_OPERATION_ID = "operation_id"
        const val KEY_BASELINE_CONFIRMED_CENTAVOS = "baseline_confirmed_centavos"
        const val KEY_BASELINE_PENDING_CENTAVOS = "baseline_pending_centavos"
        const val KEY_BASELINE_ORDER_COUNT = "baseline_order_count"
        const val KEY_STOCK_REPLENISHMENT_OPERATION_ID = "stock_replenishment_operation_id"
    }
}
