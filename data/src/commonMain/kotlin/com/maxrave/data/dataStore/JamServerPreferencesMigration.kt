package com.maxrave.data.dataStore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maxrave.data.listentogether.ListenTogetherPrefs

/** Delete any inherited server override in DataStore's background initialization scope. */
internal class JamServerPreferencesMigration : DataMigration<Preferences> {
    private val serverKey = stringPreferencesKey(ListenTogetherPrefs.SERVER_URL)

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData.contains(serverKey)

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { remove(serverKey) }.toPreferences()

    override suspend fun cleanUp() = Unit
}
