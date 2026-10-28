package com.maxrave.kotlinytmusicscraper.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreferredSongVideoIdTest {
    @Test
    fun titleWatchIdWinsOverTheOverlayPlayButton() {
        assertEquals(
            "aaaaaaaaaaa",
            preferredSongVideoId(
                playlistItemId = null,
                titleWatchId = "aaaaaaaaaaa",
                overlayWatchId = "bbbbbbbbbbb",
            ),
        )
    }

    @Test
    fun playlistItemIdWinsAndABlankTitleFallsThrough() {
        assertEquals(
            "ccccccccccc",
            preferredSongVideoId("ccccccccccc", "aaaaaaaaaaa", "bbbbbbbbbbb"),
        )
        assertEquals(
            "bbbbbbbbbbb",
            preferredSongVideoId(null, "  ", "bbbbbbbbbbb"),
        )
        assertNull(preferredSongVideoId(null, "", null))
    }
}
