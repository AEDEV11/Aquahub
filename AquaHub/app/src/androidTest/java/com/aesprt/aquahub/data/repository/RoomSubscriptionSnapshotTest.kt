package com.aesprt.aquahub.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.local.entity.PlanFeatureEntity
import com.aesprt.aquahub.data.local.entity.StationEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.local.entity.SubscriptionEntity
import com.aesprt.aquahub.data.local.entity.SubscriptionPlanEntity
import com.aesprt.aquahub.data.supabase.subscription.EffectiveEntitlementsResponse
import com.aesprt.aquahub.data.supabase.subscription.SupabaseSubscriptionRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSubscriptionSnapshotTest {
    @Test
    fun expiredPlaySubscriptionProjectsFreeAcrossStationsWithoutChangingPaidCatalog() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val freePlanId = db.subscriptionPlanDao().insertPlan(plan("FREE"))
            val paidPlanId = db.subscriptionPlanDao().insertPlan(plan("STARTER"))
            val firstStationId = db.stationDao().insertStation(station("First"))
            val secondStationId = db.stationDao().insertStation(station("Second"))
            val otherBusinessStationId = db.stationDao().insertStation(station("Other business"))
            listOf(firstStationId to "remote-first", secondStationId to "remote-second",
                otherBusinessStationId to "remote-other").forEach { (localId, remoteId) ->
                db.syncDao().upsertMetadata(SyncMetadataEntity(
                    resourceType = "STATION",
                    localId = localId,
                    remoteId = remoteId,
                    stationLocalId = localId,
                    lastLocalUpdatedAt = 1L,
                ))
            }
            db.subscriptionPlanDao().insertFeatures(listOf(
                PlanFeatureEntity(planId = paidPlanId, feature = "MOBILE_APP_ORDERING", enabled = true, limitValue = null, createdAt = 1L)
            ))
            listOf(firstStationId, secondStationId, otherBusinessStationId).forEach { stationId ->
                db.subscriptionDao().insertSubscription(paidSubscription(stationId, paidPlanId))
            }

            val scopedStationIds = localStationIdsForRemoteIds(
                db.stationDao(), db.syncDao(), setOf("remote-first", "remote-second"))
            assertEquals(setOf(firstStationId, secondStationId), scopedStationIds)

            val applied = applyEffectiveSubscriptionSnapshot(
                database = db,
                subscriptionDao = db.subscriptionDao(),
                planDao = db.subscriptionPlanDao(),
                stationIds = scopedStationIds,
                remote = SupabaseSubscriptionRow(
                    id = "expired-paid",
                    organizationId = "organization",
                    status = "EXPIRED",
                    billingCycle = "MONTHLY",
                    currentPeriodEnd = 1_000L,
                    provider = "GOOGLE_PLAY",
                    externalSubscriptionId = "token-hash",
                ),
                effective = EffectiveEntitlementsResponse(
                    organizationId = "organization",
                    planCode = "FREE",
                    status = "EXPIRED", // Older server response; client must still project Free.
                    features = mapOf("ORDER_MANAGEMENT" to true, "MOBILE_APP_ORDERING" to false),
                ),
                now = 2_000L,
                updatedAt = 2_000L,
            )

            assertTrue(applied)
            listOf(firstStationId, secondStationId).forEach { stationId ->
                val local = db.subscriptionDao().getCurrentSubscription(stationId)!!
                assertEquals(freePlanId, local.planId)
                assertEquals("FREE", local.status)
                assertNull(local.provider)
            }
            assertEquals(paidPlanId, db.subscriptionDao()
                .getCurrentSubscription(otherBusinessStationId)!!.planId)
            assertFalse(db.subscriptionPlanDao().getFeaturesForPlan(freePlanId)
                .first { it.feature == "MOBILE_APP_ORDERING" }.enabled)
            assertTrue(db.subscriptionPlanDao().getFeaturesForPlan(paidPlanId)
                .first { it.feature == "MOBILE_APP_ORDERING" }.enabled)
        } finally {
            db.close()
        }
    }

    @Test
    fun emptyServerFeatureSetClearsStaleFeaturesForMappedStationOnly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AquaHubDatabase::class.java).build()
        try {
            val freePlanId = db.subscriptionPlanDao().insertPlan(plan("FREE"))
            val stationId = db.stationDao().insertStation(station("Mapped"))
            db.subscriptionPlanDao().insertFeatures(listOf(
                PlanFeatureEntity(planId = freePlanId, feature = "MOBILE_APP_ORDERING",
                    enabled = true, limitValue = null, createdAt = 1L)
            ))
            val remote = SupabaseSubscriptionRow(
                id = "effective-free", organizationId = "organization",
                status = "FREE", billingCycle = "MONTHLY",
            )
            val effective = EffectiveEntitlementsResponse(
                organizationId = "organization", planCode = "FREE", features = emptyMap(),
            )

            assertFalse(applyEffectiveSubscriptionSnapshot(
                db, db.subscriptionDao(), db.subscriptionPlanDao(), emptySet(),
                remote, effective, 2_000L, 2_000L,
            ))
            assertNull(db.subscriptionDao().getCurrentSubscription(stationId))
            assertTrue(applyEffectiveSubscriptionSnapshot(
                db, db.subscriptionDao(), db.subscriptionPlanDao(), setOf(stationId),
                remote, effective, 2_000L, 2_000L,
            ))
            assertEquals(freePlanId, db.subscriptionDao().getCurrentSubscription(stationId)!!.planId)
            assertTrue(db.subscriptionPlanDao().getFeaturesForPlan(freePlanId).isEmpty())
        } finally {
            db.close()
        }
    }

    private fun plan(code: String) = SubscriptionPlanEntity(
        code = code,
        name = code,
        description = null,
        monthlyPrice = 0.0,
        annualPrice = 0.0,
        isPopular = false,
        trialDays = 0,
        ctaText = "",
        isActive = true,
        sortOrder = 0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun station(name: String) = StationEntity(
        name = name,
        description = null,
        phone = "",
        email = null,
        address = "",
        barangay = null,
        city = null,
        province = null,
        latitude = null,
        longitude = null,
        logoPath = null,
        openingTime = null,
        closingTime = null,
        isOpen = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun paidSubscription(stationId: Long, planId: Long) = SubscriptionEntity(
        stationId = stationId,
        planId = planId,
        status = "EXPIRED",
        billingCycle = "MONTHLY",
        startedAt = 1L,
        currentPeriodStart = 1L,
        currentPeriodEnd = 1_000L,
        trialStartedAt = null,
        trialEndsAt = null,
        cancelledAt = null,
        expiresAt = null,
        gracePeriodEndsAt = null,
        autoRenew = false,
        cancelAtPeriodEnd = true,
        pendingPlanId = null,
        pendingPlanEffectiveAt = null,
        provider = "GOOGLE_PLAY",
        externalSubscriptionId = "token-hash",
        externalCustomerId = null,
        lastPaymentId = null,
        currency = "PHP",
        createdAt = 1L,
        updatedAt = 1L,
    )
}
