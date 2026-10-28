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
        assertFalse(state.publishesLocalAudio(inJam = false))
        assertTrue(state.copy(deviceId = "phone-device-0001").publishesLocalAudio(inJam = false))
        assertFalse(state.copy(deviceId = "phone-device-0001").publishesLocalAudio(inJam = true))
    }

    @Test
    fun remoteControlsMoveTheSameSession() {
        val at = 10_000L
        val playing = session(playing = true, position = 12_000, at = at)
        val paused = playing.withControl("pause", null, 14_000)
        assertFalse(paused.isPlaying)
        assertEquals(16_000, paused.positionMs)
        val resumed = paused.withControl("play", null, 20_000)
        assertTrue(resumed.isPlaying)
        assertEquals(16_000, resumed.estimatedPositionMs(20_000))
        val sought = resumed.withControl("seek", 50_000, 21_000)
        assertEquals(50_000, sought.positionMs)
        assertEquals(180_000, resumed.withControl("seek", 999_999, 21_000).positionMs)
        val two =
            playing.copy(
                queue = playing.queue + ConnectTrack("bcdefghijkl", "Dos", "B", "", 90_000),
            )
        assertEquals(1, two.withControl("next", null, at).index)
        assertEquals(0, two.withControl("next", null, at).positionMs)
        val deep = two.copy(index = 1, positionMs = 8_000, positionAtMs = at, isPlaying = false)
        assertEquals(1, deep.withControl("previous", null, at).index)
        assertEquals(0L, deep.withControl("previous", null, at).positionMs)
        assertEquals(0, deep.copy(positionMs = 1_000).withControl("previous", null, at).index)
        assertEquals(1_000L, connectPollIntervalMs(sessionLive = true))
        assertEquals(8_000L, connectPollIntervalMs(sessionLive = false))
    }
}
