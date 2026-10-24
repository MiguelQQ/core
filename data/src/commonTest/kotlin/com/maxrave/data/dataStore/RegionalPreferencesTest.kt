package com.maxrave.data.dataStore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maxrave.common.SELECTED_LANGUAGE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionalPreferencesTest {
    private val location = stringPreferencesKey("location")
    private val chart = stringPreferencesKey("chart_key")
    private val language = stringPreferencesKey(SELECTED_LANGUAGE)
    private val visitor = stringPreferencesKey("visitor_data")
    private val moods = stringPreferencesKey("mood_and_genres_cache")
    private val artwork = stringPreferencesKey("mood_artwork_cache")

    @Test fun freshInstallUsesColombiaAndSpanish() = runBlocking {
        val manager = DataStoreManagerImpl(MemoryPreferences())
        assertEquals("CO", manager.location.first())
        assertEquals("CO", manager.chartKey.first())
        assertEquals("es-ES", manager.language.first())
    }

    @Test fun legacyRegionsMigrateAndInvalidateCachedDiscovery() {
        for (old in listOf("", "US", "IN", "us")) {
            val prefs = mutablePreferencesOf(location to old, chart to "ZZ", visitor to "old", moods to "old", artwork to "old")
            migrateRegionalPreferences(prefs)
            assertEquals("CO", prefs[location])
            assertEquals("CO", prefs[chart])
            assertTrue(prefs[REGION_MIGRATED_V076] == true)
            assertNull(prefs[visitor])
            assertNull(prefs[moods])
            assertNull(prefs[artwork])
        }
    }

    @Test fun migrationPreservesOtherExplicitRegionsAndLanguages() {
        val prefs = mutablePreferencesOf(location to "MX", language to "fr-FR", chart to "IN")
        migrateRegionalPreferences(prefs)
        assertEquals("MX", prefs[location])
        assertEquals("MX", prefs[chart])
        assertEquals("fr-FR", prefs[language])
    }

    @Test fun migrationNeverOverridesLaterSelectionsOrCaches() {
        val prefs = mutablePreferencesOf(location to "US")
        migrateRegionalPreferences(prefs)
        prefs[location] = "IN"
        prefs[visitor] = "new"
        migrateRegionalPreferences(prefs)
        assertEquals("IN", prefs[location])
        assertEquals("new", prefs[visitor])
    }

    @Test fun changingRegionClearsCachesAndKeepsChartsInSync() = runBlocking {
        val store = MemoryPreferences()
        val manager = DataStoreManagerImpl(store)
        manager.location.first() // finish the one-time migration
        manager.setVisitorData("old")
        manager.setMoodAndGenresCache("old")
        manager.setMoodArtworkCache("old")
        manager.setLocation("MX")
        assertEquals("MX", manager.chartKey.first())
        assertEquals("", manager.visitorData.first())
        assertNull(manager.moodAndGenresCache.first())
        assertNull(manager.moodArtworkCache.first())
        manager.setChartKey("CO")
        assertEquals("CO", manager.location.first())
    }

    @Test fun sameRegionKeepsValidCacheAndLanguageChangeInvalidatesIt() = runBlocking {
        val manager = DataStoreManagerImpl(MemoryPreferences())
        manager.location.first()
        manager.setVisitorData("current")
        manager.setLocation("CO")
        assertEquals("current", manager.visitorData.first())
        manager.putString(SELECTED_LANGUAGE, "es-MX")
        assertEquals("", manager.visitorData.first())
    }

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(mutablePreferencesOf())
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
