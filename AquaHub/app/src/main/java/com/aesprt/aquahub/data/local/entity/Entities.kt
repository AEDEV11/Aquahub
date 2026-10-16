package com.aesprt.aquahub.data.local.entity

import androidx.room.*
import com.aesprt.aquahub.domain.model.*

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String?,
    val phone: String,
    val email: String?,
    val address: String,
    val barangay: String?,
    val city: String?,
    val province: String?,
    val latitude: Double?,
    val longitude: Double?,
    val logoPath: String?,
    val openingTime: String?,
    val closingTime: String?,
    val isOpen: Boolean,
    val manualOpenOverride: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val placeId: String? = null,
    val isAcceptingOnlineOrders: Boolean = true,
    val deliveryRadiusKm: Double? = null,
    val deliveryFeeCentavos: Long = 0L,
    val estimatedPreparationMinutes: Int = 30,
    val businessRulesText: String? = null,
    val businessRulesVersion: Long = 0L,
    val publicStationCode: String? = null,
    val cashPaymentEnabled: Boolean = true,
    val codPaymentEnabled: Boolean = true,
    val gcashPaymentEnabled: Boolean = false,
    val gcashAccountName: String? = null,
    val gcashAccountNumber: String? = null,
    val gcashQrImagePath: String? = null,
    val mayaPaymentEnabled: Boolean = false,
    val mayaAccountName: String? = null,
    val mayaAccountNumber: String? = null,
    val mayaQrImagePath: String? = null,
    val isActive: Boolean = true
)

@Entity(tableName = "station_owners")
data class OwnerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val name: String,
    val phone: String,
    val email: String?,
    val password: String,
    val createdAt: Long,
    val updatedAt: Long,
    val firebaseUid: String? = null,
    val businessId: String? = null,
    val role: String = "OWNER"
)

@Entity(
    tableName = "customers",
    indices = [Index("stationId"), Index("phone")]
)
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val name: String,
    val phone: String,
    val email: String?,
    val notes: String?,
    val totalOrders: Int,
    val totalSpent: Double,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "customer_addresses",
    foreignKeys = [ForeignKey(
        entity = CustomerEntity::class,
        parentColumns = ["id"],
        childColumns = ["customerId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("customerId")]
)
data class CustomerAddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val customerId: Long,
    val label: String,
    val addressLine: String,
    val barangay: String?,
    val city: String?,
    val province: String?,
    val latitude: Double?,
    val longitude: Double?,
    val isDefault: Boolean
)

@Entity(tableName = "products", indices = [Index("stationId")])
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val name: String,
    val type: String, // ProductType enum
    val sizeLabel: String,
    val price: Double,
    val stockQuantity: Int,
    val imagePath: String?,
    val isAvailable: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean = true,
    val promotionLabel: String? = null,
    val promotionType: String? = null,
    val promotionPercentBps: Int? = null,
    val promotionalPrice: Double? = null,
    val promotionMinimumQuantity: Int? = null,
    val promotionStartsAt: Long? = null,
    val promotionEndsAt: Long? = null,
    val promotionIsActive: Boolean = false,
    @androidx.room.ColumnInfo(defaultValue = "0") val inventoryVersion: Long = 0L
)

@Entity(tableName = "riders", indices = [Index("stationId")])
data class RiderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val name: String,
    val phone: String,
    val vehicleType: String?,
    val vehicleName: String?,
    val plateNumber: String?,
    val rating: Double,
    val isActive: Boolean,
    val isOnline: Boolean,
    val isAvailable: Boolean,
    val latitude: Double?,
    val longitude: Double?,
    val activeOrderCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "orders",
    indices = [Index("stationId"), Index("customerId"), Index("orderStatus"), Index("createdAt")]
)
data class OrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderNumber: String,
    val stationId: Long,
    val customerId: Long,
    val customerName: String,
    val customerPhone: String,
    val riderId: Long?,
    val riderName: String?,
    val deliveryAddressId: Long?,
    val deliveryAddress: String,
    val deliveryLatitude: Double? = null,
    val deliveryLongitude: Double? = null,
    val deliveryMode: String = "DELIVERY",
    val subtotal: Double,
    val deliveryFee: Double,
    val discount: Double,
    val total: Double,
    val paymentMethod: String,  // PaymentMethod enum
    val paymentStatus: String,  // PaymentStatus enum
    val orderStatus: String,    // OrderStatus enum
    val scheduledDeliveryStart: Long?,
    val scheduledDeliveryEnd: Long?,
    val customerNote: String?,
    val stationNote: String?,
    val createdAt: Long,
    val acceptedAt: Long?,
    val readyAt: Long?,
    val assignedAt: Long?,
    val outForDeliveryAt: Long?,
    val deliveredAt: Long?,
    val updatedAt: Long?,
    val completedAt: Long?,
    val cancelledAt: Long?,
    val cancellationReason: String? = null
)

@Entity(
    tableName = "order_items",
    foreignKeys = [ForeignKey(
        entity = OrderEntity::class,
        parentColumns = ["id"],
        childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("orderId")]
)
data class OrderItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val productId: Long?,
    val productName: String,
    val productType: String,
    val sizeLabel: String,
    val unitPrice: Double,
    val quantity: Int,
    val subtotal: Double,
    val containerSource: String? = null
)

@Entity(tableName = "containers", indices = [Index("stationId")])
data class ContainerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val sizeLabel: String,
    val totalOwned: Int,
    val available: Int,
    val withCustomers: Int,
    val damaged: Int,
    val lost: Int,
    val depositPrice: Double,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "container_transactions", indices = [Index("stationId"), Index("customerId"), Index("orderId")])
data class ContainerTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val customerId: Long?,
    val customerName: String?,
    val orderId: Long?,
    val type: String, // ContainerTransactionType enum
    val quantity: Int,
    val containerSize: String,
    val notes: String?,
    val createdAt: Long
)

@Entity(
    tableName = "payments",
    foreignKeys = [ForeignKey(
        entity = OrderEntity::class,
        parentColumns = ["id"],
        childColumns = ["orderId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("orderId")]
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderId: Long,
    val paymentMethod: String,
    val amount: Double,
    val status: String,
    val referenceNumber: String?,
    val createdAt: Long,
    val paidAt: Long?,
    /** Supabase UUID. The local Long remains the Room cache key for compatibility. */
    val remoteId: String? = null,
    val firebaseId: String? = null,
    val provider: String? = null,
    val providerTransactionId: String? = null,
    val metadataJson: String? = null,
    val operationId: String? = null
)

@Entity(
    tableName = "payment_refunds",
    indices = [Index(value = ["remoteId"], unique = true), Index(value = ["operationId"], unique = true), Index("orderId"), Index("status")]
)
data class PaymentRefundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val remoteId: String? = null,
    val orderId: Long,
    val originalPaymentId: Long?,
    val operationId: String,
    val completionOperationId: String? = null,
    val failureOperationId: String? = null,
    val amountCentavos: Long,
    val paymentMethod: String,
    val status: String,
    val reason: String,
    val externalReference: String? = null,
    val failureReason: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
    val updatedAt: Long
)
