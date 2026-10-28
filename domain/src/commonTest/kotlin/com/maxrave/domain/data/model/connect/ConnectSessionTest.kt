package com.maxrave.domain.data.model.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectSessionTest {
    private fun session(playing: Boolean, position: Long, at: Long, duration: Long = 180_000) =
        ConnectSession(
            activeDeviceId = "phone-device-0001",
            revision = 3,
            isPlaying = playing,
            positionMs = position,
            positionAtMs = at,
            index = 0,
            queue = listOf(ConnectTrack("abcdefghijk", "Uno", "A", "", duration)),
        )

    @Test
    fun pausedPositionDoesNotAdvanceAndPlayingPositionDoes() {
        val paused = session(playing = false, position = 12_000, at = 1_000)
        assertEquals(12_000, paused.estimatedPositionMs(9_000))
        val playing = session(playing = true, position = 12_000, at = 1_000)
        assertEquals(16_000, playing.estimatedPositionMs(5_000))
    }

    @Test
    fun playingPositionStopsAtTheTrackLength() {
        val playing = session(playing = true, position = 170_000, at = 0, duration = 180_000)
        assertEquals(180_000, playing.estimatedPositionMs(30_000))
    }

    @Test
    fun followingIsTheOtherDeviceOfTheSameAccount() {
        val state =
            ConnectState(
                deviceId = "desk-device-00002",
                signedIn = true,
                session = session(playing = true, position = 0, at = 0),
            )
        assertTrue(state.isFollowingRemote())
        assertFalse(state.copy(deviceId = "phone-device-0001").isFollowingRemote())
        assertFalse(state.copy(signedIn = false).isFollowingRemote())
    }
}
