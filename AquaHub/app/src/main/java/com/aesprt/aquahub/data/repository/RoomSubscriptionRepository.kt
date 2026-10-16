package com.aesprt.aquahub.data.repository

import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.supabase.subscription.SupabaseSubscriptionDataSource
import com.aesprt.aquahub.data.supabase.subscription.EffectiveEntitlementsResponse
import com.aesprt.aquahub.data.supabase.subscription.SupabaseSubscriptionRow
import com.aesprt.aquahub.data.supabase.subscription.isSubscriptionSnapshotConsistent
import com.aesprt.aquahub.data.local.dao.SubscriptionDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.dao.SubscriptionHistoryDao
import com.aesprt.aquahub.data.local.dao.SubscriptionPlanDao
import com.aesprt.aquahub.data.local.entity.SubscriptionEntity
import com.aesprt.aquahub.data.local.entity.SubscriptionHistoryEntity
import com.aesprt.aquahub.data.local.entity.PlanFeatureEntity
import com.aesprt.aquahub.data.local.entity.PlanLimitEntity
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.local.mapper.toEntity
import com.aesprt.aquahub.data.local.dao.StationDao
import com.aesprt.aquahub.domain.common.TimeProvider
import com.aesprt.aquahub.domain.model.BillingCycle
import com.aesprt.aquahub.domain.model.Subscription
import com.aesprt.aquahub.domain.model.SubscriptionEventType
import com.aesprt.aquahub.domain.model.SubscriptionHistoryEntry
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.model.SubscriptionLimit
import com.aesprt.aquahub.domain.model.SubscriptionStatus
import com.aesprt.aquahub.domain.repository.SubscriptionRepository
import com.aesprt.aquahub.sync.SyncOperation
import com.aesprt.aquahub.sync.SyncResourceType
import com.aesprt.aquahub.sync.SyncQueueWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomSubscriptionRepository(
    private val database: AquaHubDatabase,
    private val subscriptionDao: SubscriptionDao,
    private val planDao: SubscriptionPlanDao,
    private val historyDao: SubscriptionHistoryDao,
    private val stationDao: StationDao,
    private val syncDao: SyncDao,
    private val timeProvider: TimeProvider,
    private val syncQueue: SyncQueueWriter,
    private val subscriptionDataSource: SupabaseSubscriptionDataSource
) : SubscriptionRepository {

    /** Prevent a dashboard refresh/expiration pass racing a user renewal. */
    private val subscriptionMutationMutex = Mutex()

    private fun requireBillingActivation(): Nothing {
        error("Billing is not connected yet. Paid plan changes require verified server activation.")
    }

    companion object {
        const val TRIAL_DURATION_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
        const val MONTHLY_DURATION_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
        const val ANNUAL_DURATION_MS = 365L * 24L * 60L * 60L * 1000L // 365 days
        const val GRACE_PERIOD_MS = 3L * 24L * 60L * 60L * 1000L // 3 days
    }

    override fun observeCurrentSubscription(stationId: Long): Flow<Subscription?> {
        return subscriptionDao.observeCurrentSubscription(stationId).map { entity ->
            if (entity == null) null
            else {
                val plan = planDao.getPlanById(entity.planId)
                val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS
                entity.toDomain(planCode)
            }
        }
    }

    override suspend fun getCurrentSubscription(stationId: Long): Subscription? {
        val entity = subscriptionDao.getCurrentSubscription(stationId) ?: return null
        val plan = planDao.getPlanById(entity.planId)
        val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS
        return entity.toDomain(planCode)
    }

    override suspend fun getCurrentBusinessSubscription(): Subscription? {
        val entity = subscriptionDao.getCurrentBusinessSubscription() ?: return null
        val plan = planDao.getPlanById(entity.planId)
        val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS
        return entity.toDomain(planCode)
    }

    override suspend fun refreshFromSupabase(): Boolean = subscriptionMutationMutex.withLock {
        var effective = subscriptionDataSource.effectiveEntitlements()
        val organizationId = effective.organizationId ?: return@withLock false
        fun freeBillingRow() = SupabaseSubscriptionRow(
            id = "effective-free",
            organizationId = organizationId,
            status = SubscriptionStatus.FREE.name,
            billingCycle = BillingCycle.MONTHLY.name,
        )
        var remote = subscriptionDataSource.current(organizationId)
            ?: if (effective.planCode.equals(SubscriptionPlanCode.FREE.name, ignoreCase = true)) freeBillingRow()
            else return@withLock false
        // An RTDN or another device can change the billing row between these
        // reads. Never combine a stale entitlement with a newer billing term.
        if (!isSubscriptionSnapshotConsistent(effective, remote)) {
            effective = subscriptionDataSource.effectiveEntitlements()
            if (effective.organizationId != organizationId) return@withLock false
            remote = subscriptionDataSource.current(organizationId)
                ?: if (effective.planCode.equals(SubscriptionPlanCode.FREE.name, ignoreCase = true)) freeBillingRow()
                else return@withLock false
            if (!isSubscriptionSnapshotConsistent(effective, remote)) return@withLock false
        }
        val billingRow = remote
        if (billingRow.organizationId != organizationId) return@withLock false
        val remoteStationIds = subscriptionDataSource.stationIdsForOrganization(organizationId)
        val localStationIds = localStationIdsForRemoteIds(stationDao, syncDao, remoteStationIds)
        if (localStationIds.isEmpty()) return@withLock false
        val now = timeProvider.now()
        val updatedAt = billingRow.updatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: billingRow.clientUpdatedAt
            ?: now
        applyEffectiveSubscriptionSnapshot(database, subscriptionDao, planDao, localStationIds, billingRow, effective, now, updatedAt)
    }


    override suspend fun isOwnerTrialConsumed(): Boolean =
        subscriptionDataSource.isOwnerTrialConsumed()

    override suspend fun createTrial(
        stationId: Long,
        planCode: SubscriptionPlanCode,
        billingCycle: BillingCycle
    ): Subscription = subscriptionMutationMutex.withLock {
        val existingCurrent = subscriptionDao.getCurrentSubscription(stationId)
        if (existingCurrent != null) requireBillingActivation()

        // A real owner's first trial is created by the trusted Supabase
        // function and then mirrored into Room. The device never grants
        // itself an untracked trial.
        val remoteTrial = subscriptionDataSource.ensureOwnerTrial(planCode.name, billingCycle.name)
        val remoteStationIds = subscriptionDataSource.stationIdsForOrganization(remoteTrial.businessId)
        val localStationIds = localStationIdsForRemoteIds(stationDao, syncDao, remoteStationIds)
        check(stationId in localStationIds) { "Trial station is not part of the verified organization." }
        val effectivePlanCode = SubscriptionPlanCode.fromString(remoteTrial.planCode)
        val planEntity = planDao.getPlanByCode(effectivePlanCode.name)
            ?: planDao.getPlanByCode(SubscriptionPlanCode.BUSINESS.name)
            ?: throw IllegalStateException("Plan $effectivePlanCode not found in database")
        val now = remoteTrial.startedAt
        val isTrial = remoteTrial.status.equals(SubscriptionStatus.TRIALING.name, ignoreCase = true)
        val trialEnd = if (isTrial) remoteTrial.trialEndsAt else null

        // Supabase owns one subscription per organization. Room keeps a
        // station-scoped replica because existing feature gates observe by
        // station. Only stations proven to belong to this organization receive
        // its trial.
        var selectedEntity: SubscriptionEntity? = null
        database.withTransaction { localStationIds.forEach { localStationId ->
            subscriptionDao.getSubscriptionsForStation(localStationId)
                .forEach { stale ->
                    syncQueue.removeResource(SyncResourceType.SUBSCRIPTION, stale.id)
                    subscriptionDao.deleteSubscription(stale.id)
                }

            val subscriptionEntity = SubscriptionEntity(
                stationId = localStationId,
                planId = planEntity.id,
                status = remoteTrial.status,
                billingCycle = remoteTrial.billingCycle,
                startedAt = now,
                currentPeriodStart = remoteTrial.currentPeriodStart,
                currentPeriodEnd = remoteTrial.currentPeriodEnd,
                trialStartedAt = if (isTrial) remoteTrial.trialStartedAt ?: now else null,
                trialEndsAt = trialEnd,
                cancelledAt = remoteTrial.cancelledAt,
                expiresAt = remoteTrial.expiresAt,
                gracePeriodEndsAt = remoteTrial.gracePeriodEndsAt,
                autoRenew = remoteTrial.autoRenew,
                cancelAtPeriodEnd = remoteTrial.cancelAtPeriodEnd,
                pendingPlanId = null,
                pendingPlanEffectiveAt = null,
                provider = "AQUAHUB_TRIAL",
                externalSubscriptionId = null,
                externalCustomerId = null,
                lastPaymentId = null,
                currency = remoteTrial.currency,
                createdAt = remoteTrial.createdAt,
                updatedAt = now
            )
            val subscriptionId = subscriptionDao.insertSubscription(subscriptionEntity)
            if (localStationId == stationId) {
                selectedEntity = subscriptionEntity.copy(id = subscriptionId)
            }

            historyDao.insertHistoryEntry(
                SubscriptionHistoryEntity(
                    stationId = localStationId,
                    subscriptionId = subscriptionId,
                    previousPlanId = null,
                    newPlanId = planEntity.id,
                    eventType = if (isTrial) {
                        SubscriptionEventType.TRIAL_STARTED.name
                    } else {
                        SubscriptionEventType.SUBSCRIBED.name
                    },
                    effectiveAt = now,
                    previousStatus = null,
                    newStatus = remoteTrial.status,
                    notes = if (isTrial) {
                        "Started 30-day free trial on ${planEntity.name} plan (${remoteTrial.billingCycle.lowercase()})"
                    } else {
                        "Started ${planEntity.name} plan"
                    },
                    createdAt = now
                )
            )
        } }

        (selectedEntity ?: subscriptionDao.getCurrentSubscription(stationId))?.toDomain(planCode)
            ?: error("Subscription cache could not be created")
    }

    override suspend fun activatePlan(
        stationId: Long,
        planId: Long,
        billingCycle: BillingCycle
    ): Subscription = subscriptionMutationMutex.withLock {
        requireBillingActivation()
        val now = timeProvider.now()
        val planEntity = planDao.getPlanById(planId)
            ?: throw IllegalStateException("Plan $planId not found")

        val planCode = SubscriptionPlanCode.fromString(planEntity.code)
        val periodDuration = if (billingCycle == BillingCycle.ANNUAL) ANNUAL_DURATION_MS else MONTHLY_DURATION_MS
        val periodEnd = now + periodDuration

        val existing = subscriptionDao.getCurrentSubscription(stationId)

        val subscriptionEntity = if (existing != null) {
            existing.copy(
                planId = planId,
                status = SubscriptionStatus.ACTIVE.name,
                billingCycle = billingCycle.name,
                currentPeriodStart = now,
                currentPeriodEnd = periodEnd,
                expiresAt = null,
                cancelledAt = null,
                cancelAtPeriodEnd = false,
                gracePeriodEndsAt = periodEnd + GRACE_PERIOD_MS,
                pendingPlanId = null,
                pendingPlanEffectiveAt = null,
                updatedAt = now
            )
        } else {
            SubscriptionEntity(
                stationId = stationId,
                planId = planId,
                status = SubscriptionStatus.ACTIVE.name,
                billingCycle = billingCycle.name,
                startedAt = now,
                currentPeriodStart = now,
                currentPeriodEnd = periodEnd,
                trialStartedAt = null,
                trialEndsAt = null,
                cancelledAt = null,
                expiresAt = null,
                gracePeriodEndsAt = periodEnd + GRACE_PERIOD_MS,
                autoRenew = true,
                cancelAtPeriodEnd = false,
                pendingPlanId = null,
                pendingPlanEffectiveAt = null,
                provider = null,
                externalSubscriptionId = null,
                externalCustomerId = null,
                lastPaymentId = null,
                currency = "PHP",
                createdAt = now,
                updatedAt = now
            )
        }

        val subId = if (existing != null) {
            subscriptionDao.updateSubscription(subscriptionEntity)
            existing.id
        } else {
            subscriptionDao.insertSubscription(subscriptionEntity)
        }
        syncQueue.enqueue(
            SyncResourceType.SUBSCRIPTION,
            subId,
            stationId,
            if (existing != null) SyncOperation.UPDATE else SyncOperation.CREATE,
            now
        )

        historyDao.insertHistoryEntry(
            SubscriptionHistoryEntity(
                stationId = stationId,
                subscriptionId = subId,
                previousPlanId = existing?.planId,
                newPlanId = planId,
                eventType = SubscriptionEventType.SUBSCRIBED.name,
                effectiveAt = now,
                previousStatus = existing?.status,
                newStatus = SubscriptionStatus.ACTIVE.name,
                notes = "Subscribed to ${planEntity.name} (${billingCycle.name.lowercase()})",
                createdAt = now
            )
        )

        subscriptionEntity.copy(id = subId).toDomain(planCode)
    }

    override suspend fun cancelSubscription(
        stationId: Long,
        cancelAtPeriodEnd: Boolean
    ): Subscription? = subscriptionMutationMutex.withLock {
        requireBillingActivation()
        val now = timeProvider.now()
        val existing = subscriptionDao.getCurrentSubscription(stationId) ?: return@withLock null
        val plan = planDao.getPlanById(existing.planId)
        val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS

        val updated = if (cancelAtPeriodEnd) {
            existing.copy(
                cancelAtPeriodEnd = true,
                cancelledAt = now,
                autoRenew = false,
                updatedAt = now
            )
        } else {
            existing.copy(
                status = SubscriptionStatus.CANCELLED.name,
                cancelledAt = now,
                expiresAt = now,
                autoRenew = false,
                cancelAtPeriodEnd = false,
                updatedAt = now
            )
        }

        subscriptionDao.updateSubscription(updated)
        syncQueue.enqueue(SyncResourceType.SUBSCRIPTION, existing.id, stationId, SyncOperation.UPDATE, now)

        historyDao.insertHistoryEntry(
            SubscriptionHistoryEntity(
                stationId = stationId,
                subscriptionId = existing.id,
                previousPlanId = existing.planId,
                newPlanId = existing.planId,
                eventType = SubscriptionEventType.CANCELLED.name,
                effectiveAt = now,
                previousStatus = existing.status,
                newStatus = updated.status,
                notes = if (cancelAtPeriodEnd) "Subscription set to cancel at end of billing period" else "Subscription cancelled immediately",
                createdAt = now
            )
        )

        updated.toDomain(planCode)
    }

    override suspend fun resumeSubscription(stationId: Long): Subscription? = subscriptionMutationMutex.withLock {
        requireBillingActivation()
        val now = timeProvider.now()
        val existing = subscriptionDao.getCurrentSubscription(stationId) ?: return@withLock null
        val plan = planDao.getPlanById(existing.planId)
        val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS

        val updated = existing.copy(
            cancelAtPeriodEnd = false,
            cancelledAt = null,
            autoRenew = true,
            status = if (existing.status == SubscriptionStatus.CANCELLED.name && existing.currentPeriodEnd > now) {
                SubscriptionStatus.ACTIVE.name
            } else existing.status,
            updatedAt = now
        )

        subscriptionDao.updateSubscription(updated)
        syncQueue.enqueue(SyncResourceType.SUBSCRIPTION, existing.id, stationId, SyncOperation.UPDATE, now)

        historyDao.insertHistoryEntry(
            SubscriptionHistoryEntity(
                stationId = stationId,
                subscriptionId = existing.id,
                previousPlanId = existing.planId,
                newPlanId = existing.planId,
                eventType = SubscriptionEventType.REACTIVATED.name,
                effectiveAt = now,
                previousStatus = existing.status,
                newStatus = updated.status,
                notes = "Subscription auto-renewal resumed",
                createdAt = now
            )
        )

        updated.toDomain(planCode)
    }

    override suspend fun renewSubscription(stationId: Long): Subscription? = subscriptionMutationMutex.withLock {
        requireBillingActivation()
        val now = timeProvider.now()
        val existing = subscriptionDao.getCurrentSubscription(stationId) ?: return@withLock null
        val plan = planDao.getPlanById(existing.planId)
        val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS

        val cycle = BillingCycle.fromString(existing.billingCycle)
        val periodDuration = if (cycle == BillingCycle.ANNUAL) ANNUAL_DURATION_MS else MONTHLY_DURATION_MS

        val start = if (existing.currentPeriodEnd > now) existing.currentPeriodEnd else now
        val end = start + periodDuration

        val updated = existing.copy(
            status = SubscriptionStatus.ACTIVE.name,
            currentPeriodStart = start,
            currentPeriodEnd = end,
            cancelledAt = null,
            cancelAtPeriodEnd = false,
            expiresAt = null,
            gracePeriodEndsAt = end + GRACE_PERIOD_MS,
            autoRenew = true,
            updatedAt = now
        )

        subscriptionDao.updateSubscription(updated)
        syncQueue.enqueue(SyncResourceType.SUBSCRIPTION, existing.id, stationId, SyncOperation.UPDATE, now)

        historyDao.insertHistoryEntry(
            SubscriptionHistoryEntity(
                stationId = stationId,
                subscriptionId = existing.id,
                previousPlanId = existing.planId,
                newPlanId = existing.planId,
                eventType = SubscriptionEventType.RENEWED.name,
                effectiveAt = now,
                previousStatus = existing.status,
                newStatus = SubscriptionStatus.ACTIVE.name,
                notes = "Renewed subscription on ${plan?.name ?: "plan"} until ${end}",
                createdAt = now
            )
        )

        updated.toDomain(planCode)
    }

    override suspend fun upgradePlan(
        stationId: Long,
        newPlanId: Long,
        billingCycle: BillingCycle
    ): Subscription = subscriptionMutationMutex.withLock {
        requireBillingActivation()
        val now = timeProvider.now()
        val newPlan = planDao.getPlanById(newPlanId)
            ?: throw IllegalStateException("Plan $newPlanId not found")

        val newPlanCode = SubscriptionPlanCode.fromString(newPlan.code)
        val existing = subscriptionDao.getCurrentSubscription(stationId)
        val previousPlanId = existing?.planId

        val periodDuration = if (billingCycle == BillingCycle.ANNUAL) ANNUAL_DURATION_MS else MONTHLY_DURATION_MS
        val periodEnd = now + periodDuration

        val updatedEntity = if (existing != null) {
            existing.copy(
                planId = newPlanId,
                status = SubscriptionStatus.ACTIVE.name,
                billingCycle = billingCycle.name,
                currentPeriodStart = now,
                currentPeriodEnd = periodEnd,
                cancelledAt = null,
                cancelAtPeriodEnd = false,
                expiresAt = null,
                gracePeriodEndsAt = periodEnd + GRACE_PERIOD_MS,
                pendingPlanId = null,
                pendingPlanEffectiveAt = null,
                updatedAt = now
            )
        } else {
            SubscriptionEntity(
                stationId = stationId,
                planId = newPlanId,
                status = SubscriptionStatus.ACTIVE.name,
                billingCycle = billingCycle.name,
                startedAt = now,
                currentPeriodStart = now,
                currentPeriodEnd = periodEnd,
                trialStartedAt = null,
                trialEndsAt = null,
                cancelledAt = null,
                expiresAt = null,
                gracePeriodEndsAt = periodEnd + GRACE_PERIOD_MS,
                autoRenew = true,
                cancelAtPeriodEnd = false,
                pendingPlanId = null,
                pendingPlanEffectiveAt = null,
                provider = null,
                externalSubscriptionId = null,
                externalCustomerId = null,
                lastPaymentId = null,
                currency = "PHP",
                createdAt = now,
                updatedAt = now
            )
        }

        val subId = if (existing != null) {
            subscriptionDao.updateSubscription(updatedEntity)
            existing.id
        } else {
            subscriptionDao.insertSubscription(updatedEntity)
        }
        syncQueue.enqueue(
            SyncResourceType.SUBSCRIPTION,
            subId,
            stationId,
            if (existing != null) SyncOperation.UPDATE else SyncOperation.CREATE,
            now
        )

        historyDao.insertHistoryEntry(
            SubscriptionHistoryEntity(
                stationId = stationId,
                subscriptionId = subId,
                previousPlanId = previousPlanId,
                newPlanId = newPlanId,
                eventType = SubscriptionEventType.UPGRADED.name,
                effectiveAt = now,
                previousStatus = existing?.status,
                newStatus = SubscriptionStatus.ACTIVE.name,
                notes = "Upgraded to ${newPlan.name} ($billingCycle)",
                createdAt = now
            )
        )

        updatedEntity.copy(id = subId).toDomain(newPlanCode)
    }

    override suspend fun schedulePlanChange(
        stationId: Long,
        planId: Long,
        effectiveAt: Long
    ): Subscription? = subscriptionMutationMutex.withLock {
        requireBillingActivation()
        val now = timeProvider.now()
        val existing = subscriptionDao.getCurrentSubscription(stationId) ?: return@withLock null
        val plan = planDao.getPlanById(existing.planId)
        val targetPlan = planDao.getPlanById(planId)
        val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS

        val updated = existing.copy(
            pendingPlanId = planId,
            pendingPlanEffectiveAt = effectiveAt,
            updatedAt = now
        )

        subscriptionDao.updateSubscription(updated)
        syncQueue.enqueue(SyncResourceType.SUBSCRIPTION, existing.id, stationId, SyncOperation.UPDATE, now)

        historyDao.insertHistoryEntry(
            SubscriptionHistoryEntity(
                stationId = stationId,
                subscriptionId = existing.id,
                previousPlanId = existing.planId,
                newPlanId = planId,
                eventType = SubscriptionEventType.DOWNGRADED.name,
                effectiveAt = effectiveAt,
                previousStatus = existing.status,
                newStatus = existing.status,
                notes = "Scheduled downgrade to ${targetPlan?.name ?: "Plan"} on $effectiveAt",
                createdAt = now
            )
        )

        updated.toDomain(planCode)
    }

    override suspend fun updateSubscriptionStatus(
        subscriptionId: Long,
        status: SubscriptionStatus
    ) = subscriptionMutationMutex.withLock {
        requireBillingActivation()
        val now = timeProvider.now()
        subscriptionDao.updateSubscriptionStatus(subscriptionId, status.name, now)
    }

    override suspend fun getSubscriptionHistory(stationId: Long): List<SubscriptionHistoryEntry> {
        // Remove rows written by older builds, which incorrectly labeled Free
        // plan activation as a trial. This is scoped to Free plan rows only.
        historyDao.deleteFreePlanTrialHistory(stationId)
        return historyDao.getHistoryForStation(stationId).map { it.toDomain() }
    }

    override suspend fun processSubscriptionExpiration(stationId: Long): Subscription? = subscriptionMutationMutex.withLock {
        val existing = subscriptionDao.getCurrentSubscription(stationId) ?: return@withLock null
        val plan = planDao.getPlanById(existing.planId)
        val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS

        // A cached provider deadline can only lower access to the baseline Free
        // tier while offline. A later verified refresh restores renewed access.
        if (planCode != SubscriptionPlanCode.FREE && !hasCurrentPaidAccess(existing, timeProvider.now())) {
            val free = planDao.getPlanByCode(SubscriptionPlanCode.FREE.name)
                ?: return@withLock existing.toDomain(planCode)
            val now = timeProvider.now()
            database.withTransaction {
                subscriptionDao.insertSubscription(projectOfflineFreeSubscription(existing, free.id, now))
            }
            return@withLock getCurrentSubscription(stationId)
        }
        existing.toDomain(planCode)
    }

    override suspend fun replicateSubscription(
        sourceStationId: Long,
        targetStationId: Long
    ): Subscription? = subscriptionMutationMutex.withLock {
        val existing = subscriptionDao.getCurrentSubscription(targetStationId)
        if (existing != null) {
            val plan = planDao.getPlanById(existing.planId)
            val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS
            return@withLock existing.toDomain(planCode)
        }
        val source = subscriptionDao.getCurrentSubscription(sourceStationId) ?: return@withLock null
        val now = timeProvider.now()
        val targetEntity = source.copy(
            id = 0L,
            stationId = targetStationId,
            createdAt = now,
            updatedAt = now
        )
        val id = subscriptionDao.insertSubscription(targetEntity)
        val plan = planDao.getPlanById(source.planId)
        val planCode = plan?.code?.let { SubscriptionPlanCode.fromString(it) } ?: SubscriptionPlanCode.BUSINESS
        targetEntity.copy(id = id).toDomain(planCode)
    }
}

