package com.aesprt.aquahub.data.local.dao

import androidx.room.*
import com.aesprt.aquahub.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {
    @Query("SELECT * FROM stations WHERE id = :stationId")
    fun observeStation(stationId: Long): Flow<StationEntity?>

    @Query("SELECT * FROM stations WHERE id = :stationId")
    suspend fun getStation(stationId: Long): StationEntity?

    @Query("SELECT * FROM stations WHERE isActive = 1 ORDER BY createdAt ASC")
    fun observeAllStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE isActive = 1 ORDER BY createdAt ASC")
    suspend fun getAllStations(): List<StationEntity>

    @Query(
        """
        SELECT * FROM stations
        WHERE isActive = 1
          AND lower(trim(name)) = lower(trim(:name))
          AND lower(trim(address)) = lower(trim(:address))
          AND phone = :phone
        ORDER BY createdAt ASC
        LIMIT 1
        """
    )
    suspend fun findActiveByIdentity(name: String, address: String, phone: String): StationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStation(station: StationEntity): Long

    @Update
    suspend fun updateStation(station: StationEntity)

    @Query("DELETE FROM stations WHERE id = :stationId")
    suspend fun deleteStation(stationId: Long)
}

@Dao
interface OwnerDao {
    @Query("SELECT * FROM station_owners WHERE phone = :phone")
    suspend fun getOwnerByPhone(phone: String): OwnerEntity?

    @Query("SELECT * FROM station_owners WHERE email = :email LIMIT 1")
    suspend fun getOwnerByEmail(email: String): OwnerEntity?

    @Query("SELECT * FROM station_owners WHERE id = :ownerId")
    fun observeOwner(ownerId: Long): Flow<OwnerEntity?>

    @Query("SELECT * FROM station_owners WHERE id = :ownerId")
    suspend fun getOwnerById(ownerId: Long): OwnerEntity?

    @Query("SELECT * FROM station_owners WHERE firebaseUid = :firebaseUid LIMIT 1")
    suspend fun getOwnerByFirebaseUid(firebaseUid: String): OwnerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOwner(owner: OwnerEntity): Long

