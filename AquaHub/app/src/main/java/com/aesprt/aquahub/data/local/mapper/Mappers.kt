package com.aesprt.aquahub.data.local.mapper

import com.aesprt.aquahub.data.local.dao.CustomerWithAddresses
import com.aesprt.aquahub.data.local.dao.OrderWithItems
import com.aesprt.aquahub.data.local.entity.*
import com.aesprt.aquahub.domain.model.*
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun String.toPaymentMethodFromStorage(): PaymentMethod {
    val storedValue = trim().uppercase()
    return when (storedValue) {
        "COD" -> PaymentMethod.CASH_ON_DELIVERY
        else -> PaymentMethod.valueOf(storedValue)
    }
}

// Station
fun StationEntity.toDomain() = Station(
    id, name, description, phone, email, address, barangay, city, province,
    latitude, longitude, logoPath, openingTime, closingTime, isOpen, manualOpenOverride, createdAt, updatedAt, placeId,
    isAcceptingOnlineOrders, deliveryRadiusKm, deliveryFeeCentavos, estimatedPreparationMinutes,
    businessRulesText, businessRulesVersion, publicStationCode,
    cashPaymentEnabled, codPaymentEnabled, gcashPaymentEnabled, gcashAccountName, gcashAccountNumber, gcashQrImagePath,
    mayaPaymentEnabled, mayaAccountName, mayaAccountNumber, mayaQrImagePath, isActive
)

fun Station.toEntity() = StationEntity(
    id, name, description, phone, email, address, barangay, city, province,
    latitude, longitude, logoPath, openingTime, closingTime, isOpen, manualOpenOverride, createdAt, updatedAt, placeId,
    isAcceptingOnlineOrders, deliveryRadiusKm, deliveryFeeCentavos, estimatedPreparationMinutes,
    businessRulesText, businessRulesVersion, publicStationCode,
    cashPaymentEnabled, codPaymentEnabled, gcashPaymentEnabled, gcashAccountName, gcashAccountNumber, gcashQrImagePath,
    mayaPaymentEnabled, mayaAccountName, mayaAccountNumber, mayaQrImagePath, isActive
)

// Owner
fun OwnerEntity.toDomain() = Owner(
    id = id,
    stationId = stationId,
    name = name,
    phone = phone,
    email = email,
    createdAt = createdAt,
    updatedAt = updatedAt,
    firebaseUid = firebaseUid,
    businessId = businessId,
    role = OwnerRole.requireFromString(role)
)

// Customer
fun CustomerWithAddresses.toDomain() = Customer(
    id = customer.id,
    stationId = customer.stationId,
    name = customer.name,
    phone = customer.phone,
    email = customer.email,
    notes = customer.notes,
    totalOrders = customer.totalOrders,
    totalSpent = customer.totalSpent,
    addresses = addresses.map { it.toDomain() },
    createdAt = customer.createdAt,
    updatedAt = customer.updatedAt
)

fun Customer.toEntity() = CustomerEntity(
    id, stationId, name, phone, email, notes, totalOrders, totalSpent, createdAt, updatedAt
)

fun CustomerAddressEntity.toDomain() = CustomerAddress(
    id, customerId, label, addressLine, barangay, city, province, latitude, longitude, isDefault
)

fun CustomerAddress.toEntity() = CustomerAddressEntity(
    id, customerId, label, addressLine, barangay, city, province, latitude, longitude, isDefault
)

// Product
fun ProductEntity.toDomain() = Product(
    id = id,
    stationId = stationId,
    name = name,
    type = ProductType.valueOf(type),
    sizeLabel = sizeLabel,
    price = price,
    stockQuantity = stockQuantity,
    imagePath = imagePath,
    isAvailable = isAvailable,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isActive = isActive,
    inventoryVersion = inventoryVersion,
    promotion = promotionType?.let { rawType ->
        runCatching {
            ProductPromotion(
                label = promotionLabel.orEmpty(),
                type = PromotionType.valueOf(rawType),
                percentBps = promotionPercentBps,
                promotionalPrice = promotionalPrice,
                minimumQuantity = promotionMinimumQuantity ?: 1,
                startsAt = promotionStartsAt ?: createdAt,
                endsAt = promotionEndsAt,
                isActive = promotionIsActive
            )
        }.getOrNull()
    }
)

fun Product.toEntity() = ProductEntity(
    id = id,
    stationId = stationId,
    name = name,
    type = type.name,
    sizeLabel = sizeLabel,
    price = price,
    stockQuantity = stockQuantity,
    imagePath = imagePath,
    isAvailable = isAvailable,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isActive = isActive,
    promotionLabel = promotion?.label,
    promotionType = promotion?.type?.name,
    promotionPercentBps = promotion?.percentBps,
    promotionalPrice = promotion?.promotionalPrice,
    promotionMinimumQuantity = promotion?.minimumQuantity,
    promotionStartsAt = promotion?.startsAt,
    promotionEndsAt = promotion?.endsAt,
    promotionIsActive = promotion?.isActive == true,
    inventoryVersion = inventoryVersion
)

// Rider
fun RiderEntity.toDomain() = Rider(
    id, stationId, name, phone, vehicleType, vehicleName, plateNumber, rating,
    isActive, isOnline, isAvailable, latitude, longitude, activeOrderCount, createdAt, updatedAt
)

fun Rider.toEntity() = RiderEntity(
    id, stationId, name, phone, vehicleType, vehicleName, plateNumber, rating,
    isActive, isOnline, isAvailable, latitude, longitude, activeOrderCount, createdAt, updatedAt
)