/** Applies one verified organization's entitlement to its mapped local stations. */
internal suspend fun applyEffectiveSubscriptionSnapshot(
    database: AquaHubDatabase,
    subscriptionDao: SubscriptionDao,
    planDao: SubscriptionPlanDao,
    stationIds: Set<Long>,
    remote: SupabaseSubscriptionRow,
    effective: EffectiveEntitlementsResponse,
    now: Long,
    updatedAt: Long,
): Boolean {
    val effectiveCode = SubscriptionPlanCode.entries.firstOrNull {
        it.name.equals(effective.planCode, ignoreCase = true)
    } ?: return false
    val serverFeatures = effective.features.mapNotNull { (name, enabled) ->
        SubscriptionFeature.fromString(name)?.let { feature ->
            PlanFeatureEntity(
                planId = 0L,
                feature = feature.name,
                enabled = enabled,
                limitValue = null,
                createdAt = now,
            )
        }
    }
    val serverLimits = effective.limits.mapNotNull { (name, value) ->
        SubscriptionLimit.fromString(name)?.let { limit ->
            PlanLimitEntity(planId = 0L, limitType = limit.name, maxValue = value)
        }
    }
    return database.withTransaction {
        val plan = planDao.getPlanByCode(effectiveCode.name) ?: return@withTransaction false
        if (stationIds.isEmpty()) return@withTransaction false
        planDao.deleteFeaturesForPlan(plan.id)
        planDao.deleteLimitsForPlan(plan.id)
        planDao.insertFeatures(serverFeatures.map { it.copy(planId = plan.id) })
        if (serverLimits.isNotEmpty()) {
            planDao.insertLimits(serverLimits.map { it.copy(planId = plan.id) })
        }
        stationIds.forEach { stationId ->
            val existing = subscriptionDao.getCurrentSubscription(stationId)
            subscriptionDao.insertSubscription(
                projectEffectiveSubscription(stationId, plan.id, existing, remote, effective, updatedAt)
            )
        }
        true
    }
}