    @Update
    suspend fun updateOwner(owner: OwnerEntity)
}

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers WHERE stationId = :stationId ORDER BY id ASC")
    suspend fun getCustomersForStation(stationId: Long): List<CustomerEntity>

    @Transaction
    @Query("SELECT * FROM customers WHERE stationId = :stationId ORDER BY name ASC")
    fun observeCustomers(stationId: Long): Flow<List<CustomerWithAddresses>>

    @Transaction
    @Query(
        """
        SELECT * FROM customers 
        WHERE stationId = :stationId 
        AND (name LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%')
        ORDER BY name ASC
    """
    )
    fun searchCustomers(stationId: Long, query: String): Flow<List<CustomerWithAddresses>>

    @Transaction
    @Query("SELECT * FROM customers WHERE id = :customerId")
    suspend fun getCustomerById(customerId: Long): CustomerWithAddresses?

    @Transaction
    @Query("SELECT * FROM customers ORDER BY id ASC")
    suspend fun getAllCustomers(): List<CustomerWithAddresses>

    @Query("SELECT * FROM customer_addresses WHERE id = :addressId LIMIT 1")
    suspend fun getAddressById(addressId: Long): CustomerAddressEntity?

    @Query("SELECT * FROM customer_addresses ORDER BY id ASC")
    suspend fun getAllAddresses(): List<CustomerAddressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomer(customer: CustomerEntity): Long

    @Update
    suspend fun updateCustomer(customer: CustomerEntity)

    @Query("DELETE FROM customers WHERE id = :customerId")
    suspend fun deleteCustomer(customerId: Long)

    @Query("UPDATE orders SET customerId = :canonicalId WHERE customerId = :duplicateId")
    suspend fun reassignOrderCustomer(duplicateId: Long, canonicalId: Long)

    @Query("UPDATE container_transactions SET customerId = :canonicalId WHERE customerId = :duplicateId")
    suspend fun reassignContainerTransactionCustomer(duplicateId: Long, canonicalId: Long)

    @Query("DELETE FROM customer_addresses WHERE customerId = :customerId")
    suspend fun deleteAddressesForCustomer(customerId: Long)

    @Query("UPDATE customer_addresses SET customerId = :canonicalCustomerId WHERE customerId = :duplicateCustomerId")
    suspend fun reassignCustomerAddresses(duplicateCustomerId: Long, canonicalCustomerId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAddress(address: CustomerAddressEntity): Long

    @Update
    suspend fun updateAddress(address: CustomerAddressEntity)

    @Query("DELETE FROM customer_addresses WHERE id = :addressId")
    suspend fun deleteAddress(addressId: Long)

    @Query("UPDATE customers SET totalOrders = totalOrders + 1, updatedAt = :now WHERE id = :customerId")
    suspend fun incrementOrderCount(customerId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE customers SET totalSpent = totalSpent + :amount, updatedAt = :now WHERE id = :customerId")
    suspend fun addPaidSpend(customerId: Long, amount: Double, now: Long = System.currentTimeMillis())
}

data class CustomerWithAddresses(
    @Embedded val customer: CustomerEntity,
    @Relation(parentColumn = "id", entityColumn = "customerId")
    val addresses: List<CustomerAddressEntity>
)

@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE stationId = :stationId AND isActive = 1 ORDER BY name ASC")
    fun observeProducts(stationId: Long): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE stationId = :stationId AND isActive = 1 AND isAvailable = 1 ORDER BY name ASC")
    fun observeAvailableProducts(stationId: Long): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :productId")
    suspend fun getProductById(productId: Long): ProductEntity?

    @Query("SELECT * FROM products ORDER BY id ASC")
    suspend fun getAllProducts(): List<ProductEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: ProductEntity): Long

    @Update
    suspend fun updateProduct(product: ProductEntity)

    @Query("UPDATE products SET isAvailable = :isAvailable WHERE id = :productId")
    suspend fun toggleAvailability(productId: Long, isAvailable: Boolean)

    @Query("UPDATE products SET stockQuantity = :newStock WHERE id = :productId")
    suspend fun updateStock(productId: Long, newStock: Int)

    @Query("UPDATE products SET isActive = 0, isAvailable = 0, updatedAt = :updatedAt WHERE id = :productId")
    suspend fun archive(productId: Long, updatedAt: Long)
}

@Dao
interface RiderDao {
    @Query("SELECT * FROM riders WHERE stationId = :stationId AND isActive = 1 ORDER BY name ASC")
    fun observeRiders(stationId: Long): Flow<List<RiderEntity>>

    @Query("SELECT * FROM riders WHERE stationId = :stationId AND isActive = 1 AND isAvailable = 1")
    fun observeAvailableRiders(stationId: Long): Flow<List<RiderEntity>>

    @Query("SELECT * FROM riders WHERE id = :riderId")
    suspend fun getRiderById(riderId: Long): RiderEntity?

    @Query("SELECT * FROM riders ORDER BY id ASC")
    suspend fun getAllRiders(): List<RiderEntity>

    @Query("SELECT * FROM riders WHERE stationId = :stationId AND isActive = 1 ORDER BY id ASC")
    suspend fun getActiveRidersForStation(stationId: Long): List<RiderEntity>

