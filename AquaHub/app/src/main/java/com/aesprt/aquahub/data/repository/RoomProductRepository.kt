package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.local.dao.ProductDao
import com.aesprt.aquahub.data.local.dao.StationDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.entity.ProductEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.local.mapper.toEntity
import com.aesprt.aquahub.data.supabase.catalog.SupabaseCatalogDataSource
import com.aesprt.aquahub.data.supabase.inventory.SupabaseInventoryDataSource
import com.aesprt.aquahub.domain.model.Product
import com.aesprt.aquahub.domain.repository.ProductRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.room.withTransaction
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.sync.*
import java.time.Instant
import java.util.UUID
import com.aesprt.aquahub.domain.repository.WorkspaceNotProvisionedException
import io.github.jan.supabase.auth.auth
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider

class RoomProductRepository(
    private val productDao: ProductDao,
    private val database: AquaHubDatabase,
    private val syncQueue: SyncQueueWriter,
    private val syncDao: SyncDao,
    private val stationDao: StationDao,
    private val supabaseCatalog: SupabaseCatalogDataSource,
    private val supabaseInventory: SupabaseInventoryDataSource
) : ProductRepository {

    override fun observeProducts(stationId: Long): Flow<List<Product>> =
        flow {
            coroutineScope {
                launch { refreshInBackground(stationId) }
                emitAll(productDao.observeProducts(stationId).map { list -> list.map { it.toDomain() } })
            }
        }

    override fun observeAvailableProducts(stationId: Long): Flow<List<Product>> =
        flow {
            coroutineScope {
                launch { refreshInBackground(stationId) }
                emitAll(productDao.observeAvailableProducts(stationId).map { list -> list.map { it.toDomain() } })
            }
        }

    override suspend fun getProductById(productId: Long): Product? {
        val cached = productDao.getProductById(productId)?.toDomain() ?: return null
        return cached
    }

    override suspend fun insertProduct(product: Product): Long {
        val updated = product.copy(updatedAt = System.currentTimeMillis())
        val legacyId = UUID.randomUUID().toString()
        var remoteSynced = false
        var resolvedRemoteId: String? = null
        // Product catalog data never owns mutable stock. The opening balance is
        // recorded in Supabase's inventory ledger after the product exists.
        val openingBalance = if (SupabaseClientProvider.client.auth.currentUserOrNull() != null) {
            runCatching {
                val stationRemoteId = requireStationRemoteId(updated.stationId)
                val remoteProduct = supabaseCatalog.upsertProduct(stationRemoteId, updated, legacyId, includeStock = false)
                resolvedRemoteId = remoteProduct.id
                if (updated.stockQuantity > 0) {
                    supabaseInventory.setQuantity(
                        stationId = stationRemoteId,
                        productId = remoteProduct.id,
                        quantity = updated.stockQuantity,
                        expectedVersion = 0L,
                        operationId = "product:$legacyId:opening",
                        reason = "Initial product stock"
                    )
                } else null
            }.fold(
                onSuccess = { balance ->
                    remoteSynced = true
                    balance
                },
                onFailure = { error ->
                    if (error.isConnectivityFailure()) null else throw error
                }
            )
        } else null
        val id = database.withTransaction {
            val localId = productDao.insertProduct(
                updated.copy(
                    stockQuantity = openingBalance?.quantity ?: updated.stockQuantity,
                    inventoryVersion = openingBalance?.version ?: 0L
                ).toEntity()
            )
            recordMetadata(localId, resolvedRemoteId ?: legacyId, updated.stationId, updated.updatedAt)
            localId
        }
        if (!remoteSynced && updated.stockQuantity > 0) {
            syncQueue.enqueue(
                SyncResourceType.PRODUCT,
                id,
                updated.stationId,
                SyncOperation.CREATE,
                updated.updatedAt
            )
            syncQueue.enqueue(
                SyncResourceType.PRODUCT,
                id,
                updated.stationId,
                SyncOperation.STOCK_ADJUSTMENT,
                updated.updatedAt,
                stockTarget = updated.stockQuantity,
                expectedInventoryVersion = 0L
            )
        } else if (!remoteSynced) {
            syncQueue.enqueue(SyncResourceType.PRODUCT, id, updated.stationId, SyncOperation.CREATE, updated.updatedAt)
        }
        return id
    }

    override suspend fun updateProduct(product: Product) {
        val existing = productDao.getProductById(product.id)
        val updated = product.copy(updatedAt = System.currentTimeMillis())
        val legacyId = syncDao.getMetadata(SyncResourceType.PRODUCT.name, updated.id)?.remoteId
            ?: UUID.randomUUID().toString()
        // Stock is intentionally excluded from catalog writes. Room's stock
        // field is only a compatibility cache populated from inventory_balances.
        var resolvedRemoteId: String? = null
        val remoteSynced = if (SupabaseClientProvider.client.auth.currentUserOrNull() != null) {
            runCatching {
                supabaseCatalog.upsertProduct(
                    requireStationRemoteId(updated.stationId),
                    updated,
                    legacyId,
                    includeStock = false
                )
            }.fold(onSuccess = { remote -> resolvedRemoteId = remote.id; true }, onFailure = { error ->
                if (error.isConnectivityFailure()) false else throw error
            })
        } else false
        database.withTransaction {
            productDao.updateProduct(updated.copy(stockQuantity = existing?.stockQuantity ?: updated.stockQuantity).toEntity())
            recordMetadata(updated.id, resolvedRemoteId ?: legacyId, updated.stationId, updated.updatedAt)
        }
        if (!remoteSynced) {
            syncQueue.enqueue(SyncResourceType.PRODUCT, updated.id, updated.stationId, SyncOperation.UPDATE, updated.updatedAt)
        }
    }

    override suspend fun toggleAvailability(productId: Long, isAvailable: Boolean) = database.withTransaction {
        val row = productDao.getProductById(productId) ?: return@withTransaction
        val now = System.currentTimeMillis()
        val updated = row.toDomain().copy(isAvailable = isAvailable, updatedAt = now)
        val legacyId = syncDao.getMetadata(SyncResourceType.PRODUCT.name, productId)?.remoteId ?: UUID.randomUUID().toString()
        var resolvedRemoteId: String? = null
        val remoteSynced = if (SupabaseClientProvider.client.auth.currentUserOrNull() != null) {
            runCatching {
                supabaseCatalog.upsertProduct(
                    requireStationRemoteId(row.stationId),
                    updated,
                    legacyId,
                    includeStock = false
                )
            }.fold(onSuccess = { remote -> resolvedRemoteId = remote.id; true }, onFailure = { error ->
                if (error.isConnectivityFailure()) false else throw error
            })
        } else false
        productDao.updateProduct(row.copy(isAvailable = isAvailable, updatedAt = now))
        recordMetadata(productId, resolvedRemoteId ?: legacyId, row.stationId, now)
        if (!remoteSynced) {
            syncQueue.enqueue(SyncResourceType.PRODUCT, productId, row.stationId, SyncOperation.UPDATE, now)
        }
    }

    override suspend fun archive(productId: Long) = database.withTransaction {
        val row = productDao.getProductById(productId) ?: return@withTransaction
        if (!row.isActive) return@withTransaction
        val now = System.currentTimeMillis()
        val legacyId = syncDao.getMetadata(SyncResourceType.PRODUCT.name, productId)?.remoteId
        val remoteSynced = if (legacyId != null && SupabaseClientProvider.client.auth.currentUserOrNull() != null) {
            runCatching { supabaseCatalog.archiveProduct(legacyId) }
                .fold(onSuccess = { true }, onFailure = { error ->
                    if (error.isConnectivityFailure()) false else throw error
                })
        } else false
        productDao.archive(productId, now)
        if (!remoteSynced) {
            syncQueue.enqueue(SyncResourceType.PRODUCT, productId, row.stationId, SyncOperation.ARCHIVE, now)
        }
    }

    override suspend fun updateStock(productId: Long, newStock: Int) = database.withTransaction {
        require(newStock >= 0) { "Stock cannot be negative" }
        val row = productDao.getProductById(productId) ?: return@withTransaction
        if (newStock == row.stockQuantity) return@withTransaction
        val now = System.currentTimeMillis()
        val legacyId = syncDao.getMetadata(SyncResourceType.PRODUCT.name, productId)?.remoteId
            ?: throw WorkspaceNotProvisionedException()
        val operationId = "stock:${UUID.randomUUID()}"
        if (SupabaseClientProvider.client.auth.currentUserOrNull() == null) {
            throw WorkspaceNotProvisionedException()
        }

        val balance = try {
            val stationRemoteId = requireStationRemoteId(row.stationId)
            val remoteProductId = requireNotNull(supabaseCatalog.findProductIdByLegacyId(legacyId)) {
                "Product is not provisioned in Supabase"
            }
            supabaseInventory.setQuantity(
                stationId = stationRemoteId,
                productId = remoteProductId,
                quantity = newStock,
                expectedVersion = row.inventoryVersion,
                operationId = operationId,
                reason = "Manual stock count"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!error.isConnectivityFailure()) throw error
            productDao.updateProduct(row.copy(stockQuantity = newStock, updatedAt = now))
            syncQueue.enqueue(
                resourceType = SyncResourceType.PRODUCT,
                localId = productId,
                stationLocalId = row.stationId,
                operation = SyncOperation.STOCK_ADJUSTMENT,
                localUpdatedAt = now,
                stockTarget = newStock,
                expectedInventoryVersion = row.inventoryVersion,
                stableOperationId = operationId
            )
            return@withTransaction
        }
        productDao.updateProduct(row.copy(stockQuantity = balance.quantity, inventoryVersion = balance.version, updatedAt = now))
    }

    private suspend fun requireStationRemoteId(stationId: Long): String {
        val station = requireNotNull(stationDao.getStation(stationId)) { "Station does not exist" }
        return supabaseCatalog.findStationId(station.name, station.address)
            ?: throw WorkspaceNotProvisionedException()
    }

    private suspend fun recordMetadata(localId: Long, remoteId: String, stationId: Long, updatedAt: Long) {
        syncDao.upsertMetadata(
            SyncMetadataEntity(
                resourceType = SyncResourceType.PRODUCT.name,
                localId = localId,
                remoteId = remoteId,
                stationLocalId = stationId,
                lastLocalUpdatedAt = updatedAt,
                lastSyncedAt = updatedAt
            )
        )
    }

    private suspend fun refreshFromSupabase(stationId: Long) {
        val stationRemoteId = requireStationRemoteId(stationId)
        val rows = supabaseCatalog.products(stationRemoteId)
        val balances = supabaseInventory.balances(stationRemoteId).associateBy { it.productId }
        database.withTransaction {
            rows.forEach { row ->
                val legacyId = row.legacyId ?: row.id
                val existing = syncDao.getMetadataByRemoteId(SyncResourceType.PRODUCT.name, legacyId)
                    ?: syncDao.getMetadataByRemoteId(SyncResourceType.PRODUCT.name, row.id)
                val existingProduct = existing?.let { productDao.getProductById(it.localId) }
                if (existing != null && syncDao.hasOutstandingMutation(
                        SyncResourceType.PRODUCT.name,
                        existing.localId
                    ) > 0
                ) {
                    return@forEach
                }
                val localId = productDao.insertProduct(
                    ProductEntity(
                        id = existing?.localId ?: 0,
                        stationId = stationId,
                        name = row.name,
                        type = row.productType,
                        sizeLabel = row.sizeLabel,
                        price = row.priceCentavos / 100.0,
                        // Legacy stock is only a migration reader fallback for
                        // products whose opening balance has not been imported.
                        stockQuantity = balances[row.id]?.quantity ?: existingProduct?.stockQuantity ?: 0,
                        imagePath = row.imagePath,
                        isAvailable = row.isAvailable,
                        createdAt = row.createdAt?.toMillisOrNull() ?: System.currentTimeMillis(),
                        updatedAt = row.updatedAt?.toMillisOrNull() ?: System.currentTimeMillis(),
                        isActive = row.isActive,
                        promotionLabel = row.promotionLabel,
                        promotionType = row.promotionType,
                        promotionPercentBps = row.promotionPercentBps,
                        promotionalPrice = row.promotionalPriceCentavos?.div(100.0),
                        promotionMinimumQuantity = row.promotionMinimumQuantity,
                        promotionStartsAt = row.promotionStartsAt,
                        promotionEndsAt = row.promotionEndsAt,
                        promotionIsActive = row.promotionIsActive,
                        inventoryVersion = balances[row.id]?.version ?: existingProduct?.inventoryVersion ?: 0L
                    )
                )
                recordMetadata(localId, row.id, stationId, System.currentTimeMillis())
            }
        }
    }

    private suspend fun refreshInBackground(stationId: Long) {
        try {
            refreshFromSupabase(stationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cached products remain available while offline or on a slow link.
        }
    }

    private fun String.toMillisOrNull(): Long? = runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()
}

private fun Throwable.isConnectivityFailure(): Boolean = generateSequence(this) { it.cause }
    .any {
        it is java.io.IOException ||
            it is java.net.ConnectException ||
            it is java.net.UnknownHostException ||
            it is java.net.SocketTimeoutException ||
            it is java.nio.channels.UnresolvedAddressException
    }
