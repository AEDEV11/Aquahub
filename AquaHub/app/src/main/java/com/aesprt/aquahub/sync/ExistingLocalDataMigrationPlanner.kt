package com.aesprt.aquahub.sync

import com.aesprt.aquahub.data.local.dao.ContainerDao
import com.aesprt.aquahub.data.local.dao.ContainerTransactionDao
import com.aesprt.aquahub.data.local.dao.CustomerDao
import com.aesprt.aquahub.data.local.dao.OrderDao
import com.aesprt.aquahub.data.local.dao.PaymentDao
import com.aesprt.aquahub.data.local.dao.ProductDao
import com.aesprt.aquahub.data.local.dao.RiderDao
import com.aesprt.aquahub.data.local.dao.StationDao


/**
 * Idempotently places pre-Firebase Room data into the same durable outbox used
 * for new writes. Stable metadata IDs prevent duplicate uploads after a crash.
 */
class ExistingLocalDataMigrationPlanner(
    private val stationDao: StationDao,
    private val customerDao: CustomerDao,
    private val productDao: ProductDao,
    private val riderDao: RiderDao,
    private val orderDao: OrderDao,
    private val containerDao: ContainerDao,
    private val transactionDao: ContainerTransactionDao,
    private val paymentDao: PaymentDao,
    private val queueWriter: SyncQueueWriter
) {
    suspend fun enqueueExistingData() {
        stationDao.getAllStations().forEach {
            queueWriter.enqueueInitialIfNeeded(SyncResourceType.STATION, it.id, it.id, it.updatedAt)
        }
        customerDao.getAllCustomers().forEach {
            queueWriter.enqueueInitialIfNeeded(
                SyncResourceType.CUSTOMER,
                it.customer.id,
                it.customer.stationId,
                it.customer.updatedAt
            )
        }
        customerDao.getAllAddresses().forEach { address ->
            val stationId = customerDao.getCustomerById(address.customerId)?.customer?.stationId
            queueWriter.enqueueInitialIfNeeded(
                SyncResourceType.CUSTOMER_ADDRESS,
                address.id,
                stationId,
                System.currentTimeMillis()
            )
        }
        productDao.getAllProducts().forEach {
            queueWriter.enqueueInitialIfNeeded(
                SyncResourceType.PRODUCT,
                it.id,
                it.stationId,
                it.updatedAt,
                operation = if (it.isActive) SyncOperation.CREATE else SyncOperation.ARCHIVE
            )
        }
        riderDao.getAllRiders().forEach {
            queueWriter.enqueueInitialIfNeeded(SyncResourceType.RIDER, it.id, it.stationId, it.updatedAt)
        }
        containerDao.getAllContainers().forEach {
            queueWriter.enqueueInitialIfNeeded(SyncResourceType.CONTAINER, it.id, it.stationId, it.updatedAt)
        }
        orderDao.getAllOrders().forEach {
            queueWriter.enqueueInitialIfNeeded(
                SyncResourceType.ORDER,
                it.order.id,
                it.order.stationId,
                it.order.updatedAt ?: it.order.createdAt
            )
        }
        transactionDao.getAllTransactions().forEach {
            queueWriter.enqueueInitialIfNeeded(
                SyncResourceType.CONTAINER_MOVEMENT,
                it.id,
                it.stationId,
                it.createdAt,
                // The current container snapshot is uploaded first. Historical
                // ledger rows must not apply their deltas to that snapshot again.
                SyncOperation.IMPORT
            )
        }
        paymentDao.getAllPayments().forEach { payment ->
            val stationId = orderDao.getOrderById(payment.orderId)?.order?.stationId
            queueWriter.enqueueInitialIfNeeded(
                SyncResourceType.PAYMENT,
                payment.id,
                stationId,
                payment.paidAt ?: payment.createdAt
            )
        }
    }
}