    @Query("SELECT COUNT(*) FROM orders WHERE riderId = :riderId AND orderStatus IN ('RIDER_ASSIGNED', 'OUT_FOR_DELIVERY')")
    suspend fun countActiveOrders(riderId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRider(rider: RiderEntity): Long

    @Update
    suspend fun updateRider(rider: RiderEntity)

    @Query("UPDATE riders SET isAvailable = :isAvailable WHERE id = :riderId")
    suspend fun updateAvailability(riderId: Long, isAvailable: Boolean)

    @Query("UPDATE riders SET activeOrderCount = activeOrderCount + 1 WHERE id = :riderId")
    suspend fun incrementActiveOrderCount(riderId: Long)

    @Query("UPDATE riders SET activeOrderCount = MAX(0, activeOrderCount - 1) WHERE id = :riderId")
    suspend fun decrementActiveOrderCount(riderId: Long)
}

@Dao
interface OrderDao {
    @Transaction
    @Query("""
        SELECT * FROM orders
        WHERE stationId = :stationId
          AND (:riderId IS NULL OR riderId = :riderId)
          AND orderStatus IN (:statuses)
        ORDER BY createdAt DESC
    """)
    fun pagingOrders(
        stationId: Long,
        riderId: Long?,
        statuses: List<String>
    ): androidx.paging.PagingSource<Int, OrderWithItems>

    @Transaction
    @Query("SELECT * FROM orders WHERE stationId = :stationId ORDER BY createdAt DESC")
    fun observeOrders(stationId: Long): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE customerId = :customerId ORDER BY createdAt DESC")
    fun observeOrdersForCustomer(customerId: Long): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE stationId = :stationId AND riderId = :riderId ORDER BY createdAt DESC")
    fun observeOrdersForRider(stationId: Long, riderId: Long): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE stationId = :stationId AND orderStatus = :status ORDER BY createdAt DESC")
    fun observeOrdersByStatus(stationId: Long, status: String): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE stationId = :stationId AND orderStatus IN (:statuses) ORDER BY createdAt DESC")
    fun observeOrdersByStatuses(stationId: Long, statuses: List<String>): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE stationId = :stationId AND riderId = :riderId AND orderStatus IN (:statuses) ORDER BY createdAt DESC")
    fun observeOrdersForRiderByStatuses(stationId: Long, riderId: Long, statuses: List<String>): Flow<List<OrderWithItems>>

