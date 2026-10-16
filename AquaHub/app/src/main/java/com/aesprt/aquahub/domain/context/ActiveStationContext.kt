package com.aesprt.aquahub.domain.context

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.aesprt.aquahub.core.common.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface ActiveStationContext {
    fun observeActiveStationId(): Flow<Long?>
    suspend fun getActiveStationId(): Long?
    suspend fun setActiveStationId(stationId: Long)
}

class DataStoreActiveStationContext(
    private val dataStore: DataStore<Preferences>
) : ActiveStationContext {

    private val keyActiveStationId = longPreferencesKey(Constants.DS_KEY_STATION_ID)

    override fun observeActiveStationId(): Flow<Long?> {
        return dataStore.data.map { prefs ->
            prefs[keyActiveStationId]
        }
    }

    override suspend fun getActiveStationId(): Long? {
        return dataStore.data.first()[keyActiveStationId]
    }

    override suspend fun setActiveStationId(stationId: Long) {
        dataStore.edit { prefs ->
            prefs[keyActiveStationId] = stationId
        }
    }
}
