package org.simpmusic.listentogether

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

/** Replays the snapshots killmon-api (src/jam/rooms.js) really sends, in the order it sends them. */
class JamSyncSequenceTest {
    private val a = "dQw4w9WgXcQ"
    private val b = "aaaaaaaaaaa"

    private fun snap(seq: Int, phase: String, position: Double, current: Int, serverTime: Long, waiting: String = "", queue: String = "[{\"qid\":1,\"videoId\":\"$a\",\"title\":\"A\",\"artist\":\"Art\",\"is_video\":false},{\"qid\":2,\"videoId\":\"$b\",\"title\":\"B\",\"artist\":\"Art\",\"is_video\":true}]"): JsonObject =
        Json.parseToJsonElement(
            """{"code":"ABC234","rev":$seq,"seq":$seq,"phase":"$phase","playing":${phase == "playing"},"position":$position,"server_time":$serverTime,"host_id":"h","current_qid":$current,"queue":$queue,"gate":${if (phase == "loading") """{"waiting":[$waiting],"timed_out":false}""" else "null"},"members":[{"id":"h","name":"H","connected":true,"host":true,"loading":false},{"id":"g","name":"G","connected":true,"host":false,"loading":${waiting.isNotEmpty()}}]}""",
        ) as JsonObject

    private fun decode(raw: JsonObject, old: ListenTogetherState) = decodeJamState(raw, old)

    @Test fun guestJoiningMidSongNeverReloadsOrRestartsTheListenerWhoWasPlaying() {
        // Host is at 87 s of A. G attaches -> beginGate(seq 4, position 87.2, wantPlay) -> G ready -> release.
        var room = ListenTogetherState(selfUserId = "h")
        val gate = snap(4, "loading", 87.2, 1, 5_000, "\"g\"")
        val released = snap(4, "playing", 87.2, 1, 11_000)
        val steps = listOf(gate, released).map { raw ->
            room = decode(raw, room)
            // the host's player sits at 87.4 s, holding A (bare id)
            JamSync.planStep("dQw4w9WgXcQ", room.currentTrack!!.id, 87_400, room.position)
        }
        assertEquals(listOf<JamLoadStep>(JamLoadStep.Keep, JamLoadStep.Keep), steps)
    }

    @Test fun rebufferGateOnTheSameVideoKeepsTheVideoItemAndTheSecond() {
        var room = ListenTogetherState(selfUserId = "g")
        room = decode(snap(9, "loading", 40.0, 2, 1_000, "\"h\""), room)
        // video item: the player's id carries the prefix; the room's does not
        assertEquals(JamLoadStep.Keep, JamSync.planStep("Video$b", room.currentTrack!!.id, 40_300, room.position))
        assertTrue(room.queueIsVideo[1])
        assertFalse(room.queueIsVideo[0])
    }

    @Test fun trackChangeIsOneLoadAtTheRoomPositionNeverSetThenSeekToZero() {
        var room = ListenTogetherState(selfUserId = "g")
        room = decode(snap(5, "loading", 0.0, 2, 1_000, "\"g\""), room)
        val step = JamSync.planStep(a, room.currentTrack!!.id, 213_000, room.position)
        assertEquals(JamLoadStep.Load(0), step)
        // joining late: the same load starts at the room's second, not at 0
        val late = decode(snap(6, "loading", 61.5, 2, 2_000, "\"g\""), room)
        assertEquals(JamLoadStep.Load(61_500), JamSync.planStep(a, late.currentTrack!!.id, 0, late.position))
    }

    @Test fun farListenerSeeksOnlyAndNearListenerDoesNothing() {
        assertEquals(JamLoadStep.Seek(60_000), JamSync.planStep(a, a, 50_000, 60_000))
        assertEquals(JamLoadStep.Keep, JamSync.planStep(a, a, 59_200, 60_000))
    }

    @Test fun seekAndRebufferStormsAreDamped() {
        assertFalse(JamSync.mayCorrectDrift(JamSync.STATE_READY, 10_000, 9_000))
        assertTrue(JamSync.mayCorrectDrift(JamSync.STATE_READY, 12_600, 10_000))
        assertFalse(JamSync.mayCorrectDrift(JamSync.STATE_ENDED, 99_000, 0), "an ended player is never dragged back into the song")
        assertFalse(JamSync.isRealStall(JamSync.STATE_BUFFERING, 10_000, 8_000), "the rebuffer our own seek causes is not a stall")
        assertTrue(JamSync.isRealStall(JamSync.STATE_BUFFERING, 20_000, 8_000))
        assertFalse(JamSync.isRealStall(JamSync.STATE_READY, 20_000, 0))
    }

    @Test fun loadingReachesThePlayersWhileTheOtherPhoneLoads() {
        var room = ListenTogetherState(selfUserId = "h")
        room = decode(snap(4, "loading", 87.2, 1, 5_000, "\"g\""), room)
        assertTrue(room.loading)
        assertTrue(JamSync.showLoading(false, room), "player idle, room waiting -> spinner")
        assertEquals(listOf("g"), room.waitingFor)
        room = decode(snap(4, "playing", 87.2, 1, 11_000), room)
        assertFalse(JamSync.showLoading(false, room))
        assertTrue(JamSync.showLoading(true, room))
        assertFalse(JamSync.showLoading(false, ListenTogetherState()), "outside a room only the player decides")
    }

    @Test fun playAddedBehindCurrentKeepsThePlaylistOrderInTheMirror() {
        val queue = """[{"qid":1,"videoId":"$a"},{"qid":3,"videoId":"ccccccccccc"},{"qid":2,"videoId":"$b"}]"""
        val room = decode(snap(7, "loading", 0.0, 3, 1_000, "", queue), ListenTogetherState(selfUserId = "g"))
        assertEquals(listOf(1L, 3L, 2L), room.queueIds)
        assertEquals("ccccccccccc", room.currentTrack?.id)
    }
}
