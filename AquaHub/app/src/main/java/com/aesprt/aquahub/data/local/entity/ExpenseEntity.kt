package com.aesprt.aquahub.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "expenses",
    indices = [Index("stationId"), Index("incurredAt"), Index(value = ["stationId", "incurredAt"])]
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val amountCentavos: Long,
    val category: String,
    val description: String,
    val vendor: String?,
    val paymentMethod: String,
    val referenceNumber: String?,
    val notes: String?,
    val incurredAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean = true,
    val remoteId: String? = null,
    val firebaseId: String? = null,
    val organizationRemoteId: String? = null,
    val createdByRemoteId: String? = null,
    val receiptPath: String? = null,
    val operationId: String? = null
)
