package com.maxrave.media3.di

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import com.maxrave.media3.service.prefetch.NextTrackPrefetcher
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class PrefetcherCacheTypeTest {
    /** The module resolves `get(named(PLAYER_CACHE))` by the parameter's class; it must be SimpleCache. */
    @Test
    fun cacheParametersAreTheRegisteredClass() {
        val types =
            NextTrackPrefetcher::class.java.constructors
                .single()
                .parameterTypes
        assertEquals(2, types.count { it == SimpleCache::class.java })
    }
}
