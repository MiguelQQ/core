package com.maxrave.data.dataStore

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maxrave.data.listentogether.ListenTogetherPrefs
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class JamServerPreferencesMigrationTest {
    @Test fun removesAllLegacyOverridesWithoutChangingRegionOrAccount() = runBlocking {
        val server = stringPreferencesKey(ListenTogetherPrefs.SERVER_URL)
        val region = stringPreferencesKey("location")
        val account = stringPreferencesKey("account")
        val migration = JamServerPreferencesMigration()
        for (url in listOf("wss://lt.simpmusic.org", "wss://nyx.example/ws", "wss://custom.example", "https://killmon.seriepokemon.online", "")) {
            val initial = mutablePreferencesOf(server to url, region to "CO", account to "existing")
            assertTrue(migration.shouldMigrate(initial))
            val migrated = migration.migrate(initial)
            assertNull(migrated[server])
            assertEquals("CO", migrated[region])
            assertEquals("existing", migrated[account])
            assertFalse(migration.shouldMigrate(migrated))
        }
    }
}
