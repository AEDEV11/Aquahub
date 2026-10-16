package com.aesprt.aquahub.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aesprt.aquahub.core.common.AppError
import com.aesprt.aquahub.core.common.Constants
import com.aesprt.aquahub.data.local.dao.OwnerDao
import com.aesprt.aquahub.data.local.mapper.toDomain
import com.aesprt.aquahub.domain.model.Owner
import com.aesprt.aquahub.domain.model.Station
import com.aesprt.aquahub.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class RoomAuthRepository(
    private val ownerDao: OwnerDao,
    private val dataStore: DataStore<Preferences>
) : AuthRepository {

    private val keyIsLoggedIn = booleanPreferencesKey(Constants.DS_KEY_IS_LOGGED_IN)
    private val keyOwnerId = longPreferencesKey(Constants.DS_KEY_OWNER_ID)
    private val keyStationId = longPreferencesKey(Constants.DS_KEY_STATION_ID)
    private val keyOwnerName = stringPreferencesKey(Constants.DS_KEY_OWNER_NAME)
    private val keyHasCompletedOnboarding = booleanPreferencesKey(Constants.DS_KEY_HAS_COMPLETED_ONBOARDING)

    override suspend fun loginWithEmail(email: String, password: String): Result<Owner> {
        val owner = ownerDao.getOwnerByEmail(email)
            ?: return Result.failure(AppError.AuthenticationError())
        if (owner.password != password) {
            return Result.failure(AppError.AuthenticationError())
        }
        dataStore.edit { prefs ->
            prefs[keyIsLoggedIn] = true
            prefs[keyOwnerId] = owner.id
            prefs[keyStationId] = owner.stationId
            prefs[keyOwnerName] = owner.name
            prefs[keyHasCompletedOnboarding] = true
        }
        return Result.success(owner.toDomain())
    }

    override suspend fun register(owner: Owner, password: String, station: Station?, marketingConsent: Boolean): Result<Owner> {
        val existing = ownerDao.getOwnerByPhone(owner.phone)
        if (existing != null) {
            return Result.failure(AppError.ValidationError("Phone number is already registered"))
        }
        val entity = com.aesprt.aquahub.data.local.entity.OwnerEntity(
            id = 0,
            stationId = owner.stationId,
            name = owner.name,
            phone = owner.phone,
            email = owner.email,
            password = password,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val ownerId = ownerDao.insertOwner(entity)
        val createdOwner = owner.copy(id = ownerId)
        dataStore.edit { prefs ->
            prefs[keyIsLoggedIn] = true
            prefs[keyOwnerId] = ownerId
            prefs[keyStationId] = owner.stationId
            prefs[keyOwnerName] = owner.name
            prefs[keyHasCompletedOnboarding] = true
        }
        return Result.success(createdOwner)
    }

    override suspend fun logout() {
        dataStore.edit { it.clear() }
    }

    override suspend fun isLoggedIn(): Boolean {
        return dataStore.data.first()[keyIsLoggedIn] == true
    }

    override suspend fun hasCompletedOnboarding(): Boolean {
        return dataStore.data.first()[keyHasCompletedOnboarding] == true
    }

    override suspend fun setCompletedOnboarding(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[keyHasCompletedOnboarding] = completed
        }
    }

    override fun observeCurrentOwner(): Flow<Owner?> {
        return dataStore.data.map { prefs ->
            val ownerId = prefs[keyOwnerId] ?: return@map null
            ownerDao.getOwnerById(ownerId)?.toDomain()
        }
    }

    override suspend fun getCurrentOwner(): Owner? {
        val ownerId = dataStore.data.first()[keyOwnerId] ?: return null
        return ownerDao.getOwnerById(ownerId)?.toDomain()
    }
}
