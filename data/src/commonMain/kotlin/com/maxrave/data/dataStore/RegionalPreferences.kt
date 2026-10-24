package com.maxrave.data.dataStore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maxrave.common.SELECTED_LANGUAGE

internal val REGION_MIGRATED_V076 = booleanPreferencesKey("region_migrated_v076")

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
