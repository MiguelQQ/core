package com.maxrave.media3.di

import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.junit.After
import org.junit.Test

/**
 * v0.7.9 crashed at launch because NextTrackPrefetcher asked Koin for the `Cache` interface while
 * the caches are registered as `SimpleCache` under a qualifier: Koin looks a type up exactly, so the
 * adapter definition threw NoDefinitionFoundException the moment the media service built it.
 * This pins that behaviour with plain JDK types, so the rule "ask for the registered class" holds.
 */
class KoinSupertypeLookupTest {
    @After
    fun tearDown() = stopKoin()

    @Test
    fun registeredClassResolves_butItsInterfaceDoesNot() {
        val registered = ArrayList<String>()
        val koin =
            startKoin {
                modules(module { single<ArrayList<String>>(named("cache")) { registered } })
            }.koin
        assertSame(registered, koin.get<ArrayList<String>>(named("cache")))
        try {
            koin.get<List<String>>(named("cache"))
            fail("Koin resolved a supertype that was never bound")
        } catch (_: org.koin.core.error.NoDefinitionFoundException) {
        }
    }
}
