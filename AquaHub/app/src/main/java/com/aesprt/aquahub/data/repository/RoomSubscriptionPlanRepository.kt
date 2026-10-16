package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.local.dao.SubscriptionPlanDao
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.domain.model.FeatureEntitlement
import com.aesprt.aquahub.domain.model.PlanLimit
import com.aesprt.aquahub.domain.model.SubscriptionPlan
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import com.aesprt.aquahub.domain.repository.SubscriptionPlanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSubscriptionPlanRepository(
    private val planDao: SubscriptionPlanDao
) : SubscriptionPlanRepository {

    override fun observePlans(): Flow<List<SubscriptionPlan>> {
        return planDao.observePlans().map { entities ->
            entities.map { entity ->
                val features = planDao.getFeaturesForPlan(entity.id).mapNotNull { it.toDomain() }
                val limits = planDao.getLimitsForPlan(entity.id).mapNotNull { it.toDomain() }
                entity.toDomain(features, limits)
            }
        }
    }

    override suspend fun getPlans(): List<SubscriptionPlan> {
        val entities = planDao.getPlans()
        return entities.map { entity ->
            val features = planDao.getFeaturesForPlan(entity.id).mapNotNull { it.toDomain() }
            val limits = planDao.getLimitsForPlan(entity.id).mapNotNull { it.toDomain() }
            entity.toDomain(features, limits)
        }
    }

    override suspend fun getPlan(planId: Long): SubscriptionPlan? {
        val entity = planDao.getPlanById(planId) ?: return null
        val features = planDao.getFeaturesForPlan(entity.id).mapNotNull { it.toDomain() }
        val limits = planDao.getLimitsForPlan(entity.id).mapNotNull { it.toDomain() }
        return entity.toDomain(features, limits)
    }

    override suspend fun getPlanByCode(code: SubscriptionPlanCode): SubscriptionPlan? {
        val entity = planDao.getPlanByCode(code.name) ?: return null
        val features = planDao.getFeaturesForPlan(entity.id).mapNotNull { it.toDomain() }
        val limits = planDao.getLimitsForPlan(entity.id).mapNotNull { it.toDomain() }
        return entity.toDomain(features, limits)
    }

    override suspend fun getPlanFeatures(planId: Long): List<FeatureEntitlement> {
        return planDao.getFeaturesForPlan(planId).mapNotNull { it.toDomain() }
    }

    override suspend fun getPlanLimits(planId: Long): List<PlanLimit> {
        return planDao.getLimitsForPlan(planId).mapNotNull { it.toDomain() }
    }
}
