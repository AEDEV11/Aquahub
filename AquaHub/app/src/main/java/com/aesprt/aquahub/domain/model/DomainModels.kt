package com.aesprt.aquahub.domain.model

data class Station(
    val id: Long = 0,
    val name: String = "",
    val description: String? = null,
    val phone: String = "",
    val email: String? = null,
    val address: String = "",
    val barangay: String? = null,
    val city: String? = null,
    val province: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val logoPath: String? = null,
    val openingTime: String? = null,
    val closingTime: String? = null,
    val isOpen: Boolean = true,
    val manualOpenOverride: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
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

enum class OwnerRole {
    OWNER,
    MANAGER,
    STAFF,
    RIDER;

    companion object {
        fun fromString(value: String?): OwnerRole = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OWNER

        fun requireFromString(value: String?): OwnerRole =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: error("AquaHub membership has an invalid role")
    }
}

data class Owner(
    val id: Long = 0,
    val stationId: Long = 0,
    val name: String = "",
    val phone: String = "",
    val email: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val firebaseUid: String? = null,
    val businessId: String? = null,
    val role: OwnerRole = OwnerRole.OWNER
)

data class Customer(
    val id: Long = 0,
    val stationId: Long = 0,
    val name: String = "",
    val phone: String = "",
    val email: String? = null,
    val notes: String? = null,
    val totalOrders: Int = 0,
    val totalSpent: Double = 0.0,
    val addresses: List<CustomerAddress> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class CustomerAddress(
    val id: Long = 0,
    val customerId: Long = 0,
    val label: String = "",
    val addressLine: String = "",
    val barangay: String? = null,
    val city: String? = null,
    val province: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isDefault: Boolean = false
)

data class Product(
    val id: Long = 0,
    val stationId: Long = 0,
    val name: String = "",
    val type: ProductType = ProductType.REFILL,
    val sizeLabel: String = "",
    val price: Double = 0.0,
    val stockQuantity: Int = 0,
    val imagePath: String? = null,
    val isAvailable: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    val promotion: ProductPromotion? = null,
    val inventoryVersion: Long = 0L
)

enum class PromotionType {
    PERCENTAGE,
    FIXED_PRICE,
    QUANTITY_BREAK
}

data class ProductPromotion(
    val label: String,
    val type: PromotionType,
    /** Percentage discount in basis points: 1% = 100, 100% = 10_000. */
    val percentBps: Int? = null,
    val promotionalPrice: Double? = null,
    val minimumQuantity: Int = 1,
    val startsAt: Long = System.currentTimeMillis(),
    val endsAt: Long? = null,
    val isActive: Boolean = true
) {
    fun appliesAt(now: Long = System.currentTimeMillis()): Boolean =
        isActive && startsAt <= now && (endsAt == null || endsAt > now)
}

fun Product.unitPriceFor(quantity: Int, promotionsAllowed: Boolean = true, now: Long = System.currentTimeMillis()): Double {
    val offer = promotion?.takeIf { promotionsAllowed && it.appliesAt(now) && quantity >= it.minimumQuantity }
        ?: return price
    val regularCentavos = kotlin.math.round(price * 100.0).toLong()
    val discountedCentavos = when (offer.type) {
        PromotionType.FIXED_PRICE -> kotlin.math.round((offer.promotionalPrice ?: price) * 100.0).toLong()
        PromotionType.PERCENTAGE, PromotionType.QUANTITY_BREAK -> {
            val bps = offer.percentBps ?: return price
            (regularCentavos * (10_000L - bps) + 5_000L) / 10_000L
        }
    }
    return discountedCentavos.coerceIn(0L, regularCentavos).toDouble() / 100.0
}

data class Rider(
    val id: Long = 0,
    val stationId: Long = 0,
    val name: String = "",
    val phone: String = "",
    val vehicleType: String? = null,
    val vehicleName: String? = null,
    val plateNumber: String? = null,
    val rating: Double = 5.0,
    val isActive: Boolean = true,
    val isOnline: Boolean = false,
    val isAvailable: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val activeOrderCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class Order(
    val id: Long = 0,
    val orderNumber: String = "",
    val stationId: Long = 0,
    val customerId: Long = 0,
    val customerName: String = "",
    val customerPhone: String = "",
    val riderId: Long? = null,
    val riderName: String? = null,
    val deliveryAddressId: Long? = null,
    val deliveryAddress: String = "",
    val deliveryLatitude: Double? = null,
    val deliveryLongitude: Double? = null,
    val deliveryMode: String = "DELIVERY",
    val items: List<OrderItem> = emptyList(),
    val subtotal: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val discount: Double = 0.0,
    val total: Double = 0.0,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH_ON_DELIVERY,
    val paymentStatus: PaymentStatus = PaymentStatus.PENDING,
    val orderStatus: OrderStatus = OrderStatus.PENDING,
    val scheduledDeliveryStart: Long? = null,
    val scheduledDeliveryEnd: Long? = null,
    val customerNote: String? = null,
    val stationNote: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val acceptedAt: Long? = null,
    val readyAt: Long? = null,
    val assignedAt: Long? = null,
    val outForDeliveryAt: Long? = null,
    val deliveredAt: Long? = null,
    val updatedAt: Long? = null,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
    val cancellationReason: String? = null
)

data class OrderItem(
    val id: Long = 0,
    val orderId: Long = 0,
    val productId: Long? = null,
    val productName: String = "",
    val productType: ProductType = ProductType.REFILL,
    val sizeLabel: String = "",
    val unitPrice: Double = 0.0,
    val quantity: Int = 1,
    val subtotal: Double = 0.0,
    val containerSource: String? = null
)

data class Container(
    val id: Long = 0,
    val stationId: Long = 0,
    val sizeLabel: String = "",
    val totalOwned: Int = 0,
    val available: Int = 0,
    val withCustomers: Int = 0,
    val damaged: Int = 0,
    val lost: Int = 0,
    val depositPrice: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ContainerTransaction(
    val id: Long = 0,
    val stationId: Long = 0,
    val customerId: Long? = null,
    val customerName: String? = null,
    val orderId: Long? = null,
    val type: ContainerTransactionType = ContainerTransactionType.DELIVERED,
    val quantity: Int = 0,
    val containerSize: String = "",
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class Payment(
    val id: Long = 0,
    val orderId: Long = 0,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH_ON_DELIVERY,
    val amount: Double = 0.0,
    val status: PaymentStatus = PaymentStatus.PENDING,
    val referenceNumber: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val paidAt: Long? = null,
    val deliveryPaymentProofObjectPath: String? = null
)

data class PaymentRefund(
    val id: String? = null,
    val orderId: Long,
    val originalPaymentId: Long?,
    val operationId: String,
    val amountCentavos: Long,
    val paymentMethod: PaymentMethod,
    val status: RefundStatus,
    val reason: String,
    val externalReference: String? = null,
    val failureReason: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null
)

data class CancelledOrderReturnItem(val productId: String, val quantity: Int)

data class CustomerAccountEntry(
    val order: Order,
    val paidAmount: Double,
    val balance: Double
)

data class CustomerAccountSnapshot(
    val customer: Customer,
    val entries: List<CustomerAccountEntry>,
    val payments: List<Payment>,
    val allOrders: List<Order> = emptyList()
) {
    val totalBilled: Double get() = entries.sumOf { it.order.total }
    val totalPaid: Double get() = entries.sumOf { it.paidAmount }
    val outstandingBalance: Double get() = entries.sumOf { it.balance }
}

data class DashboardSummary(
    val todayOrdersCount: Int = 0,
    val pendingOrdersCount: Int = 0,
    val outForDeliveryCount: Int = 0,
    val completedTodayCount: Int = 0,
    val todaySales: Double = 0.0,
    val yesterdaySales: Double = 0.0,
    val pendingOfflineCash: Double = 0.0,
    val recentOrders: List<Order> = emptyList()
)

data class SalesSummary(
    val period: String = "",
    val totalSales: Double = 0.0,
    val totalOrders: Int = 0,
    val completedOrders: Int = 0,
    val cancelledOrders: Int = 0,
    val averageOrderValue: Double = 0.0,
    val pendingOfflineCash: Double = 0.0
)

data class TopProduct(
    val productName: String = "",
    val sizeLabel: String = "",
    val totalQuantity: Int = 0,
    val totalRevenue: Double = 0.0
)

data class RiderRecommendation(
    val rider: Rider,
    val score: Double,
    val distanceKm: Double?
)

data class CustomerContainerBalance(
    val containerSize: String = "",
    val quantityWithCustomer: Int = 0
)
