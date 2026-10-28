package com.aesprt.aquahub.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.core.common.Constants
import com.aesprt.aquahub.core.database.AquaHubDatabase
import com.aesprt.aquahub.data.firebase.messaging.AquaHubMessagingService
import com.aesprt.aquahub.data.local.dao.OwnerDao
import com.aesprt.aquahub.data.local.entity.OwnerEntity
import com.aesprt.aquahub.data.local.entity.StationEntity
import com.aesprt.aquahub.data.local.entity.SyncMetadataEntity
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.data.local.seeder.DatabaseSeeder
import com.aesprt.aquahub.data.observability.AuthTelemetry
import com.aesprt.aquahub.data.supabase.SupabaseClientProvider
import com.aesprt.aquahub.data.supabase.account.SupabaseAccountDataSource
import com.aesprt.aquahub.data.supabase.account.SupabaseAccountProfile
import com.aesprt.aquahub.data.supabase.account.SupabaseStationRow
import com.aesprt.aquahub.data.supabase.auth.SupabaseAuthDataSource
import com.aesprt.aquahub.data.supabase.auth.ReauthenticationIdentityMismatchException
import com.aesprt.aquahub.data.supabase.marketing.SupabaseMarketingPreferencesDataSource
import com.aesprt.aquahub.data.supabase.auth.SupabaseProfileRecord
import com.aesprt.aquahub.data.supabase.workspace.SupabaseWorkspaceDataSource
import com.aesprt.aquahub.domain.model.Owner
import com.aesprt.aquahub.domain.model.OwnerRole
import com.aesprt.aquahub.domain.repository.AuthRepository
import com.aesprt.aquahub.domain.repository.GoogleAccountNeedsSetupException
import com.aesprt.aquahub.domain.repository.LocalAccountSwitchConfirmationRequiredException
import com.aesprt.aquahub.domain.usecase.auth.RegistrationInput
import com.aesprt.aquahub.sync.AccountScopeAction
import com.aesprt.aquahub.sync.LocalAccountScopeManager
import com.aesprt.aquahub.sync.LocalAccountScopeMismatchException
import com.aesprt.aquahub.sync.decideAccountScopeAction
import com.aesprt.aquahub.sync.SyncResourceType
import com.aesprt.aquahub.sync.SyncScheduler
import com.aesprt.aquahub.sync.isVerifiedLegacyOwnerMapping
import com.aesprt.aquahub.sync.shouldClearLocalAccountCacheAfterLogout
import com.aesprt.aquahub.sync.countOutstandingLocalOperations
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Instant
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseAuthRepository(
    private val ownerDao: OwnerDao,
    private val dataStore: DataStore<Preferences>,
    private val authDataSource: SupabaseAuthDataSource,
    private val accountDataSource: SupabaseAccountDataSource,
    private val authTelemetry: AuthTelemetry,
    private val scheduler: SyncScheduler,
    private val database: AquaHubDatabase,
    private val databaseSeeder: DatabaseSeeder,
    private val appContext: Context,
    private val workspaceDataSource: SupabaseWorkspaceDataSource,
    private val localAccountScope: LocalAccountScopeManager,
    private val marketingPreferencesDataSource: SupabaseMarketingPreferencesDataSource
) : AuthRepository {
    private val keyIsLoggedIn = booleanPreferencesKey(Constants.DS_KEY_IS_LOGGED_IN)
    private val keyOwnerId = longPreferencesKey(Constants.DS_KEY_OWNER_ID)
    private val keyStationId = longPreferencesKey(Constants.DS_KEY_STATION_ID)
    private val keyOwnerName = stringPreferencesKey(Constants.DS_KEY_OWNER_NAME)
    private val keyHasCompletedOnboarding = booleanPreferencesKey(Constants.DS_KEY_HAS_COMPLETED_ONBOARDING)
    private var deletionReauthenticationUid: String? = null

    override suspend fun prepareForRegistration(): Result<Unit> = withContext(Dispatchers.IO) {
        localAccountScope.withExclusive {
            val currentUid = authDataSource.currentUserId
            val hasMatchingOwner = currentUid != null && ownerDao.getOwnerByFirebaseUid(currentUid) != null
            if (!hasMatchingOwner) {
                val syncDao = database.syncDao()
                val outstandingCount = syncDao.getOutstandingCount()
                if (outstandingCount > 0) {
                    return@withExclusive Result.failure(
                        AppError.ValidationError(
                            "This device has $outstandingCount preserved offline change${if (outstandingCount == 1) "" else "s"} from the previous account. They will not be replayed automatically. Sign in to the original account or complete an explicit recovery before registering another account."
                        )
                    )
                }
                if (syncDao.hasLocalBusinessData()) {
                    return@withExclusive Result.failure(
                        AppError.ValidationError(
                            "This device contains existing local business data. It is being preserved and will not be cleared or assigned to a new owner automatically. Use a clean QA installation or recover the original account first."
                        )
                    )
                }
                clearLocalAccountCache()
            }
            Result.success(Unit)
        }
    }

    override suspend fun loginWithEmail(email: String, password: String): Result<Owner> = withContext(Dispatchers.IO) {
        runCatching {
            localAccountScope.withExclusive {
                authDataSource.signInWithEmail(email, password)
                val uid = requireNotNull(authDataSource.currentUserId)
                val profile = authDataSource.loadCurrentProfile()
                reconcileLocalAccountScope(uid, profile?.firebaseUid)
                val identity = requireNotNull(authDataSource.currentIdentity()) { "Supabase session could not be restored." }
                recordLogin(uid, "password")
                databaseSeeder.seedPlansIfEmpty()
                val owner = establishLocalOwner(uid, phoneFallback = identity.phone.orEmpty())
                storeSession(owner)
                recoverLegacyOutboxForVerifiedOwner(uid, owner)
                AquaHubMessagingService.registerCurrentDeviceToken(appContext, uid)
                scheduler.enqueueImmediate()
                owner.toDomain()
            }
        }.recoverCatching { error ->
            throw if (error is AppError || error is LocalAccountSwitchConfirmationRequiredException) error
            else AppError.AuthenticationError(error.message ?: "Supabase authentication failed")
        }
    }

    override suspend fun restoreCurrentSession(): Result<Owner> = withContext(Dispatchers.IO) {
        runCatching {
            localAccountScope.withExclusive {
                check(authDataSource.restoreSession()) { "Authentication session could not be restored." }
                val uid = requireNotNull(authDataSource.currentUserId)
                val profile = try {
                    authDataSource.loadCurrentProfile()
                } catch (error: Exception) {
                    if (error is CancellationException || !error.isOfflineTransportFailure()) throw error
                    null
                }
                reconcileLocalAccountScope(uid, profile?.firebaseUid)
                val owner = try {
                    establishLocalOwner(uid, authDataSource.currentUserPhone.orEmpty())
                } catch (error: Exception) {
                    if (!error.isOfflineTransportFailure()) throw error
                    val cached = ownerDao.getOwnerByFirebaseUid(uid) ?: throw error
                    check(canUseCachedOwnerForOfflineRestore(
                        activeAuthUid = authDataSource.currentUserId,
                        expectedAuthUid = uid,
                        boundLocalUid = localAccountScope.ownerUid(),
                        cachedOwnerUid = cached.firebaseUid
                    )) {
                        "The cached AquaHub account is not bound to the authenticated Supabase user."
                    }
                    cached
                }
                storeSession(owner)
                recoverLegacyOutboxForVerifiedOwner(uid, owner)
                recordLogin(uid, "session_restore")
                AquaHubMessagingService.registerCurrentDeviceToken(appContext, uid)
                scheduler.enqueueImmediate()
                owner.toDomain()
            }
        }.recoverCatching { error ->
            throw if (error is AppError || error is LocalAccountSwitchConfirmationRequiredException) error
            else AppError.AuthenticationError(error.message ?: "Unable to restore your AquaHub account")
        }
    }

    override suspend fun loginWithGoogle(idToken: String): Result<Owner> = withContext(Dispatchers.IO) {
        runCatching {
            localAccountScope.withExclusive {
                val uid = authDataSource.signInWithGoogle(idToken)
                val profile = authDataSource.loadCurrentProfile()
                reconcileLocalAccountScope(uid, profile?.firebaseUid)
                val account = runCatching { accountDataSource.loadCurrent() }.getOrElse {
                    if (it.message == "AquaHub cloud profile is missing") throw GoogleAccountNeedsSetupException()
                    throw it
                }
                val owner = establishLocalOwner(uid, account.phone)
                storeSession(owner)
                recoverLegacyOutboxForVerifiedOwner(uid, owner)
                AquaHubMessagingService.registerCurrentDeviceToken(appContext, uid)
                scheduler.enqueueImmediate()
                owner.toDomain()
            }
        }.recoverCatching { error ->
            throw when (error) {
                is AppError, is GoogleAccountNeedsSetupException, is LocalAccountSwitchConfirmationRequiredException -> error
                else -> AppError.AuthenticationError(error.message ?: "Google authentication failed")
            }
        }
    }

    override suspend fun register(
        owner: Owner,
        password: String,
        station: com.aesprt.aquahub.domain.model.Station?,
        marketingConsent: Boolean
    ): Result<Owner> = withContext(Dispatchers.IO) {
        runCatching {
            localAccountScope.withExclusive {
                val uid = authDataSource.currentUserId ?: if (!owner.email.isNullOrBlank()) {
                    authDataSource.createEmailUser(
                        email = owner.email,
                        password = password,
                        displayName = owner.name,
                        phone = owner.phone
                    )
                } else {
                    throw IllegalStateException("Verify the phone number before continuing.")
                }
                val previousUid = localAccountScope.ownerUid()
                if (previousUid != null && previousUid != uid) {
                    val outstandingCount = database.syncDao().getOutstandingCount()
                    if (outstandingCount > 0) throw LocalAccountScopeMismatchException()
                    clearLocalAccountCache()
                }
                val existing = ownerDao.getOwnerByPhone(owner.phone)
                authDataSource.persistAccountProfile(owner.name, owner.phone)
                val account = if (station != null) {
                    accountDataSource.provisionOwner(owner, station)
                } else {
                    accountDataSource.loadCurrent()
                }
                if (marketingConsent) marketingPreferencesDataSource.setMyPreference(true)
                recordSignup(uid)
                val local = writeLocalOwner(existing, owner, account, uid)
                localAccountScope.bind(uid)
                storeSession(local)
                recoverLegacyOutboxForVerifiedOwner(uid, local)
                scheduler.enqueueImmediate()
                local.toDomain()
            }
        }
    }

    override suspend fun deleteAccount(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            localAccountScope.withExclusive {
                val currentUid = requireNotNull(authDataSource.currentUserId) { "No authenticated owner session" }
                requireDeletionIdentity(deletionReauthenticationUid, currentUid)
                check(database.syncDao().getOutstandingCount() == 0) {
                    "Sync all pending changes before deleting this account so no sale, payment, or inventory update is lost."
                }
                SupabaseClientProvider.client.functions.invoke(
                    function = "delete-account",
                    body = buildJsonObject { put("operation", "delete") }
                )
                authDataSource.deleteSupabaseSession()
                deletionReauthenticationUid = null
                scheduler.cancelPending()
                clearLocalAccountCache()
            }
        }
    }

    override suspend fun resetPassword(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { authDataSource.sendPasswordReset(email) }
    }

    override suspend fun reauthenticate(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            localAccountScope.withExclusive {
                val originalUid = requireNotNull(authDataSource.currentUserId) { "Sign in again before deleting your account." }
                val owner = requireNotNull(getCurrentOwner()) { "Sign in again before deleting your account." }
                require(owner.phone.isNotBlank()) { "Your owner account has no password credential." }
                val email = requireNotNull(authDataSource.currentUserEmail) { "Email/password reauthentication is unavailable for phone-only accounts." }
                deletionReauthenticationUid = null
                authDataSource.reauthenticate(email, password, originalUid)
                deletionReauthenticationUid = originalUid
            }
        }
    }

    override suspend fun reauthenticateWithGoogle(idToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            localAccountScope.withExclusive {
                val originalUid = requireNotNull(authDataSource.currentUserId) { "Sign in again before deleting your account." }
                deletionReauthenticationUid = null
                authDataSource.reauthenticateWithGoogle(idToken, originalUid)
                deletionReauthenticationUid = originalUid
            }
        }
    }

    override suspend fun logout() = withContext(Dispatchers.IO) {
        localAccountScope.withExclusive {
            deletionReauthenticationUid = null
            val outstandingWork = outstandingLocalOperationCount()
            authDataSource.signOut()
            authTelemetry.clearUser()
            if (shouldClearLocalAccountCacheAfterLogout(outstandingWork)) {
                clearLocalAccountCache(clearOnboarding = false)
            } else {
                scheduler.cancelPending()
                clearSessionPreferences()
            }
        }
        Unit
    }

    override suspend fun clearLocalDataForAccountSwitch(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            localAccountScope.withExclusive {
                val previousUid = requireNotNull(localAccountScope.ownerUid()) {
                    "The previous account could not be verified. Sign in to that account to recover its data."
                }
                check(ownerDao.getOwnerByFirebaseUid(previousUid) != null) {
                    "The previous account could not be verified. Sign in to that account to recover its data."
                }
                check(outstandingLocalOperationCount() == 0) {
                    "This device has preserved offline changes. Sign in to the original account to sync or recover them before switching."
                }
                check(database.syncDao().hasLocalBusinessData()) {
                    "There is no previous account data to clear. Sign in again to continue."
                }
                authDataSource.signOut()
                check(authDataSource.currentUserId == null) {
                    "Sign out before clearing this device's saved account data."
                }
                authTelemetry.clearUser()
                scheduler.cancelPending()
                clearLocalAccountCache()
            }
        }
    }

    override suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        dataStore.data.first()[keyIsLoggedIn] == true && restoreCurrentSession().isSuccess
    }

    override suspend fun hasCompletedOnboarding(): Boolean = dataStore.data.first()[keyHasCompletedOnboarding] == true

    override suspend fun setCompletedOnboarding(completed: Boolean) = withContext(Dispatchers.IO) {
        dataStore.edit { it[keyHasCompletedOnboarding] = completed }
        Unit
    }

    override fun observeCurrentOwner(): Flow<Owner?> = dataStore.data.map { prefs ->
        prefs[keyOwnerId]?.let { ownerDao.getOwnerById(it)?.toDomain() }
    }.flowOn(Dispatchers.IO)

    override suspend fun getCurrentOwner(): Owner? = withContext(Dispatchers.IO) {
        dataStore.data.first()[keyOwnerId]?.let { ownerDao.getOwnerById(it)?.toDomain() }
    }

    private suspend fun establishLocalOwner(uid: String, phoneFallback: String): OwnerEntity {
        val account = accountDataSource.loadCurrent()
        val existing = ownerDao.getOwnerByFirebaseUid(uid)
            ?: account.profile.firebaseUid
                ?.takeIf { it != uid }
                ?.let { legacyUid -> ownerDao.getOwnerByFirebaseUid(legacyUid) }
            ?: account.phone.takeIf { it.isNotBlank() }?.let { ownerDao.getOwnerByPhone(it) }
        if (existing != null) return writeLocalOwner(existing, existing.toDomain(), account, uid)
        check(account.stations.isNotEmpty()) { "No accessible AquaHub station was found." }
        val stationId = account.stations.map { upsertLocalStation(it) }.first()
        val now = System.currentTimeMillis()
        val id = ownerDao.insertOwner(
            OwnerEntity(
                stationId = stationId,
                name = account.displayName.ifBlank { "AquaHub owner" },
                phone = account.phone.ifBlank { phoneFallback },
                email = account.email,
                password = "",
                createdAt = now,
                updatedAt = now,
                firebaseUid = uid,
                businessId = account.organizationId,
                role = account.role.name
            )
        )
        return requireNotNull(ownerDao.getOwnerById(id))
    }

    private suspend fun writeLocalOwner(existing: OwnerEntity?, fallback: Owner, account: SupabaseAccountProfile, uid: String): OwnerEntity {
        // Always reconcile the cloud station list before choosing the owner
        // station. Registration starts with a local draft, while Supabase
        // provisioning returns the canonical station UUID; skipping this on an
        // existing local owner leaves the draft and cloud-backed rows split.
        val mappedStationIds = account.stations.map { upsertLocalStation(it) }
        // A user may own more than one organization. Keep the prior station
        // only while it belongs to the organization selected by Supabase;
        // otherwise the billing screen would display the previous business.
        val stationId = existing?.stationId?.takeIf { it in mappedStationIds }
            ?: mappedStationIds.firstOrNull()
            ?: error("No accessible AquaHub station was found")
        val owner = OwnerEntity(
            id = existing?.id ?: 0,
            stationId = stationId,
            name = account.displayName.ifBlank { fallback.name },
            phone = account.phone.ifBlank { fallback.phone },
            email = account.email ?: fallback.email,
            password = "",
            createdAt = existing?.createdAt ?: fallback.createdAt,
            updatedAt = System.currentTimeMillis(),
            firebaseUid = uid,
            businessId = account.organizationId,
            role = account.role.name
        )
        if (existing == null) return ownerDao.getOwnerById(ownerDao.insertOwner(owner))!!
        ownerDao.updateOwner(owner)
        return owner
    }

    private suspend fun upsertLocalStation(row: SupabaseStationRow): Long {
        val metadata = database.syncDao().getMetadataByRemoteId(SyncResourceType.STATION.name, row.id)
        if (metadata != null) {
            val serverCreatedAt = row.createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            if (serverCreatedAt != null && metadata.lastSyncedAt != null) {
                database.stationDao().getStation(metadata.localId)?.let { cached ->
                    if (cached.createdAt != serverCreatedAt) {
                        database.stationDao().updateStation(cached.copy(createdAt = serverCreatedAt))
                    }
                }
            }
            // A previous onboarding build could have left a duplicate local
            // draft with CREATE rows after the cloud station was provisioned.
            // Remove only those still-unsynced station-draft outbox rows; the
            // canonical station and all operational data remain untouched.
            database.stationDao().getAllStations()
                .filter { it.id != metadata.localId && sameStationIdentity(it.name, it.address, it.phone, row) }
                .forEach { draft ->
                    val draftMetadata = database.syncDao().getMetadata(SyncResourceType.STATION.name, draft.id)
                    if (draftMetadata == null || draftMetadata.lastSyncedAt == null) {
                        database.syncDao().deleteQueueForResource(SyncResourceType.STATION.name, draft.id)
                        database.syncDao().deleteMetadataForResource(SyncResourceType.STATION.name, draft.id)
                    }
                }
            return metadata.localId
        }
        val now = System.currentTimeMillis()
        val draft = database.stationDao().findActiveByIdentity(row.name, row.address, row.phone)
        val draftMetadata = draft?.let {
            database.syncDao().getMetadata(SyncResourceType.STATION.name, it.id)
        }
        val localId = if (draft != null && (draftMetadata == null || draftMetadata.lastSyncedAt == null)) {
            // The station was created locally as part of onboarding and has
            // not reached a remote authority yet. Rebind that row to the
            // provisioned Supabase station instead of creating a duplicate.
            database.syncDao().deleteQueueForResource(SyncResourceType.STATION.name, draft.id)
            database.syncDao().deleteMetadataForResource(SyncResourceType.STATION.name, draft.id)
            database.stationDao().updateStation(row.toEntity(now).copy(id = draft.id, createdAt = draft.createdAt))
            draft.id
        } else {
            database.stationDao().insertStation(row.toEntity(now))
        }
        database.syncDao().upsertMetadata(
            SyncMetadataEntity(
                resourceType = SyncResourceType.STATION.name,
                localId = localId,
                remoteId = row.id,
                stationLocalId = localId,
                lastLocalUpdatedAt = now,
                lastServerUpdatedAt = now,
                lastSyncedAt = now
            )
        )
        return localId
    }

    private fun sameStationIdentity(
        localName: String,
        localAddress: String,
        localPhone: String,
        remote: SupabaseStationRow
    ): Boolean = localName.trim().equals(remote.name.trim(), ignoreCase = true) &&
        localAddress.trim().equals(remote.address.trim(), ignoreCase = true) &&
        localPhone == remote.phone

    private suspend fun storeSession(owner: OwnerEntity) {
        dataStore.edit { prefs ->
            prefs[keyIsLoggedIn] = true; prefs[keyOwnerId] = owner.id; prefs[keyStationId] = owner.stationId
            prefs[keyOwnerName] = owner.name; prefs[keyHasCompletedOnboarding] = true
        }
    }

    private suspend fun reconcileLocalAccountScope(uid: String, authenticatedProfileLegacyUid: String? = null) {
        val boundUid = localAccountScope.ownerUid()
        val verifiedLegacyOwnerUid = authenticatedProfileLegacyUid
            ?.takeIf { legacyUid ->
                val ownerUid = ownerDao.getOwnerByFirebaseUid(legacyUid)?.firebaseUid
                isVerifiedLegacyOwnerMapping(
                    authenticatedUid = uid,
                    boundUid = boundUid,
                    serverLegacyUid = legacyUid,
                    localOwnerUid = ownerUid
                )
            }
        if (verifiedLegacyOwnerUid != null) {
            // The authenticated profiles row is scoped to uid and its server-owned
            // firebase_uid exactly identifies this local owner. Claim the existing
            // cache without changing or dropping any outbox operation IDs. The
            // subsequent owner refresh rekeys the Room owner to the Supabase UID.
            localAccountScope.bind(uid)
            return
        }
        val pendingCount = outstandingLocalOperationCount()
        val action = decideAccountScopeAction(
            boundUid = boundUid,
            requestedUid = uid,
            outstandingWork = pendingCount,
            hasMatchingLocalOwner = ownerDao.getOwnerByFirebaseUid(uid) != null,
            hasLocalBusinessData = database.syncDao().hasLocalBusinessData(),
            hasBoundLocalOwner = boundUid?.let { ownerDao.getOwnerByFirebaseUid(it) != null } == true
        )
        when (action) {
            AccountScopeAction.KEEP -> Unit
            AccountScopeAction.CLAIM_LEGACY_CACHE -> localAccountScope.bind(uid)
            AccountScopeAction.CLEAR_AND_BIND -> {
                clearLocalAccountCache()
                localAccountScope.bind(uid)
            }
            AccountScopeAction.BLOCK_PENDING_WORK -> {
                // Reject the newly authenticated identity before any cached
                // rows/outbox operations can be observed or uploaded as it.
                authDataSource.signOut()
                authTelemetry.clearUser()
                clearSessionPreferences()
                throw AppError.ValidationError(
                    "This device still has $pendingCount preserved offline change${if (pendingCount == 1) "" else "s"} for another AquaHub account. They will not be replayed as this account. Sign in to the original account or complete an explicit recovery before switching."
                )
            }
            AccountScopeAction.BLOCK_LOCAL_DATA -> {
                // Keep the cloud cache available for recovery instead of clearing it
                // or attaching it to a different authenticated identity.
                authDataSource.signOut()
                authTelemetry.clearUser()
                clearSessionPreferences()
                throw AppError.ValidationError(
                    "This device contains local business data for a different or unverified account. It has been preserved and will not be cleared or uploaded under this account. Recover the original account before switching."
                )
            }
            AccountScopeAction.CONFIRM_LOCAL_DATA_CLEAR -> {
                // The newly authenticated account must not see the old cache. A separate,
                // explicit confirmation from the login UI is required before it is erased.
                authDataSource.signOut()
                authTelemetry.clearUser()
                clearSessionPreferences()
                throw LocalAccountSwitchConfirmationRequiredException()
            }
        }
    }

    private suspend fun recoverLegacyOutboxForVerifiedOwner(uid: String, owner: OwnerEntity) {
        val syncDao = database.syncDao()
        if (syncDao.getLegacyQuarantinedCount() == 0) return
        check(
            mayRecoverLegacyOutbox(
                authenticatedUid = uid,
                boundUid = localAccountScope.ownerUid(),
                cachedOwnerUid = owner.firebaseUid
            )
        ) {
            "Earlier offline changes remain preserved because this authenticated account could not be verified as their original owner."
        }
        syncDao.requeueVerifiedLegacyOutstanding()
    }

    private suspend fun clearSessionPreferences() {
        dataStore.edit { prefs ->
            prefs.remove(keyIsLoggedIn)
            prefs.remove(keyOwnerId)
            prefs.remove(keyStationId)
            prefs.remove(keyOwnerName)
        }
    }

    private suspend fun clearLocalAccountCache(clearOnboarding: Boolean = true) {
        scheduler.cancelPending(); database.clearAllTables(); databaseSeeder.seedPlansIfEmpty()
        localAccountScope.clear()
        dataStore.edit { prefs ->
            prefs.remove(keyIsLoggedIn); prefs.remove(keyOwnerId); prefs.remove(keyStationId); prefs.remove(keyOwnerName)
            if (clearOnboarding) prefs.remove(keyHasCompletedOnboarding)
        }
    }

    private suspend fun outstandingLocalOperationCount(): Int = countOutstandingLocalOperations(
        syncOperations = database.syncDao().getOutstandingCount(),
        offlinePosOperations = database.offlinePosDao().getOutstandingOperationCount(),
    )

    private fun recordLogin(uid: String, method: String) {
        authTelemetry.setUser(uid)
        authTelemetry.login(method)
    }

    private fun recordSignup(uid: String) {
        authTelemetry.setUser(uid)
        authTelemetry.signUp("supabase")
    }
}