// Order
fun OrderWithItems.toDomain() = Order(
    id = order.id,
    orderNumber = order.orderNumber,
    stationId = order.stationId,
    customerId = order.customerId,
    customerName = order.customerName,
    customerPhone = order.customerPhone,
    riderId = order.riderId,
    riderName = order.riderName,
    deliveryAddressId = order.deliveryAddressId,
    deliveryAddress = order.deliveryAddress,
    deliveryLatitude = order.deliveryLatitude,
    deliveryLongitude = order.deliveryLongitude,
    deliveryMode = order.deliveryMode,
    items = items.map { it.toDomain() },
    subtotal = order.subtotal,
    deliveryFee = order.deliveryFee,
    discount = order.discount,
    total = order.total,
    paymentMethod = order.paymentMethod.toPaymentMethodFromStorage(),
    paymentStatus = PaymentStatus.valueOf(order.paymentStatus),
    orderStatus = OrderStatus.valueOf(order.orderStatus),
    scheduledDeliveryStart = order.scheduledDeliveryStart,
    scheduledDeliveryEnd = order.scheduledDeliveryEnd,
    customerNote = order.customerNote,
    stationNote = order.stationNote,
    createdAt = order.createdAt,
    acceptedAt = order.acceptedAt,
    readyAt = order.readyAt,
    assignedAt = order.assignedAt,
    outForDeliveryAt = order.outForDeliveryAt,
    deliveredAt = order.deliveredAt,
    updatedAt = order.updatedAt,
    completedAt = order.completedAt,
    cancelledAt = order.cancelledAt,
    cancellationReason = order.cancellationReason
)

fun Order.toEntity() = OrderEntity(
    id = id,
    orderNumber = orderNumber,
    stationId = stationId,
    customerId = customerId,
    customerName = customerName,
    customerPhone = customerPhone,
    riderId = riderId,
    riderName = riderName,
    deliveryAddressId = deliveryAddressId,
    deliveryAddress = deliveryAddress,
    deliveryLatitude = deliveryLatitude,
    deliveryLongitude = deliveryLongitude,
    deliveryMode = deliveryMode,
    subtotal = subtotal,
    deliveryFee = deliveryFee,
    discount = discount,
    total = total,
    paymentMethod = paymentMethod.name,
    paymentStatus = paymentStatus.name,
    orderStatus = orderStatus.name,
    scheduledDeliveryStart = scheduledDeliveryStart,
    scheduledDeliveryEnd = scheduledDeliveryEnd,
    customerNote = customerNote,
    stationNote = stationNote,
    createdAt = createdAt,
    acceptedAt = acceptedAt,
    readyAt = readyAt,
    assignedAt = assignedAt,
    outForDeliveryAt = outForDeliveryAt,
    deliveredAt = deliveredAt,
    updatedAt = updatedAt,
    completedAt = completedAt,
    cancelledAt = cancelledAt,
    cancellationReason = cancellationReason
)

fun OrderItemEntity.toDomain() = OrderItem(
    id,
    orderId,
    productId,
    productName,
    ProductType.valueOf(productType),
    sizeLabel,
    unitPrice,
    quantity,
    subtotal,
    containerSource
)

fun OrderItem.toEntity(orderIdOverride: Long = orderId) = OrderItemEntity(
    id,
    orderIdOverride,
    productId,
    productName,
    productType.name,
    sizeLabel,
    unitPrice,
    quantity,
    subtotal,
    containerSource
)

// Container
fun ContainerEntity.toDomain() = Container(
    id,
    stationId,
    sizeLabel,
    totalOwned,
    available,
    withCustomers,
    damaged,
    lost,
    depositPrice,
    createdAt,
    updatedAt
)

fun Container.toEntity() = ContainerEntity(
    id,
    stationId,
    sizeLabel,
    totalOwned,
    available,
    withCustomers,
    damaged,
    lost,
    depositPrice,
    createdAt,
    updatedAt
)

// ContainerTransaction
fun ContainerTransactionEntity.toDomain() = ContainerTransaction(
    id, stationId, customerId, customerName, orderId,
    ContainerTransactionType.valueOf(type), quantity, containerSize, notes, createdAt
)

fun ContainerTransaction.toEntity() = ContainerTransactionEntity(
    id,
    stationId,
    customerId,
    customerName,
    orderId,
    type.name,
    quantity,
    containerSize,
    notes,
    createdAt
)

fun PaymentEntity.toDomain() = Payment(
    id = id,
    orderId = orderId,
    paymentMethod = paymentMethod.toPaymentMethodFromStorage(),
    amount = amount,
    status = PaymentStatus.valueOf(status),
    referenceNumber = referenceNumber,
    createdAt = createdAt,
    paidAt = paidAt,
    deliveryPaymentProofObjectPath = metadataJson?.let { raw ->
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw)
                .jsonObject["delivery_payment_proof_object_path"]
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }
)

fun PaymentRefundEntity.toDomain() = PaymentRefund(
    id = remoteId,
    orderId = orderId,
    originalPaymentId = originalPaymentId,
    operationId = operationId,
    amountCentavos = amountCentavos,
    paymentMethod = paymentMethod.toPaymentMethodFromStorage(),
    status = RefundStatus.valueOf(status),
    reason = reason,
    externalReference = externalReference,
    failureReason = failureReason,
    createdAt = createdAt,
    completedAt = completedAt
)
