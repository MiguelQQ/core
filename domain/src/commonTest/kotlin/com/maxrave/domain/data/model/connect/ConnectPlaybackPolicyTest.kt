package com.maxrave.domain.data.model.connect

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Artist
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

    @Test
    fun staleSnapshotDoesNotEraseAnOptimisticPause() {
        val current = session(playing = false, position = 16_000, at = 2_000).copy(revision = 10)
        val stale = session(playing = true, position = 12_000, at = 1_000).copy(revision = 10)
        val hold = holdFor("pause", current, baseRevision = 10, nowMs = 2_000)
        val kept = mergeConnectSession(current, stale, hold, nowMs = 2_200)
        assertFalse(kept.session?.isPlaying == true)
        assertEquals(10, kept.session?.revision)
        val older = mergeConnectSession(current.copy(revision = 11), stale, hold = null, nowMs = 3_000)
        assertEquals(11, older.session?.revision)
        assertFalse(older.session?.isPlaying == true)
        val confirmed = stale.copy(revision = 11, isPlaying = false, positionMs = 16_000)
        val accepted = mergeConnectSession(current, confirmed, hold, nowMs = 2_400)
        assertFalse(accepted.session?.isPlaying == true)
        assertEquals(11, accepted.session?.revision)
        assertEquals(null, accepted.hold)
        val expired = mergeConnectSession(current, stale.copy(isPlaying = true), hold, nowMs = 2_000 + 2_500)
        assertTrue(expired.session?.isPlaying == true)
    }

    @Test
    fun duplicatePauseIsDroppedAndTheOppositeGestureIsNot() {
        assertFalse(shouldSendTransport("pause", lastAtMs = 1_000, action = "pause", nowMs = 1_200))
        assertTrue(shouldSendTransport("pause", lastAtMs = 1_000, action = "play", nowMs = 1_200))
        assertTrue(shouldSendTransport("pause", lastAtMs = 1_000, action = "pause", nowMs = 1_400))
        assertTrue(shouldSendTransport(null, lastAtMs = 0, action = "seek", nowMs = 10))
    }

    @Test
    fun volumeKeysStepTheRemoteLevelAndStayInsideTheRange() {
        assertEquals(0.35f, nextRemoteVolume(0.30f, up = true), absoluteTolerance = 0.0001f)
        assertEquals(0.25f, nextRemoteVolume(0.30f, up = false), absoluteTolerance = 0.0001f)
        assertEquals(1f, nextRemoteVolume(0.99f, up = true))
        assertEquals(0f, nextRemoteVolume(0.02f, up = false))
        val louder = session(playing = true, position = 0, at = 0).copy(volume = 0.4f, revision = 4)
        assertEquals(0.4f, louder.withControl("volume", null, 10, volume = 0.4f).volume)
        val hold = holdFor("volume", louder, baseRevision = 4, nowMs = 10)
        val oldLevel = louder.copy(volume = 1f, revision = 4)
        assertEquals(0.4f, mergeConnectSession(louder, oldLevel, hold, nowMs = 20).session?.volume)
    }

    @Test
    fun transferKeepsTheActiveVideoAndDoesNotPublishADifferentOne() {
        val queued =
            session(playing = true, position = 40_000, at = 1_000).copy(
                index = 1,
                queue =
                    listOf(
                        ConnectTrack("abcdefghijk", "Uno", "A", "", 180_000),
                        ConnectTrack("bcdefghijkl", "Dos", "B", "", 200_000),
                    ),
            )
        val load = queued.toTransferLoad(5_000)
        assertEquals("bcdefghijkl", load?.videoId)
        assertEquals(1, load?.index)
        assertEquals(44_000, load?.positionMs)
        assertTrue(load?.play == true)
        assertFalse(transferAckIncludesPlayback())
        assertFalse(reportedTrackMatches("abcdefghijk", "bcdefghijkl"))
        assertTrue(reportedTrackMatches("bcdefghijkl", "bcdefghijkl"))
        assertFalse(shouldPublishPlayback(nowMs = 100, suppressUntilMs = 500))
        assertTrue(shouldPublishPlayback(nowMs = 500, suppressUntilMs = 500))
        assertTrue(followerNeedsSilence(itemCount = 2, playWhenReady = false, isPlaying = false))
        assertTrue(followerNeedsSilence(itemCount = 0, playWhenReady = true, isPlaying = false))
        assertFalse(followerNeedsSilence(itemCount = 0, playWhenReady = false, isPlaying = false))
        assertEquals(listOf(QueueLoadOp.REPLACE_ALL, QueueLoadOp.LOAD_INDEX), connectQueueLoadOps())
        assertEquals(null, simulateQueuedClearThenSyncAdd(listOf("abcdefghijk", "bcdefghijkl"), 1))
        assertEquals("bcdefghijkl", simulateAtomicReplace(listOf("abcdefghijk", "bcdefghijkl"), 1))
    }

    @Test
    fun pickingASongKeepsTheActiveDeviceAndAStaleSnapshotDoesNotRestoreTheOldTrack() {
        val playing = session(playing = true, position = 20_000, at = 1_000).copy(revision = 8, volume = 0.4f)
        val picked =
            ConnectPlayback(
                queue =
                    listOf(
                        ConnectTrack("cdefghijklm", "Tres", "C", "", 120_000),
                    ),
                index = 0,
                positionMs = 0L,
                isPlaying = true,
                volume = 1f,
            )
        val loaded = playing.withLoadedQueue(picked, nowMs = 3_000)
        assertEquals("desk-device-00002", loaded.activeDeviceId)
        assertEquals("cdefghijklm", loaded.currentTrack()?.videoId)
        assertEquals(0.4f, loaded.volume)
        assertTrue(loaded.isPlaying)
        assertEquals(0L, loaded.estimatedPositionMs(3_000))
        val hold = holdFor("load", loaded, baseRevision = 8, nowMs = 3_000)
        val stale = playing.copy(revision = 9)
        val kept = mergeConnectSession(loaded, stale, hold, nowMs = 3_200)
        assertEquals("cdefghijklm", kept.session?.currentTrack()?.videoId)
        assertEquals("desk-device-00002", kept.session?.activeDeviceId)
        val confirmed = loaded.copy(revision = 9)
        val accepted = mergeConnectSession(loaded, confirmed, hold, nowMs = 3_400)
        assertEquals("cdefghijklm", accepted.session?.currentTrack()?.videoId)
        assertEquals(null, accepted.hold)
    }

    @Test
    fun localPlayWhileFollowingDoesNotTakeOverAndSeekStaysOnTheLoadedFile() {
        assertEquals(LocalPlayRoute.REMOTE_LOAD, localPlayRoute(followingRemote = true))
        assertEquals(LocalPlayRoute.LOCAL, localPlayRoute(followingRemote = false))
        val session = session(playing = true, position = 10_000, at = 0)
        val same = session.toPlayback()
        assertFalse(shouldPushLocalPick(same, session))
        val other =
            same.copy(
                queue = listOf(ConnectTrack("cdefghijklm", "Tres", "C", "", 90_000)),
                index = 0,
            )
        assertTrue(shouldPushLocalPick(other, session))
        assertTrue(
            seekAppliesInPlace(
                action = "seek",
                currentMediaId = "Songabcdefghijk",
                targetVideoId = "abcdefghijk",
                currentIndex = 0,
                targetIndex = 0,
                itemCount = 2,
            ),
        )
        assertFalse(
            seekAppliesInPlace(
                action = "seek",
                currentMediaId = "abcdefghijk",
                targetVideoId = "bcdefghijkl",
                currentIndex = 0,
                targetIndex = 1,
                itemCount = 2,
            ),
        )
        assertFalse(seekAppliesInPlace("next", "abcdefghijk", "abcdefghijk", 0, 0, 2))
        val sought = session.withControl("seek", 50_000, nowMs = 2_000)
        assertEquals(50_000, sought.estimatedPositionMs(2_000))
        assertEquals(51_000, sought.estimatedPositionMs(3_000))
        val hold = holdFor("seek", sought, baseRevision = 4, nowMs = 2_000)
        val oldPosition = session.copy(revision = 6, positionMs = 12_000, positionAtMs = 2_000)
        val kept = mergeConnectSession(sought, oldPosition, hold, nowMs = 2_300)
        assertEquals(50_000, kept.session?.positionMs)
        assertTrue(kept.hold != null)
        val paused = session.withControl("pause", null, nowMs = 4_000)
        assertFalse(paused.isPlaying)
        assertEquals(14_000, paused.estimatedPositionMs(9_000))
    }

    @Test
    fun remoteQueueUsesTheTappedIndexAndDropsIdsTheProtocolRejects() {
        val first = track("abcdefghijk", "Uno")
        val second = track("bcdefghijkl", "Dos")
        val broken = track("short", "No")
        val playback = connectPlaybackFromTracks(listOf(first, broken, second), index = 2, volume = 0.4f)
        assertEquals("bcdefghijkl", playback?.queue?.getOrNull(playback.index)?.videoId)
        assertEquals(1, playback?.index)
        assertEquals(0.4f, playback?.volume)
        assertTrue(playback?.isPlaying == true)
        val queued = queueForRemotePlay(listOf(first, second), second)
        assertEquals(1, indexForRemotePlay(queued, second.videoId, 1))
        assertEquals(listOf(second.videoId), queueForRemotePlay(listOf(first), second).map { it.videoId })
        assertFalse(ConnectPlayRouter.blocksLocal())
        ConnectPlayRouter.followingRemote = { true }
        var sent: String? = null
        ConnectPlayRouter.offer =
            ConnectPlayRouter.Offer { tracks, index ->
                sent = tracks.getOrNull(index)?.videoId
                true
            }
        assertTrue(ConnectPlayRouter.redirect(listOf(first, second), 1))
        assertEquals("bcdefghijkl", sent)
        ConnectPlayRouter.followingRemote = { false }
        ConnectPlayRouter.offer = null
        assertFalse(ConnectPlayRouter.redirect(listOf(first), 0))
    }
}

private fun track(id: String, title: String) =
    Track(
        album = null,
        artists = listOf(Artist(id = null, name = "A")),
        duration = null,
        durationSeconds = 180,
        isAvailable = true,
        isExplicit = false,
        likeStatus = null,
        thumbnails = null,
        title = title,
        videoId = id,
        videoType = null,
        category = null,
        feedbackTokens = null,
        resultType = null,
    )

/** El clear encolado corre después de los add hechos en el otro hilo y se lleva la cola. */
private fun simulateQueuedClearThenSyncAdd(items: List<String>, index: Int): String? {
    val jobs = ArrayDeque<() -> Unit>()
    val playlist = mutableListOf<String>()
    jobs.add { playlist.clear() }
    items.forEach { playlist.add(it) }
    while (jobs.isNotEmpty()) jobs.removeFirst().invoke()
    return playlist.getOrNull(index)
}

/** Un solo turno: la lista queda reemplazada y el índice pedido sigue ahí. */
private fun simulateAtomicReplace(items: List<String>, index: Int): String? {
    val playlist = mutableListOf<String>()
    playlist.clear()
    playlist.addAll(items)
    return playlist.getOrNull(index)
}
