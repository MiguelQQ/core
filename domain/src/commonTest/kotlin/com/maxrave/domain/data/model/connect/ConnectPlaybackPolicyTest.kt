package com.maxrave.domain.data.model.connect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectPlaybackPolicyTest {
    private fun session(playing: Boolean, position: Long, at: Long, duration: Long = 180_000) =
        ConnectSession(
            activeDeviceId = "desk-device-00002",
            revision = 4,
            isPlaying = playing,
            positionMs = position,
            positionAtMs = at,
            index = 0,
            queue =
                listOf(
                    ConnectTrack("abcdefghijk", "Uno", "A", "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", duration),
                ),
        )

    @Test
    fun displayPlaybackMatchesTheSessionAndDoesNotAdvanceWhilePaused() {
        val paused = session(playing = false, position = 12_000, at = 1_000)
        val shown = paused.toDisplayPlayback(9_000)
        assertEquals("Uno", shown?.title)
        assertEquals("A", shown?.artist)
        assertEquals("abcdefghijk", shown?.videoId)
        assertFalse(shown?.isPlaying == true)
        assertEquals(12_000, shown?.positionMs)
        assertEquals(180_000, shown?.durationMs)
        val playing = session(playing = true, position = 12_000, at = 1_000)
        assertEquals(16_000, playing.toDisplayPlayback(5_000)?.positionMs)
        assertEquals(180_000, playing.copy(positionMs = 170_000, positionAtMs = 0).toDisplayPlayback(30_000)?.positionMs)
        assertTrue(playing.toDisplayPlayback(5_000)?.artwork?.contains("abcdefghijk") == true)
    }

    @Test
    fun followerDoesNotPublishLocalAudio() {
        val state =
            ConnectState(
                deviceId = "phone-device-0001",
                signedIn = true,
                session = session(playing = true, position = 0, at = 0),
            )
        assertTrue(state.isFollowingRemote())
        assertFalse(state.publishesLocalAudio(inJam = false))
        assertNull(state.listeningOnDeviceName())
        val named =
            state.copy(
                devices =
                    listOf(
                        ConnectDevice("desk-device-00002", "windows", "Windows · Miguel", isActive = true, isSelf = false),
                    ),
            )
        assertEquals("Windows · Miguel", named.listeningOnDeviceName())
    }

    @Test
    fun pickerListsEveryAccountDeviceAndChecksOnlyTheActiveOne() {
        val devices =
            listOf(
                ConnectDevice("desk-device-00002", "windows", "Windows · Miguel", isActive = true, isSelf = true),
                ConnectDevice("phone-device-0001", "android", "Pixel 8", isActive = false, isSelf = false),
            )
        val rows = connectPickerRows(devices, session(playing = true, position = 0, at = 0))
        assertEquals(listOf("desk-device-00002", "phone-device-0001"), rows.map { it.deviceId })
        assertEquals("Reproduciendo · Uno", rows.first { it.selected }.status)
        assertEquals(1, rows.count { it.selected })
        assertEquals("En línea", rows.first { it.platform == "android" }.status)
        assertFalse(rows.first { it.platform == "android" }.selected)
        val paused = connectPickerRows(devices, session(playing = false, position = 0, at = 0))
        assertEquals("En pausa · Uno", paused.first { it.selected }.status)
    }

    @Test
    fun playWakesTheBackendAndTheAckCarriesNoPlayback() {
        val wake = playWhileMarkedPlaying()
        assertTrue(wake.playWhenReady)
        assertTrue(wake.callBackend)
        assertFalse(controlAckIncludesPlayback())
        assertTrue(wasPlayingAfterControl("play", sessionIsPlaying = false, playerPlayWhenReady = false))
        assertFalse(wasPlayingAfterControl("pause", sessionIsPlaying = true, playerPlayWhenReady = true))
    }

    @Test
    fun newHandleNeverStartsAtFullGainWhenTheUserIsLower() {
        assertNull(volumeWriteWhenNormalizeOff())
        assertEquals(0.3f, newHandleVolume(0.3f))
        assertTrue(newHandleVolume(0.3f) != 1f)
        val held = mpvGainWrite(masterPercent = 30, fadePercent = 100, sleepPercent = 100, outputReady = false)
        assertEquals(0.0, held.softwareVolume)
        assertTrue(held.softwareVolume != 100.0)
        val live = mpvGainWrite(masterPercent = 30, fadePercent = 100, sleepPercent = 100, outputReady = true)
        assertEquals(30.0, live.aoVolume)
        assertEquals(100.0, live.softwareVolume)
        val incoming = incomingHandleOrder()
        assertEquals(HandleStep.APPLY_LEVELS, incoming.first())
        assertTrue(incoming.indexOf(HandleStep.LOAD_FILE) > incoming.indexOf(HandleStep.MUTE))
        assertTrue(incoming.indexOf(HandleStep.LOAD_FILE) > incoming.indexOf(HandleStep.FADE_ZERO))
        assertEquals(HandleStep.LOAD_FILE, audibleHandleOrder().last())
        assertEquals(HandleStep.APPLY_LEVELS, audibleHandleOrder().first())
    }
}