    @Query("SELECT COUNT(*) FROM orders WHERE stationId = :stationId AND orderStatus NOT IN ('COMPLETED', 'CANCELLED', 'REJECTED')")
    fun observeUncompletedOrdersCount(stationId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM orders WHERE stationId = :stationId AND createdAt >= :startAt AND createdAt < :endAt")
    suspend fun countOrdersBetween(stationId: Long, startAt: Long, endAt: Long): Int

    @Transaction
    @Query("SELECT * FROM orders WHERE stationId = :stationId AND createdAt >= :startOfDay AND createdAt < :endOfDay ORDER BY createdAt DESC")
    fun observeTodayOrders(
        stationId: Long,
        startOfDay: Long,
        endOfDay: Long
    ): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE stationId = :stationId AND riderId = :riderId AND createdAt >= :startOfDay AND createdAt < :endOfDay ORDER BY createdAt DESC")
    fun observeTodayOrdersForRider(
        stationId: Long,
        riderId: Long,
        startOfDay: Long,
        endOfDay: Long
    ): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE stationId = :stationId ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentOrders(stationId: Long, limit: Int): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE stationId = :stationId AND riderId = :riderId ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentOrdersForRider(stationId: Long, riderId: Long, limit: Int): Flow<List<OrderWithItems>>

    @Transaction
    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun getOrderById(orderId: Long): OrderWithItems?

    @Query("SELECT * FROM orders WHERE id = :orderId")
    suspend fun getOrderEntityById(orderId: Long): OrderEntity?

    @Transaction
    @Query("SELECT * FROM orders ORDER BY id ASC")
    suspend fun getAllOrders(): List<OrderWithItems>

    @Insert
    suspend fun insertOrder(order: OrderEntity): Long

    @Update
    suspend fun updateOrder(order: OrderEntity): Int

    /**
     * Updates an existing order without SQLite REPLACE. REPLACE deletes then
     * reinserts the parent row and would trigger CASCADE on payments/items.
     */
    @Transaction
    suspend fun upsertOrderPreservingChildren(order: OrderEntity): Long {
        if (order.id > 0 && updateOrder(order) > 0) return order.id
        return insertOrder(order)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderItems(items: List<OrderItemEntity>)

    @Query("DELETE FROM order_items WHERE orderId = :orderId")
    suspend fun deleteOrderItems(orderId: Long)

    @Query("UPDATE orders SET orderStatus = :status, updatedAt = :timestamp WHERE id = :orderId")
    suspend fun updateOrderStatus(orderId: Long, status: String, timestamp: Long)

    @Query("UPDATE orders SET paymentStatus = 'PAID', updatedAt = :timestamp WHERE id = :orderId")
    suspend fun markOfflineOrderPaid(orderId: Long, timestamp: Long)

    @Query("UPDATE orders SET acceptedAt = :timestamp, orderStatus = 'ACCEPTED', updatedAt = :timestamp WHERE id = :orderId")
    suspend fun setAcceptedAt(orderId: Long, timestamp: Long)

    @Query("UPDATE orders SET readyAt = :timestamp, orderStatus = 'READY_FOR_PICKUP', updatedAt = :timestamp WHERE id = :orderId")
    suspend fun setReadyAt(orderId: Long, timestamp: Long)

    @Query("UPDATE orders SET riderId = :riderId, riderName = :riderName, assignedAt = :timestamp, orderStatus = 'RIDER_ASSIGNED', updatedAt = :timestamp WHERE id = :orderId")
    suspend fun assignRider(orderId: Long, riderId: Long, riderName: String, timestamp: Long)

    @Query("UPDATE orders SET outForDeliveryAt = :timestamp, orderStatus = 'OUT_FOR_DELIVERY', updatedAt = :timestamp WHERE id = :orderId")
    suspend fun setOutForDeliveryAt(orderId: Long, timestamp: Long)

    @Query("UPDATE orders SET deliveredAt = :timestamp, orderStatus = 'DELIVERED', updatedAt = :timestamp WHERE id = :orderId")
    suspend fun setDeliveredAt(orderId: Long, timestamp: Long)

    @Query("UPDATE orders SET completedAt = :timestamp, orderStatus = 'COMPLETED', updatedAt = :timestamp WHERE id = :orderId")
    suspend fun setCompletedAt(orderId: Long, timestamp: Long)

    @Query("UPDATE orders SET cancelledAt = :timestamp, cancellationReason = :reason, orderStatus = 'CANCELLED', updatedAt = :timestamp WHERE id = :orderId")
    suspend fun setCancelledAt(orderId: Long, timestamp: Long, reason: String)

    @Query("UPDATE orders SET orderStatus = 'REJECTED', updatedAt = :timestamp WHERE id = :orderId")
    suspend fun setRejected(orderId: Long, timestamp: Long = System.currentTimeMillis())

    @Transaction
    @Query("""
        SELECT * FROM orders 
        WHERE stationId = :stationId AND orderStatus = 'COMPLETED' 
        AND completedAt >= :startOfDay AND completedAt < :endOfDay 
        ORDER BY completedAt DESC
    """)
    fun observeCompletedTodayOrders(
        stationId: Long,
        startOfDay: Long,
        endOfDay: Long
    ): Flow<List<OrderWithItems>>

    @Transaction
    @Query("""
        SELECT * FROM orders
        WHERE stationId = :stationId AND riderId = :riderId AND orderStatus = 'COMPLETED'
        AND completedAt >= :startOfDay AND completedAt < :endOfDay
        ORDER BY completedAt DESC
    """)
    fun observeCompletedTodayOrdersForRider(
        stationId: Long,
        riderId: Long,
        startOfDay: Long,
        endOfDay: Long
    ): Flow<List<OrderWithItems>>

    @Transaction
    @Query("""
        SELECT * FROM orders 
        WHERE stationId = :stationId AND orderStatus = 'COMPLETED' 
        AND completedAt >= :startOfYesterday AND completedAt < :startOfToday 
        ORDER BY completedAt DESC
    """)
    fun observeCompletedYesterdayOrders(
        stationId: Long,
        startOfYesterday: Long,
        startOfToday: Long
    ): Flow<List<OrderWithItems>>

    @Transaction
    @Query("""
        SELECT * FROM orders
        WHERE stationId = :stationId AND riderId = :riderId AND orderStatus = 'COMPLETED'
        AND completedAt >= :startOfYesterday AND completedAt < :startOfToday
        ORDER BY completedAt DESC
    """)
    fun observeCompletedYesterdayOrdersForRider(
        stationId: Long,
        riderId: Long,
        startOfYesterday: Long,
        startOfToday: Long
    ): Flow<List<OrderWithItems>>

    @Query("UPDATE orders SET stationNote = :note WHERE id = :orderId")
    suspend fun updateStationNote(orderId: Long, note: String)

    @Query("UPDATE orders SET paymentStatus = :status, paymentMethod = :method, updatedAt = :timestamp WHERE id = :orderId")
    suspend fun updatePaymentState(orderId: Long, status: String, method: String, timestamp: Long)

    // Reports queries
    @Query(
        """
        SELECT
          COALESCE((SELECT SUM(p.amount) FROM payments p JOIN orders o ON o.id = p.orderId
            WHERE o.stationId = :stationId AND p.status = 'PAID'
              AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
              AND COALESCE(p.paidAt, p.createdAt) >= :start AND COALESCE(p.paidAt, p.createdAt) < :end), 0.0)
          - COALESCE((SELECT SUM(r.amountCentavos) / 100.0 FROM payment_refunds r JOIN orders o ON o.id = r.orderId
            WHERE o.stationId = :stationId AND r.status = 'COMPLETED'
              AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
              AND r.completedAt >= :start AND r.completedAt < :end), 0.0)
    """
    )
    suspend fun getTotalSales(stationId: Long, start: Long, end: Long): Double?

    @Query(
        """
        SELECT COUNT(*) FROM orders 
        WHERE stationId = :stationId AND orderStatus IN ('PENDING', 'ACCEPTED')
    """
    )
    suspend fun getPendingCount(stationId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM orders 
        WHERE stationId = :stationId AND orderStatus IN ('RIDER_ASSIGNED', 'OUT_FOR_DELIVERY', 'DELIVERED')
    """
    )
    suspend fun getOutForDeliveryCount(stationId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM orders 
        WHERE stationId = :stationId AND orderStatus = 'COMPLETED' 
        AND completedAt >= :startOfDay AND completedAt < :endOfDay
    """
    )
    suspend fun getCompletedTodayCount(stationId: Long, startOfDay: Long, endOfDay: Long): Int

    @Query("SELECT COUNT(*) FROM orders WHERE stationId = :stationId AND createdAt >= :start AND createdAt < :end")
    suspend fun getOrderCount(stationId: Long, start: Long, end: Long): Int

    @Query("SELECT COUNT(*) FROM orders WHERE stationId = :stationId AND createdAt = :createdAt")
    suspend fun getOrderCountAtTimestamp(stationId: Long, createdAt: Long): Int

    @Query("SELECT COUNT(*) FROM orders WHERE stationId = :stationId AND orderStatus = 'COMPLETED' AND completedAt >= :start AND completedAt < :end")
    suspend fun getCompletedCount(stationId: Long, start: Long, end: Long): Int

    @Query("SELECT COUNT(*) FROM orders WHERE stationId = :stationId AND orderStatus IN ('CANCELLED','REJECTED') AND createdAt >= :start AND createdAt < :end")
    suspend fun getCancelledCount(stationId: Long, start: Long, end: Long): Int
}

data class OrderWithItems(
    @Embedded val order: OrderEntity,
    @Relation(parentColumn = "id", entityColumn = "orderId")
    val items: List<OrderItemEntity>
)

@Dao
interface ContainerDao {
    @Query("SELECT * FROM containers WHERE stationId = :stationId")
    fun observeContainers(stationId: Long): Flow<List<ContainerEntity>>

    @Query("SELECT * FROM containers WHERE stationId = :stationId AND LOWER(TRIM(sizeLabel)) = LOWER(TRIM(:sizeLabel)) LIMIT 1")
    suspend fun getContainerBySizeLabel(stationId: Long, sizeLabel: String): ContainerEntity?

    @Query("SELECT * FROM containers WHERE id = :containerId LIMIT 1")
    suspend fun getContainerById(containerId: Long): ContainerEntity?

    @Query("SELECT * FROM containers ORDER BY id ASC")
    suspend fun getAllContainers(): List<ContainerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContainer(container: ContainerEntity)

    @Query("UPDATE containers SET available = available + :delta WHERE stationId = :stationId AND sizeLabel = :sizeLabel")
    suspend fun adjustAvailable(stationId: Long, sizeLabel: String, delta: Int)

    @Query("UPDATE containers SET withCustomers = withCustomers + :delta WHERE stationId = :stationId AND sizeLabel = :sizeLabel")
    suspend fun adjustWithCustomers(stationId: Long, sizeLabel: String, delta: Int)

    @Query("UPDATE containers SET damaged = damaged + :delta WHERE stationId = :stationId AND sizeLabel = :sizeLabel")
    suspend fun adjustDamaged(stationId: Long, sizeLabel: String, delta: Int)

    @Query("UPDATE containers SET lost = lost + :delta WHERE stationId = :stationId AND sizeLabel = :sizeLabel")
    suspend fun adjustLost(stationId: Long, sizeLabel: String, delta: Int)
}

@Dao
interface ContainerTransactionDao {
    @Query("SELECT * FROM container_transactions WHERE stationId = :stationId ORDER BY createdAt DESC")
    fun pagingTransactions(stationId: Long): androidx.paging.PagingSource<Int, ContainerTransactionEntity>

    @Query("SELECT * FROM container_transactions WHERE stationId = :stationId ORDER BY createdAt DESC")
    fun observeTransactions(stationId: Long): Flow<List<ContainerTransactionEntity>>

    @Query("SELECT * FROM container_transactions WHERE customerId = :customerId ORDER BY createdAt DESC")
    fun observeCustomerTransactions(customerId: Long): Flow<List<ContainerTransactionEntity>>

    @Query("SELECT * FROM container_transactions WHERE id = :transactionId LIMIT 1")
    suspend fun getTransactionById(transactionId: Long): ContainerTransactionEntity?

    @Query("SELECT * FROM container_transactions ORDER BY id ASC")
    suspend fun getAllTransactions(): List<ContainerTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: ContainerTransactionEntity): Long
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments WHERE orderId = :orderId")
    suspend fun getPaymentForOrder(orderId: Long): PaymentEntity?

    @Query("SELECT * FROM payments WHERE orderId = :orderId ORDER BY COALESCE(paidAt, createdAt) DESC")
    suspend fun getPaymentsForOrder(orderId: Long): List<PaymentEntity>

    @Query("SELECT * FROM payments WHERE orderId IN (:orderIds) ORDER BY COALESCE(paidAt, createdAt) DESC")
    suspend fun getPaymentsForOrders(orderIds: List<Long>): List<PaymentEntity>

    @Query("""
        SELECT payments.* FROM payments
        INNER JOIN orders ON orders.id = payments.orderId
        WHERE orders.customerId = :customerId
        ORDER BY COALESCE(payments.paidAt, payments.createdAt) DESC
    """)
    fun observePaymentsForCustomer(customerId: Long): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE id = :paymentId LIMIT 1")
    suspend fun getPaymentById(paymentId: Long): PaymentEntity?

    @Query("SELECT * FROM payments WHERE operationId = :operationId LIMIT 1")
    suspend fun getPaymentByOperationId(operationId: String): PaymentEntity?

    @Update
    suspend fun updatePayment(payment: PaymentEntity): Int

    @Query("SELECT * FROM payments ORDER BY id ASC")
    suspend fun getAllPayments(): List<PaymentEntity>

    @Query(
        """
        SELECT
          COALESCE((SELECT SUM(p.amount)
            FROM payments p INNER JOIN orders o ON o.id = p.orderId
            WHERE o.stationId = :stationId AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
              AND p.status = 'PAID'
              AND COALESCE(p.paidAt, p.createdAt) >= :start
              AND COALESCE(p.paidAt, p.createdAt) < :end), 0.0)
          - COALESCE((SELECT SUM(r.amountCentavos) / 100.0
            FROM payment_refunds r INNER JOIN orders o ON o.id = r.orderId
            WHERE o.stationId = :stationId AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
              AND r.status = 'COMPLETED'
              AND r.completedAt >= :start AND r.completedAt < :end), 0.0)
        """
    )
    fun observeCollectedRevenue(stationId: Long, start: Long, end: Long): Flow<Double>

    @Query(
        """
        SELECT
          COALESCE((SELECT SUM(p.amount) FROM payments p JOIN orders o ON o.id = p.orderId
            WHERE o.stationId = :stationId AND o.riderId = :riderId AND p.status = 'PAID'
              AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
              AND COALESCE(p.paidAt, p.createdAt) >= :start AND COALESCE(p.paidAt, p.createdAt) < :end), 0.0)
          - COALESCE((SELECT SUM(r.amountCentavos) / 100.0 FROM payment_refunds r JOIN orders o ON o.id = r.orderId
            WHERE o.stationId = :stationId AND o.riderId = :riderId AND r.status = 'COMPLETED'
              AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
              AND r.completedAt >= :start AND r.completedAt < :end), 0.0)
        """
    )
    fun observeCollectedRevenueForRider(
        stationId: Long,
        riderId: Long,
        start: Long,
        end: Long,
    ): Flow<Double>

    @Query(
        """
        SELECT
          COALESCE((SELECT SUM(p.amount) FROM payments p JOIN orders o ON o.id = p.orderId
            WHERE o.stationId = :stationId AND p.status = 'PAID'
              AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
              AND COALESCE(p.paidAt, p.createdAt) >= :start AND COALESCE(p.paidAt, p.createdAt) < :end), 0.0)
          - COALESCE((SELECT SUM(r.amountCentavos) / 100.0 FROM payment_refunds r JOIN orders o ON o.id = r.orderId
            WHERE o.stationId = :stationId AND r.status = 'COMPLETED'
              AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
              AND r.completedAt >= :start AND r.completedAt < :end), 0.0)
        """
    )
    suspend fun getCollectedRevenue(stationId: Long, start: Long, end: Long): Double

    @Query("""
        WITH paid AS (
          SELECT p.orderId, SUM(p.amount) AS amount
          FROM payments p JOIN orders o ON o.id = p.orderId
          WHERE o.stationId = :stationId AND p.status = 'PAID'
            AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
            AND COALESCE(p.paidAt, p.createdAt) >= :start AND COALESCE(p.paidAt, p.createdAt) < :end
          GROUP BY p.orderId
        ), refunded AS (
          SELECT r.orderId, SUM(r.amountCentavos) / 100.0 AS amount
          FROM payment_refunds r JOIN orders o ON o.id = r.orderId
          WHERE o.stationId = :stationId AND r.status = 'COMPLETED'
            AND o.orderStatus NOT IN ('CANCELLED', 'REJECTED')
            AND r.completedAt >= :start AND r.completedAt < :end
          GROUP BY r.orderId
        )
        SELECT COUNT(*) FROM paid p LEFT JOIN refunded r ON r.orderId = p.orderId
        WHERE p.amount - COALESCE(r.amount, 0.0) > 0.0
        """)
    suspend fun getPaidOrderCount(stationId: Long, start: Long, end: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPayment(payment: PaymentEntity): Long
}

@Dao
interface PaymentRefundDao {
    @Query("SELECT * FROM payment_refunds WHERE orderId = :orderId ORDER BY createdAt DESC")
    suspend fun getForOrder(orderId: Long): List<PaymentRefundEntity>

    @Query("SELECT * FROM payment_refunds WHERE orderId IN (:orderIds) ORDER BY createdAt DESC")
    suspend fun getForOrders(orderIds: List<Long>): List<PaymentRefundEntity>

    @Query("SELECT * FROM payment_refunds WHERE operationId = :operationId LIMIT 1")
    suspend fun getByOperationId(operationId: String): PaymentRefundEntity?

    @Query("SELECT * FROM payment_refunds WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): PaymentRefundEntity?

    @Query("SELECT * FROM payment_refunds WHERE orderId = :orderId AND originalPaymentId = :paymentId AND amountCentavos = :amountCentavos AND reason = :reason AND status = 'REQUESTING' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getUnconfirmedRequest(orderId: Long, paymentId: Long, amountCentavos: Long, reason: String): PaymentRefundEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(refund: PaymentRefundEntity): Long

    @Update
    suspend fun update(refund: PaymentRefundEntity)
}

@Dao
interface OrderItemDao {
    @Query("""
        WITH period_activity AS (
            SELECT p.orderId
            FROM payments p INNER JOIN orders o ON o.id = p.orderId
            WHERE o.stationId = :stationId AND p.status = 'PAID'
              AND COALESCE(p.paidAt, p.createdAt) >= :start AND COALESCE(p.paidAt, p.createdAt) < :end
            UNION
            SELECT r.orderId
            FROM payment_refunds r INNER JOIN orders o ON o.id = r.orderId
            WHERE o.stationId = :stationId AND r.status = 'COMPLETED'
              AND r.completedAt >= :start AND r.completedAt < :end
        ), gross AS (
            SELECT p.orderId, SUM(p.amount) AS amount
            FROM payments p INNER JOIN orders o ON o.id = p.orderId
            WHERE o.stationId = :stationId AND p.status = 'PAID'
              AND COALESCE(p.paidAt, p.createdAt) >= :start AND COALESCE(p.paidAt, p.createdAt) < :end
            GROUP BY p.orderId
        ), refunded AS (
            SELECT r.orderId, SUM(r.amountCentavos) / 100.0 AS amount
            FROM payment_refunds r INNER JOIN orders o ON o.id = r.orderId
            WHERE o.stationId = :stationId AND r.status = 'COMPLETED'
              AND r.completedAt >= :start AND r.completedAt < :end
            GROUP BY r.orderId
        ), paid_orders AS (
            SELECT a.orderId, COALESCE(g.amount, 0.0) - COALESCE(r.amount, 0.0) AS paidAmount
            FROM period_activity a
            LEFT JOIN gross g ON g.orderId = a.orderId
            LEFT JOIN refunded r ON r.orderId = a.orderId
        )
        SELECT oi.productName AS productName,
               oi.sizeLabel AS sizeLabel,
               SUM(oi.quantity) AS totalQty,
               SUM(CASE
                   WHEN paid.paidAmount >= o.total THEN oi.subtotal
                   WHEN paid.paidAmount > 0.0 AND o.total > 0 THEN oi.subtotal * paid.paidAmount / o.total
                   ELSE 0
               END) AS totalRev
        FROM order_items oi 
        JOIN orders o ON oi.orderId = o.id 
        JOIN paid_orders paid ON paid.orderId = o.id
        WHERE o.stationId = :stationId 
          AND o.orderStatus IN ('COMPLETED', 'DELIVERED')
        GROUP BY oi.productName, oi.sizeLabel 
        ORDER BY totalQty DESC 
        LIMIT :limit
    """)
    suspend fun getTopProducts(
        stationId: Long,
        start: Long,
        end: Long,
        limit: Int
    ): List<TopProductResult>
}

data class TopProductResult(
    val productName: String,
    val sizeLabel: String,
    val totalQty: Int,
    val totalRev: Double
)