private fun SupabaseStationRow.toEntity(now: Long): StationEntity = StationEntity(
    name = name, description = description, phone = phone, email = email, address = address,
    barangay = barangay, city = city, province = province, latitude = latitude, longitude = longitude,
    logoPath = logoPath, openingTime = openingTime, closingTime = closingTime, isOpen = isOpen,
    manualOpenOverride = manualOpenOverride,
    createdAt = createdAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: now,
    updatedAt = now,
    placeId = placeId, isAcceptingOnlineOrders = isAcceptingOnlineOrders, deliveryRadiusKm = deliveryRadiusKm,
    deliveryFeeCentavos = deliveryFeeCentavos, estimatedPreparationMinutes = estimatedPreparationMinutes,
    businessRulesText = businessRulesText, businessRulesVersion = businessRulesVersion,
    publicStationCode = publicStationCode, cashPaymentEnabled = cashPaymentEnabled, codPaymentEnabled = codPaymentEnabled,
    gcashPaymentEnabled = gcashPaymentEnabled, gcashAccountName = gcashAccountName, gcashAccountNumber = gcashAccountNumber,
    gcashQrImagePath = gcashQrImagePath, mayaPaymentEnabled = mayaPaymentEnabled, mayaAccountName = mayaAccountName,
    mayaAccountNumber = mayaAccountNumber, mayaQrImagePath = mayaQrImagePath, isActive = true
)

