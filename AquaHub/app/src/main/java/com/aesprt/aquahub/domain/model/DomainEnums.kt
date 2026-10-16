package com.aesprt.aquahub.domain.model

enum class OrderStatus {
    PENDING,
    ACCEPTED,
    PREPARING,
    READY_FOR_PICKUP,
    RIDER_ASSIGNED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    COMPLETED,
    REJECTED,
    CANCELLED;

    fun allowedTransitions(): Set<OrderStatus> = when (this) {
        PENDING -> setOf(ACCEPTED, REJECTED, CANCELLED)
        ACCEPTED -> setOf(PREPARING, CANCELLED)
        PREPARING -> setOf(READY_FOR_PICKUP, CANCELLED)
        READY_FOR_PICKUP -> setOf(RIDER_ASSIGNED, COMPLETED)
        RIDER_ASSIGNED -> setOf(OUT_FOR_DELIVERY)
        OUT_FOR_DELIVERY -> setOf(DELIVERED)
        DELIVERED -> setOf(COMPLETED)
        COMPLETED, REJECTED, CANCELLED -> emptySet()
    }

    fun canTransitionTo(newStatus: OrderStatus): Boolean = newStatus in allowedTransitions()

    fun displayName(): String = when (this) {
        PENDING -> "Pending"
        ACCEPTED -> "Accepted"
        PREPARING -> "Preparing"
        READY_FOR_PICKUP -> "Ready for Pickup"
        RIDER_ASSIGNED -> "Rider Assigned"
        OUT_FOR_DELIVERY -> "Out for Delivery"
        DELIVERED -> "Delivered"
        COMPLETED -> "Completed"
        REJECTED -> "Rejected"
        CANCELLED -> "Cancelled"
    }

    fun isTerminal(): Boolean = this in setOf(COMPLETED, REJECTED, CANCELLED)
    fun isActive(): Boolean = !isTerminal()
}

enum class ProductType {
    REFILL,
    NEW_CONTAINER,
    EMPTY_CONTAINER,
    OTHER;

    fun displayName(): String = when (this) {
        REFILL -> "Refill"
        NEW_CONTAINER -> "New Container"
        EMPTY_CONTAINER -> "Empty Container"
        OTHER -> "Other"
    }
}

enum class PaymentMethod {
    CASH_ON_DELIVERY,
    CASH,
    GCASH,
    MAYA,
    CLIENT_ACCOUNT,
    CARD,
    ONLINE_PAYMENT;

    fun displayName(): String = when (this) {
        CASH_ON_DELIVERY -> "Cash on Delivery"
        CASH -> "Cash"
        GCASH -> "GCash"
        MAYA -> "Maya"
        CLIENT_ACCOUNT -> "Client Account"
        CARD -> "Card"
        ONLINE_PAYMENT -> "Online Payment"
    }
}

enum class PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    REFUNDED;

    fun displayName(): String = when (this) {
        PENDING -> "Pending"
        PAID -> "Paid"
        FAILED -> "Failed"
        REFUNDED -> "Refunded"
    }
}

enum class RefundStatus {
    REQUESTING,
    PENDING,
    COMPLETED,
    FAILED;

    fun displayName(): String = when (this) {
        REQUESTING -> "Awaiting server confirmation"
        PENDING -> "Refund pending"
        COMPLETED -> "Refund completed"
        FAILED -> "Refund failed"
    }
}

enum class ContainerTransactionType {
    DELIVERED,
    RETURNED,
    NEW_CONTAINER_SOLD,
    DAMAGED,
    LOST,
    ADJUSTMENT;

    fun displayName(): String = when (this) {
        DELIVERED -> "Delivered"
        RETURNED -> "Returned"
        NEW_CONTAINER_SOLD -> "New Container Sold"
        DAMAGED -> "Damaged"
        LOST -> "Lost"
        ADJUSTMENT -> "Adjustment"
    }
}

enum class OrderFilter(val displayName: String) {
    ALL("All"),
    NEW("New"),
    PREPARING("Preparing"),
    ON_THE_WAY("On The Way"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    fun matchesStatus(status: OrderStatus): Boolean = when (this) {
        ALL -> true
        NEW -> status in setOf(OrderStatus.PENDING, OrderStatus.ACCEPTED)
        PREPARING -> status in setOf(OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP)
        ON_THE_WAY -> status in setOf(OrderStatus.RIDER_ASSIGNED, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED)
        COMPLETED -> status == OrderStatus.COMPLETED
        CANCELLED -> status in setOf(OrderStatus.CANCELLED, OrderStatus.REJECTED)
    }
}
