package com.maxrave.data.dataStore

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maxrave.common.SELECTED_LANGUAGE

internal val REGION_MIGRATED_V076 = booleanPreferencesKey("region_migrated_v076")

/** Runs in DataStore's initialization scope, before any reader sees preferences. */
internal class RegionalPreferencesMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[REGION_MIGRATED_V076] != true

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { migrateRegionalPreferences(this) }.toPreferences()

    override suspend fun cleanUp() = Unit
}

internal fun clearRegionalCache(settings: MutablePreferences) {
    listOf("visitor_data", "mood_and_genres_cache", "mood_artwork_cache").forEach {
        settings.remove(stringPreferencesKey(it))
    }
}

internal fun migrateRegionalPreferences(settings: MutablePreferences) {
    if (settings[REGION_MIGRATED_V076] == true) return
    val location = stringPreferencesKey("location")
    if (settings[location].isNullOrBlank() || settings[location]?.uppercase() in setOf("US", "IN")) {
        settings[location] = "CO"
    }
    val language = stringPreferencesKey(SELECTED_LANGUAGE)
    if (settings[language].isNullOrBlank()) settings[language] = "es-ES"
    settings[stringPreferencesKey("chart_key")] = settings[location] ?: "CO"
    clearRegionalCache(settings)
    settings[REGION_MIGRATED_V076] = true
}
