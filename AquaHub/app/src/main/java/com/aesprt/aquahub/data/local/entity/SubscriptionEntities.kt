package com.aesprt.aquahub.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subscription_plans",
    indices = [Index(value = ["code"], unique = true)]
)
data class SubscriptionPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String, // STARTER, BUSINESS, PRO
    val name: String,
    val description: String?,
    val monthlyPrice: Double,
    val annualPrice: Double,
    val isPopular: Boolean,
    val trialDays: Int,
    val ctaText: String,
    val isActive: Boolean,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "subscriptions",
    foreignKeys = [
        ForeignKey(
            entity = StationEntity::class,
            parentColumns = ["id"],
            childColumns = ["stationId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SubscriptionPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("stationId"),
        Index("planId"),
        Index("status")
    ]
)
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val planId: Long,
    val status: String, // SubscriptionStatus enum
    val billingCycle: String, // BillingCycle enum
    val startedAt: Long,
    val currentPeriodStart: Long,
    val currentPeriodEnd: Long,
    val trialStartedAt: Long?,
    val trialEndsAt: Long?,
    val cancelledAt: Long?,
    val expiresAt: Long?,
    val gracePeriodEndsAt: Long?,
    val autoRenew: Boolean,
    val cancelAtPeriodEnd: Boolean,
    val pendingPlanId: Long?,
    val pendingPlanEffectiveAt: Long?,
    val provider: String?,
    val externalSubscriptionId: String?,
    val externalCustomerId: String?,
    val lastPaymentId: String?,
    val currency: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "plan_features",
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("planId"),
        Index(value = ["planId", "feature"], unique = true)
    ]
)
data class PlanFeatureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val feature: String, // SubscriptionFeature enum
    val enabled: Boolean,
    val limitValue: Long?,
    val createdAt: Long
)

@Entity(
    tableName = "plan_limits",
    foreignKeys = [
        ForeignKey(
            entity = SubscriptionPlanEntity::class,
            parentColumns = ["id"],
            childColumns = ["planId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("planId"),
        Index(value = ["planId", "limitType"], unique = true)
    ]
)
data class PlanLimitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val limitType: String, // SubscriptionLimit enum
    val maxValue: Long
)

@Entity(
    tableName = "subscription_history",
    foreignKeys = [
        ForeignKey(
            entity = StationEntity::class,
            parentColumns = ["id"],
            childColumns = ["stationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("stationId"),
        Index("subscriptionId")
    ]
)
data class SubscriptionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val subscriptionId: Long,
    val previousPlanId: Long?,
    val newPlanId: Long?,
    val eventType: String, // SubscriptionEventType enum
    val effectiveAt: Long,
    val previousStatus: String?,
    val newStatus: String?,
    val notes: String?,
    val createdAt: Long
)
