package com.aesprt.aquahub.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.aesprt.aquahub.data.local.entity.PlanFeatureEntity
import com.aesprt.aquahub.data.local.entity.PlanLimitEntity
import com.aesprt.aquahub.data.local.entity.SubscriptionEntity
import com.aesprt.aquahub.data.local.entity.SubscriptionHistoryEntity
import com.aesprt.aquahub.data.local.entity.SubscriptionPlanEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionPlanDao {

    @Query("SELECT * FROM subscription_plans WHERE isActive = 1 ORDER BY sortOrder ASC")
    fun observePlans(): Flow<List<SubscriptionPlanEntity>>

    @Query("SELECT * FROM subscription_plans WHERE isActive = 1 ORDER BY sortOrder ASC")
    suspend fun getPlans(): List<SubscriptionPlanEntity>

    @Query("SELECT * FROM subscription_plans WHERE id = :planId LIMIT 1")
    suspend fun getPlanById(planId: Long): SubscriptionPlanEntity?

    @Query("SELECT * FROM subscription_plans WHERE code = :code LIMIT 1")
    suspend fun getPlanByCode(code: String): SubscriptionPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlan(plan: SubscriptionPlanEntity): Long

    @Update
    suspend fun updatePlan(plan: SubscriptionPlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlans(plans: List<SubscriptionPlanEntity>): List<Long>

    @Query("SELECT * FROM plan_features WHERE planId = :planId")
    suspend fun getFeaturesForPlan(planId: Long): List<PlanFeatureEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeatures(features: List<PlanFeatureEntity>)

    @Query("DELETE FROM plan_features WHERE planId = :planId")
    suspend fun deleteFeaturesForPlan(planId: Long)

    @Query("SELECT * FROM plan_limits WHERE planId = :planId")
    suspend fun getLimitsForPlan(planId: Long): List<PlanLimitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLimits(limits: List<PlanLimitEntity>)

    @Query("DELETE FROM plan_limits WHERE planId = :planId")
    suspend fun deleteLimitsForPlan(planId: Long)
}

@Dao
interface SubscriptionDao {

    @Query("""
        SELECT * FROM subscriptions
        WHERE stationId = :stationId
        ORDER BY createdAt DESC
        LIMIT 1
    """)
    fun observeCurrentSubscription(stationId: Long): Flow<SubscriptionEntity?>

    @Query("""
        SELECT * FROM subscriptions
        WHERE stationId = :stationId
        ORDER BY createdAt DESC
        LIMIT 1
    """)
    suspend fun getCurrentSubscription(stationId: Long): SubscriptionEntity?

    /**
     * Subscriptions are business-scoped in Supabase and replicated locally per station.
     * Use the newest replica when a screen needs the account-wide subscription.
     */
    @Query("""
        SELECT * FROM subscriptions
        ORDER BY updatedAt DESC, createdAt DESC
        LIMIT 1
    """)
    suspend fun getCurrentBusinessSubscription(): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE id = :id LIMIT 1")
    suspend fun getSubscriptionById(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE stationId = :stationId")
    suspend fun getSubscriptionsForStation(stationId: Long): List<SubscriptionEntity>

    @Query("DELETE FROM subscriptions WHERE id = :subscriptionId")
    suspend fun deleteSubscription(subscriptionId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(subscription: SubscriptionEntity)

    @Query("UPDATE subscriptions SET status = :status, updatedAt = :updatedAt WHERE id = :subscriptionId")
    suspend fun updateSubscriptionStatus(subscriptionId: Long, status: String, updatedAt: Long)

    /** Removes all subscription rows for a station. Called before inserting a fresh trial so
     *  stale rows from previous sessions or the demo seeder never shadow the new record. */
    @Query("DELETE FROM subscriptions WHERE stationId = :stationId")
    suspend fun deleteAllForStation(stationId: Long)
}

@Dao
interface SubscriptionHistoryDao {

    @Query("""
        SELECT * FROM subscription_history
        WHERE stationId = :stationId
        ORDER BY effectiveAt DESC, createdAt DESC
    """)
    suspend fun getHistoryForStation(stationId: Long): List<SubscriptionHistoryEntity>

    /** Removes the legacy trial row that could be created for a Free plan. */
    @Query("""
        DELETE FROM subscription_history
        WHERE stationId = :stationId
          AND eventType = 'TRIAL_STARTED'
          AND newPlanId IN (
              SELECT id FROM subscription_plans WHERE code = 'FREE'
          )
    """)
    suspend fun deleteFreePlanTrialHistory(stationId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryEntry(entry: SubscriptionHistoryEntity): Long
}
