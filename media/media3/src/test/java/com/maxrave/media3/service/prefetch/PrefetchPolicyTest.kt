package com.maxrave.media3.service.prefetch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchPolicyTest {
    private val mib = 1024L * 1024L

    @Test
    fun noNetworkOrRestrictedNetworkNeverPrefetches() {
        assertFalse(PrefetchPolicy.allows(PrefetchNetwork.NONE, 4 * mib))
        assertFalse(PrefetchPolicy.allows(PrefetchNetwork.RESTRICTED, 4 * mib))
        assertFalse(PrefetchPolicy.allows(PrefetchNetwork.RESTRICTED, null))
    }

    @Test
    fun meteredNetworkHasSmallerCapThanWifi() {
        assertTrue(PrefetchPolicy.allows(PrefetchNetwork.METERED, 6 * mib))
        assertFalse(PrefetchPolicy.allows(PrefetchNetwork.METERED, 20 * mib))
        assertTrue(PrefetchPolicy.allows(PrefetchNetwork.UNMETERED, 20 * mib))
        assertFalse(PrefetchPolicy.allows(PrefetchNetwork.UNMETERED, 60 * mib))
    }

    @Test
    fun unknownLengthIsAllowedAndCappedByWriter() {
        assertTrue(PrefetchPolicy.allows(PrefetchNetwork.METERED, null))
        assertTrue(PrefetchPolicy.allows(PrefetchNetwork.UNMETERED, 0L))
    }

    @Test
    fun plainOrderTakesTheNextTwo() {
        assertEquals(listOf(3, 4), PrefetchPolicy.upcomingIndices(10, 2, 2, false, null, null))
    }

    @Test
    fun endOfQueueStopsUnlessRepeatAll() {
        assertEquals(listOf(4), PrefetchPolicy.upcomingIndices(5, 3, 2, false, null, null))
        assertEquals(emptyList<Int>(), PrefetchPolicy.upcomingIndices(5, 4, 2, false, null, null))
        assertEquals(listOf(0, 1), PrefetchPolicy.upcomingIndices(5, 4, 2, true, null, null))
    }

    @Test
    fun neverReturnsTheCurrentTrackOrDuplicates() {
        assertEquals(listOf(1), PrefetchPolicy.upcomingIndices(2, 0, 2, true, null, null))
        assertEquals(emptyList<Int>(), PrefetchPolicy.upcomingIndices(1, 0, 2, true, null, null))
    }

    @Test
    fun shuffleFollowsPlayOrderNotPlaylistOrder() {
        // Play order: 2, 0, 3, 1 -> after playlist index 0 come 3 then 1.
        val order = listOf(2, 0, 3, 1)
        val inverse = listOf(1, 3, 0, 2)
        assertEquals(listOf(3, 1), PrefetchPolicy.upcomingIndices(4, 0, 2, false, order, inverse))
        assertEquals(listOf(2, 0), PrefetchPolicy.upcomingIndices(4, 1, 2, true, order, inverse))
    }

    @Test
    fun staleShuffleOrderFallsBackToPlaylistOrder() {
        assertEquals(listOf(1, 2), PrefetchPolicy.upcomingIndices(4, 0, 2, false, listOf(0, 1), listOf(0, 1)))
    }

    @Test
    fun invalidCurrentIsEmpty() {
        assertEquals(emptyList<Int>(), PrefetchPolicy.upcomingIndices(3, -1, 2, false, null, null))
        assertEquals(emptyList<Int>(), PrefetchPolicy.upcomingIndices(0, 0, 2, false, null, null))
    }

    @Test
    fun retryBacksOff() {
        assertTrue(PrefetchPolicy.retryDelayMs(2) > PrefetchPolicy.retryDelayMs(1))
    }
}
