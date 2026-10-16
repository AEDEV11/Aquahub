package com.aesprt.aquahub.domain.repository

import com.aesprt.aquahub.domain.model.Product
import kotlinx.coroutines.flow.Flow

interface ProductRepository {
    fun observeProducts(stationId: Long): Flow<List<Product>>
    fun observeAvailableProducts(stationId: Long): Flow<List<Product>>
    suspend fun getProductById(productId: Long): Product?
    suspend fun insertProduct(product: Product): Long
    suspend fun updateProduct(product: Product)
    suspend fun toggleAvailability(productId: Long, isAvailable: Boolean)
    suspend fun updateStock(productId: Long, newStock: Int)
    suspend fun archive(productId: Long)
}