internal suspend fun localStationIdsForRemoteIds(
    stationDao: StationDao,
    syncDao: SyncDao,
    remoteIds: Set<String>,
): Set<Long> {
    if (remoteIds.isEmpty()) return emptySet()
    return stationDao.getAllStations().mapNotNull { station ->
        val remoteId = syncDao.getMetadata(SyncResourceType.STATION.name, station.id)?.remoteId
        station.id.takeIf { remoteId != null && remoteId in remoteIds }
    }.toSet()
}

/** Projects server-effective access, which can be Free while the billing row remains paid/expired. */
internal fun projectEffectiveSubscription(
    stationId: Long,
    planId: Long,
    existing: SubscriptionEntity?,
    remote: SupabaseSubscriptionRow,
    effective: EffectiveEntitlementsResponse,
    updatedAt: Long,
): SubscriptionEntity {
    val isFree = effective.planCode.equals(SubscriptionPlanCode.FREE.name, ignoreCase = true)
    val periodStart = if (isFree) updatedAt else remote.currentPeriodStart ?: updatedAt
    return SubscriptionEntity(
        id = existing?.id ?: 0L,
        stationId = stationId,
        planId = planId,
        status = if (isFree) SubscriptionStatus.FREE.name else SubscriptionStatus.fromString(effective.status).name,
        billingCycle = remote.billingCycle,
        startedAt = if (isFree) updatedAt else remote.startedAt ?: updatedAt,
        currentPeriodStart = periodStart,
        currentPeriodEnd = if (isFree) updatedAt else remote.currentPeriodEnd ?: updatedAt,
        trialStartedAt = if (isFree) null else remote.trialStartedAt,
        trialEndsAt = if (isFree) null else remote.trialEndsAt,
        cancelledAt = if (isFree) null else remote.cancelledAt,
        expiresAt = if (isFree) null else remote.expiresAt,
        gracePeriodEndsAt = if (isFree) null else remote.gracePeriodEndsAt,
        autoRenew = !isFree && remote.autoRenew,
        cancelAtPeriodEnd = !isFree && remote.cancelAtPeriodEnd,
        pendingPlanId = null,
        pendingPlanEffectiveAt = null,
        provider = if (isFree) null else remote.provider,
        externalSubscriptionId = if (isFree) null else remote.externalSubscriptionId,
        externalCustomerId = null,
        lastPaymentId = null,
        currency = remote.currency,
        createdAt = existing?.createdAt
            ?: remote.createdAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: updatedAt,
        updatedAt = updatedAt,
    )
}

