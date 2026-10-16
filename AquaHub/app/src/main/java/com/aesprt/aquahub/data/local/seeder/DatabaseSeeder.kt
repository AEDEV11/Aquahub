package com.aesprt.aquahub.data.local.seeder

import com.aesprt.aquahub.data.local.dao.*
import com.aesprt.aquahub.data.local.mapper.toEntity
import com.aesprt.aquahub.data.local.entity.*
import com.aesprt.aquahub.domain.model.*

class DatabaseSeeder(
    private val subscriptionPlanDao: SubscriptionPlanDao
) {
    suspend fun seedPlansIfEmpty() {
        val now = System.currentTimeMillis()
        DEFAULT_SUBSCRIPTION_PLANS.forEach { plan ->
            val existing = subscriptionPlanDao.getPlanByCode(plan.code.name)
            val planId = if (existing == null) {
                subscriptionPlanDao.insertPlan(plan.toEntity().copy(id = plan.id, createdAt = now, updatedAt = now))
            } else {
                subscriptionPlanDao.updatePlan(
                    plan.toEntity().copy(id = existing.id, createdAt = existing.createdAt, updatedAt = now)
                )
                existing.id
            }
            // Replace child rows so upgrades from the old QR/unused-feature catalog
            // receive exactly the active feature and limit contract.
            subscriptionPlanDao.deleteFeaturesForPlan(planId)
            subscriptionPlanDao.deleteLimitsForPlan(planId)
            subscriptionPlanDao.insertFeatures(
                plan.features.map { feature ->
                    PlanFeatureEntity(
                        planId = planId,
                        feature = feature.feature.name,
                        enabled = feature.enabled,
                        limitValue = feature.limit,
                        createdAt = now
                    )
                }
            )
            subscriptionPlanDao.insertLimits(
                plan.limits.map { limit ->
                    PlanLimitEntity(
                        planId = planId,
                        limitType = limit.limitType.name,
                        maxValue = limit.maxValue
                    )
                }
            )
        }
    }


}

