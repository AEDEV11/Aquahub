package com.aesprt.aquahub.domain.usecase.customer

import android.content.Context
import com.aesprt.aquahub.domain.export.CustomerStatementExporter
import com.aesprt.aquahub.domain.export.ReportExportResult
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.CustomerAccountEntry
import com.aesprt.aquahub.domain.model.CustomerAccountSnapshot
import com.aesprt.aquahub.domain.model.PaymentMethod
import com.aesprt.aquahub.domain.model.PaymentStatus
import com.aesprt.aquahub.domain.model.OrderStatus
import com.aesprt.aquahub.domain.model.SubscriptionAccessResult
import com.aesprt.aquahub.domain.model.SubscriptionFeature
import com.aesprt.aquahub.domain.repository.OrderRepository
import com.aesprt.aquahub.domain.repository.PaymentRepository
import com.aesprt.aquahub.domain.repository.StationRepository
import com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlin.math.max

class ObserveCustomerAccountUseCase(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository
) {
    operator fun invoke(customer: Customer): Flow<CustomerAccountSnapshot> = combine(
        orderRepository.observeOrdersForCustomer(customer.id),
        paymentRepository.observePaymentsForCustomer(customer.id)
    ) { orders, payments ->
        val paidByOrder = payments
            .filter { it.status == PaymentStatus.PAID }
            .groupBy { it.orderId }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
        CustomerAccountSnapshot(
            customer = customer,
            allOrders = orders,
            entries = orders
                // PENDING means payment has not been confirmed yet; it is not debt.
                // Only an explicit Client Account settlement belongs in receivables.
                .filter {
                    it.paymentMethod == PaymentMethod.CLIENT_ACCOUNT &&
                        it.orderStatus !in setOf(OrderStatus.CANCELLED, OrderStatus.REJECTED)
                }
                .map { order ->
                // Order status is fulfillment state; only a paid receipt counts
                // toward what the customer has already paid.
                val paid = paidByOrder[order.id] ?: 0.0
                CustomerAccountEntry(order, paid, max(0.0, order.total - paid))
            },
            payments = payments
        )
    }
}

class RefreshCustomerOrderHistoryUseCase(
    private val orderRepository: OrderRepository
) {
    suspend operator fun invoke(stationId: Long): Result<Unit> =
        orderRepository.refreshDeliveryReads(stationId)
}

class RecordCustomerPaymentUseCase(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val access: CheckSubscriptionAccessUseCase,
    private val stationRepository: StationRepository
) {
    suspend operator fun invoke(
        orderId: Long,
        amount: Double,
        method: PaymentMethod,
        referenceNumber: String?
    ): Result<Unit> = runCatching {
        val order = requireNotNull(orderRepository.getOrderById(orderId)) { "Order not found" }
        require(order.orderStatus != OrderStatus.CANCELLED && order.orderStatus != OrderStatus.REJECTED) {
            "Payments cannot be recorded for a cancelled or rejected order"
        }
        require(access(order.stationId, SubscriptionFeature.ACCOUNTS_RECEIVABLE) is SubscriptionAccessResult.Allowed) {
            "Client credit and payment history require the Business or Pro plan"
        }
        val station = requireNotNull(stationRepository.getStation(order.stationId)) { "Station not found" }
        val methodEnabled = when (method) {
            PaymentMethod.CASH -> station.cashPaymentEnabled
            PaymentMethod.GCASH -> station.gcashPaymentEnabled
            PaymentMethod.MAYA -> station.mayaPaymentEnabled
            else -> false
        }
        require(methodEnabled) { "This payment method is not active for the station" }
        paymentRepository.recordPayment(orderId, amount, method, referenceNumber)
    }
}

class ExportCustomerStatementUseCase(
    private val exporter: CustomerStatementExporter,
    private val access: CheckSubscriptionAccessUseCase
) {
    suspend operator fun invoke(
        context: Context,
        stationId: Long,
        stationName: String,
        snapshot: CustomerAccountSnapshot
    ): Result<ReportExportResult> {
        if (access(stationId, SubscriptionFeature.ACCOUNTS_RECEIVABLE) !is SubscriptionAccessResult.Allowed) {
            return Result.failure(IllegalStateException("PDF statements require the Business or Pro plan"))
        }
        if (access(stationId, SubscriptionFeature.EXPORT_PDF) !is SubscriptionAccessResult.Allowed) {
            return Result.failure(IllegalStateException("PDF export is unavailable for this plan"))
        }
        return exporter.export(context, stationName, snapshot)
    }
}