/** Mirrors the server's time/status validity rules only to lower stale offline access. */
internal fun hasCurrentPaidAccess(subscription: SubscriptionEntity, now: Long): Boolean {
    if (subscription.expiresAt?.let { it <= now } == true) return false
    return when (SubscriptionStatus.fromString(subscription.status)) {
        SubscriptionStatus.TRIALING -> (subscription.trialEndsAt ?: subscription.currentPeriodEnd) > now
        SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE_UNTIL_PERIOD_END -> subscription.currentPeriodEnd > now
        SubscriptionStatus.GRACE_PERIOD -> subscription.gracePeriodEndsAt?.let { it > now } == true
        else -> false
    }
}

internal fun projectOfflineFreeSubscription(
    previous: SubscriptionEntity,
    freePlanId: Long,
    now: Long,
): SubscriptionEntity = previous.copy(
    planId = freePlanId,
    status = SubscriptionStatus.FREE.name,
    startedAt = now,
    currentPeriodStart = now,
    currentPeriodEnd = now,
    trialStartedAt = null,
    trialEndsAt = null,
    cancelledAt = null,
    expiresAt = null,
    gracePeriodEndsAt = null,
    autoRenew = false,
    cancelAtPeriodEnd = false,
    pendingPlanId = null,
    pendingPlanEffectiveAt = null,
    provider = null,
    externalSubscriptionId = null,
    externalCustomerId = null,
    lastPaymentId = null,
    updatedAt = now,
)