internal fun Throwable.isOfflineTransportFailure(): Boolean {
    if (this is CancellationException) return false
    return generateSequence(this) { it.cause }.any {
        it is IOException ||
            it is ConnectException ||
            it is SocketTimeoutException ||
            it is UnknownHostException ||
            it is UnresolvedAddressException
    }
}

internal fun canUseCachedOwnerForOfflineRestore(
    activeAuthUid: String?,
    expectedAuthUid: String,
    boundLocalUid: String?,
    cachedOwnerUid: String?
): Boolean = expectedAuthUid.isNotBlank() &&
    activeAuthUid == expectedAuthUid &&
    boundLocalUid == expectedAuthUid &&
    cachedOwnerUid == expectedAuthUid

internal fun requireDeletionIdentity(reauthenticatedUid: String?, currentUid: String) {
    if (reauthenticatedUid.isNullOrBlank()) {
        throw AccountDeletionReauthenticationRequiredException()
    }
    check(reauthenticatedUid == currentUid) {
        "The signed-in account changed after reauthentication. Sign in again before deleting an account."
    }
}

internal class AccountDeletionReauthenticationRequiredException : IllegalStateException(
    "Confirm your password or Google account before deleting this account."
)

internal fun mayRecoverLegacyOutbox(
    authenticatedUid: String,
    boundUid: String?,
    cachedOwnerUid: String?
): Boolean = authenticatedUid.isNotBlank() &&
    boundUid == authenticatedUid &&
    cachedOwnerUid == authenticatedUid
