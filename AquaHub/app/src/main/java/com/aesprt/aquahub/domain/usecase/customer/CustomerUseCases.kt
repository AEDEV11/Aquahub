package com.aesprt.aquahub.domain.usecase.customer

import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.domain.common.LocalTransactionRunner
import com.aesprt.aquahub.domain.model.ContainerTransactionType
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.CustomerContainerBalance
import com.aesprt.aquahub.domain.repository.CustomerRepository
import com.aesprt.aquahub.domain.repository.InventoryRepository
import com.aesprt.aquahub.domain.usecase.subscription.CanCreateCustomerUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import com.aesprt.aquahub.domain.repository.WorkspaceNotProvisionedException

class ObserveCustomersUseCase(private val customerRepository: CustomerRepository) {
    operator fun invoke(stationId: Long): Flow<List<Customer>> =
        customerRepository.observeCustomers(stationId)
}

class SearchCustomersUseCase(private val customerRepository: CustomerRepository) {
    operator fun invoke(stationId: Long, query: String): Flow<List<Customer>> =
        customerRepository.searchCustomers(stationId, query)
}

class GetCustomerDetailUseCase(private val customerRepository: CustomerRepository) {
    suspend operator fun invoke(customerId: Long): Customer? =
        customerRepository.getCustomerById(customerId)
}

class CreateCustomerUseCase(
    private val customerRepository: CustomerRepository,
    private val canCreateCustomerUseCase: CanCreateCustomerUseCase,
    private val transactionRunner: LocalTransactionRunner
) {
    suspend operator fun invoke(customer: Customer): Result<Long> = transactionRunner.run {
        if (customer.name.isBlank()) return@run Result.failure(AppError.ValidationError("Customer name is required"))
        if (customer.phone.isBlank()) return@run Result.failure(AppError.ValidationError("Phone number is required"))
        customer.addresses.forEach { address ->
            if (address.addressLine.isBlank()) {
                return@run Result.failure(AppError.ValidationError("Delivery address is required when a location is selected"))
            }
            val hasLatitude = address.latitude != null
            val hasLongitude = address.longitude != null
            if (hasLatitude != hasLongitude) {
                return@run Result.failure(AppError.ValidationError("Delivery location must include both latitude and longitude"))
            }
            if (address.latitude != null && address.latitude !in -90.0..90.0) {
                return@run Result.failure(AppError.ValidationError("Delivery latitude is outside the valid range"))
            }
            if (address.longitude != null && address.longitude !in -180.0..180.0) {
                return@run Result.failure(AppError.ValidationError("Delivery longitude is outside the valid range"))
            }
        }
        val access = canCreateCustomerUseCase(customer.stationId)
        if (access !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed) {
            return@run Result.failure(AppError.ValidationError("Customer management is not available for this subscription"))
        }
        return@run runCatching { customerRepository.insertCustomer(customer) }
            .fold(
                onSuccess = { Result.success(it) },
                onFailure = {
                    if (it is WorkspaceNotProvisionedException) {
                        Result.failure(AppError.ValidationError("We're finishing your AquaHub workspace setup. Please retry in a moment."))
                    } else {
                        Result.failure(it)
                    }
                }
            )
    }
}

class UpdateCustomerUseCase(private val customerRepository: CustomerRepository, private val access: com.aesprt.aquahub.domain.usecase.subscription.CheckSubscriptionAccessUseCase) {
    suspend operator fun invoke(customer: Customer): Result<Unit> {
        if (access(customer.stationId, com.aesprt.aquahub.domain.model.SubscriptionFeature.CUSTOMER_MANAGEMENT) !is com.aesprt.aquahub.domain.model.SubscriptionAccessResult.Allowed)
            return Result.failure(AppError.ValidationError("This feature is unavailable for your subscription"))
        if (customer.name.isBlank()) return Result.failure(AppError.ValidationError("Customer name is required"))
        customerRepository.updateCustomer(customer)
        return Result.success(Unit)
    }
}

class GetCustomerContainerBalanceUseCase(private val inventoryRepository: InventoryRepository) {
    suspend operator fun invoke(customerId: Long): List<CustomerContainerBalance> {
        val transactions = inventoryRepository.observeCustomerTransactions(customerId).first()
        val balance = mutableMapOf<String, Int>()
        for (txn in transactions) {
            val current = balance.getOrDefault(txn.containerSize, 0)
            balance[txn.containerSize] = when (txn.type) {
                ContainerTransactionType.DELIVERED -> current + txn.quantity
                ContainerTransactionType.RETURNED, ContainerTransactionType.LOST -> current - txn.quantity
                else -> current
            }
        }
        return balance.filter { it.value > 0 }.map { (size, qty) ->
            CustomerContainerBalance(containerSize = size, quantityWithCustomer = qty)
        }
    }
}
