package com.maxrave.domain.mediaservice.handler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasteAutoplayTest {
    @Test
    fun `tops up only when two or fewer tracks remain including the current one`() {
        assertFalse(TasteAutoplay.shouldTopUp(queueSize = 10, currentIndex = 7))
        assertTrue(TasteAutoplay.shouldTopUp(queueSize = 10, currentIndex = 8))
        assertTrue(TasteAutoplay.shouldTopUp(queueSize = 10, currentIndex = 9))
        assertTrue(TasteAutoplay.shouldTopUp(queueSize = 10, currentIndex = 10))
        assertFalse(TasteAutoplay.shouldTopUp(queueSize = 0, currentIndex = 0))
        assertFalse(TasteAutoplay.shouldTopUp(queueSize = 4, currentIndex = -1))
    }

    @Test
    fun `drops ids already queued or recently played and caps the batch at 20`() {
        val current = (1..30).map { id(it) }
        val picked = TasteAutoplay.merge(current, emptyList(), exclude = setOf(id(1), id(2), "not-a-video"))
        assertEquals(20, picked.size)
        assertFalse(id(1) in picked)
        assertFalse(id(2) in picked)
        assertEquals(id(3), picked.first())
    }

    @Test
    fun `mixes taste into the current radio and still fills the batch`() {
        val current = (1..30).map { id(it) }
        val liked = listOf(id(1), id(100), id(101))
        val artist = listOf(id(102), "bad id", id(3))
        val picked =
            TasteAutoplay.merge(
                currentRadio = current,
                tasteRadios = listOf(liked, artist),
                exclude = setOf(id(2)),
            )
        assertEquals(20, picked.size)
        assertEquals((1..14).map { id(it) }.filter { it != id(2) }, picked.take(13))
        assertTrue(id(100) in picked)
        assertTrue(id(101) in picked)
        assertTrue(id(102) in picked)
        assertFalse(id(2) in picked)
        assertFalse(id(3) in picked.drop(13).take(3))
        assertEquals(20, picked.distinct().size)
    }

    @Test
    fun `a short current radio is filled from taste`() {
        val picked =
            TasteAutoplay.merge(
                currentRadio = listOf(id(1), id(2)),
                tasteRadios = listOf((10..40).map { id(it) }),
                exclude = setOf(id(10)),
            )
        assertEquals(20, picked.size)
        assertEquals(listOf(id(1), id(2)), picked.take(2))
        assertEquals(id(11), picked[2])
    }

    @Test
    fun `seeds come from the newest like and a song by a top artist`() {
        val seeds =
            TasteAutoplay.pickSeeds(
                currentVideoId = id(1),
                recentLikedVideoIds = listOf(id(1), id(5), id(6)),
                mostPlayed =
                    listOf(
                        TasteAutoplay.PlayedSong(id(1), listOf("artist-a")),
                        TasteAutoplay.PlayedSong(id(5), listOf("artist-b")),
                        TasteAutoplay.PlayedSong(id(9), listOf("artist-a")),
                    ),
                topArtistIds = listOf("artist-a", "artist-b"),
            )
        assertEquals(id(5), seeds.likedVideoId)
        assertEquals(id(9), seeds.artistVideoId)
    }

    @Test
    fun `artist seed falls back when no played song matches the top artists`() {
        val seeds =
            TasteAutoplay.pickSeeds(
                currentVideoId = id(1),
                recentLikedVideoIds = emptyList(),
                mostPlayed = listOf(TasteAutoplay.PlayedSong(id(4), listOf("someone-else"))),
                topArtistIds = listOf("artist-a"),
            )
        assertNull(seeds.likedVideoId)
        assertEquals(id(4), seeds.artistVideoId)
    }

    private fun id(n: Int): String = "v" + n.toString().padStart(10, '0')
}
