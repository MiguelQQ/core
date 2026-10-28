package com.maxrave.data.dataStore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maxrave.common.SELECTED_LANGUAGE
import createDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegionalDataStoreTest {
    @Test
    fun repeatedRegionalReadsNeverEnqueueWrites() = runBlocking {
        withStore { store ->
            val writes = AtomicInteger()
            val manager = DataStoreManagerImpl(object : DataStore<Preferences> by store {
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    writes.incrementAndGet()
                    return store.updateData(transform)
                }
            })
            repeat(10) {
                assertEquals("CO", manager.location.first())
                assertEquals("CO", manager.chartKey.first())
                assertEquals("es-ES", manager.language.first())
                manager.getString("miniplayer_guide").first()
                manager.visitorData.first()
                manager.moodAndGenresCache.first()
                manager.moodArtworkCache.first()
            }
            assertEquals(0, writes.get(), "A preference read must never queue a DataStore edit")
        }
    }

    @Test
    fun suspendedMainCollectorCannotDeadlockAnotherStartupReader() = runBlocking {
        withStore { store ->
            store.data.first() // Warm up DataStore, so only the caller's Main dispatcher is paused.
            val manager = DataStoreManagerImpl(store)
            // Model Main after a collector suspends, while synchronous startup code occupies it.
            // DataStore executes updateData transforms in the caller's dispatcher. In 0.7.6 the
            // first reader queues an edit needing Main; the next reader queues behind that edit.
            val main = PausedMainDispatcher()
            val reader = CoroutineScope(coroutineContext + main).launch { manager.location.first() }
            try {
                main.runNext()
                withTimeout(5_000) {
                    while (!reader.isCompleted && main.isEmpty) delay(1)
                }
                val language = withTimeoutOrNull(1_000) { manager.language.first() }
                assertNotNull(language, "Startup read waits for an edit whose transform needs the occupied Main dispatcher")
                assertEquals("es-ES", language)
            } finally {
                reader.cancel()
                withTimeout(5_000) {
                    while (!reader.isCompleted || !main.isEmpty) {
                        main.runNext()
                        delay(1)
                    }
                }
            }
        }
    }

    @Test
    fun concurrentFirstReadersSeeTheNativeMigrationAlreadyCommitted() = runBlocking {
        val location = stringPreferencesKey("location")
        val language = stringPreferencesKey(SELECTED_LANGUAGE)
        val visitor = stringPreferencesKey("visitor_data")
        val initial = mutablePreferencesOf(
            location to "IN",
            stringPreferencesKey("chart_key") to "ZZ",
            visitor to "stale",
            stringPreferencesKey("mood_and_genres_cache") to "stale",
            stringPreferencesKey("mood_artwork_cache") to "stale",
            stringPreferencesKey("unrelated") to "keep",
        )
        withStore(initial) { store ->
            val manager = DataStoreManagerImpl(store)
            coroutineScope {
                List(32) {
                    async(Dispatchers.Default) {
                        // Raw data readers, too, must see the migration before the first value.
                        val snapshot = store.data.first()
                        assertEquals("CO", snapshot[location])
                        assertEquals("es-ES", snapshot[language])
                        assertEquals("CO", snapshot[stringPreferencesKey("chart_key")])
                        assertTrue(snapshot[REGION_MIGRATED_V076] == true)
                        assertNull(snapshot[visitor])
                        assertEquals("keep", snapshot[stringPreferencesKey("unrelated")])
                        assertEquals("CO", manager.location.first())
                        assertEquals("CO", manager.chartKey.first())
                        assertEquals("es-ES", manager.language.first())
                        assertNull(manager.moodAndGenresCache.first())
                        assertNull(manager.moodArtworkCache.first())
                    }
                }.awaitAll()
            }
        }
    }

    @Test
    fun restartPreservesPostMigrationSelectionsAndCaches() = runBlocking {
        withPreferencesFile(mutablePreferencesOf(stringPreferencesKey("location") to "US")) { file ->
            withProductionStore(file) { store ->
                val manager = DataStoreManagerImpl(store)
                assertEquals("CO", manager.location.first())
                manager.setChartKey("IN")
                manager.putString(SELECTED_LANGUAGE, "fr-FR")
                manager.setVisitorData("new")
                manager.setMoodAndGenresCache("new-moods")
                manager.setMoodArtworkCache("new-artwork")
            }
            withProductionStore(file) { store ->
                val manager = DataStoreManagerImpl(store)
                assertEquals("IN", manager.location.first())
                assertEquals("IN", manager.chartKey.first())
                assertEquals("fr-FR", manager.language.first())
                assertEquals("new", manager.visitorData.first())
                assertEquals("new-moods", manager.moodAndGenresCache.first())
                assertEquals("new-artwork", manager.moodArtworkCache.first())
            }
        }
    }

    @Test
    fun freshProductionStoreUsesColombiaAndSpanish() = runBlocking {
        withStore(mutablePreferencesOf()) { store ->
            val manager = DataStoreManagerImpl(store)
            assertEquals("CO", manager.location.first())
            assertEquals("es-ES", manager.language.first())
            assertTrue(store.data.first()[REGION_MIGRATED_V076] == true)
        }
    }

    private suspend fun withStore(
        initial: Preferences = mutablePreferencesOf(
            REGION_MIGRATED_V076 to true,
            stringPreferencesKey("location") to "CO",
            stringPreferencesKey(SELECTED_LANGUAGE) to "es-ES",
        ),
        block: suspend (DataStore<Preferences>) -> Unit,
    ) = withPreferencesFile(initial) { file -> withProductionStore(file, block) }

    private suspend fun withPreferencesFile(initial: Preferences, block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("killmon-region-").toFile()
        val file = directory.resolve("settings.preferences_pb")
        try {
            val seedJob = SupervisorJob()
            try {
                val legacyStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + seedJob)) { file }
                legacyStore.updateData { initial }
            } finally {
                seedJob.cancelAndJoin()
            }
            withTimeout(10_000) { block(file) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun withProductionStore(file: File, block: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        try {
            block(createDataStore(scope = CoroutineScope(Dispatchers.IO + job)) { file.absolutePath })
        } finally {
            job.cancelAndJoin()
        }
    }

    private class PausedMainDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        val isEmpty: Boolean get() = queue.isEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun runNext() { queue.poll(100, TimeUnit.MILLISECONDS)?.run() }
    }
}
