package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.CustomerAddress
import kotlinx.coroutines.flow.Flow

interface CustomerRepository {
    fun observeCustomers(stationId: Long): Flow<List<Customer>>
    fun searchCustomers(stationId: Long, query: String): Flow<List<Customer>>
    suspend fun getCustomerById(customerId: Long): Customer?
    suspend fun insertCustomer(customer: Customer): Long
    suspend fun updateCustomer(customer: Customer)
    suspend fun deleteCustomer(customerId: Long)
    suspend fun insertAddress(address: CustomerAddress): Long
    suspend fun updateAddress(address: CustomerAddress)
    suspend fun deleteAddress(addressId: Long)
    suspend fun incrementOrderCount(customerId: Long)
}
