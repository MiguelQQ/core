package com.maxrave.media3.service.callback

import androidx.media3.common.Player
import com.maxrave.domain.data.player.JamPlaybackRouter

/** MediaSession commands route through the room before the local player can act. */
internal object JamSessionCommands {
    fun intercept(command: Int, positionMs: Long, playing: Boolean, jam: JamPlaybackRouter.Actions): Boolean {
        when (command) {
            Player.COMMAND_PLAY_PAUSE -> jam.control(if (playing) "pause" else "play", positionMs)
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> jam.control("next")
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> jam.control("prev")
            Player.COMMAND_SEEK_BACK -> jam.control("seek", (positionMs - 10_000).coerceAtLeast(0))
            Player.COMMAND_SEEK_FORWARD -> jam.control("seek", positionMs + 10_000)
            // This callback has no requested seek position. The app scrubber sends it via PlayerEvent.
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_STOP, Player.COMMAND_PREPARE -> Unit
            else -> return false
        }
        return true
    }
}
