package com.maxrave.media3.service.callback

import androidx.media3.common.Player
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.JamPlaybackRouter
import org.junit.Assert.*
import org.junit.Test

class JamSessionCommandsTest {
    @Test fun transportGoesToRoomAndLocalOverridesAreBlocked() {
        val sent = mutableListOf<Pair<String, Long?>>()
        val jam = object : JamPlaybackRouter.Actions {
            override fun add(item: GenericMediaItem) = Unit
            override fun control(type: String, positionMs: Long?) { sent += type to positionMs }
        }
        assertTrue(JamSessionCommands.intercept(Player.COMMAND_PLAY_PAUSE, 12000, true, jam))
        assertEquals(listOf("pause" to 12000L), sent)
        assertTrue(JamSessionCommands.intercept(Player.COMMAND_SEEK_TO_NEXT, 12000, true, jam))
        assertEquals("next", sent.last().first)
        assertTrue(JamSessionCommands.intercept(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, 0, false, jam))
        assertTrue(JamSessionCommands.intercept(Player.COMMAND_SET_SPEED_AND_PITCH, 0, false, jam))
        assertFalse(JamSessionCommands.intercept(Player.COMMAND_SET_VOLUME, 0, false, jam))
        assertEquals(2, sent.size)
    }
}
