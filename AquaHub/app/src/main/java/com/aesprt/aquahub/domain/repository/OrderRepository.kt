package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.Order
import com.aesprt.aquahub.domain.model.OrderStatus
import kotlinx.coroutines.flow.Flow
import androidx.paging.PagingSource

fun interface RemoteOrderReader {
    /** Refreshes the online delivery/order read model into Room. */
    suspend fun refreshDeliveryReads(stationId: Long): Result<Unit>
}

interface OrderRepository : RemoteOrderReader {
    override suspend fun refreshDeliveryReads(stationId: Long): Result<Unit> = Result.success(Unit)
    fun pagingOrders(stationId: Long, riderId: Long?, statuses: List<OrderStatus>): PagingSource<Int, Order>
    fun observeOrders(stationId: Long): Flow<List<Order>>
    fun observeOrdersForCustomer(customerId: Long): Flow<List<Order>>
    fun observeOrdersForRider(stationId: Long, riderId: Long): Flow<List<Order>>
    fun observeOrdersByStatus(stationId: Long, status: OrderStatus): Flow<List<Order>>
    fun observeOrdersForRiderByStatuses(stationId: Long, riderId: Long, statuses: List<OrderStatus>): Flow<List<Order>>
    fun observeOrdersByStatuses(stationId: Long, statuses: List<OrderStatus>): Flow<List<Order>>
    fun observeTodayOrders(stationId: Long, startOfDay: Long, endOfDay: Long): Flow<List<Order>>
    suspend fun getOrderById(orderId: Long): Order?
    suspend fun countOrdersBetween(stationId: Long, startAt: Long, endAt: Long): Int
    suspend fun insertOrder(order: Order): Long
    /** Inserts a canonical Supabase order into the local cache without enqueuing a cloud write. */
    suspend fun insertOrderFromSupabase(order: Order, remoteId: String? = null): Long
    suspend fun updateOrderStatus(orderId: Long, status: OrderStatus, timestamp: Long)
    suspend fun assignRider(orderId: Long, riderId: Long, riderName: String)
    suspend fun updateStationNote(orderId: Long, note: String)
    suspend fun markChargedToAccount(orderId: Long, timestamp: Long)
    suspend fun cancelOrder(orderId: Long, timestamp: Long, reason: String)
    fun observeRecentOrders(stationId: Long, limit: Int): Flow<List<Order>>
    fun observeUncompletedOrdersCount(stationId: Long): Flow<Int>
}
