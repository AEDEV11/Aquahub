package com.aesprt.aquahub.data.repository

import com.aesprt.aquahub.data.local.dao.CustomerDao
import com.aesprt.aquahub.data.local.dao.StationDao
import com.aesprt.aquahub.data.local.dao.SyncDao
import com.aesprt.aquahub.data.local.entity.CustomerEntity
import com.aesprt.aquahub.data.local.entity.CustomerAddressEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.local.mapper.toEntity
import com.aesprt.aquahub.data.supabase.catalog.SupabaseCatalogDataSource
import com.aesprt.aquahub.data.supabase.catalog.SupabaseCustomerRow
import com.aesprt.aquahub.data.supabase.catalog.SupabaseCustomerAddressRow
import com.aesprt.aquahub.domain.model.Customer
import com.aesprt.aquahub.domain.model.CustomerAddress
import com.aesprt.aquahub.domain.repository.CustomerRepository
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
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.nio.channels.UnresolvedAddressException
import com.aesprt.aquahub.domain.repository.WorkspaceNotProvisionedException

class RoomCustomerRepository(
    private val customerDao: CustomerDao,
    private val database: AquaHubDatabase,
    private val syncQueue: SyncQueueWriter,
    private val syncDao: SyncDao,
    private val stationDao: StationDao,
    private val supabaseCatalog: SupabaseCatalogDataSource
) : CustomerRepository {

    override fun observeCustomers(stationId: Long): Flow<List<Customer>> =
        flow {
            coroutineScope {
                launch { refreshInBackground(stationId) }
                emitAll(customerDao.observeCustomers(stationId).map { list -> list.map { it.toDomain() } })
            }
        }

    override fun searchCustomers(stationId: Long, query: String): Flow<List<Customer>> =
        flow {
            coroutineScope {
                launch { refreshInBackground(stationId) }
                emitAll(customerDao.searchCustomers(stationId, query).map { list -> list.map { it.toDomain() } })
            }
        }

    override suspend fun getCustomerById(customerId: Long): Customer? {
        val cached = customerDao.getCustomerById(customerId)?.toDomain() ?: return null
        return cached
    }

    override suspend fun insertCustomer(customer: Customer): Long {
        val normalizedPhone = customer.phone.normalizeCustomerPhone()
        if (normalizedPhone.isNotEmpty()) {
            customerDao.getCustomersForStation(customer.stationId)
                .firstOrNull { it.phone.normalizeCustomerPhone() == normalizedPhone }
                ?.let { existing ->
                    // A retry/offline double-tap must reuse the existing local
                    // identity instead of creating another customer record.
                    return existing.id
                }
        }
        val stationRemoteId = resolveStationRemoteIdForWrite(customer.stationId)
        val now = System.currentTimeMillis()
        val local = customer.copy(updatedAt = now)
        val legacyId = UUID.randomUUID().toString()
        val remote = stationRemoteId?.let {
            runCatching { supabaseCatalog.upsertCustomer(it, local, legacyId) }
                .getOrElse { error -> if (error.isConnectivityFailure()) null else throw error }
        }
        val (id, inserted) = database.withTransaction {
            val existing = normalizedPhone.takeIf(String::isNotEmpty)?.let { phone ->
                customerDao.getCustomersForStation(customer.stationId)
                    .firstOrNull { it.phone.normalizeCustomerPhone() == phone }
            }
            if (existing != null) {
                recordMetadata(SyncResourceType.CUSTOMER, existing.id, remote?.id ?: legacyId, customer.stationId, now)
                existing.id to false
            } else {
                val localId = customerDao.insertCustomer(local.toEntity())
                recordMetadata(SyncResourceType.CUSTOMER, localId, remote?.id ?: legacyId, customer.stationId, now)
                localId to true
            }
        }
        if (!inserted) return id
        local.addresses.forEach { address ->
            val addressLegacyId = UUID.randomUUID().toString()
            var remoteAddress: SupabaseCustomerAddressRow? = null
            val addressId = database.withTransaction {
                val localAddressId = customerDao.insertAddress(address.copy(customerId = id).toEntity())
                recordMetadata(SyncResourceType.CUSTOMER_ADDRESS, localAddressId, addressLegacyId, customer.stationId, now)
                localAddressId
            }
            var addressRemoteSynced = false
            if (stationRemoteId != null && remote != null) {
                runCatching {
                    supabaseCatalog.upsertAddress(stationRemoteId, remote.id, address, addressLegacyId)
                }.fold(
                    onSuccess = { row -> remoteAddress = row; addressRemoteSynced = true },
                    onFailure = { error -> if (!error.isConnectivityFailure()) throw error }
                )
            }
            remoteAddress?.id?.let { remoteAddressId ->
                database.withTransaction {
                    recordMetadata(SyncResourceType.CUSTOMER_ADDRESS, addressId, remoteAddressId, customer.stationId, now)
                }
            }
            if (!addressRemoteSynced) {
                syncQueue.enqueue(SyncResourceType.CUSTOMER_ADDRESS, addressId, customer.stationId, SyncOperation.CREATE, now)
            }
        }
        if (remote == null) {
            syncQueue.enqueue(SyncResourceType.CUSTOMER, id, customer.stationId, SyncOperation.CREATE, now)
        }
        return id
    }

    override suspend fun updateCustomer(customer: Customer) {
        val stationRemoteId = resolveStationRemoteIdForWrite(customer.stationId)
        val updated = customer.copy(updatedAt = System.currentTimeMillis())
        val legacyId = syncDao.getMetadata(SyncResourceType.CUSTOMER.name, customer.id)?.remoteId
            ?: UUID.randomUUID().toString()
        var remoteSynced = false
        var resolvedRemoteId: String? = null
        if (stationRemoteId != null) {
            runCatching { supabaseCatalog.upsertCustomer(stationRemoteId, updated, legacyId) }
                .fold(
                    onSuccess = { row -> resolvedRemoteId = row.id; remoteSynced = true },
                    onFailure = { error -> if (!error.isConnectivityFailure()) throw error }
                )
        }
        database.withTransaction {
            customerDao.updateCustomer(updated.toEntity())
            recordMetadata(SyncResourceType.CUSTOMER, updated.id, resolvedRemoteId ?: legacyId, updated.stationId, updated.updatedAt)
        }
        if (!remoteSynced) {
            syncQueue.enqueue(SyncResourceType.CUSTOMER, updated.id, updated.stationId, SyncOperation.UPDATE, updated.updatedAt)
        }
    }

    override suspend fun deleteCustomer(customerId: Long) {
        val row = customerDao.getCustomerById(customerId)
        val stationId = row?.customer?.stationId
        var customerRemoteSynced = true
        val addressRemoteSynced = mutableMapOf<Long, Boolean>()
        if (row != null) {
            val customerLegacyId = syncDao.getMetadata(SyncResourceType.CUSTOMER.name, customerId)?.remoteId
            if (customerLegacyId != null) {
                customerRemoteSynced = false
                runCatching { supabaseCatalog.archiveCustomer(customerLegacyId) }
                    .fold(
                        onSuccess = { customerRemoteSynced = true },
                        onFailure = { error -> if (!error.isConnectivityFailure()) throw error }
                    )
            }
            row.addresses.forEach { address ->
                syncDao.getMetadata(SyncResourceType.CUSTOMER_ADDRESS.name, address.id)?.remoteId?.let {
                    addressRemoteSynced[address.id] = false
                    runCatching { supabaseCatalog.archiveAddress(it) }
                        .fold(
                            onSuccess = { addressRemoteSynced[address.id] = true },
                            onFailure = { error -> if (!error.isConnectivityFailure()) throw error }
                        )
                }
            }
        }
        database.withTransaction {
            if (!customerRemoteSynced) {
                syncQueue.enqueue(SyncResourceType.CUSTOMER, customerId, stationId, SyncOperation.ARCHIVE)
            }
            row?.addresses?.forEach { address ->
                if (addressRemoteSynced[address.id] == false) {
                    syncQueue.enqueue(SyncResourceType.CUSTOMER_ADDRESS, address.id, stationId, SyncOperation.ARCHIVE)
                }
            }
            customerDao.deleteCustomer(customerId)
        }
    }

    override suspend fun insertAddress(address: CustomerAddress): Long {
        val stationId = customerDao.getCustomerById(address.customerId)?.customer?.stationId
            ?: error("Customer does not exist")
        val stationRemoteId = resolveStationRemoteIdForWrite(stationId)
        val customerLegacyId = syncDao.getMetadata(SyncResourceType.CUSTOMER.name, address.customerId)?.remoteId
            ?: error("Customer has no remote mapping")
        val legacyId = UUID.randomUUID().toString()
        var remoteSynced = false
        var resolvedRemoteId: String? = null
        if (stationRemoteId != null) {
            val customerRemoteId = requireNotNull(supabaseCatalog.resolveCustomerId(customerLegacyId)) {
                "Customer is not provisioned in Supabase"
            }
            runCatching { supabaseCatalog.upsertAddress(stationRemoteId, customerRemoteId, address, legacyId) }
                .fold(
                    onSuccess = { row -> resolvedRemoteId = row.id; remoteSynced = true },
                    onFailure = { error -> if (!error.isConnectivityFailure()) throw error }
                )
        }
        val id = database.withTransaction {
            val localId = customerDao.insertAddress(address.toEntity())
            recordMetadata(SyncResourceType.CUSTOMER_ADDRESS, localId, legacyId, stationId, System.currentTimeMillis())
            localId
        }
        if (!remoteSynced) {
            syncQueue.enqueue(SyncResourceType.CUSTOMER_ADDRESS, id, stationId, SyncOperation.CREATE)
        }
        return id
    }

    override suspend fun updateAddress(address: CustomerAddress) {
        val stationId = customerDao.getCustomerById(address.customerId)?.customer?.stationId ?: error("Customer does not exist")
        val stationRemoteId = resolveStationRemoteIdForWrite(stationId)
        val customerLegacyId = syncDao.getMetadata(SyncResourceType.CUSTOMER.name, address.customerId)?.remoteId
            ?: error("Customer has no remote mapping")
        val legacyId = syncDao.getMetadata(SyncResourceType.CUSTOMER_ADDRESS.name, address.id)?.remoteId
            ?: UUID.randomUUID().toString()
        var remoteSynced = false
        var resolvedRemoteId: String? = null
        if (stationRemoteId != null) {
            val customerRemoteId = requireNotNull(supabaseCatalog.resolveCustomerId(customerLegacyId)) {
                "Customer is not provisioned in Supabase"
            }
            runCatching { supabaseCatalog.upsertAddress(stationRemoteId, customerRemoteId, address, legacyId) }
                .fold(
                    onSuccess = { row -> resolvedRemoteId = row.id; remoteSynced = true },
                    onFailure = { error -> if (!error.isConnectivityFailure()) throw error }
                )
        }
        database.withTransaction {
            customerDao.updateAddress(address.toEntity())
            recordMetadata(SyncResourceType.CUSTOMER_ADDRESS, address.id, resolvedRemoteId ?: legacyId, stationId, System.currentTimeMillis())
        }
        if (!remoteSynced) {
            syncQueue.enqueue(SyncResourceType.CUSTOMER_ADDRESS, address.id, stationId, SyncOperation.UPDATE)
        }
    }

    override suspend fun deleteAddress(addressId: Long) = database.withTransaction {
        val address = customerDao.getAddressById(addressId)
        val stationId = address?.let { customerDao.getCustomerById(it.customerId)?.customer?.stationId }
        val remoteId = syncDao.getMetadata(SyncResourceType.CUSTOMER_ADDRESS.name, addressId)?.remoteId
        var remoteSynced = remoteId == null
        if (remoteId != null) {
            runCatching { supabaseCatalog.archiveAddress(remoteId) }
                .fold(
                    onSuccess = { remoteSynced = true },
                    onFailure = { error -> if (!error.isConnectivityFailure()) throw error }
                )
        }
        if (!remoteSynced) {
            syncQueue.enqueue(SyncResourceType.CUSTOMER_ADDRESS, addressId, stationId, SyncOperation.ARCHIVE)
        }
        customerDao.deleteAddress(addressId)
    }

    override suspend fun incrementOrderCount(customerId: Long) = database.withTransaction {
        // Order counts describe fulfillment activity; spending is maintained
        // separately from paid receipts in Room and Supabase.
        val now = System.currentTimeMillis()
        customerDao.incrementOrderCount(customerId, now)
        val customer = customerDao.getCustomerById(customerId)?.customer
        syncQueue.enqueue(SyncResourceType.CUSTOMER, customerId, customer?.stationId, SyncOperation.UPDATE, now)
        Unit
    }

    private suspend fun requireStationRemoteId(stationId: Long): String {
        val station = requireNotNull(stationDao.getStation(stationId)) { "Station does not exist" }
        return supabaseCatalog.findStationId(station.name, station.address)
            ?: throw WorkspaceNotProvisionedException()
    }

    private suspend fun resolveStationRemoteIdForWrite(stationId: Long): String? {
        val cachedRemoteId = syncDao.getMetadata(SyncResourceType.STATION.name, stationId)?.remoteId
        return runCatching {
            val station = requireNotNull(stationDao.getStation(stationId)) { "Station does not exist" }
            // Never trust a cached station UUID across sessions. A Room cache can
            // outlive the Supabase user who created it, while RLS correctly
            // rejects writes for another tenant. Re-resolve through the current
            // authenticated session whenever the network is available.
            supabaseCatalog.findStationId(station.name, station.address)
                ?: throw WorkspaceNotProvisionedException()
        }.getOrElse { error ->
            if (error.isConnectivityFailure()) cachedRemoteId else throw error
        }
    }

    private suspend fun recordMetadata(
        type: SyncResourceType,
        localId: Long,
        remoteId: String,
        stationId: Long,
        updatedAt: Long
    ) {
        syncDao.upsertMetadata(
            SyncMetadataEntity(
                resourceType = type.name,
                localId = localId,
                remoteId = remoteId,
                stationLocalId = stationId,
                localVersion = 1,
                lastLocalUpdatedAt = updatedAt,
                lastSyncedAt = updatedAt
            )
        )
    }

    private suspend fun refreshFromSupabase(stationId: Long) {
        val stationRemoteId = requireStationRemoteId(stationId)
        val customers = supabaseCatalog.customers(stationRemoteId)
        val addresses = supabaseCatalog.customerAddresses(stationRemoteId)
        database.withTransaction {
            // Supabase is the primary catalog. Once the remote read succeeds,
            // reconcile mapped Room rows that are no longer active remotely.
            // If the read fails, this transaction is never entered and the
            // cached data remains available for the offline-read path.
            val activeRemoteIds = customers
                .flatMap { row -> listOfNotNull(row.id, row.legacyId) }
                .toSet()
            syncDao.getMetadataForStation(SyncResourceType.CUSTOMER.name, stationId)
                .filterNot { it.remoteId in activeRemoteIds }
                .forEach { metadata ->
                    if (syncDao.hasOutstandingMutation(SyncResourceType.CUSTOMER.name, metadata.localId) > 0) {
                        return@forEach
                    }
                    customerDao.deleteAddressesForCustomer(metadata.localId)
                    customerDao.deleteCustomer(metadata.localId)
                    syncDao.deleteQueueForResource(SyncResourceType.CUSTOMER.name, metadata.localId)
                    syncDao.deleteMetadataForResource(SyncResourceType.CUSTOMER.name, metadata.localId)
                }
            val customerLocalIds = mutableMapOf<String, Long>()
            val existingLocalCustomers = customerDao.getCustomersForStation(stationId)
            val localIdsByPhone = existingLocalCustomers
                .sortedBy { it.id }
                .mapNotNull { entity ->
                    entity.phone.normalizeCustomerPhone()
                        .takeIf { it.isNotEmpty() }
                        ?.let { it to entity.id }
                }
                .distinctBy { it.first }
                .toMap()
            val remoteGroups = customers.groupBy { row ->
                row.phone.normalizeCustomerPhone().ifBlank { "remote:${row.id}" }
            }
            val remoteIdToCanonicalId = mutableMapOf<String, String>()
            remoteGroups.values.forEach { rows ->
                val row = rows.minWithOrNull(compareBy<SupabaseCustomerRow>({ it.createdAt.toMillisOrNull() ?: Long.MAX_VALUE }, { it.id }))
                    ?: return@forEach
                val legacyId = row.legacyId ?: row.id
                val existing = syncDao.getMetadataByRemoteId(SyncResourceType.CUSTOMER.name, legacyId)
                    ?: syncDao.getMetadataByRemoteId(SyncResourceType.CUSTOMER.name, row.id)
                val phoneMatch = row.phone.normalizeCustomerPhone().takeIf { it.isNotEmpty() }?.let(localIdsByPhone::get)
                val existingLocalId = phoneMatch ?: existing?.localId ?: 0L
                val hasLocalPendingWrite = existingLocalId > 0 &&
                    syncDao.hasOutstandingMutation(SyncResourceType.CUSTOMER.name, existingLocalId) > 0
                val localId = if (hasLocalPendingWrite) {
                    existingLocalId
                } else customerDao.insertCustomer(
                    CustomerEntity(
                        // Phone identity wins over a stale remote-id mapping so
                        // differing local E.164/prefix formats still reconcile.
                        id = existingLocalId,
                        stationId = stationId,
                        name = row.name,
                        phone = row.phone,
                        email = row.email,
                        notes = row.notes,
                        totalOrders = row.totalOrders,
                        totalSpent = row.totalSpentCentavos / 100.0,
                        createdAt = row.createdAt.toMillisOrNull() ?: System.currentTimeMillis(),
                        updatedAt = row.updatedAt.toMillisOrNull() ?: System.currentTimeMillis()
                    )
                )
                rows.forEach { remoteRow ->
                    remoteIdToCanonicalId[remoteRow.id] = row.id
                    remoteRow.legacyId?.let { remoteIdToCanonicalId[it] = row.id }
                    customerLocalIds[remoteRow.id] = localId
                    remoteRow.legacyId?.let { customerLocalIds[it] = localId }
                }
                if (!hasLocalPendingWrite) {
                    recordMetadata(SyncResourceType.CUSTOMER, localId, row.id, stationId, System.currentTimeMillis())
                }
            }
            existingLocalCustomers.forEach { duplicate ->
                val phone = duplicate.phone.normalizeCustomerPhone()
                val canonicalId = phone.takeIf { it.isNotEmpty() }?.let { localIdsByPhone[it] }
                if (canonicalId != null && canonicalId != duplicate.id &&
                    syncDao.hasOutstandingMutation(SyncResourceType.CUSTOMER.name, duplicate.id) == 0
                ) {
                    customerDao.reassignOrderCustomer(duplicate.id, canonicalId)
                    customerDao.reassignContainerTransactionCustomer(duplicate.id, canonicalId)
                    customerDao.reassignCustomerAddresses(duplicate.id, canonicalId)
                    customerDao.deleteCustomer(duplicate.id)
                    syncDao.deleteQueueForResource(SyncResourceType.CUSTOMER.name, duplicate.id)
                    syncDao.deleteMetadataForResource(SyncResourceType.CUSTOMER.name, duplicate.id)
                }
            }
            addresses.forEach { row ->
                val customerLegacyId = remoteIdToCanonicalId[row.customerId] ?: row.customerId
                val customerId = customerLocalIds[customerLegacyId] ?: return@forEach
                val legacyId = row.legacyId ?: row.id
                val existing = syncDao.getMetadataByRemoteId(SyncResourceType.CUSTOMER_ADDRESS.name, legacyId)
                    ?: syncDao.getMetadataByRemoteId(SyncResourceType.CUSTOMER_ADDRESS.name, row.id)
                val localId = customerDao.insertAddress(
                    CustomerAddressEntity(
                        id = existing?.localId ?: 0,
                        customerId = customerId,
                        label = row.label,
                        addressLine = row.addressLine,
                        barangay = row.barangay,
                        city = row.city,
                        province = row.province,
                        latitude = row.latitude,
                        longitude = row.longitude,
                        isDefault = row.isDefault
                    )
                )
                recordMetadata(SyncResourceType.CUSTOMER_ADDRESS, localId, row.id, stationId, System.currentTimeMillis())
            }
        }
    }

    private fun String?.toMillisOrNull(): Long? = this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private suspend fun refreshInBackground(stationId: Long) {
        try {
            refreshFromSupabase(stationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Room remains the immediate read path while an offline/slow remote
            // refresh is retried on the next observation or sync run.
        }
    }
}

internal fun String?.normalizeCustomerPhone(): String =
    this.orEmpty().filter(Char::isDigit).let { digits ->
        when {
            digits.startsWith("63") -> digits.drop(2)
            digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
    }

private fun Throwable.isConnectivityFailure(): Boolean = generateSequence(this) { it.cause }
    .any {
        it is IOException || it is UnknownHostException || it is ConnectException ||
            it is SocketTimeoutException || it is UnresolvedAddressException
    }
