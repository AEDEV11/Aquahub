package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.FeatureEntitlement
import com.aesprt.aquahub.domain.model.PlanLimit
import com.aesprt.aquahub.domain.model.SubscriptionPlan
import com.aesprt.aquahub.domain.model.SubscriptionPlanCode
import kotlinx.coroutines.flow.Flow

interface SubscriptionPlanRepository {

    fun observePlans(): Flow<List<SubscriptionPlan>>

    suspend fun getPlans(): List<SubscriptionPlan>

    suspend fun getPlan(planId: Long): SubscriptionPlan?

    suspend fun getPlanByCode(code: SubscriptionPlanCode): SubscriptionPlan?

    suspend fun getPlanFeatures(planId: Long): List<FeatureEntitlement>

    suspend fun getPlanLimits(planId: Long): List<PlanLimit>
}
