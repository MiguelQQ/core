package com.maxrave.domain.jam

import com.maxrave.domain.data.model.listentogether.ListenTogetherRoom
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.GenericMediaMetadata
import com.maxrave.domain.extension.toSongEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JamPlayerStateTest {
    @Test
    fun roomGateShowsTheSpinnerEvenWhenThePlayerIsReadyAndPaused() {
        val gate = ListenTogetherRoom(roomCode = "ABC234", loading = true)
        assertTrue(gate.showsLoading(playerLoading = false))
        assertFalse(ListenTogetherRoom(roomCode = "ABC234", loading = false).showsLoading(false))
        assertTrue(ListenTogetherRoom(roomCode = "ABC234").showsLoading(true))
    }

    @Test
    fun outsideARoomOnlyThePlayerDecides() {
        assertFalse(ListenTogetherRoom(loading = true).showsLoading(false))
        assertTrue(ListenTogetherRoom().showsLoading(true))
    }

    @Test
    fun aRoomTrackWithoutArtistOrAlbumDoesNotBecomeTheTextNull() {
        val song = GenericMediaItem(mediaId = "dQw4w9WgXcQ", uri = "", metadata = GenericMediaMetadata(title = "Mi canción")).toSongEntity()
        assertNull(song.artistName, "an absent artist must stay absent, not become \"null\"")
        assertNull(song.albumName)
        assertEquals("Mi canción", song.title)
        val blank = GenericMediaItem(mediaId = "dQw4w9WgXcQ", uri = "", metadata = GenericMediaMetadata(title = "x", artist = "  ")).toSongEntity()
        assertNull(blank.artistName)
        val full = GenericMediaItem(mediaId = "dQw4w9WgXcQ", uri = "", metadata = GenericMediaMetadata(title = "x", artist = "Natalia Lafourcade")).toSongEntity()
        assertEquals(listOf("Natalia Lafourcade"), full.artistName)
    }
}
